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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Dictation that keeps working while another app owns the screen.
 *
 * One [SpeechRecognizer] per session. A session is finished with `stopListening()` and only ends
 * when the speech service delivers `onResults` / `onError`; the recognizer is never destroyed
 * while a result is still on its way. A timeout guard covers a speech service that never answers.
 * Partial transcripts are mirrored to the PC preview and shown in the ongoing notification.
 *
 * [ACTION_QUICK] is the one-tap entry from the notification shade: it starts listening and then
 * finalizes by itself a moment after the speech service reports the end of speech, so a single tap
 * sends the transcript without a second tap. The permanent ready notification in the shade is owned
 * by [DictationNotifications]; it is removed while this service owns the shade and restored when the
 * session ends, so the two never show up together.
 */
class DictationService : Service() {

    private enum class State { IDLE, LISTENING, FINALIZING, SENDING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewLatest = AtomicReference<String?>(null)
    private val previewScheduled = AtomicBoolean(false)

    private var recognizer: SpeechRecognizer? = null
    private var state = State.IDLE
    private var contentText = IDLE_TEXT
    private var outputMode = "native"
    private var lastNotificationUpdate = 0L

    /** True for a session started by [ACTION_QUICK], which finalizes without a second tap. */
    private var quickSession = false

    /** Fires when `stopListening()` was called but the speech service stays silent. */
    private val finalizeTimeout = Runnable {
        if (state == State.LISTENING || state == State.FINALIZING) {
            abortSession("No speech was recognized. Nothing was sent.")
        }
    }

    /**
     * Quick dictation finals by itself: a shade tap already expressed the intent to send, so
     * `onEndOfSpeech` schedules this once the utterance has had time to settle into a result.
     */
    private val quickFinalize = Runnable {
        if (state == State.LISTENING) stopSession()
    }

    /** Fires when a recognizer never reports anything at all, so the service cannot stay stuck. */
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
            ACTION_START -> startSession(quick = false)
            // One tap from the notification shade: listen, then send once speech ends.
            ACTION_QUICK -> startSession(quick = true)
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
        mainHandler.removeCallbacks(quickFinalize)
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.removeCallbacks(sendTimeout)
        mainHandler.removeCallbacks(shutdownRunnable)
        releaseRecognizer()
        markActive(false)
        worker.shutdownNow()
        previewWorker.shutdownNow()
        // The session notification is gone, so the permanent shade entry point comes back.
        DictationNotifications.restore(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ session control

    private fun startSession(quick: Boolean) {
        if (state != State.IDLE) return
        mainHandler.removeCallbacks(shutdownRunnable)

        if (!hasMicPermission(this)) {
            // No crash and no silent failure: explain, then let the content intent open the app.
            finishSession(MISSING_MIC_MESSAGE)
            return
        }
        if (SecureConnectionStore(this).load() == null) {
            finishSession("Not paired with a PC. Open Voice to PC to pair.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishSession("No speech recognition service is available on this phone.")
            return
        }

        outputMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_OUTPUT_MODE, "native")
            ?: "native"

        val created = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (_: Exception) {
            null
        }
        if (created == null) {
            finishSession("Could not start speech recognition on this phone.")
            return
        }

        created.setRecognitionListener(recognitionListener)
        recognizer = created
        state = State.LISTENING
        quickSession = quick
        contentText = LISTENING_TEXT
        markActive(true)
        // The session notification takes over the shade; two ongoing cards would be noise.
        DictationNotifications.cancel(this)
        updateNotification()
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(quickFinalize)
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.postDelayed(sessionTimeout, SESSION_TIMEOUT_MS)
        try {
            created.startListening(recognizerIntent())
        } catch (error: Exception) {
            abortSession("Could not start the microphone: ${error.message ?: "speech service error"}")
        }
    }

    /** Stop & send: ask the recognizer to finalize so `onResults` can deliver the transcript. */
    private fun stopSession() {
        when (state) {
            State.LISTENING -> {
                mainHandler.removeCallbacks(quickFinalize)
                state = State.FINALIZING
                contentText = FINISHING_TEXT
                markActive(false)
                updateNotification()
                mainHandler.removeCallbacks(sessionTimeout)
                mainHandler.removeCallbacks(finalizeTimeout)
                mainHandler.postDelayed(finalizeTimeout, FINALIZE_TIMEOUT_MS)
                try {
                    recognizer?.stopListening()
                } catch (error: Exception) {
                    abortSession("Could not finish the recording: ${error.message ?: "speech service error"}")
                }
            }
            // Results are already on the way; the timeout guard covers a silent speech service.
            State.FINALIZING, State.SENDING -> Unit
            State.IDLE -> shutdown()
        }
    }

    /** Cancel: stop without sending. The in-flight utterance is dropped, not delivered. */
    private fun cancelSession() {
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(quickFinalize)
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.removeCallbacks(sendTimeout)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
            // The speech service may already be gone.
        }
        releaseRecognizer()
        queuePreview("")
        state = State.IDLE
        markActive(false)
        shutdown()
    }

    private fun abortSession(message: String) {
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(quickFinalize)
        mainHandler.removeCallbacks(sessionTimeout)
        releaseRecognizer()
        queuePreview("")
        finishSession(message)
    }

    // ------------------------------------------------------------------ recognition

