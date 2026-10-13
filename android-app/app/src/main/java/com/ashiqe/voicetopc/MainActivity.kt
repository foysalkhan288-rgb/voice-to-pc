package com.ashiqe.voicetopc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.net.InetAddress
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


private const val MIC_PERMISSION_REQUEST = 401
private const val CAMERA_PERMISSION_REQUEST = 402
private const val PREFS_NAME = "voice_to_pc"
private const val KEY_ALIAS = "voice_to_pc_connection_key"
private const val KEY_ONLINE_MODE = "online_mode"
private const val KEY_OUTPUT_MODE = "output_mode"
private const val KEY_LANGUAGE_MODE = "language_mode"

data class PcConnection(
    val host: String,
    val port: Int,
    val sessionToken: String,
    val fingerprint: String,
)

class MainActivity : AppCompatActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private lateinit var statusView: TextView
    private lateinit var privacyView: TextView
    private lateinit var connectionView: TextView
    private lateinit var outputButton: Button
    private lateinit var languageButton: Button
    private lateinit var micButton: Button
    private lateinit var pairButton: Button
    private lateinit var retryButton: Button
    private lateinit var discardButton: Button
    private lateinit var onlineButton: Button
    private lateinit var offlineButton: Button

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var pendingId: String? = null
    private var pendingText: String? = null
    private var pendingUncertain: Boolean = false
    private var privacyOnline: Boolean? = null
    private var outputMode: String = "native"
    private var languageMode: String = "auto"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        privacyOnline = if (preferences.contains(KEY_ONLINE_MODE)) {
            preferences.getBoolean(KEY_ONLINE_MODE, true)
        } else null
        outputMode = preferences.getString(KEY_OUTPUT_MODE, "native") ?: "native"
        languageMode = preferences.getString(KEY_LANGUAGE_MODE, "auto") ?: "auto"
        buildUi()
        refreshUi()
        if (privacyOnline == null) {
            setStatus("Choose Online or Offline speech recognition to get started.")
        }
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(Color.rgb(244, 247, 245))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.addView(outer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        outer.addView(TextView(this).apply {
            text = "VOICE TO PC"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(35, 112, 82))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchWrap())
        outer.addView(TextView(this).apply {
            text = "Speak. See it typed."
            textSize = 30f
            setTextColor(Color.rgb(24, 45, 36))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(3))
        }, matchWrap())
        outer.addView(bodyText("Dictate on your phone and send the transcript to the active text field on your PC."), matchWrap())
        addGap(outer, 18)

        val connectionCard = card()
        connectionCard.addView(sectionTitle("PC connection"), matchWrap())
        connectionView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(24, 45, 36))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addGap(connectionCard, 6)
        connectionCard.addView(connectionView, matchWrap())
        pairButton = button("Pair with PC", primary = true) { startQrScan() }
        addGap(connectionCard, 12)
        connectionCard.addView(pairButton, buttonParams())
        addCard(outer, connectionCard)

        val recognitionCard = card()
        recognitionCard.addView(sectionTitle("Speech recognition"), matchWrap())
        privacyView = bodyText("")
        addGap(recognitionCard, 4)
        recognitionCard.addView(privacyView, matchWrap())
        onlineButton = button("Online recognition") {
            choosePrivacy(online = true)
        }
        addGap(recognitionCard, 10)
        recognitionCard.addView(onlineButton, buttonParams())
        offlineButton = button("Offline only") {
            choosePrivacy(online = false)
        }
        addGap(recognitionCard, 8)
        recognitionCard.addView(offlineButton, buttonParams())
        addCard(outer, recognitionCard)

        val optionsCard = card()
        optionsCard.addView(sectionTitle("Output and language"), matchWrap())
        outputButton = button("") { toggleOutputMode() }
        addGap(optionsCard, 10)
        optionsCard.addView(outputButton, buttonParams())
        languageButton = button("") { cycleLanguageMode() }
        addGap(optionsCard, 8)
        optionsCard.addView(languageButton, buttonParams())
        addCard(outer, optionsCard)

        val dictationCard = card()
        dictationCard.addView(sectionTitle("Dictation"), matchWrap())
        micButton = button("Start speaking", primary = true) { beginDictation() }
        micButton.textSize = 18f
        micButton.minHeight = dp(64)
        addGap(dictationCard, 12)
        dictationCard.addView(micButton, buttonParams())
        statusView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(74, 94, 83))
            setPadding(dp(6), dp(14), dp(6), dp(8))
        }
        dictationCard.addView(statusView, matchWrap())
        retryButton = button("Retry sending") { retryPending() }
        discardButton = button("Discard transcript") { discardPending() }
        addGap(dictationCard, 6)
        dictationCard.addView(retryButton, buttonParams())
        addGap(dictationCard, 8)
        dictationCard.addView(discardButton, buttonParams())
        addCard(outer, dictationCard)

        outer.addView(TextView(this).apply {
            text = "Before speaking, click the target text field on your PC. Each tap records one utterance and sends the final transcript automatically."
            textSize = 13f
            setTextColor(Color.rgb(86, 103, 94))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), 0)
        }, matchWrap())
        setContentView(scroll)
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        minHeight = dp(52)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(if (primary) Color.rgb(31, 112, 79) else Color.rgb(232, 242, 236), 16)
        setTextColor(if (primary) Color.WHITE else Color.rgb(31, 93, 68))
        setOnClickListener { action() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.WHITE, 20, Color.rgb(228, 235, 230))
    }

    private fun addCard(parent: LinearLayout, child: LinearLayout) {
        parent.addView(child, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(14) })
    }

    private fun addGap(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = 17f
        setTextColor(Color.rgb(24, 45, 36))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun bodyText(label: String) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.rgb(86, 103, 94))
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun applyChoiceStyle(button: Button, selected: Boolean) {
        button.background = rounded(
            if (selected) Color.rgb(223, 240, 229) else Color.rgb(246, 248, 246),
            16,
            if (selected) Color.rgb(65, 142, 103) else Color.rgb(226, 233, 228),
        )
        button.setTextColor(if (selected) Color.rgb(30, 102, 68) else Color.rgb(64, 78, 70))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun refreshUi() {
        val connection = SecureConnectionStore(this).load()
        connectionView.text = if (connection == null) "Not connected" else "Connected"
        privacyView.text = when (privacyOnline) {
            true -> "Online recognition may send audio to your phone's speech service."
            false -> "Offline only uses a model on your phone. It will not switch to online recognition."
            null -> "Choose where speech is recognized. Select a mode before dictation."
        }
        applyChoiceStyle(onlineButton, privacyOnline == true)
        applyChoiceStyle(offlineButton, privacyOnline == false)
        outputButton.text = if (outputMode == "native") "Output: Bengali script  ·  Tap to switch" else "Output: Banglish  ·  Tap to switch"
        languageButton.text = when {
            Build.VERSION.SDK_INT >= 34 && languageMode == "auto" -> "Speech language: Auto (Bangla + English)"
            languageMode == "en" -> "Speech language: English"
            else -> "Speech language: Bangla"
        }
        val hasPending = pendingText != null
        retryButton.visibility = if (hasPending) View.VISIBLE else View.GONE
        discardButton.visibility = if (hasPending) View.VISIBLE else View.GONE
        retryButton.isEnabled = connection != null && !listening && !pendingUncertain
        micButton.isEnabled = privacyOnline != null && !listening && !hasPending
        pairButton.isEnabled = !listening
    }

    private fun choosePrivacy(online: Boolean) {
        privacyOnline = online
        preferences.edit().putBoolean(KEY_ONLINE_MODE, online).apply()
        refreshUi()
        setStatus(if (online) "Online recognition selected." else "Offline-only recognition selected.")
    }

    private fun toggleOutputMode() {
        outputMode = if (outputMode == "native") "roman" else "native"
        preferences.edit().putString(KEY_OUTPUT_MODE, outputMode).apply()
        refreshUi()
    }

    private fun cycleLanguageMode() {
        languageMode = if (Build.VERSION.SDK_INT >= 34) {
            when (languageMode) {
                "auto" -> "bn"
                "bn" -> "en"
                else -> "auto"
            }
        } else {
            if (languageMode == "en") "bn" else "en"
        }
        preferences.edit().putString(KEY_LANGUAGE_MODE, languageMode).apply()
        refreshUi()
    }

    private fun startQrScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }
        launchQrScan()
    }

    private fun launchQrScan() {
        try {
            IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan the QR code shown on your PC")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan()
        } catch (error: Exception) {
            setStatus("Could not open the QR scanner: ${error.message ?: "scanner unavailable"}")
        }
    }

    @Deprecated("ZXing embedded scanner returns its result through this callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) return
        val raw = result.contents ?: run {
            setStatus("QR scan cancelled.")
            return
        }
        pairFromQr(raw)
    }

    private fun pairFromQr(raw: String) {
        worker.execute {
            try {
                val json = JSONObject(raw)
                require(json.optString("app") == "voice-to-pc") { "This is not a Voice to PC QR code." }
                val host = json.getString("host")
                val address = InetAddress.getByName(host)
                require(address.isSiteLocalAddress || address.isLinkLocalAddress) {
                    "The QR code does not contain a local PC address."
                }
                val port = json.getInt("port")
                val code = json.getString("code")
                val fingerprint = json.getString("fingerprint").lowercase()
                require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "The certificate fingerprint is invalid." }
                val response = postJson(
                    host,
                    port,
                    fingerprint,
                    "/v1/pair",
                    JSONObject().put("code", code),
                    null,
                )
                val token = response.getString("session_token")
                SecureConnectionStore(this).save(PcConnection(host, port, token, fingerprint))
                runOnUiThread {
                    refreshUi()
                    setStatus("Paired with your PC. Click a text field on the PC before speaking.")
                }
            } catch (error: Exception) {
                runOnUiThread { setStatus("Pairing failed: ${error.message ?: "connection error"}") }
            }
        }
    }

    private fun beginDictation() {
        if (privacyOnline == null) {
            setStatus("Choose Online or Offline recognition first.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
            return
        }
        val available = if (privacyOnline == true) {
            SpeechRecognizer.isRecognitionAvailable(this)
        } else {
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        }
        if (!available) {
            setStatus(if (privacyOnline == true) "No speech recognition service is available on this phone." else "Offline speech recognition is unavailable on this phone. Select Online recognition to continue.")
            return
        }

        try {
            recognizer = if (privacyOnline == true) {
                SpeechRecognizer.createSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            }
            recognizer?.setRecognitionListener(recognitionListener())
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                when {
                    Build.VERSION.SDK_INT >= 34 && languageMode == "auto" -> {
                        putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH", "balanced")
                        putStringArrayListExtra(
                            "android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES",
                            arrayListOf("bn-BD", "en-US"),
                        )
                        putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                    }
                    languageMode == "en" -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    else -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                }
                if (privacyOnline == false) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            listening = true
            micButton.text = "Listening…"
            refreshUi()
            setStatus("Speak now. The transcript will be sent when you finish.")
            recognizer?.startListening(intent)
        } catch (error: Exception) {
            stopRecognizer()
            setStatus("Could not start the microphone: ${error.message ?: "speech service error"}")
        }
    }

    private fun recognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            runOnUiThread { setStatus("Listening…") }
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            runOnUiThread { setStatus("Speech ended. Preparing transcript…") }
        }
        override fun onError(error: Int) {
            runOnUiThread {
                stopRecognizer()
                val explanation = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The speech service does not support this language."
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The language model is unavailable. Download it for offline use or choose another language."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service has a network error."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was recognized. Nothing was sent."
                    else -> "Speech recognition failed (code $error). Nothing was sent."
                }
                setStatus(explanation)
            }
        }
        override fun onResults(results: Bundle?) {
            val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            runOnUiThread {
                stopRecognizer()
                if (transcript.isBlank()) {
                    setStatus("No text was recognized.")
                    return@runOnUiThread
                }
                val output = if (outputMode == "roman") BengaliRomanizer.transliterate(transcript) else transcript
                pendingId = UUID.randomUUID().toString()
                pendingText = output
                pendingUncertain = false
                refreshUi()
                sendPending()
            }
        }
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun stopRecognizer() {
        listening = false
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
        if (::micButton.isInitialized) {
            micButton.text = "Start speaking"
            refreshUi()
        }
    }

    private fun sendPending() {
        val transcript = pendingText ?: return
        val id = pendingId ?: UUID.randomUUID().toString().also { pendingId = it }
        val connection = SecureConnectionStore(this).load()
        if (connection == null) {
            setStatus("Not paired with a PC. The transcript is saved in this session; pair and retry.")
            refreshUi()
            return
        }
        retryButton.isEnabled = false
        setStatus("Sending to your PC…")
        worker.execute {
            try {
                val response = postJson(
                    connection.host,
                    connection.port,
                    connection.fingerprint,
                    "/v1/send",
                    JSONObject().put("id", id).put("text", transcript),
                    connection.sessionToken,
                )
                if (!response.optBoolean("ok")) throw IllegalStateException("The PC did not accept the text.")
                runOnUiThread {
                    pendingId = null
                    pendingText = null
                    pendingUncertain = false
                    refreshUi()
                    setStatus(if (response.optBoolean("duplicate")) "This text was already sent, so it was not duplicated." else "Text sent to your PC.")
                }
            } catch (error: Exception) {
                val uncertain = error.message?.contains("uncertain=true") == true
                runOnUiThread {
                    pendingUncertain = uncertain
                    refreshUi()
                    setStatus(if (uncertain) "Text may be partially entered on the PC. Retry is disabled; check the field, then discard this transcript." else "Could not send. The transcript is still on your phone; fix the connection and retry.")
                }
            }
        }
    }

    private fun retryPending() {
        sendPending()
    }

    private fun discardPending() {
        pendingId = null
        pendingText = null
        pendingUncertain = false
        refreshUi()
        setStatus("Unsent transcript discarded.")
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchQrScan()
                else setStatus("Camera permission is required to scan the PC pairing code.")
            }
            MIC_PERMISSION_REQUEST -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginDictation()
                else setStatus("Microphone permission is required for voice recognition.")
            }
        }
    }

    override fun onDestroy() {
        stopRecognizer()
        worker.shutdownNow()
        super.onDestroy()
    }
}

