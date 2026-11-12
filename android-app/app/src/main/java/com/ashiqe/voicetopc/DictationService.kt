package com.ashiqe.voicetopc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Dictation that keeps working while another app owns the screen.
 *
 * One [SpeechSession] per utterance: the phone recognizes speech on-device (offline language
 * packs preferred) and sends only text to the paired PC. A session ends when the speaker
 * pauses (silence auto-stop), when Stop & send is tapped, or when the session timeout fires;
 * the final transcript is delivered through /v1/send exactly once.
 *
 * [ACTION_QUICK] is the one-tap entry from the notification shade: it starts listening and
 * sends by itself once the speech ends. The permanent ready notification in the shade is
 * owned by [DictationNotifications]; it is removed while this service owns the shade and
 * restored when the session ends, so the two never show up together.
 */
class DictationService : Service() {

    private enum class State { IDLE, LISTENING, FINALIZING, SENDING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private var micSession: SpeechSession? = null
    private var state = State.IDLE
    private var contentText = IDLE_TEXT
    private var lastNotificationUpdate = 0L

    /** Fires when the recognizer never delivers a transcript after stop. */
    private val finalizeTimeout = Runnable {
        if (state == State.LISTENING || state == State.FINALIZING) {
            abortSession("Transcription timed out. Nothing was sent.")
        }
    }

    /** Fires when a session runs past the duration cap, so the service cannot stay stuck. */
    private val sessionTimeout = Runnable {
        if (state == State.LISTENING) stopSession()
    }

    /** Backstop for a send that outlives the HTTP timeouts. */
    private val sendTimeout = Runnable {
        if (state == State.SENDING) finishSession("Sending timed out. Check the PC connection.")
    }

