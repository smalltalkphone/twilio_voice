package com.twilio.twilio_voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.twilio.twilio_voice.R
import com.twilio.twilio_voice.types.ContextExtension.appName

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
     * Post the incoming-call notification. [from] is shown as the caller.
     *
     * The full-screen intent is the mechanism that surfaces a call over the
     * lock screen and above other apps. When the device is unlocked and in
     * use, Android deliberately downgrades it to a heads-up banner instead —
     * that is correct behaviour, not a failure, and the notification carries
     * enough on its own to be actionable in that case.
     */
    fun show(ctx: Context, from: String) {
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

        val n = Notification.Builder(ctx, channel(ctx).id).apply {
            setSmallIcon(R.drawable.ic_microphone)
            setContentTitle(from)
            setContentText("Incoming call")
            // CATEGORY_CALL is what tells the system this is a call: it ranks
            // above other notifications, survives Do Not Disturb where the
            // user has allowed calls, and on Android 14+ is the category that
            // makes USE_FULL_SCREEN_INTENT grantable to a calling app.
            setCategory(Notification.CATEGORY_CALL)
            setOngoing(true)
            setAutoCancel(false)
            setContentIntent(pending)
            setFullScreenIntent(pending, true)
            setVisibility(Notification.VISIBILITY_PUBLIC)
        }.build()

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