private class SecureConnectionStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(connection: PcConnection) {
        val json = JSONObject()
            .put("host", connection.host)
            .put("port", connection.port)
            .put("sessionToken", connection.sessionToken)
            .put("fingerprint", connection.fingerprint)
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("connection_iv", Base64.getEncoder().encodeToString(cipher.iv))
            .putString("connection_data", Base64.getEncoder().encodeToString(encrypted))
            .apply()
    }

    fun load(): PcConnection? {
        val iv = preferences.getString("connection_iv", null) ?: return null
        val data = preferences.getString("connection_data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.getDecoder().decode(iv)),
            )
            val clear = cipher.doFinal(Base64.getDecoder().decode(data))
            val json = JSONObject(String(clear, Charsets.UTF_8))
            PcConnection(
                json.getString("host"),
                json.getInt("port"),
                json.getString("sessionToken"),
                json.getString("fingerprint"),
            )
        } catch (_: Exception) {
            preferences.edit().remove("connection_iv").remove("connection_data").apply()
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}

private fun postJson(
    host: String,
    port: Int,
    fingerprint: String,
    path: String,
    body: JSONObject,
    sessionToken: String?,
): JSONObject {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull() ?: throw SecurityException("PC certificate is missing.")
            val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actual.equals(fingerprint, ignoreCase = true)) {
                throw SecurityException("PC certificate fingerprint does not match.")
            }
        }
    }
    val context = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
    val url = URL("https://$host:$port$path")
    val connection = (url.openConnection() as HttpsURLConnection).apply {
        sslSocketFactory = context.socketFactory
        hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        connectTimeout = 5_000
        readTimeout = 8_000
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Cache-Control", "no-store")
        if (sessionToken != null) setRequestProperty("Authorization", "Bearer $sessionToken")
    }
    try {
        val requestBytes = body.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(requestBytes.size)
        connection.outputStream.use { it.write(requestBytes) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val response = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        if (status !in 200..299) {
            val uncertain = response.optBoolean("uncertain")
            throw IllegalStateException("HTTP $status: ${response.optString("error")}${if (uncertain) "; uncertain=true" else ""}")
        }
        return response
    } finally {
        connection.disconnect()
    }
}

