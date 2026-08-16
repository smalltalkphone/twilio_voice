package com.twilio.twilio_voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.twilio.twilio_voice.R
import com.twilio.twilio_voice.types.ContextExtension.appName

/**
 * The bare number inside whatever handle Twilio hands us, or null when there
 * isn't one. `sip:5550009@domain` -> `5550009`; `client:app-9537526` ->
 * `9537526`; `+14165550001` -> `+14165550001`; a display name -> null.
 *
 * Shared by [TVIncomingCallNotification] (the notification's subtitle) and
 * `TVConnectionService.applyParameters` (the Telecom address), so the two
 * surfaces can never disagree about what the counterparty's number is.
 * A leading `+` survives so a PSTN handle stays a valid `tel:` URI.
 */
internal fun counterpartyNumber(raw: String?): String? {
    if (raw.isNullOrEmpty()) return null
    val userPart = raw
        .removePrefix("sip:")
        .substringBefore("@")
        .removePrefix("client:")
        .removePrefix("app-")
    val plus = if (userPart.startsWith("+")) "+" else ""
    val digits = userPart.filter { it.isDigit() }
    return if (digits.isEmpty()) null else plus + digits
}

/**
 * The incoming-call announcement for a SELF-MANAGED ConnectionService.
 *
 * A `CAPABILITY_SELF_MANAGED` account gets no help from the system dialer: it
 * does not ring, does not show over the lock screen, and does not wake the
 * display. The app owns all of that. This plugin previously owned none of it,
 * because as a `CALL_PROVIDER` it never had to.
 *
 * The observable result before this (Pixel 9 Pro, 2026-07-25): an incoming
 * call showed its screen only if the app was already open and foregrounded.
 * Backgrounded, or locked, it produced NOTHING — no ring, no notification, no
 * lock-screen UI. The call was genuinely arriving and waiting; it simply never
 * announced itself, so it expired unanswered while the phone sat there looking
 * idle. For a product whose point is that a child can reach a parent, that is
 * a silent failure of the core promise.
 *
 * Deliberately a SEPARATE channel from the foreground-service one. That one is
 * `IMPORTANCE_NONE` on purpose — it exists to satisfy the microphone
 * foreground-service requirement and must stay silent. Reusing it would mean a
 * ringing call that cannot ring.
 */
object TVIncomingCallNotification {

    private const val TAG = "TVIncomingCallNotification"

    /** One visible incoming call at a time, so a second invite replaces it. */
    const val NOTIFICATION_ID = 20260726

    private const val CHANNEL_SUFFIX = "_incoming_calls"

