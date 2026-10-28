package com.ashiqe.voicetopc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.ashiqe.voicetopc.ui.ButtonStyle
import com.ashiqe.voicetopc.ui.Design
import com.ashiqe.voicetopc.ui.Metric
import com.ashiqe.voicetopc.ui.Palette
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.io.File
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
private const val NOTIFICATION_PERMISSION_REQUEST = 403
private const val FINALIZE_TIMEOUT_MS = 10_000L
private const val UPDATE_APK_NAME = "voice-to-pc.apk"
internal const val PREFS_NAME = "voice_to_pc"
private const val KEY_ALIAS = "voice_to_pc_connection_key"
private const val KEY_ONLINE_MODE = "online_mode"
internal const val KEY_OUTPUT_MODE = "output_mode"
private const val KEY_LANGUAGE_MODE = "language_mode"

data class PcConnection(
    val host: String,
    val port: Int,
    val sessionToken: String,
    val fingerprint: String,
    val hostname: String = "",
)

data class UpdateInfo(val sha256: String, val size: Long)

class MainActivity : AppCompatActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private lateinit var statusView: TextView
    private lateinit var privacyView: TextView
    private lateinit var connectionView: TextView
    private lateinit var outputValue: TextView
    private lateinit var languageValue: TextView
    private lateinit var micButton: Button
    private lateinit var holdButton: Button
    private lateinit var pairButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var retryButton: Button
    private lateinit var discardButton: Button
    private lateinit var onlineButton: Button
    private lateinit var offlineButton: Button
    private lateinit var statusDot: TextView
    private lateinit var updateButton: Button
    private lateinit var updateView: TextView

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var pendingId: String? = null
    private var pendingText: String? = null
    private var pendingUncertain: Boolean = false
    private var privacyOnline: Boolean? = null
    // ponytail: outputMode stays a 3-string ("native"|"roman"|"english"); upgrade to enum when a 4th mode appears.
    private var outputMode: String = "native"
    private var languageMode: String = "auto"
    private var linkState: String = "unknown" // connected | unreachable | unpaired | unknown
    private var availableUpdate: UpdateInfo? = null
    private var updateBusy = false
    private var holdActive = false
    // Partial transcripts are previewed on the PC; they are never typed into the focused field.
    private val previewWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewLatest = AtomicReference<String?>(null)
    private val previewScheduled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    // Hold-to-talk finalizes through stopListening(); this covers a speech service that never answers.
    private val finalizeTimeout = Runnable {
        if (listening) {
            stopRecognizer()
            setStatus("No speech was recognized. Nothing was sent.")
        }
    }

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
        checkLink()
        requestNotificationPermission()
        // Keep the shade control available: dictate from any app without returning here.
        DictationNotifications.ensureReady(this)
    }

    /**
     * The foreground dictation service cannot post its notification without this on Android 13+.
     * Denial is harmless: dictation still works, only the ongoing notification is hidden.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }

    override fun onResume() {
        super.onResume()
        checkLink()
    }

    private fun checkLink() {
        val connection = SecureConnectionStore(this).load()
        if (connection == null) {
            linkState = "unpaired"
            runOnUiThread { refreshUi() }
            return
        }
        worker.execute {
            try {
                val health = getJson(connection.host, connection.port, connection.fingerprint, "/v1/health", connection.sessionToken)
                if (health.optBoolean("ok")) {
                    val hostname = health.optString("hostname")
                    if (hostname.isNotEmpty() && hostname != connection.hostname) {
                        SecureConnectionStore(this).save(connection.copy(hostname = hostname))
                    }
                    linkState = "connected"
                } else {
                    linkState = "unreachable"
                }
            } catch (_: Exception) {
                // Same-Wi-Fi sweep so a changed IP no longer needs a new QR scan.
                linkState = if (discoverPc(connection)) "connected" else "unreachable"
            }
            runOnUiThread { refreshUi() }
        }
    }

    private fun discoverPc(connection: PcConnection): Boolean {
        // ponytail: single /24 sweep only; upgrade to mDNS when multiple subnets matter.
        val subnets = linkedSetOf<String>()
        try {
            @Suppress("MissingPermission")
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val octets = intArrayOf(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
                subnets.add("${octets[0]}.${octets[1]}.${octets[2]}")
            }
        } catch (_: Exception) { }
        try {
            val hostIp = InetAddress.getByName(connection.host).hostAddress ?: ""
            val parts = hostIp.split(".")
            if (parts.size == 4) subnets.add(parts.take(3).joinToString("."))
        } catch (_: Exception) { }
        if (subnets.isEmpty()) subnets.add("192.168.1")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(24)
        return try {
            val futures = mutableListOf<java.util.concurrent.Future<Boolean>>()
            for (subnet in subnets) {
                for (last in 1..254) {
                    val host = "$subnet.$last"
                    futures.add(pool.submit<Boolean> {
                        try {
                            val health = getJson(host, connection.port, connection.fingerprint, "/v1/health", connection.sessionToken)
                            if (health.optBoolean("ok") && health.optString("hostname") == connection.hostname) {
                                SecureConnectionStore(this).save(connection.copy(host = host))
                                true
                            } else false
                        } catch (_: Exception) { false }
                    })
                }
            }
            var found = false
            for (future in futures) {
                try {
                    if (future.get(6, java.util.concurrent.TimeUnit.SECONDS)) { found = true; break }
                } catch (_: Exception) { }
            }
            found
        } finally {
            pool.shutdownNow()
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Palette.BACKGROUND)
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BACKGROUND)
            setPadding(dp(Metric.GUTTER), space(3), dp(Metric.GUTTER), space(6))
        }
        scroll.addView(outer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        // ---- brand header -----------------------------------------------------------------
        outer.addView(Design.overline(this, "VOICE TO PC", Palette.ACCENT), matchWrap())
        outer.addView(Design.title(this, "Speak. See it typed."), matchWrap().apply { topMargin = space(1) })
        outer.addView(
            Design.body(this, "Dictate on your phone and send the transcript to the active text field on your PC."),
            matchWrap().apply { topMargin = space(1) },
        )
        addGap(outer, 24)

        // ---- dictation: the primary action, so it sits first and reads as the hero ---------
        val dictationCard = card()
        dictationCard.addView(Design.overline(this, "DICTATION"), matchWrap())
        dictationCard.addView(Design.headline(this, "Capture speech"), matchWrap().apply { topMargin = space(1) })
        micButton = button("Start speaking", primary = true, hero = true) { beginDictation() }
        dictationCard.addView(micButton, buttonParams().apply { topMargin = space(2) })
        holdButton = button("Hold to talk", style = ButtonStyle.SECONDARY, hero = true) { }
        holdButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    holdActive = true
                    beginDictation()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    finalizeListening()
                    true
                }
                else -> false
            }
        }
        dictationCard.addView(holdButton, buttonParams().apply { topMargin = space(1) })
        val statusPanel = Design.panel(this)
        statusPanel.addView(Design.overline(this, "STATUS"), matchWrap())
        statusView = Design.value(this, "Ready when you are.").apply {
            setTextColor(Palette.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.START
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, space(1), 0, 0)
        }
        statusPanel.addView(statusView, matchWrap())
        dictationCard.addView(statusPanel, matchWrap().apply { topMargin = space(2) })
        retryButton = button("Retry sending") { retryPending() }
        dictationCard.addView(retryButton, buttonParams().apply { topMargin = space(2) })
        discardButton = button("Discard transcript", style = ButtonStyle.QUIET) { discardPending() }
        dictationCard.addView(discardButton, buttonParams().apply { topMargin = space(1) })
        addCard(outer, dictationCard)

        // ---- notification shade: talk without leaving the app you are in -------------------
        val shadeCard = card()
        shadeCard.addView(Design.overline(this, "ANY APP"), matchWrap())
        shadeCard.addView(Design.headline(this, "Talk from the notification shade"), matchWrap().apply { topMargin = space(1) })
        shadeCard.addView(
            Design.body(
                this,
                "Keep a Talk button in the shade. Swipe down, tap Talk, speak, and the text lands on your PC without closing the app you are in.",
            ),
            matchWrap().apply { topMargin = space(1) },
        )
        val shadeShow = button("Show Talk in the notification shade", primary = true) {
            DictationNotifications.ensureReady(this)
            setStatus("Voice to PC is pinned to the notification shade. Swipe down to reach it from any app.")
        }
        shadeCard.addView(shadeShow, buttonParams().apply { topMargin = space(2) })
        val shadeHide = button("Remove from the notification shade", style = ButtonStyle.QUIET) {
            DictationNotifications.dismiss(this)
            setStatus("Shade controls hidden. Reopen the app or tap Show to bring them back.")
        }
        shadeCard.addView(shadeHide, buttonParams().apply { topMargin = space(1) })
        addCard(outer, shadeCard)

        // ---- connection -------------------------------------------------------------------
        val connectionCard = card()
        connectionCard.addView(Design.overline(this, "CONNECTION"), matchWrap())
        connectionCard.addView(Design.headline(this, "Your PC"), matchWrap().apply { topMargin = space(1) })
        val statusPill = Design.pill(this)
        statusDot = TextView(this).apply {
            text = "\u25CF"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(0, 0, space(1), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        connectionView = Design.value(this, "")
        statusPill.addView(
            statusDot,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        statusPill.addView(
            connectionView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        connectionCard.addView(statusPill, matchWrap().apply { topMargin = space(2) })
        pairButton = button("Pair with PC", primary = true) { startQrScan() }
        connectionCard.addView(pairButton, buttonParams().apply { topMargin = space(2) })
        reconnectButton = button("Reconnect", style = ButtonStyle.QUIET) { checkLink() }
        connectionCard.addView(reconnectButton, buttonParams().apply { topMargin = space(1) })
        connectionCard.addView(Design.divider(this), matchWrap().apply { topMargin = space(2) })
        updateButton = button("Check for app update", style = ButtonStyle.QUIET) { onUpdateButton() }
        connectionCard.addView(updateButton, buttonParams().apply { topMargin = space(2) })
        updateView = Design.caption(this, "")
        updateView.visibility = View.GONE
        connectionCard.addView(updateView, matchWrap().apply { topMargin = space(1) })
        addCard(outer, connectionCard)

        // ---- recognition ------------------------------------------------------------------
        val recognitionCard = card()
        recognitionCard.addView(Design.overline(this, "SPEECH"), matchWrap())
        recognitionCard.addView(Design.headline(this, "Recognition source"), matchWrap().apply { topMargin = space(1) })
        privacyView = bodyText("")
        recognitionCard.addView(privacyView, matchWrap().apply { topMargin = space(1) })
        val privacyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        onlineButton = button("Online", primary = false) { choosePrivacy(online = true) }
        offlineButton = button("Offline only", primary = false) { choosePrivacy(online = false) }
        privacyRow.addView(
            onlineButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        privacyRow.addView(
            offlineButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = space(1)
            },
        )
        recognitionCard.addView(privacyRow, matchWrap().apply { topMargin = space(2) })
        addCard(outer, recognitionCard)

        // ---- output -----------------------------------------------------------------------
        val optionsCard = card()
        optionsCard.addView(Design.overline(this, "OUTPUT"), matchWrap())
        optionsCard.addView(Design.headline(this, "Text and language"), matchWrap().apply { topMargin = space(1) })
        val outputRow = Design.settingRow(this, "TEXT FORMAT", "Bengali script") { outputValue = it }
        outputRow.setOnClickListener { toggleOutputMode() }
        optionsCard.addView(outputRow, matchWrap().apply { topMargin = space(1) })
        optionsCard.addView(Design.divider(this), matchWrap())
        val languageRow = Design.settingRow(this, "SPEECH LANGUAGE", "Bangla") { languageValue = it }
        languageRow.setOnClickListener { cycleLanguageMode() }
        optionsCard.addView(languageRow, matchWrap())
        addCard(outer, optionsCard)

        outer.addView(
            Design.caption(
                this,
                "Click the target text field on your PC before speaking. Each utterance is sent once, when you finish talking.",
            ).apply { gravity = Gravity.CENTER },
            matchWrap().apply { topMargin = space(1) },
        )
        setContentView(scroll)
    }

    private fun button(
        label: String,
        primary: Boolean = false,
        style: ButtonStyle = if (primary) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
        hero: Boolean = false,
        action: () -> Unit,
    ): Button = Design.button(this, label, style, hero, action)

    /** One standard surface card from the shared design system. */
    private fun card(): LinearLayout = Design.card(this)

    private fun addCard(parent: LinearLayout, child: LinearLayout) {
        parent.addView(child, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(Metric.CARD_GAP) })
    }

    private fun addGap(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun bodyText(label: String): TextView = Design.body(this, label)

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun applyChoiceStyle(button: Button, selected: Boolean) {
        button.background = Design.choiceBackground(this, selected)
        button.setTextColor(Design.choiceTextColors(selected))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** One 8dp spacing step, so vertical rhythm stays on the design scale. */
    /** One 8dp spacing step, so vertical rhythm stays on the design scale. */
    private fun space(steps: Int): Int = dp(steps * Metric.SPACING_UNIT)

    private fun refreshUi() {
        val connection = SecureConnectionStore(this).load()
        val dotColor = when {
            connection == null -> Palette.DISABLED_TEXT
            linkState == "connected" -> Palette.SUCCESS
            linkState == "unreachable" -> Palette.DANGER
            else -> Palette.WARNING
        }
        if (::statusDot.isInitialized) statusDot.setTextColor(dotColor)
        if (::statusDot.isInitialized) statusDot.text = "●"
        connectionView.text = when {
            connection == null -> "Not connected"
            linkState == "connected" -> "Connected · ${connection.host}"
            linkState == "unreachable" -> "PC unreachable · tap Reconnect"
            else -> "Checking…"
        }
        privacyView.text = when (privacyOnline) {
            true -> "Online recognition may send audio to your phone's speech service."
            false -> "Offline only uses a model on your phone. It will not switch to online recognition."
            null -> "Choose where speech is recognized. Select a mode before dictation."
        }
        applyChoiceStyle(onlineButton, privacyOnline == true)
        applyChoiceStyle(offlineButton, privacyOnline == false)
        outputValue.text = when (outputMode) {
            "roman" -> "Banglish"
            "english" -> "English"
            else -> "Bengali script"
        }
        languageValue.text = when {
            Build.VERSION.SDK_INT >= 34 && languageMode == "auto" -> "Auto (Bangla + English)"
            outputMode == "english" -> "English (output lock)"
            languageMode == "en" -> "English"
            else -> "Bangla"
        }
        val hasPending = pendingText != null
        retryButton.visibility = if (hasPending) View.VISIBLE else View.GONE
        discardButton.visibility = if (hasPending) View.VISIBLE else View.GONE
        retryButton.isEnabled = connection != null && !listening && !pendingUncertain
        micButton.isEnabled = privacyOnline != null && !listening && !hasPending
        if (::holdButton.isInitialized) {
            holdButton.isEnabled = privacyOnline != null && !hasPending
        }
        pairButton.isEnabled = !listening
        if (::reconnectButton.isInitialized) {
            reconnectButton.visibility = if (connection != null && linkState != "connected") View.VISIBLE else View.GONE
            reconnectButton.isEnabled = !listening
        }
        if (::updateButton.isInitialized) {
            updateButton.isEnabled = connection != null && !updateBusy
            updateButton.text = when {
                updateBusy -> "Checking for update…"
                availableUpdate != null -> "Install update"
                else -> "Check for app update"
            }
            val pending = availableUpdate
            if (pending != null) {
                updateView.visibility = View.VISIBLE
                updateView.text = "Update ready · ${pending.size / 1024} KB · tap Install update"
            } else {
                updateView.visibility = View.GONE
            }
        }
    }

    private fun choosePrivacy(online: Boolean) {
        privacyOnline = online
        preferences.edit().putBoolean(KEY_ONLINE_MODE, online).apply()
        refreshUi()
        setStatus(if (online) "Online recognition selected." else "Offline-only recognition selected.")
    }

    private fun toggleOutputMode() {
        outputMode = when (outputMode) {
            "native" -> "roman"
            "roman" -> "english"
            else -> "native"
        }
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
                val hostname = response.optString("hostname")
                SecureConnectionStore(this).save(PcConnection(host, port, token, fingerprint, hostname))
                runOnUiThread {
                    linkState = "connected"
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
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                when {
                    outputMode == "english" -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
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
            if (::holdButton.isInitialized) {
                holdButton.text = if (holdActive) "Release to send" else "Hold to talk"
            }
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
                queuePreview("")
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
                val output = transformOutput(transcript)
                pendingId = UUID.randomUUID().toString()
                pendingText = output
                pendingUncertain = false
                refreshUi()
                sendPending()
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) queuePreview(transformOutput(text))
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun transformOutput(text: String): String = when (outputMode) {
        "roman" -> BengaliRomanizer.transliterate(text)
        "english" -> EnglishNormalizer.normalize(text)
        else -> text
    }

    /**
     * Preview updates are best-effort: keep only the newest text and drop the rest, so a slow
     * network can never build a backlog ahead of the final transcript.
     */
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

    /** Hold-to-talk: ask the recognizer to finalize so onResults can deliver the transcript. */
    private fun finalizeListening() {
        val active = recognizer
        if (active == null) {
            stopRecognizer()
            return
        }
        try {
            active.stopListening()
            setStatus("Finishing your utterance...")
            mainHandler.removeCallbacks(finalizeTimeout)
            mainHandler.postDelayed(finalizeTimeout, FINALIZE_TIMEOUT_MS)
        } catch (error: Exception) {
            stopRecognizer()
            setStatus("Could not finish the recording: ${error.message ?: "speech service error"}")
        }
    }

    private fun stopRecognizer() {
        listening = false
        holdActive = false
        mainHandler.removeCallbacks(finalizeTimeout)
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
        if (::micButton.isInitialized) {
            micButton.text = "Start speaking"
            refreshUi()
        }
        if (::holdButton.isInitialized) holdButton.text = "Hold to talk"
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

    private fun onUpdateButton() {
        val connection = SecureConnectionStore(this).load()
        if (connection == null) {
            setStatus("Pair with your PC first, then check for updates.")
            return
        }
        if (availableUpdate != null) {
            installAvailableUpdate(connection)
            return
        }
        updateBusy = true
        refreshUi()
        setStatus("Checking the PC for a newer app build…")
        worker.execute {
            try {
                val release = getJson(
                    connection.host,
                    connection.port,
                    connection.fingerprint,
                    "/v1/app-release",
                    connection.sessionToken,
                )
                val remote = release.optString("sha256").lowercase()
                val size = release.optLong("size")
                val found = if (remote.isNotEmpty() && remote != installedApkSha256()) {
                    UpdateInfo(remote, size)
                } else {
                    null
                }
                runOnUiThread {
                    updateBusy = false
                    availableUpdate = found
                    refreshUi()
                    setStatus(
                        if (found != null) {
                            "A newer build is on your PC (${found.size / 1024} KB). Tap Install update."
                        } else {
                            "The installed app already matches the build on your PC."
                        }
                    )
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateBusy = false
                    refreshUi()
                    setStatus("Could not check for updates: ${error.message ?: "connection error"}")
                }
            }
        }
    }

    /** SHA-256 of the APK this phone is currently running, used to detect a newer build. */
    private fun installedApkSha256(): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        File(applicationInfo.sourceDir).inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (_: Exception) {
        null
    }

    private fun installAvailableUpdate(connection: PcConnection) {
        if (!packageManager.canRequestPackageInstalls()) {
            setStatus("Allow Voice to PC to install updates, then tap Install update again.")
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                setStatus("Open Settings and allow installs from Voice to PC.")
            }
            return
        }
        updateBusy = true
        refreshUi()
        setStatus("Downloading the update from your PC…")
        worker.execute {
            try {
                val bytes = getBytes(
                    connection.host,
                    connection.port,
                    connection.fingerprint,
                    "/v1/app.apk",
                    connection.sessionToken,
                )
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val expected = availableUpdate?.sha256
                if (expected != null && digest != expected) {
                    throw IllegalStateException("The downloaded file did not match the PC copy.")
                }
                val folder = File(cacheDir, "updates").apply { mkdirs() }
                val target = File(folder, UPDATE_APK_NAME)
                target.outputStream().use { it.write(bytes) }
                runOnUiThread {
                    updateBusy = false
                    refreshUi()
                    launchInstaller(target)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateBusy = false
                    refreshUi()
                    setStatus("Update download failed: ${error.message ?: "connection error"}")
                }
            }
        }
    }

    private fun launchInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            setStatus("Confirm the install in the system dialog.")
        } catch (error: Exception) {
            setStatus("Could not open the installer: ${error.message ?: "no installer available"}")
        }
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
            // Denial is fine: dictation still works, the foreground service just stays silent.
            NOTIFICATION_PERMISSION_REQUEST -> Unit
        }
    }

    override fun onDestroy() {
        stopRecognizer()
        worker.shutdownNow()
        previewWorker.shutdownNow()
        super.onDestroy()
    }
}

