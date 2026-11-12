package com.ashiqe.voicetopc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Recognizes speech on the phone with the system [SpeechRecognizer] (offline language
 * packs preferred) and sends only text to the paired PC: partial results feed /v1/preview,
 * and the final transcript is delivered through [Callback.onResult] for /v1/send.
 * No audio ever leaves the phone.
 *
 * One session per utterance. [stop] asks the recognizer to finalize; [cancel] drops the
 * utterance without a result. All callbacks arrive on the main thread.
 */
internal class SpeechSession(
    private val context: Context,
    private val languageMode: String,
    private val outputMode: String,
    private val autoStopOnSilence: Boolean,
) {
    interface Callback {
        /** Voice was detected, so a hold release may finalize the utterance. */
        fun onSpeech()

        /** Final transcript as recognized on the phone; empty when nothing was recognized. */
        fun onResult(text: String)

        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewWorker: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var cancelled = false

    @Volatile
    private var finished = false

    private var recognizer: SpeechRecognizer? = null
    private var speechHeard = false

    // Some speech services (e.g. Google TTS on-device) return an empty final result after
    // good partials; the last partial is then the only transcript we have.
    private var lastPartial = ""

    /** Guards the single online retry when the offline language pack is unavailable. */
    private var offlineFallbackDone = false
    private val previewPending = AtomicReference<String?>(null)
    private val previewQueued = AtomicBoolean(false)

    /** Backstop so a recognizer that never endpoint cannot hold the mic forever. */
    private val maxDuration = Runnable {
        if (!finished) recognizer?.stopListening()
    }

    fun start(callback: Callback) {
        mainHandler.post { begin(callback) }
    }

    /** Finish the utterance and deliver the transcript through [Callback.onResult]. */
    fun stop() {
        mainHandler.post {
            if (!finished) recognizer?.stopListening()
        }
    }

    /** Drop the utterance; no callback is delivered. */
    fun cancel() {
        cancelled = true
        mainHandler.post {
            finished = true
            releaseRecognizer()
        }
    }

    private fun begin(callback: Callback) {
        if (cancelled || finished) return
        val connection = SecureConnectionStore(context).load()
        if (connection == null) {
            fail(callback, "Not paired with a PC. Open SpeakType to pair.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail(callback, "This phone has no speech recognition service. Install Google or another speech service, then try again.")
            return
        }
        launchRecognizer(callback, connection, preferOffline = true)
    }

    /**
     * Offline packs are preferred, but EXTRA_PREFER_OFFLINE alone makes some speech services
     * reject a language whose offline pack is missing instead of falling back. When that happens
     * we retry once with online recognition allowed so dictation still works without the pack.
     */
    private fun launchRecognizer(callback: Callback, connection: PcConnection, preferOffline: Boolean) {
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Exception) {
            null
        }
        if (recognizer == null) {
            finished = true
            releaseRecognizer()
            fail(callback, "Could not start the phone's speech recognizer. Try again.")
            return
        }
        this.recognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() {
                markSpeech(callback)
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (cancelled || finished) return
                val languageUnavailable =
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (languageUnavailable && !offlineFallbackDone) {
                    offlineFallbackDone = true
                    // Reuse the same recognizer: destroying it unbinds the service, and an
                    // immediate rebind races that unbind and drops the retry. Restart in place.
                    try {
                        recognizer.startListening(buildIntent(preferOffline = false))
                    } catch (_: Exception) {
                        finished = true
                        releaseRecognizer()
                        fail(callback, "Could not start the phone's speech recognizer. Try again.")
                    }
                    return
                }
                finished = true
                releaseRecognizer()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        mainHandler.post { callback.onResult(lastPartial) }
                    else -> fail(callback, friendly(error))
                }
            }

            override fun onResults(results: Bundle?) {
                if (cancelled || finished) return
                finished = true
                releaseRecognizer()
                val finalText = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                val text = finalText.ifEmpty { lastPartial }
                mainHandler.post { callback.onResult(text) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (cancelled || finished) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isEmpty()) return
                lastPartial = text
                markSpeech(callback)
                previewPending.set(text)
                schedulePreview(connection)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        try {
            recognizer.startListening(buildIntent(preferOffline))
            mainHandler.postDelayed(maxDuration, MAX_DURATION_MS)
        } catch (_: Exception) {
            finished = true
            releaseRecognizer()
            fail(callback, "Could not start the phone's speech recognizer. Try again.")
        }
    }

    private fun buildIntent(preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // The English output lock means the utterance is English, whatever the language row says.
            when (if (outputMode == "english") "en" else languageMode) {
                "bn" -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                "en" -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            }
            val silence = if (autoStopOnSilence) SILENCE_FINALIZE_MS else HOLD_SILENCE_MS
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
        }

    private fun markSpeech(callback: Callback) {
        if (speechHeard) return
        speechHeard = true
        mainHandler.post {
            if (!cancelled && !finished) callback.onSpeech()
        }
    }

    /** Latest-only coalescing: at most one preview in flight; newer partials replace older ones. */
    private fun schedulePreview(connection: PcConnection) {
        if (!previewQueued.compareAndSet(false, true)) return
        try {
            previewWorker.execute {
                val latest = previewPending.getAndSet(null)
                previewQueued.set(false)
                if (latest != null && !cancelled && !finished) {
                    try {
                        postJson(
                            connection.host, connection.port, connection.fingerprint,
                            "/v1/preview",
                            JSONObject().put("text", latest).put("output", outputMode),
                            connection.sessionToken,
                        )
                    } catch (_: Exception) {
                        // Preview is best effort; the final transcript still gets sent.
                    }
                }
                if (previewPending.get() != null) schedulePreview(connection)
            }
        } catch (_: RejectedExecutionException) {
            previewQueued.set(false)
        }
    }

    private fun releaseRecognizer() {
        mainHandler.removeCallbacks(maxDuration)
        recognizer?.destroy()
        recognizer = null
        previewPending.set(null)
        previewWorker.shutdownNow()
    }

    private fun fail(callback: Callback, message: String) {
        mainHandler.post {
            if (!cancelled) callback.onError(message)
        }
    }

    private fun friendly(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition has no offline pack for this language and no internet. Install the offline pack in your phone's speech settings, or connect to the internet."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is required. Tap to open SpeakType and allow it."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "The phone's speech recognizer is busy. Wait a moment and try again."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "The phone's speech service does not support the selected language. Choose another language or install its offline pack."
        SpeechRecognizer.ERROR_AUDIO ->
            "Could not read the microphone. Another app may be using it."
        else ->
            "The phone's speech recognition failed (error $error). Try again."
    }

    companion object {
        /** Matches the previous silence auto-stop: finalize after a short pause. */
        private const val SILENCE_FINALIZE_MS = 1800

        /** Hold mode: the release finalizes, so tolerate long thinking pauses. */
        private const val HOLD_SILENCE_MS = 60_000

        /** Keeps a session under the old audio cap even if silence detection never fires. */
        private const val MAX_DURATION_MS = 170_000L
    }
}