private object BengaliRomanizer {
    private val consonants = mapOf(
        'ক' to "k", 'খ' to "kh", 'গ' to "g", 'ঘ' to "gh", 'ঙ' to "ng",
        'চ' to "ch", 'ছ' to "chh", 'জ' to "j", 'ঝ' to "jh", 'ঞ' to "ny",
        'ট' to "t", 'ঠ' to "th", 'ড' to "d", 'ঢ' to "dh", 'ণ' to "n",
        'ত' to "t", 'থ' to "th", 'দ' to "d", 'ধ' to "dh", 'ন' to "n",
        'প' to "p", 'ফ' to "f", 'ব' to "b", 'ভ' to "bh", 'ম' to "m",
        'য' to "j", 'র' to "r", 'ল' to "l", 'শ' to "sh", 'ষ' to "sh",
        'স' to "s", 'হ' to "h", 'ড়' to "r", 'ঢ়' to "rh", 'য়' to "y",
        'ৎ' to "t",
    )
    private val independentVowels = mapOf(
        'অ' to "o", 'আ' to "a", 'ই' to "i", 'ঈ' to "i", 'উ' to "u",
        'ঊ' to "u", 'ঋ' to "ri", 'এ' to "e", 'ঐ' to "oi", 'ও' to "o", 'ঔ' to "ou",
    )
    private val vowelSigns = mapOf(
        'া' to "a", 'ি' to "i", 'ী' to "i", 'ু' to "u", 'ূ' to "u",
        'ৃ' to "ri", 'ে' to "e", 'ৈ' to "oi", 'ো' to "o", 'ৌ' to "ou",
    )

