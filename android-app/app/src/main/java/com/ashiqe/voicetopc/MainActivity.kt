package com.ashiqe.voicetopc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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

class MainActivity : Activity() {
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
            setStatus("শুরু করার আগে Online অথবা Offline recognition বেছে নিন।")
        }
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(247, 249, 248))
        }
        val scroll = ScrollView(this)
        scroll.addView(outer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        outer.addView(TextView(this).apply {
            text = "Voice to PC"
            textSize = 28f
            setTextColor(Color.rgb(21, 71, 54))
            gravity = Gravity.CENTER
        }, matchWrap())
        outer.addView(TextView(this).apply {
            text = "ফোনে বলুন, PC-র active text field-এ লেখা বসবে।"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }, matchWrap())

        connectionView = TextView(this).apply { textSize = 15f }
        outer.addView(connectionView, matchWrap())
        pairButton = button("PC pair করুন") { startQrScan() }
        outer.addView(pairButton, matchWrap())

        outer.addView(TextView(this).apply {
            text = "Recognition privacy"
            textSize = 18f
            setPadding(0, dp(14), 0, dp(4))
        }, matchWrap())
        privacyView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }
        outer.addView(privacyView, matchWrap())
        onlineButton = button("Online: speech service ব্যবহার করবে") {
            choosePrivacy(online = true)
        }
        offlineButton = button("Offline only: model না থাকলে পাঠাবে না") {
            choosePrivacy(online = false)
        }
        outer.addView(onlineButton, matchWrap())
        outer.addView(offlineButton, matchWrap())

        outputButton = button("") { toggleOutputMode() }
        outer.addView(outputButton, matchWrap())
        languageButton = button("") { cycleLanguageMode() }
        outer.addView(languageButton, matchWrap())

        micButton = button("🎙  কথা বলুন") { beginDictation() }
        micButton.textSize = 20f
        outer.addView(micButton, matchWrap())
        statusView = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(10))
        }
        outer.addView(statusView, matchWrap())
        retryButton = button("আবার পাঠান") { retryPending() }
        discardButton = button("লেখাটি বাদ দিন") { discardPending() }
        outer.addView(retryButton, matchWrap())
        outer.addView(discardButton, matchWrap())
        outer.addView(TextView(this).apply {
            text = "পিসিতে আগে একটি text field-এ click করে caret রাখুন। এক tap-এ একটি dictation হবে; কথা শেষ হলে পাঠিয়ে mic বন্ধ হবে।"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }, matchWrap())
        setContentView(scroll)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun refreshUi() {
        val connection = SecureConnectionStore(this).load()
        connectionView.text = if (connection == null) "PC: pair করা নেই" else "PC: pair করা আছে"
        privacyView.text = when (privacyOnline) {
            true -> "Online mode নির্বাচিত। Recognition service audio বাইরে পাঠাতে পারে।"
            false -> "Offline only নির্বাচিত। ফোনে model না থাকলে recognition ব্যর্থ হবে।"
            null -> "Audio কোথায় process হবে তা নিজে বেছে নিন।"
        }
        onlineButton.setBackgroundColor(if (privacyOnline == true) Color.rgb(190, 230, 205) else Color.LTGRAY)
        offlineButton.setBackgroundColor(if (privacyOnline == false) Color.rgb(190, 230, 205) else Color.LTGRAY)
        outputButton.text = if (outputMode == "native") "Output: বাংলা অক্ষর   ⇄   Banglish" else "Output: Banglish   ⇄   বাংলা অক্ষর"
        languageButton.text = when {
            Build.VERSION.SDK_INT >= 34 && languageMode == "auto" -> "Speech: Auto Bangla + English   ⇄   ভাষা বাছুন"
            languageMode == "en" -> "Speech: English   ⇄   ভাষা বাছুন"
            else -> "Speech: বাংলা   ⇄   ভাষা বাছুন"
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
        setStatus(if (online) "Online recognition বেছে নেওয়া হয়েছে।" else "Offline-only recognition বেছে নেওয়া হয়েছে।")
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
        if (privacyOnline == null) {
            setStatus("আগে Online বা Offline recognition বেছে নিন।")
            return
        }
        try {
            IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("PC-তে দেখানো QR code scan করুন")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan()
        } catch (_: Exception) {
            setStatus("QR scanner চালু হয়নি। Camera permission ও ZXing module পরীক্ষা করুন।")
        }
    }

    @Deprecated("ZXing embedded scanner returns its result through this callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) return
        val raw = result.contents ?: run {
            setStatus("QR scan বাতিল হয়েছে।")
            return
        }
        pairFromQr(raw)
    }

    private fun pairFromQr(raw: String) {
        worker.execute {
            try {
                val json = JSONObject(raw)
                require(json.optString("app") == "voice-to-pc") { "এটি Voice to PC QR নয়।" }
                val host = json.getString("host")
                val address = InetAddress.getByName(host)
                require(address.isSiteLocalAddress || address.isLinkLocalAddress) {
                    "QR code-এ local PC address নেই।"
                }
                val port = json.getInt("port")
                val code = json.getString("code")
                val fingerprint = json.getString("fingerprint").lowercase()
                require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Certificate fingerprint ভুল।" }
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
                    setStatus("PC pair হয়েছে। এখন পিসির text field-এ click করে কথা বলুন।")
                }
            } catch (error: Exception) {
                runOnUiThread { setStatus("Pair করা যায়নি: ${error.message ?: "connection error"}") }
            }
        }
    }

    private fun beginDictation() {
        if (privacyOnline == null) {
            setStatus("আগে Online অথবা Offline recognition বেছে নিন।")
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
            setStatus(if (privacyOnline == true) "ফোনে speech recognition service পাওয়া যায়নি।" else "এই ফোনে offline speech recognition নেই; Online-এ নিজে mode না বদলালে কাজ করবে না।")
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
            micButton.text = "শুনছি…"
            refreshUi()
            setStatus("বলুন। কথা শেষ হলে transcript পাঠানো হবে।")
            recognizer?.startListening(intent)
        } catch (error: Exception) {
            stopRecognizer()
            setStatus("Mic চালু হয়নি: ${error.message ?: "speech service error"}")
        }
    }

    private fun recognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            runOnUiThread { setStatus("শুনছি…") }
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            runOnUiThread { setStatus("কথা শেষ। Transcript তৈরি হচ্ছে…") }
        }
        override fun onError(error: Int) {
            runOnUiThread {
                stopRecognizer()
                val explanation = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission নেই।"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "এই ভাষা recognition service সমর্থন করে না।"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "এই ভাষার model পাওয়া যায়নি। Offline mode-এ model download করুন বা language toggle বদলান।"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Recognition service-এ network error হয়েছে।"
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কথা শনাক্ত হয়নি; কিছু পাঠানো হয়নি।"
                    else -> "Speech recognition ব্যর্থ হয়েছে (code $error); কিছু পাঠানো হয়নি।"
                }
                setStatus(explanation)
            }
        }
        override fun onResults(results: Bundle?) {
            val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            runOnUiThread {
                stopRecognizer()
                if (transcript.isBlank()) {
                    setStatus("কোনো text শনাক্ত হয়নি।")
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
            micButton.text = "🎙  কথা বলুন"
            refreshUi()
        }
    }

    private fun sendPending() {
        val transcript = pendingText ?: return
        val id = pendingId ?: UUID.randomUUID().toString().also { pendingId = it }
        val connection = SecureConnectionStore(this).load()
        if (connection == null) {
            setStatus("PC pair করা নেই। Transcript ফোনে রাখা আছে; pair করে আবার পাঠান।")
            refreshUi()
            return
        }
        retryButton.isEnabled = false
        setStatus("PC-তে পাঠানো হচ্ছে…")
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
                if (!response.optBoolean("ok")) throw IllegalStateException("PC text গ্রহণ করেনি।")
                runOnUiThread {
                    pendingId = null
                    pendingText = null
                    pendingUncertain = false
                    refreshUi()
                    setStatus(if (response.optBoolean("duplicate")) "এই text আগেই পাঠানো হয়েছে; পুনরাবৃত্তি ঠেকানো হয়েছে." else "Text PC-তে বসেছে।")
                }
            } catch (error: Exception) {
                val uncertain = error.message?.contains("uncertain=true") == true
                runOnUiThread {
                    pendingUncertain = uncertain
                    refreshUi()
                    setStatus(if (uncertain) "PC-তে text আংশিক বসে থাকতে পারে। নিরাপত্তার জন্য retry বন্ধ আছে; field পরীক্ষা করে transcript বাদ দিন।" else "পাঠানো যায়নি। Transcript ফোনে আছে; connection ঠিক করে আবার পাঠান।")
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
        setStatus("Unsent transcript বাদ দেওয়া হয়েছে।")
    }

    private fun setStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginDictation()
            else setStatus("Mic permission ছাড়া voice recognition চালু হবে না।")
        }
    }

    override fun onDestroy() {
        stopRecognizer()
        worker.shutdownNow()
        super.onDestroy()
    }
}

private class SecureConnectionStore(private val activity: Activity) {
    private val preferences = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)

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
            val certificate = chain?.firstOrNull() ?: throw SecurityException("PC certificate নেই")
            val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actual.equals(fingerprint, ignoreCase = true)) {
                throw SecurityException("PC certificate fingerprint মেলেনি")
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