    private val shutdownRunnable = Runnable { shutdown() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // Android 14+ refuses to start a microphone foreground service without RECORD_AUDIO
        // (SecurityException from startForeground), so explain before entering the foreground.
        if ((action == ACTION_START || action == ACTION_QUICK) && !hasMicPermission(this)) {
            notifyHint(this, MISSING_MIC_MESSAGE)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel(this)
        // Android 14+ requires a microphone foreground service to post its notification right
        // after startForegroundService(), even when the request turns out to be a no-op.
        if (!ensureForeground()) return START_NOT_STICKY
        when (action) {
            // Both entries listen and then send once the speech ends; QUICK is the shade tap.
            ACTION_START, ACTION_QUICK -> startSession()
            ACTION_STOP -> stopSession()
            ACTION_CANCEL -> cancelSession()
            // Without a user action there is no consent to open the microphone: release instead.
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.removeCallbacks(sendTimeout)
        mainHandler.removeCallbacks(shutdownRunnable)
        micSession?.cancel()
        micSession = null
        markActive(false)
        worker.shutdownNow()
        // The session notification is gone, so the permanent shade entry point comes back.
        DictationNotifications.restore(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ session control

    private fun startSession() {
        if (state != State.IDLE) return
        mainHandler.removeCallbacks(shutdownRunnable)

        if (!hasMicPermission(this)) {
            // No crash and no silent failure: explain, then let the content intent open the app.
            finishSession(MISSING_MIC_MESSAGE)
            return
        }
        if (SecureConnectionStore(this).load() == null) {
            finishSession("Not paired with a PC. Open SpeakType to pair.")
            return
        }

        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val outputMode = preferences.getString(KEY_OUTPUT_MODE, "native") ?: "native"
        val languageMode = preferences.getString(KEY_LANGUAGE_MODE, "auto") ?: "auto"

        val session = SpeechSession(this, languageMode, outputMode, autoStopOnSilence = true)
        micSession = session
        state = State.LISTENING
        contentText = LISTENING_TEXT
        markActive(true)
        // The session notification takes over the shade; two ongoing cards would be noise.
        DictationNotifications.cancel(this)
        updateNotification()
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.postDelayed(sessionTimeout, SESSION_TIMEOUT_MS)
        session.start(object : SpeechSession.Callback {
            override fun onSpeech() = Unit

            override fun onResult(text: String) {
                if (state != State.LISTENING && state != State.FINALIZING) return
                if (micSession !== session) return
                mainHandler.removeCallbacks(finalizeTimeout)
                mainHandler.removeCallbacks(sessionTimeout)
                micSession = null
                state = State.IDLE
                markActive(false)
                if (text.isEmpty()) {
                    finishSession("No speech was recognized. Nothing was sent.")
                    return
                }
                // The PC applies the chosen text format when typing.
                sendTranscript(text)
            }

            override fun onError(message: String) {
                if (state != State.LISTENING && state != State.FINALIZING) return
                if (micSession !== session) return
                abortSession(message)
            }
        })
    }

    /** Stop & send: let the phone's recognizer finalize the utterance. */
    private fun stopSession() {
        when (state) {
            State.LISTENING -> {
                state = State.FINALIZING
                contentText = FINISHING_TEXT
                markActive(false)
                updateNotification()
                mainHandler.removeCallbacks(sessionTimeout)
                mainHandler.removeCallbacks(finalizeTimeout)
                mainHandler.postDelayed(finalizeTimeout, FINALIZE_TIMEOUT_MS)
                micSession?.stop()
            }
            // The transcript is already on its way; the timeout guard covers a silent PC.
            State.FINALIZING, State.SENDING -> Unit
            State.IDLE -> shutdown()
        }
    }

    /** Cancel: stop without sending. The in-flight utterance is dropped, not delivered. */
    private fun cancelSession() {
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.removeCallbacks(sendTimeout)
        micSession?.cancel()
        micSession = null
        state = State.IDLE
        markActive(false)
        shutdown()
    }

    private fun abortSession(message: String) {
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(sessionTimeout)
        micSession?.cancel()
        micSession = null
        finishSession(message)
    }

    // ------------------------------------------------------------------ sending

    private fun sendTranscript(transcript: String) {
        val connection = SecureConnectionStore(this).load()
        if (connection == null) {
            finishSession("Not paired with a PC. Open SpeakType to pair.")
            return
        }
        state = State.SENDING
        contentText = SENDING_TEXT
        updateNotification()
        mainHandler.removeCallbacks(sendTimeout)
        mainHandler.postDelayed(sendTimeout, SEND_TIMEOUT_MS)
        val outputMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_OUTPUT_MODE, "native") ?: "native"
        worker.execute {
            try {
                val response = postJson(
                    connection.host,
                    connection.port,
                    connection.fingerprint,
                    "/v1/send",
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("text", transcript)
                        .put("output", outputMode),
                    connection.sessionToken,
                )
                if (!response.optBoolean("ok")) throw IllegalStateException("The PC did not accept the text.")
                mainHandler.post {
                    if (state != State.SENDING) return@post
                    mainHandler.removeCallbacks(sendTimeout)
                    finishSession(
                        if (response.optBoolean("duplicate")) "Already sent to PC" else "Sent to PC",
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (state != State.SENDING) return@post
                    mainHandler.removeCallbacks(sendTimeout)
                    finishSession(
                        "Could not send: ${error.message ?: "connection error"}",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ notification

    /**
     * Posts the ongoing notification immediately. Returns false when the system refuses the
     * foreground start, in which case the caller must not begin a session.
     */
    private fun ensureForeground(): Boolean = try {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (_: Exception) {
        // A denied POST_NOTIFICATIONS or a refused background start must never crash dictation.
        stopSelf()
        false
    }

    private fun buildNotification(): Notification {
        val canStop = state == State.LISTENING || state == State.FINALIZING
        val toggleLabel = if (canStop) "Stop & send" else "Talk"
        val toggleAction = if (canStop) ACTION_STOP else ACTION_START
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("SpeakType")
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(this))
            .addAction(notificationAction(toggleLabel, toggleAction, REQUEST_TOGGLE))
        // Once the text is on its way, Cancel cannot recall it; don't offer a false promise.
        if (state != State.SENDING) {
            builder.addAction(notificationAction("Cancel", ACTION_CANCEL, REQUEST_CANCEL))
        }
        return builder.build()
    }

    private fun notificationAction(label: String, action: String, requestCode: Int): Notification.Action {
        val intent = Intent(this, DictationService::class.java).setAction(action)
        val pending = PendingIntent.getForegroundService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_stat_mic)
        return Notification.Action.Builder(icon, label, pending).build()
    }

    /** Updates the one foreground notification in place; never posts a second one. */
    private fun updateNotification() {
        lastNotificationUpdate = SystemClock.uptimeMillis()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            // Notification permission may be denied on Android 13+; dictation still works.
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Shows the outcome of a session, then releases the service.
     *
     * The session notification must not outlive the session: once it is gone the permanent ready
     * notification returns in [onDestroy], so the shade always ends up with exactly one entry point
     * back into dictation.
     */
    private fun finishSession(message: String) {
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.removeCallbacks(sendTimeout)
        mainHandler.removeCallbacks(shutdownRunnable)
        state = State.IDLE
        contentText = message
        markActive(false)
        updateNotification()
        mainHandler.postDelayed(shutdownRunnable, MESSAGE_DISPLAY_MS)
    }

    private fun shutdown() {
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.removeCallbacks(sendTimeout)
        mainHandler.removeCallbacks(shutdownRunnable)
        micSession?.cancel()
        micSession = null
        state = State.IDLE
        markActive(false)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // Not in the foreground any more.
        }
        stopSelf()
    }

    /** Mirrors the session flag for the Quick Settings tile and asks the tile to redraw. */
    private fun markActive(value: Boolean) {
        setActive(value)
        DictationTileService.requestTileRefresh(this)
    }

    companion object {
        const val ACTION_START = "com.ashiqe.voicetopc.action.START"
        const val ACTION_STOP = "com.ashiqe.voicetopc.action.STOP"
        const val ACTION_CANCEL = "com.ashiqe.voicetopc.action.CANCEL"

        /** One-tap dictation from the notification shade; finalizes without a second tap. */
        const val ACTION_QUICK = "com.ashiqe.voicetopc.action.QUICK"

        private const val CHANNEL_ID = "dictation"
        private const val NOTIFICATION_ID = 1001
        private const val HINT_NOTIFICATION_ID = 1002
        private const val REQUEST_TOGGLE = 10
        private const val REQUEST_CANCEL = 11
        private const val REQUEST_OPEN_APP = 12

        private const val IDLE_TEXT = "Tap Talk to dictate"
        private const val LISTENING_TEXT = "Listening…"
        private const val FINISHING_TEXT = "Finishing…"
        private const val SENDING_TEXT = "Sending…"
        private const val MISSING_MIC_MESSAGE =
            "Microphone permission is required. Tap to open SpeakType and allow it."

        /** On-device recognition finalizes quickly; the timeout only guards a stuck recognizer. */
        private const val FINALIZE_TIMEOUT_MS = 15_000L
        private const val SESSION_TIMEOUT_MS = 180_000L
        private const val SEND_TIMEOUT_MS = 45_000L

        /** How long the outcome message stays before the service releases the shade. */
        private const val MESSAGE_DISPLAY_MS = 5_000L

        @Volatile
        private var listening = false

        /** True while a dictation session owns the microphone. Read by the Quick Settings tile. */
        fun isActive(): Boolean = listening

        internal fun setActive(value: Boolean) {
            listening = value
        }

        internal fun hasMicPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        fun requestStart(context: Context) {
            if (!hasMicPermission(context)) {
                // Starting the service here would only be refused by the system.
                notifyHint(context, MISSING_MIC_MESSAGE)
                return
            }
            start(context, ACTION_START)
        }

        fun requestStop(context: Context) = start(context, ACTION_STOP)

        fun requestCancel(context: Context) = start(context, ACTION_CANCEL)

        private fun start(context: Context, action: String) {
            val intent = Intent(context, DictationService::class.java).setAction(action)
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                // The system can refuse a background start; the user can retry from the tile.
            }
        }

        internal fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            try {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                if (manager.getNotificationChannel(CHANNEL_ID) != null) return
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Dictation",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Dictation controls while another app is in the foreground."
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            } catch (_: Exception) {
                // Notification settings are unavailable; dictation still works without the shade.
            }
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_APP,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Best-effort explanation when dictation cannot start at all (no microphone permission).
         * Posting may be suppressed by a denied POST_NOTIFICATIONS; that must not throw.
         */
        internal fun notifyHint(context: Context, message: String) {
            try {
                createChannel(context)
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                val notification = Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_mic)
                    .setContentTitle("SpeakType")
                    .setContentText(message)
                    .setStyle(Notification.BigTextStyle().bigText(message))
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent(context))
                    .build()
                manager.notify(HINT_NOTIFICATION_ID, notification)
            } catch (_: Exception) {
                // Notifications are blocked or unavailable; nothing else to do here.
            }
        }
    }
}