internal class SecureConnectionStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(connection: PcConnection) {
        val json = JSONObject()
            .put("host", connection.host)
            .put("port", connection.port)
            .put("sessionToken", connection.sessionToken)
            .put("fingerprint", connection.fingerprint)
            .put("hostname", connection.hostname)
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
                json.optString("hostname"),
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

internal fun postJson(
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
    return openJson(trustManager, host, port, path, body.toString(), sessionToken, "GET")
}

internal fun getJson(
    host: String,
    port: Int,
    fingerprint: String,
    path: String,
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
    return openJson(trustManager, host, port, path, null, sessionToken, "GET")
}

private fun getBytes(
    host: String,
    port: Int,
    fingerprint: String,
    path: String,
    sessionToken: String?,
): ByteArray {
    val url = URL("https://$host:$port$path")
    val connection = (url.openConnection() as HttpsURLConnection).apply {
        sslSocketFactory = pinnedContext(fingerprint).socketFactory
        hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        connectTimeout = 5_000
        readTimeout = 30_000
        requestMethod = "GET"
        setRequestProperty("Cache-Control", "no-store")
        if (sessionToken != null) setRequestProperty("Authorization", "Bearer $sessionToken")
    }
    try {
        val status = connection.responseCode
        if (status !in 200..299) {
            throw IllegalStateException("HTTP $status while downloading the update")
        }
        return connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}

private fun pinnedContext(fingerprint: String): SSLContext {
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
    return SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
}

private fun openJson(
    trustManager: X509TrustManager,
    host: String,
    port: Int,
    path: String,
    requestText: String?,
    sessionToken: String?,
    method: String,
): JSONObject {
    val context = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
    val url = URL("https://$host:$port$path")
    val connection = (url.openConnection() as HttpsURLConnection).apply {
        sslSocketFactory = context.socketFactory
        hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        connectTimeout = if (method == "GET") 2_500 else 5_000
        readTimeout = if (method == "GET") 4_000 else 8_000
        requestMethod = method
        if (requestText != null) {
            doOutput = true
        }
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Cache-Control", "no-store")
        if (sessionToken != null) setRequestProperty("Authorization", "Bearer $sessionToken")
    }
    try {
        if (requestText != null) {
            val requestBytes = requestText.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(requestBytes.size)
            connection.outputStream.use { it.write(requestBytes) }
        }
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

internal object EnglishNormalizer {
    // ponytail: puny local cleanup only; upgrade to on-device translation model when user asks for real bn→en translation.
    fun normalize(text: String): String {
        var out = text.replace(Regex("[\\u0980-\\u09FF]+")) { m -> BengaliRomanizer.transliterate(m.value) }
        out = out.replace(Regex("\\s+"), " ").trim()
        if (out.isEmpty()) return out
        return out[0].uppercaseChar() + out.substring(1)
    }
}

internal object BengaliRomanizer {
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