    private fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            if (outputMode == "english") "en-US" else "bn-BD",
        )
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            mainHandler.post {
                if (state == State.LISTENING) {
                    contentText = LISTENING_TEXT
                    updateNotification()
                }
            }
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            mainHandler.post {
                if (state == State.LISTENING) {
                    contentText = FINISHING_TEXT
                    updateNotification()
                    // A quick session has no second tap coming, so it finalizes on its own.
                    if (quickSession) {
                        mainHandler.removeCallbacks(quickFinalize)
                        mainHandler.postDelayed(quickFinalize, QUICK_FINALIZE_DELAY_MS)
                    }
                }
            }
        }

        override fun onError(error: Int) {
            mainHandler.post {
                if (state == State.LISTENING || state == State.FINALIZING) {
                    abortSession(explainError(error))
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            mainHandler.post {
                if (state != State.LISTENING && state != State.FINALIZING) return@post
                mainHandler.removeCallbacks(finalizeTimeout)
                mainHandler.removeCallbacks(quickFinalize)
                mainHandler.removeCallbacks(sessionTimeout)
                releaseRecognizer()
                state = State.IDLE
                markActive(false)
                if (transcript.isEmpty()) {
                    finishSession("No text was recognized. Nothing was sent.")
                    return@post
                }
                sendTranscript(transformOutput(transcript))
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) return
            mainHandler.post {
                if (state != State.LISTENING) return@post
                contentText = text
                updateNotificationThrottled()
                queuePreview(transformOutput(text))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun explainError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The speech service does not support this language."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "The language model is unavailable. Choose another language in the app."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The speech service has a network error."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No speech was recognized. Nothing was sent."
        else -> "Speech recognition failed (code $error). Nothing was sent."
    }

    private fun releaseRecognizer() {
        val active = recognizer ?: return
        recognizer = null
        try {
            active.setRecognitionListener(null)
        } catch (_: Exception) {
            // Already detached.
        }
        try {
            active.destroy()
        } catch (_: Exception) {
            // Already released by the speech service.
        }
    }

    private fun transformOutput(text: String): String = when (outputMode) {
        "roman" -> BengaliRomanizer.transliterate(text)
        "english" -> EnglishNormalizer.normalize(text)
        else -> text
    }

    // ------------------------------------------------------------------ sending

    private fun sendTranscript(transcript: String) {
        val connection = SecureConnectionStore(this).load()
        if (connection == null) {
            finishSession("Not paired with a PC. Open Voice to PC to pair.")
            return
        }
        state = State.SENDING
        contentText = SENDING_TEXT
        updateNotification()
        mainHandler.removeCallbacks(sendTimeout)
        mainHandler.postDelayed(sendTimeout, SEND_TIMEOUT_MS)
        worker.execute {
            try {
                val response = postJson(
                    connection.host,
                    connection.port,
                    connection.fingerprint,
                    "/v1/send",
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("text", transcript),
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

    // ------------------------------------------------------------------ preview mirroring

    /** Previews are best-effort: keep only the newest text so a slow PC cannot build a backlog. */
    private fun queuePreview(text: String) {
        previewLatest.set(text)
        schedulePreview()
    }

    private fun schedulePreview() {
        if (previewLatest.get() == null) return
        if (!previewScheduled.compareAndSet(false, true)) return
        previewWorker.execute {
            try {
                while (true) {
                    val next = previewLatest.getAndSet(null) ?: break
                    try {
                        postPreview(next)
                    } catch (_: Exception) {
                        // Ignore: the final transcript is what actually gets typed.
                    }
                }
            } finally {
                previewScheduled.set(false)
            }
            if (previewLatest.get() != null) schedulePreview()
        }
    }

    private fun postPreview(text: String) {
        val connection = SecureConnectionStore(this).load() ?: return
        postJson(
            connection.host,
            connection.port,
            connection.fingerprint,
            "/v1/preview",
            JSONObject().put("text", text),
            connection.sessionToken,
        )
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Voice to PC")
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(this))
            .addAction(notificationAction(toggleLabel, toggleAction, REQUEST_TOGGLE))
            .addAction(notificationAction("Cancel", ACTION_CANCEL, REQUEST_CANCEL))
            .build()
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

    private fun updateNotificationThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastNotificationUpdate < PARTIAL_UPDATE_INTERVAL_MS) return
        updateNotification()
    }

    // ------------------------------------------------------------------ lifecycle helpers

    /**
     * Shows the outcome of a session, then releases the service.
     *
     * The session notification must not outlive the session: once it is gone the permanent ready
     * notification returns in [onDestroy], so the shade always ends up with exactly one entry point
     * back into dictation.
     */
    private fun finishSession(message: String) {
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(quickFinalize)
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
        mainHandler.removeCallbacks(quickFinalize)
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.removeCallbacks(sendTimeout)
        mainHandler.removeCallbacks(shutdownRunnable)
        releaseRecognizer()
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
            "Microphone permission is required. Tap to open Voice to PC and allow it."

        private const val FINALIZE_TIMEOUT_MS = 10_000L
        private const val SESSION_TIMEOUT_MS = 180_000L
        private const val SEND_TIMEOUT_MS = 20_000L

        /** Grace period after `onEndOfSpeech` so a quick session can still receive its result. */
        private const val QUICK_FINALIZE_DELAY_MS = 700L

        /** How long the outcome message stays before the service releases the shade. */
        private const val MESSAGE_DISPLAY_MS = 5_000L
        private const val PARTIAL_UPDATE_INTERVAL_MS = 150L

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
                    .setContentTitle("Voice to PC")
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
