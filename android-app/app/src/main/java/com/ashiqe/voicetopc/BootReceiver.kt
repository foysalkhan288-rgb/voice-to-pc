package com.ashiqe.voicetopc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Puts the ready notification back after a reboot.
 *
 * The ready notification is the only entry point into dictation from another app, and a reboot
 * clears the shade, so without this the feature would silently disappear until the next app open.
 * The receiver only re-posts the notification; it never starts the microphone.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // One binder call on the main thread; no work is offloaded because none is needed.
        DictationNotifications.restore(context)
    }
}
