package com.ashiqe.voicetopc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

/**
 * Owns the permanent "ready to dictate" notification in the shade.
 *
 * The service's foreground notification only exists while a session runs, so it cannot be the entry
 * point into a session. This separate low-importance notification is always available, which is what
 * lets the user start dictation while another app owns the screen. The Hide choice is remembered,
 * because a notification the user removed on purpose must not come back on its own.
 */
object DictationNotifications {

    /** Action sent by the Hide button; the receiver below turns it into [dismiss]. */
    internal const val ACTION_HIDE_READY = "com.ashiqe.voicetopc.action.HIDE_READY"

    /** Set when the user hid the ready notification; survives service restarts and reboots. */
    private const val KEY_READY_HIDDEN = "ready_hidden"

    /** Low importance so the shortcut never makes a sound or draws attention to itself. */
    private const val CHANNEL_ID = "dictation_ready"
    private const val NOTIFICATION_ID = 1003

    // Distinct from the service request codes so the two notifications cannot share a PendingIntent.
    private const val REQUEST_TALK = 20
    private const val REQUEST_OPEN = 21
    private const val REQUEST_HIDE = 22

    private const val TITLE = "Voice to PC"
    private const val READY_TEXT = "Tap Talk to dictate from any app"

    /**
     * Posts the ready notification and clears a previous Hide: the user opening the app is an
     * explicit request to have the shortcut back.
     */
    fun ensureReady(context: Context) {
        setHidden(context, false)
        post(context)
    }

    /** Removes the ready notification until the next app open, remembering the user's choice. */
    fun dismiss(context: Context) {
        setHidden(context, true)
        cancel(context)
    }

    /**
     * Re-posts the ready notification unless the user hid it. Used after a session ends and after a
     * reboot, where the shortcut must return without overriding an explicit Hide.
     */
    internal fun restore(context: Context) {
        if (isHidden(context)) return
        post(context)
    }

    /** Removes the ready notification without touching the user's Hide choice. */
    internal fun cancel(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            // A denied POST_NOTIFICATIONS or a missing manager must never crash dictation.
        }
    }

    private fun post(context: Context) {
        try {
            createChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, build(context))
        } catch (_: Exception) {
            // Notifications can be blocked; dictation still works from the tile and the app.
        }
    }

    private fun build(context: Context): Notification {
        val talk = PendingIntent.getForegroundService(
            context,
            REQUEST_TALK,
            Intent(context, DictationService::class.java).setAction(DictationService.ACTION_QUICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hide = PendingIntent.getBroadcast(
            context,
            REQUEST_HIDE,
            Intent(context, ReadyNotificationReceiver::class.java).setAction(ACTION_HIDE_READY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(context, R.drawable.ic_stat_mic)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(TITLE)
            .setContentText(READY_TEXT)
            .setStyle(Notification.BigTextStyle().bigText(READY_TEXT))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "Talk", talk).build())
            .addAction(Notification.Action.Builder(icon, "Open", open).build())
            .addAction(Notification.Action.Builder(icon, "Hide", hide).build())
            .build()
    }

    /** Creates the silent channel once; a recreated channel would lose the user's own settings. */
    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dictation shortcut",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Always-available Talk shortcut for dictating from any app."
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        } catch (_: Exception) {
            // Notification settings are unavailable; the shortcut simply stays hidden.
        }
    }

    private fun isHidden(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_READY_HIDDEN, false)

    private fun setHidden(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_READY_HIDDEN, value)
            .apply()
    }
}

/**
 * Receives the Hide action of the ready notification.
 *
 * A broadcast is used instead of a service start so hiding never touches the microphone or the
 * foreground state, and so it works while another app owns the screen.
 */
class ReadyNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DictationNotifications.ACTION_HIDE_READY) return
        DictationNotifications.dismiss(context)
    }
}