    /**
     * IMPORTANCE_HIGH is what buys the heads-up banner, the sound and the
     * vibration. A channel's importance is fixed at creation — the system
     * ignores later changes — so a pre-existing low-importance channel with
     * this id would silently defeat the whole fix. The id is therefore
     * distinct from any this plugin has shipped before.
     */
    private fun channel(ctx: Context): NotificationChannel {
        val id = "${ctx.packageName}$CHANNEL_SUFFIX"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.getNotificationChannel(id)?.let { return it }

        val ch = NotificationChannel(id, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings when someone calls you"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        nm.createNotificationChannel(ch)
        return ch
    }

    /**
     * Post the incoming-call notification. [from] is shown as the caller;
     * [number] (when known and different from [from]) becomes the subtitle,
     * so a lock screen reads "Lincoln's Phone / 555-0002" rather than a bare
     * name with no way to tell which line is calling.
     *
     * The full-screen intent is the mechanism that surfaces a call over the
     * lock screen and above other apps. When the device is unlocked and in
     * use, Android deliberately downgrades it to a heads-up banner instead —
     * that is correct behaviour, not a failure.
     *
     * Which is exactly why the Answer/Decline actions below are not optional.
     * The downgraded banner is the ONLY thing an unlocked parent sees, and
     * until 2026-08-15 it carried no actions at all: tapping it opened the app,
     * which opened the call screen, which is where the call could finally be
     * answered. Two taps to reach your own child, and invisible on a bench
     * because every locked-phone test path shows the full-screen UI instead,
     * where the notification's own buttons never appear. Found by a founder
     * taking a real call from his son on an unlocked handset.
     */
    fun show(ctx: Context, from: String, number: String? = null) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        if (launch == null) {
            // Nothing to open. Better to log than to post a notification whose
            // only action does nothing.
            Log.w(TAG, "show: no launch intent for ${ctx.packageName}; not posting")
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(ctx, NOTIFICATION_ID, launch, flags)

        // Answer and Decline, straight from the notification. These reach the
        // SAME entry points the in-app call screen uses — ACTION_ANSWER ->
        // acceptInvite(), ACTION_REJECT -> rejectInvite() — so there is one
        // answer path and one decline path, not two implementations that can
        // drift. Neither carries EXTRA_CALL_HANDLE: the service falls back to
        // getIncomingCallHandle(), and while this notification is posted there
        // is by definition exactly one ringing invite.
        //
        // Distinct request codes. FLAG_UPDATE_CURRENT keys on the request code,
        // so reusing NOTIFICATION_ID here would have each PendingIntent
        // overwrite the last and leave both buttons doing whichever was built
        // most recently.
        fun callAction(action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                ctx,
                requestCode,
                Intent(ctx, TVConnectionService::class.java).setAction(action),
                flags,
            )
        val answerPending = callAction(TVConnectionService.ACTION_ANSWER, NOTIFICATION_ID + 1)
        val declinePending = callAction(TVConnectionService.ACTION_REJECT, NOTIFICATION_ID + 2)

        // "555-0002" under the name, but never the same string twice — when no
        // name resolved, [from] already IS the number and the subtitle would
        // just repeat the title.
        val subtitle = number
            ?.let { if (it.length == 7) "${it.take(3)}-${it.drop(3)}" else it }
            ?.takeIf { !from.contains(it) }
            ?: "Incoming call"

        val n = Notification.Builder(ctx, channel(ctx).id).apply {
            setSmallIcon(R.drawable.ic_microphone)
            setContentTitle(from)
            setContentText(subtitle)
            // CATEGORY_CALL is what tells the system this is a call: it ranks
            // above other notifications, survives Do Not Disturb where the
            // user has allowed calls, and on Android 14+ is the category that
            // makes USE_FULL_SCREEN_INTENT grantable to a calling app.
            setCategory(Notification.CATEGORY_CALL)
            // Who is calling, as something the SYSTEM can match rather than
            // just render. Do Not Disturb's "calls from contacts" filter tests
            // the people attached to a notification against the user's
            // contacts; with none attached there is nothing to match and the
            // notification is silenced no matter who is calling.
            //
            // Verified 2026-07-27: with the caller saved AND starred in
            // contacts — `content://com.android.contacts/phone_lookup/5550007`
            // resolving to that contact — DND still suppressed us completely,
            // because CATEGORY_CALL alone does not say WHO.
            //
            // The tel: URI carries the bare digits, which is the form the
            // lookup matches on.
            val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Person.Builder()
                    .setName(from)
                    .apply { number?.let { setUri("tel:$it") } }
                    .setImportant(true)
                    .build()
            } else {
                null
            }
            if (person != null) {
                addPerson(person)
            } else {
                @Suppress("DEPRECATION")
                number?.let { addPerson("tel:$it") }
            }

            // CallStyle is what puts Answer and Decline ON the banner, styled
            // as a call rather than as a notification with two buttons stuck to
            // it. It needs a Person, which is why the Person is now built even
            // when no digits resolved — a name-only Person still satisfies it,
            // and the tel: URI is added when we have one so the DND matching
            // above is unchanged.
            //
            // ⚠️ TRADE-OFF, deliberate: CallStyle renders the Person's name and
            // its own "Incoming call" text, so [subtitle] — the "555-0005" line
            // — is likely not shown on API 31+. That is acceptable because the
            // case it exists for is the one where no name resolved, and there
            // [from] IS the number, so it becomes the Person's name and the
            // parent still sees it. The number is not lost where it is the only
            // identity available. setContentText stays for the pre-31 path and
            // for any surface that still reads it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && person != null) {
                setStyle(
                    Notification.CallStyle.forIncomingCall(person, declinePending, answerPending)
                )
            } else {
                // API 26-30: no CallStyle, so plain actions. Same intents, same
                // order (decline first, matching CallStyle's own argument order
                // and the platform dialer's layout).
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
                        "Decline",
                        declinePending,
                    ).build()
                )
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(ctx, android.R.drawable.sym_action_call),
                        "Answer",
                        answerPending,
                    ).build()
                )
            }
            setOngoing(true)
            setAutoCancel(false)
            setContentIntent(pending)
            setFullScreenIntent(pending, true)
            setVisibility(Notification.VISIBILITY_PUBLIC)
            // Backstop, not the mechanism: every exit path dismisses, but a
            // Twilio invite dies at ~30s and a call notification that outlives
            // its call is its own bug — so if some exit is ever missed (a
            // process death mid-ring, a teardown shape from the #399 family),
            // the system clears it rather than leaving an undismissable,
            // looping ring.
            setTimeoutAfter(60_000L)
        }.build().apply {
            // A channel's sound plays ONCE per notification. A phone call
            // rings until answered or abandoned. INSISTENT is the flag that
            // loops the sound and vibration until the notification is
            // cancelled — without it a phone in a pocket gets a few seconds
            // of ringtone and then silence for the rest of the ring window,
            // which reads as "it rang" on a bench and as "it never rang" to a
            // parent across the room.
            // (`this.` is load-bearing: the local PendingIntent `flags` val
            // above shadows the Notification field inside this apply.)
            this.flags = this.flags or Notification.FLAG_INSISTENT
        }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, n)
        Log.d(TAG, "show: posted incoming-call notification for $from")
    }

    /**
     * Take it down. Called on answer, reject, and disconnect — every path out
     * of a ringing call, because an ongoing notification the user cannot
     * dismiss is worse than never having posted one.
     */
    fun dismiss(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }
}
