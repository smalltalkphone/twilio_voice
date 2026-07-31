package com.twilio.twilio_voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.twilio.twilio_voice.R

/**
 * The quiet notice an unanswered call leaves behind (smalltalk_app #516).
 *
 * Every exit from a ringing call dismisses [TVIncomingCallNotification] —
 * correctly, an ongoing ring must never outlive its call — but the
 * caller-gave-up exit then left NOTHING: the phone looked exactly as if no
 * call had happened, and the parent found out only by opening the app. For
 * this product, "your kid tried to reach you" is the one notification that
 * must exist. iOS has posted its equivalent since upstream
 * (`showMissedCallNotification`); this is the Android half.
 *
 * Deliberately a SEPARATE channel and id from the incoming-call one. A
 * channel's importance is frozen at creation (the #350 trap), and the ringing
 * channel is IMPORTANCE_HIGH with a looping ringtone — exactly wrong for a
 * notice about a call that is already over. This one is IMPORTANCE_LOW:
 * visible in the shade and status bar, and silent, because the ring already
 * made all the noise this call gets to make.
 *
 * Posted ONLY on the cancelled-invite exits (`onAbort`, and the FCM orphan
 * backstop when the process died mid-ring). Never on answer, and never on a
 * local decline — declining IS an answer, not a miss.
 */
object TVMissedCallNotification {

    private const val TAG = "TVMissedCallNotification"

    /** One missed-call notice at a time: a burst of unanswered calls folds
     * into it rather than stacking five copies. Distinct from every id this
     * plugin has ever posted under. */
    const val NOTIFICATION_ID = 20260731

    private const val CHANNEL_SUFFIX = "_missed_calls"

    /** Carried in the posted notification's extras so the next miss can fold
     * itself in: how many calls this notice stands for, and the distinct
     * caller names among them. Read back from the active notification —
     * nothing in-process, so the count survives process death with the
     * notification itself. */
    private const val EXTRA_MISSED_COUNT = "com.twilio.twilio_voice.EXTRA_MISSED_COUNT"
    private const val EXTRA_MISSED_NAMES = "com.twilio.twilio_voice.EXTRA_MISSED_NAMES"

    private fun channel(ctx: Context): NotificationChannel {
        val id = "${ctx.packageName}$CHANNEL_SUFFIX"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.getNotificationChannel(id)?.let { return it }

        val ch = NotificationChannel(id, "Missed calls", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Tells you when a call went unanswered"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
        return ch
    }

    /**
     * Post (or fold into) the missed-call notice. [from] is the caller as the
     * ring announced them — the `__TWI_CALLER_NAME` custom parameter when the
     * router sent one ("Griffin's Phone"), a formatted number otherwise —
     * and [number] the bare digits when known.
     */
    fun show(ctx: Context, from: String, number: String? = null) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        if (launch == null) {
            Log.w(TAG, "show: no launch intent for ${ctx.packageName}; not posting")
            return
        }
        val intentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(ctx, NOTIFICATION_ID, launch, intentFlags)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Fold a burst: if our notice is already up, this miss joins it. Five
        // rapid unanswered calls must read as one sane notice, not five
        // interruptions. The previous state rides the notification's own
        // extras, so nothing here depends on the process that posted it.
        var count = 1
        var names = arrayListOf(from)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.activeNotifications
                .firstOrNull { it.id == NOTIFICATION_ID }
                ?.notification?.extras?.let { prev ->
                    count = prev.getInt(EXTRA_MISSED_COUNT, 1) + 1
                    val prevNames =
                        prev.getStringArrayList(EXTRA_MISSED_NAMES) ?: arrayListOf()
                    names = ArrayList((prevNames + from).distinct())
                }
        }

        val title = if (count == 1) "Missed call" else "$count missed calls"

        val n = Notification.Builder(ctx, channel(ctx).id).apply {
            setSmallIcon(R.drawable.ic_microphone)
            setContentTitle(title)
            // Every caller this notice stands for — a burst from one phone
            // reads "3 missed calls / Griffin's Phone", never a count with no
            // name and never only the latest name over someone else's calls.
            setContentText(names.joinToString(", "))
            // The category the system reserves for exactly this notice; it is
            // also what a launcher's badge and a car display key off.
            setCategory(Notification.CATEGORY_MISSED_CALL)
            // Same rationale as the ringing notification: a Person with a
            // tel: URI is what notification ranking and contact affinity can
            // actually match — CATEGORY alone says what happened, not who.
            number?.let { digits ->
                val uri = "tel:$digits"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    addPerson(
                        Person.Builder()
                            .setName(from)
                            .setUri(uri)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    addPerson(uri)
                }
            }
            setContentIntent(pending)
            setAutoCancel(true)
            setShowWhen(true)
            setWhen(System.currentTimeMillis())
            setVisibility(Notification.VISIBILITY_PUBLIC)
            addExtras(Bundle().apply {
                putInt(EXTRA_MISSED_COUNT, count)
                putStringArrayList(EXTRA_MISSED_NAMES, names)
            })
        }.build()

        nm.notify(NOTIFICATION_ID, n)
        Log.d(TAG, "show: posted missed-call notification ($count) for $from")
    }
}