    fun transliterate(text: String): String {
        val regex = Regex("[\\u0980-\\u09FF]+")
        return regex.replace(text) { match -> transliterateBengaliWord(match.value) }
    }

    private fun transliterateBengaliWord(word: String): String {
        val result = StringBuilder()
        var index = 0
        var trailingInherentVowel = false
        while (index < word.length) {
            val char = word[index]
            val nasalOrVisarga = when (char) {
                'ং' -> "ng"
                'ঃ' -> "h"
                'ঁ' -> "n"
                else -> null
            }
            if (nasalOrVisarga != null) {
                result.append(nasalOrVisarga)
                trailingInherentVowel = false
                index++
                continue
            }
            val consonant = consonants[char]
            if (consonant != null) {
                result.append(consonant)
                index++
                trailingInherentVowel = false
                when {
                    index < word.length && vowelSigns.containsKey(word[index]) -> {
                        result.append(vowelSigns.getValue(word[index]))
                        index++
                    }
                    index < word.length && word[index] == '্' -> index++
                    else -> {
                        result.append('o')
                        trailingInherentVowel = true
                    }
                }
                continue
            }
            val vowel = independentVowels[char]
            if (vowel != null) {
                result.append(vowel)
                trailingInherentVowel = false
            } else {
                val sign = vowelSigns[char]
                if (sign != null) {
                    result.append(sign)
                    trailingInherentVowel = false
                } else {
                    result.append(char)
                    trailingInherentVowel = false
                }
            }
            index++
        }
        if (trailingInherentVowel && result.endsWith("o")) result.setLength(result.length - 1)
        return result.toString()
    }
}

