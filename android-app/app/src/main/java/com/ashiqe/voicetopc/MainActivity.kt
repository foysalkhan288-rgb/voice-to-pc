package com.ashiqe.voicetopc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import com.ashiqe.voicetopc.ui.ButtonStyle
import com.ashiqe.voicetopc.ui.Design
import com.ashiqe.voicetopc.ui.Metric
import com.ashiqe.voicetopc.ui.Palette
import com.ashiqe.voicetopc.ui.SegmentedControl
import com.ashiqe.voicetopc.ui.TalkButton
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
import java.util.concurrent.atomic.AtomicInteger
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
/** On-device recognition finalizes quickly; the timeout only guards a stuck recognizer. */
private const val FINALIZE_TIMEOUT_MS = 15_000L
private const val SESSION_TIMEOUT_MS = 180_000L

/** Peak 16-bit sample considered speech rather than room noise. */
private const val MIC_TEST_SPEECH_PEAK = 100

private const val UPDATE_APK_NAME = "speaktype.apk"
internal const val PREFS_NAME = "speak_type"
private const val KEY_ALIAS = "speak_type_connection_key"
internal const val KEY_OUTPUT_MODE = "output_mode"
internal const val KEY_LANGUAGE_MODE = "language_mode"
private const val KEY_TALK_MODE = "talk_mode"
private const val KEY_THEME = "theme_mode"
private const val DEFAULT_THEME = "dark"
private const val STATE_PAGE = "page_index"

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
    private lateinit var statusLiveDot: TextView
    private lateinit var connectionView: TextView
    private lateinit var outputValue: TextView
    private lateinit var languageValue: TextView
    private lateinit var talkButton: TalkButton
    private lateinit var modeTabs: SegmentedControl
    private lateinit var modeHintView: TextView
    private lateinit var pageTabs: SegmentedControl
    private lateinit var homePage: ScrollView
    private lateinit var settingsPage: ScrollView
    private lateinit var homeConnectionView: TextView
    private lateinit var homeConnectionDot: TextView
    private lateinit var pairButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var retryButton: Button
    private lateinit var discardButton: Button
    private lateinit var statusDot: TextView
    private lateinit var updateButton: Button
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateBannerText: TextView
    private lateinit var updateBannerAction: TextView
    private lateinit var updateView: TextView
    private lateinit var voiceDiagView: TextView
    private lateinit var permissionValue: TextView
    private lateinit var micTestView: TextView
    private lateinit var micTestButton: Button

    private var micSession: SpeechSession? = null
    private var listening = false
    private var lastVoiceError: String? = null
    private var pendingId: String? = null
    private var pendingText: String? = null
    private var pendingUncertain: Boolean = false
    private var pendingMicStart = false
    private var lastMicTest: String? = null
    // ponytail: outputMode stays a 3-string ("native"|"roman"|"english"); upgrade to enum when a 4th mode appears.
    private var outputMode: String = "native"
    private var languageMode: String = "auto"
    private var linkState: String = "unknown" // connected | unreachable | unpaired | unknown
    private var linkError: String? = null // cert | network
    // Bumped on every successful pairing so an older in-flight link check cannot overwrite it.
    private val connectionGeneration = AtomicInteger(0)
    private var availableUpdate: UpdateInfo? = null
    private var githubUpdateUrl: String? = null
    private var updateBusy = false
    private var holdMode: Boolean = true
    private var themeMode: String = DEFAULT_THEME
    private var currentPage: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    // Hold-to-talk finalizes through stop(); this covers a recognizer that never answers.
    private val finalizeTimeout = Runnable {
        if (listening) {
            micSession?.cancel()
            stopMicSession()
            setStatus("Speech recognition timed out. Nothing was sent.")
        }
    }
    // Backstop for a recognizer that goes silent without delivering results.
    private val sessionTimeout = Runnable {
        if (listening) finalizeListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark is the default look; applying it before the activity attaches avoids a light flash.
        themeMode = preferences.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
        AppCompatDelegate.setDefaultNightMode(nightModeFor(themeMode))
        super.onCreate(savedInstanceState)
        Palette.applyDarkMode(isNightMode())
        applyWindowTheme()
        outputMode = preferences.getString(KEY_OUTPUT_MODE, "native") ?: "native"
        languageMode = preferences.getString(KEY_LANGUAGE_MODE, "auto") ?: "auto"
        holdMode = preferences.getString(KEY_TALK_MODE, "hold") != "tap"
        buildUi()
        refreshUi()
        // A theme switch recreates the activity; keep the user on the page they were reading.
        showPage(savedInstanceState?.getInt(STATE_PAGE, 0) ?: 0)
        createUpdateNotificationChannel()
        checkLink()
        requestNotificationPermission()
        // Automatically look for a newer build on launch.
        autoCheckForUpdate()
        // Keep the shade control available: dictate from any app without returning here.
        DictationNotifications.ensureReady(this)
    }

    private fun nightModeFor(mode: String): Int = when (mode) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /** Window chrome follows the palette, so the status and nav bars blend into the page. */
    private fun applyWindowTheme() {
        val dark = Palette.isDark
        window.setBackgroundDrawable(ColorDrawable(Palette.BACKGROUND))
        @Suppress("DEPRECATION")
        window.statusBarColor = Palette.BACKGROUND
        @Suppress("DEPRECATION")
        window.navigationBarColor = Palette.BACKGROUND
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    /**
     * Persists the choice and lets AppCompat recreate the activity, which rebuilds every view from
     * the new palette. Selecting the already-active mode is a no-op.
     */
    private fun chooseTheme(mode: String) {
        if (mode == themeMode) return
        themeMode = mode
        preferences.edit().putString(KEY_THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(nightModeFor(mode))
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
        // Permission and engine choices can change in the phone settings while we are away.
        if (::permissionValue.isInitialized) refreshUi()
        checkLink()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, currentPage)
    }

    private fun createUpdateNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel("speaktype_updates") == null) {
            val channel = NotificationChannel(
                "speaktype_updates",
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Notifies when a newer SpeakType build is available." }
            manager.createNotificationChannel(channel)
        }
    }

    private fun notifyUpdateAvailable(info: UpdateInfo, fromPc: Boolean) {
        val source = if (fromPc) "your PC" else "GitHub"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = androidx.core.app.NotificationCompat.Builder(this, "speaktype_updates")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("SpeakType update available")
            .setContentText("A newer build (${info.size / 1024} KB) is on $source. Tap to install.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        manager.notify(2001, notification)
    }

    private fun checkLink() {
        val generation = connectionGeneration.get()
        val connection = SecureConnectionStore(this).load()
        if (connection == null) {
            linkState = "unpaired"
            linkError = null
            runOnUiThread { refreshUi(); autoCheckForUpdate() }
            return
        }
        worker.execute {
            try {
                val health = getJson(connection.host, connection.port, connection.fingerprint, "/v1/health", connection.sessionToken)
                if (generation != connectionGeneration.get()) return@execute
                if (health.optBoolean("ok")) {
                    val hostname = health.optString("hostname")
                    if (hostname.isNotEmpty() && hostname != connection.hostname) {
                        SecureConnectionStore(this).save(connection.copy(hostname = hostname))
                    }
                    linkState = "connected"
                    linkError = null
                } else {
                    linkState = "unreachable"
                    linkError = "network"
                }
            } catch (error: Exception) {
                if (generation != connectionGeneration.get()) return@execute
                // Same-Wi-Fi sweep so a changed IP no longer needs a new QR scan.
                val recovered = discoverPc(connection, generation)
                if (generation != connectionGeneration.get()) return@execute
                linkState = if (recovered) "connected" else "unreachable"
                linkError = when {
                    recovered -> null
                    isCertificateError(error) -> "cert"
                    else -> "network"
                }
            }
            runOnUiThread { refreshUi(); autoCheckForUpdate() }
        }
    }

    private fun isCertificateError(error: Exception): Boolean {
        val text = (error.message ?: "") + " " + (error.cause?.message ?: "")
        return text.contains("fingerprint", ignoreCase = true) || text.contains("certificate", ignoreCase = true)
    }

    private fun discoverPc(connection: PcConnection, generation: Int): Boolean {
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
                            // The pinned certificate already proves this is the paired PC; a
                            // renamed PC must not block rediscovery after an IP change.
                            if (health.optBoolean("ok") && generation == connectionGeneration.get()) {
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BACKGROUND)
            clipChildren = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        // Two pages share the window: talking first, every technical switch behind Settings.
        homePage = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Palette.BACKGROUND)
        }
        settingsPage = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Palette.BACKGROUND)
            visibility = View.GONE
        }
        val homeColumn = pageColumn()
        val settingsColumn = pageColumn()
        homePage.addView(homeColumn, frameWrap())
        settingsPage.addView(settingsColumn, frameWrap())
        val pages = FrameLayout(this)
        pages.addView(homePage, frameFull())
        pages.addView(settingsPage, frameFull())

        // ---- home header: label and connection on one line, the title full width below ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            Design.overline(this, "VOICE TO PC", Palette.ACCENT),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val homePill = Design.pill(this).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(Design.rippleColor),
                Design.fill(this@MainActivity, Palette.SURFACE, Metric.PILL_RADIUS, Palette.BORDER, Metric.HAIRLINE_WIDTH),
                Design.fill(this@MainActivity, Palette.SURFACE, Metric.PILL_RADIUS),
            )
            isClickable = true
            setOnClickListener { showPage(1) }
        }
        homeConnectionDot = Design.dot(this, Palette.DISABLED_TEXT).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        homePill.addView(
            homeConnectionDot,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = space(1) },
        )
        homeConnectionView = Design.value(this, "").apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(112)
        }
        homePill.addView(
            homeConnectionView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        header.addView(
            homePill,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = space(1) },
        )
        homeColumn.addView(header, matchWrap())
        homeColumn.addView(
            Design.title(this, "Speak. See it typed."),
            matchWrap().apply { topMargin = space(1) },
        )

        updateBanner = Design.cardTinted(this, Palette.ACCENT_SOFT).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setOnClickListener { onUpdateButton() }
        }
        updateBanner.addView(
            Design.dot(this, Palette.ACCENT),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = space(1) },
        )
        updateBannerText = Design.value(this, "").apply {
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }
        updateBanner.addView(
            updateBannerText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        updateBannerAction = Design.caption(this, "Install").apply { setTextColor(Palette.ACCENT) }
        updateBanner.addView(
            updateBannerAction,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        homeColumn.addView(updateBanner, matchWrap().apply { topMargin = space(2) })

        // ---- live status: a single line, no section label ---------------------------------
        val statusPanel = Design.panel(this).apply { minimumHeight = space(6) }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusLiveDot = Design.dot(this, Palette.MUTED)
        statusRow.addView(
            statusLiveDot,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = space(1) },
        )
        statusView = Design.value(this, "Ready when you are.").apply {
            setTextColor(Palette.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.START
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        statusRow.addView(
            statusView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        statusPanel.addView(statusRow, matchWrap())
        homeColumn.addView(statusPanel, matchWrap().apply { topMargin = space(2) })

        retryButton = button("Retry sending") { retryPending() }
        homeColumn.addView(retryButton, buttonParams().apply { topMargin = space(2) })
        discardButton = button("Discard transcript", style = ButtonStyle.QUIET) { discardPending() }
        homeColumn.addView(discardButton, buttonParams().apply { topMargin = space(1) })

        homeColumn.addView(
            Design.caption(
                this,
                "Click the target text field on your PC before speaking. Each utterance is sent once, when you finish talking.",
            ).apply { gravity = Gravity.CENTER },
            matchWrap().apply { topMargin = space(2) },
        )

        // ---- the one control that matters, pinned low for the thumb -----------------------
        // Flexible spacer: when the page is shorter than the screen the talk cluster
        // sinks to the bottom instead of floating mid-page.
        homeColumn.addView(
            View(this),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, space(2), 1f),
        )
        talkButton = TalkButton(this)
        talkButton.onTalkStart = { beginDictation() }
        talkButton.onTalkStop = { finalizeListening() }
        // A gesture the system interrupted must never send a half utterance.
        talkButton.onTalkCancel = { cancelDictation() }
        homeColumn.addView(
            talkButton,
            LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = space(3)
            },
        )

        modeTabs = SegmentedControl(this).apply {
            setItems(listOf("Hold", "Tap"), if (holdMode) 0 else 1)
            onSelect = { index ->
                holdMode = index == 0
                preferences.edit().putString(KEY_TALK_MODE, if (holdMode) "hold" else "tap").apply()
                refreshUi()
            }
        }
        homeColumn.addView(
            modeTabs,
            LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = space(1)
            },
        )
        modeHintView = Design.caption(this, "").apply { gravity = Gravity.CENTER }
        homeColumn.addView(modeHintView, matchWrap().apply { topMargin = space(1) })

        // ---- settings page: everything technical, in one calm stack -----------------------
        settingsColumn.addView(Design.title(this, "Settings"), matchWrap())
        settingsColumn.addView(
            Design.body(this, "Pairing, recognition and text options live here."),
            matchWrap().apply { topMargin = space(1) },
        )
        addGap(settingsColumn, 16)

        // ---- appearance: dark is the default, light matches the PC dashboard --------------
        val appearanceCard = card()
        appearanceCard.addView(Design.overline(this, "APPEARANCE"), matchWrap())
        appearanceCard.addView(Design.headline(this, "Theme"), matchWrap().apply { topMargin = space(1) })
        appearanceCard.addView(
            Design.body(this, "Dark keeps the talk screen calm at night. Light matches the PC dashboard."),
            matchWrap().apply { topMargin = space(1) },
        )
        val themeTabs = SegmentedControl(this).apply {
            setItems(listOf("System", "Light", "Dark"), themeModeIndex())
            onSelect = { index ->
                chooseTheme(
                    when (index) {
                        1 -> "light"
                        2 -> "dark"
                        else -> "system"
                    },
                )
            }
        }
        appearanceCard.addView(themeTabs, matchWrap().apply { topMargin = space(2) })
        addCard(settingsColumn, appearanceCard)

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
        addCard(settingsColumn, connectionCard)

        // ---- recognition ------------------------------------------------------------------
        val recognitionCard = card()
        recognitionCard.addView(Design.overline(this, "SPEECH"), matchWrap())
        recognitionCard.addView(Design.headline(this, "Recognition"), matchWrap().apply { topMargin = space(1) })
        recognitionCard.addView(
            Design.body(
                this,
                "Your phone recognizes speech with its own speech service — offline when the language pack is installed — and sends only text to the PC. Without an offline pack, the service may recognize online. Nothing is downloaded on the PC.",
            ),
            matchWrap().apply { topMargin = space(1) },
        )
        recognitionCard.addView(Design.divider(this), matchWrap())
        val permissionRow = Design.settingRow(this, "MICROPHONE ACCESS", "Granted") { permissionValue = it }
        permissionRow.setOnClickListener { onMicrophoneRow() }
        recognitionCard.addView(permissionRow, matchWrap())
        recognitionCard.addView(Design.divider(this), matchWrap())
        micTestButton = button("Test microphone (3 s)", style = ButtonStyle.QUIET) { runMicTest() }
        recognitionCard.addView(micTestButton, buttonParams().apply { topMargin = space(2) })
        micTestView = Design.caption(
            this,
            "Records 3 seconds locally to confirm the app is actually receiving sound.",
        )
        recognitionCard.addView(micTestView, matchWrap().apply { topMargin = space(1) })
        addCard(settingsColumn, recognitionCard)

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
        addCard(settingsColumn, optionsCard)

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
            setStatus("SpeakType is pinned to the notification shade. Swipe down to reach it from any app.")
        }
        shadeCard.addView(shadeShow, buttonParams().apply { topMargin = space(2) })
        val shadeHide = button("Remove from the notification shade", style = ButtonStyle.QUIET) {
            DictationNotifications.dismiss(this)
            setStatus("Shade controls hidden. Reopen the app or tap Show to bring them back.")
        }
        shadeCard.addView(shadeHide, buttonParams().apply { topMargin = space(1) })
        addCard(settingsColumn, shadeCard)

        // ---- about ------------------------------------------------------------------------
        val aboutCard = card()
        aboutCard.addView(Design.overline(this, "ABOUT"), matchWrap())
        aboutCard.addView(
            Design.headline(this, "SpeakType ${BuildConfig.VERSION_NAME}"),
            matchWrap().apply { topMargin = space(1) },
        )
        aboutCard.addView(
            Design.body(this, "Build ${BuildConfig.VERSION_CODE} · Updates arrive over the air from your PC or GitHub."),
            matchWrap().apply { topMargin = space(1) },
        )
        voiceDiagView = Design.caption(
            this,
            lastVoiceError ?: "Last voice error: none this session.",
        )
        aboutCard.addView(voiceDiagView, matchWrap().apply { topMargin = space(1) })
        val copyDiagnosticsButton = button("Copy diagnostics", style = ButtonStyle.QUIET) { copyDiagnostics() }
        aboutCard.addView(copyDiagnosticsButton, buttonParams().apply { topMargin = space(2) })
        aboutCard.addView(
            Design.caption(this, "Your PC must be on the same Wi-Fi network."),
            matchWrap().apply { topMargin = space(1) },
        )
        addCard(settingsColumn, aboutCard)

        settingsColumn.addView(
            Design.caption(
                this,
                "Pairing trusts the certificate fingerprint shown in the QR code. Transcripts are sent only to your paired PC.",
            ).apply { gravity = Gravity.CENTER },
            matchWrap().apply { topMargin = space(2) },
        )

        // ---- top nav: page switch up top so the talk button owns the bottom ---------------
        pageTabs = SegmentedControl(this).apply {
            setItems(listOf("Talk", "Settings"), 0)
            elevation = dp(8).toFloat()
            onSelect = { index -> if (index in 0..1) showPage(index) }
        }
        val nav = FrameLayout(this).apply {
            setPadding(dp(Metric.GUTTER), space(1), dp(Metric.GUTTER), space(1))
            // Let the pill's elevation shadow fade into the page instead of
            // ending on a hard clip line at the wrapper bounds.
            clipChildren = false
            clipToPadding = false
        }
        nav.addView(
            pageTabs,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            nav,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            pages,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
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

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    /** One scrollable page: gutters on the sides; the nav sits above, not below. */
    private fun pageColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Metric.GUTTER), space(2), dp(Metric.GUTTER), space(3))
    }

    private fun frameFull() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun frameWrap() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
    )

    /** Two fixed pages: the floating nav is the only way between them. */
    private fun showPage(index: Int) {
        if (!::homePage.isInitialized || !::settingsPage.isInitialized) return
        val toSettings = index == 1
        currentPage = if (toSettings) 1 else 0
        homePage.visibility = if (toSettings) View.GONE else View.VISIBLE
        settingsPage.visibility = if (toSettings) View.VISIBLE else View.GONE
        // select() does not fire onSelect, so this cannot loop back into showPage.
        if (::pageTabs.isInitialized) pageTabs.select(if (toSettings) 1 else 0)
        if (toSettings) refreshUi()
    }

    private fun themeModeIndex(): Int = when (themeMode) {
        "light" -> 1
        "dark" -> 2
        else -> 0
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        val connectionLabel = when {
            connection == null -> "Not connected"
            linkState == "connected" -> "Connected · ${connection.host}"
            linkState == "unreachable" && linkError == "cert" ->
                "PC certificate changed · scan the QR code again"
            linkState == "unreachable" -> "PC unreachable · ${connection.host} · tap Reconnect"
            else -> "Checking…"
        }
        if (::statusDot.isInitialized) statusDot.setTextColor(dotColor)
        if (::statusDot.isInitialized) statusDot.text = "●"
        connectionView.text = connectionLabel
        if (::homeConnectionDot.isInitialized) homeConnectionDot.setTextColor(dotColor)
        if (::homeConnectionView.isInitialized) {
            val shortLabel = when {
                connection == null -> "Not paired"
                linkState == "connected" -> "Connected"
                linkState == "unreachable" && linkError == "cert" -> "Scan QR again"
                linkState == "unreachable" -> "PC offline"
                else -> "Checking…"
            }
            homeConnectionView.text = shortLabel
            homeConnectionView.parent?.let { pill ->
                (pill as? View)?.contentDescription = "Connection: $shortLabel. Open settings."
            }
        }
        if (::permissionValue.isInitialized) {
            permissionValue.text =
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "Granted · tap to manage"
                } else {
                    "Not granted · tap to allow"
                }
        }
        outputValue.text = when (outputMode) {
            "roman" -> "Banglish"
            "english" -> "English"
            else -> "Bengali script"
        }
        languageValue.text = when {
            outputMode == "english" -> "English (output lock)"
            languageMode == "auto" -> "Auto (phone default)"
            languageMode == "en" -> "English"
            else -> "Bangla"
        }
        val hasPending = pendingText != null
        retryButton.visibility = if (hasPending) View.VISIBLE else View.GONE
        discardButton.visibility = if (hasPending) View.VISIBLE else View.GONE
        retryButton.isEnabled = connection != null && !listening && !pendingUncertain
        if (::talkButton.isInitialized) {
            val ready = connection != null && linkState == "connected"
            talkButton.mode = if (holdMode) TalkButton.Mode.HOLD else TalkButton.Mode.TAP
            talkButton.phase = if (listening) TalkButton.Phase.LISTENING else TalkButton.Phase.IDLE
            talkButton.isEnabled = ready && !hasPending
            talkButton.title = when {
                connection == null -> "Pair first"
                !ready -> "PC offline"
                listening -> "Listening…"
                holdMode -> "Hold to talk"
                else -> "Tap to talk"
            }
            talkButton.hint = when {
                connection == null -> "Pair with your PC in Settings"
                !ready -> "Check Wi-Fi, then tap Reconnect"
                listening && holdMode -> "Speak, then release or tap to send"
                listening -> "Tap to send"
                holdMode -> "Hold to talk, or tap to start"
                else -> "Tap to start · tap again to stop"
            }
        }
        if (::modeTabs.isInitialized) modeTabs.select(if (holdMode) 0 else 1)
        if (::modeHintView.isInitialized) {
            modeHintView.text = if (holdMode) {
                "Hold while you speak, or tap to start and tap again to send."
            } else {
                "Tap to start; tap again to stop, or pause and it sends by itself."
            }
        }
        pairButton.isEnabled = !listening
        if (::reconnectButton.isInitialized) {
            reconnectButton.visibility = if (connection != null && linkState != "connected") View.VISIBLE else View.GONE
            reconnectButton.isEnabled = !listening
        }
        if (::updateButton.isInitialized) {
            // Unpaired still checks GitHub, so the update path never depends on a PC.
            updateButton.isEnabled = !updateBusy
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
        if (::updateBanner.isInitialized) {
            val pending = availableUpdate
            updateBanner.visibility = if (pending != null) View.VISIBLE else View.GONE
            if (pending != null) {
                updateBannerText.text =
                    if (updateBusy) "Downloading the update…"
                    else "Update ready · ${pending.size / 1024} KB"
                updateBannerAction.text = if (updateBusy) "" else "Install"
                updateBanner.isEnabled = !updateBusy
            }
        }
    }

    private fun onMicrophoneRow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicStart = false
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
        } else {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            setStatus("Could not open the phone's app settings.")
        }
    }

    private fun runMicTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicStart = false
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
            setStatus("Allow the microphone, then run the test again.")
            return
        }
        if (::micTestButton.isInitialized) micTestButton.isEnabled = false
        if (::micTestView.isInitialized) micTestView.text = "Listening for 3 seconds. Say something now…"
        Thread {
            val peak = recordMicPeak(3000)
            runOnUiThread {
                if (::micTestButton.isInitialized) micTestButton.isEnabled = true
                val working = peak >= MIC_TEST_SPEECH_PEAK
                lastMicTest = when {
                    peak < 0 -> "Mic test: could not open the microphone."
                    working -> "Mic test: sound detected (peak $peak)."
                    else -> "Mic test: no sound reached the app (peak $peak)."
                }
                if (::micTestView.isInitialized) {
                    micTestView.text = when {
                        peak < 0 ->
                            "The microphone could not be opened. Another app may be using it, or the phone is blocking access."
                        working ->
                            "Microphone works — the app heard sound (peak $peak). If Talk still fails, check the PC connection."
                        else ->
                            "No sound reached the app (peak $peak). Allow Microphone for SpeakType, turn off any mic privacy switch, then test again."
                    }
                }
            }
        }.start()
    }

    /** Highest 16-bit sample while recording for [durationMs]; -1 if the mic cannot be opened. */
    private fun recordMicPeak(durationMs: Int): Int {
        val sampleRate = 16000
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            sampleRate,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (_: Exception) {
            return -1
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return -1
        }
        val buffer = ShortArray(1600)
        val target = sampleRate * durationMs / 1000
        var read = 0
        var peak = 0
        try {
            record.startRecording()
            while (read < target) {
                val count = record.read(buffer, 0, buffer.size)
                if (count <= 0) break
                read += count
                for (index in 0 until count) {
                    val value = kotlin.math.abs(buffer[index].toInt())
                    if (value > peak) peak = value
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
                // The recorder may already be stopped.
            }
            record.release()
        }
        return peak
    }

    private fun copyDiagnostics() {
        val connection = SecureConnectionStore(this).load()
        val text = buildString {
            appendLine("SpeakType ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}")
            appendLine(
                "Mic permission: " +
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        "granted"
                    } else {
                        "denied"
                    },
            )
            appendLine("Recognition: on-phone speech service · link: $linkState")
            appendLine("PC: ${connection?.host ?: "not paired"}:${connection?.port ?: "-"}")
            appendLine("Speech language: $languageMode · text format: $outputMode")
            appendLine(lastMicTest ?: "Mic test: not run")
            appendLine(lastVoiceError ?: "Last voice error: none this session")
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SpeakType diagnostics", text))
        setStatus("Diagnostics copied. Paste them into the chat with me.")
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
        languageMode = when (languageMode) {
            "auto" -> "bn"
            "bn" -> "en"
            else -> "auto"
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
                require(json.optString("app") == "speaktype") { "This is not a SpeakType QR code." }
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
                connectionGeneration.incrementAndGet()
                SecureConnectionStore(this).save(PcConnection(host, port, token, fingerprint, hostname))
                runOnUiThread {
                    linkState = "connected"
                    linkError = null
                    refreshUi()
                    setStatus("Paired with your PC. Click a text field on the PC before speaking.")
                }
            } catch (error: Exception) {
                runOnUiThread { setStatus("Pairing failed: ${error.message ?: "connection error"}") }
            }
        }
    }

    private fun beginDictation() {
        if (listening) return
        val connection = SecureConnectionStore(this).load()
        if (connection == null) {
            setStatus("Pair with your PC first. Open Settings and scan the QR code.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
            return
        }
        if (linkState != "connected") {
            setStatus("The PC is not reachable. Check that both devices are on the same Wi-Fi, then tap Reconnect in Settings.")
            return
        }
        val session = SpeechSession(this, languageMode, outputMode, autoStopOnSilence = !holdMode)
        micSession = session
        listening = true
        if (::talkButton.isInitialized) talkButton.speechHeard = false
        refreshUi()
        setStatus("Speak now. The transcript will be sent when you finish.")
        mainHandler.removeCallbacks(sessionTimeout)
        mainHandler.postDelayed(sessionTimeout, SESSION_TIMEOUT_MS)
        session.start(object : SpeechSession.Callback {
            override fun onSpeech() {
                if (micSession === session) markSpeechHeard()
            }

            override fun onResult(text: String) {
                if (!listening || micSession !== session) return
                stopMicSession()
                if (text.isBlank()) {
                    setStatus("No speech was recognized. Nothing was sent.")
                    return
                }
                // The PC applies the chosen text format when typing.
                pendingId = UUID.randomUUID().toString()
                pendingText = text
                pendingUncertain = false
                refreshUi()
                sendPending()
            }

            override fun onError(message: String) {
                if (!listening || micSession !== session) return
                stopMicSession()
                lastVoiceError = "Last voice error: $message"
                if (::voiceDiagView.isInitialized) voiceDiagView.text = lastVoiceError
                setStatus(message)
            }
        })
    }

    /** Release or tap to stop: let the phone's recognizer finalize the utterance. */
    private fun finalizeListening() {
        val session = micSession
        if (session == null) {
            stopMicSession()
            return
        }
        setStatus("Finishing…")
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.postDelayed(finalizeTimeout, FINALIZE_TIMEOUT_MS)
        session.stop()
    }

    /** The gesture was interrupted or the page let go of the mic: drop it without sending. */
    private fun cancelDictation() {
        micSession?.cancel()
        stopMicSession()
        setStatus("Dictation cancelled. Nothing was sent.")
    }

    /** Voice was detected, so a hold release may finalize the utterance. */
    private fun markSpeechHeard() {
        if (::talkButton.isInitialized) talkButton.speechHeard = true
    }

    private fun stopMicSession() {
        listening = false
        mainHandler.removeCallbacks(finalizeTimeout)
        mainHandler.removeCallbacks(sessionTimeout)
        micSession = null
        if (::talkButton.isInitialized) refreshUi()
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
                    JSONObject().put("id", id).put("text", transcript).put("output", outputMode),
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
        if (availableUpdate != null) {
            if (connection != null) installAvailableUpdate(connection) else installGitHubUpdate()
            return
        }
        if (connection == null) {
            checkGitHubUpdate(silent = false)
            return
        }
        checkPcUpdate(silent = false)
    }

    private fun checkPcUpdate(silent: Boolean) {
        val connection = SecureConnectionStore(this).load() ?: return
        if (!silent) {
            updateBusy = true
            refreshUi()
            setStatus("Checking the PC for a newer app build…")
        }
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
                    if (!silent) {
                        setStatus(
                            if (found != null) {
                                "A newer build is on your PC (${found.size / 1024} KB). Tap Install update."
                            } else {
                                "The installed app already matches the build on your PC."
                            }
                        )
                    }
                    if (found != null) notifyUpdateAvailable(found, fromPc = true)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateBusy = false
                    refreshUi()
                    if (!silent) setStatus("Could not check for updates: ${error.message ?: "connection error"}")
                }
            }
        }
    }

    private fun autoCheckForUpdate() {
        val connection = SecureConnectionStore(this).load()
        if (connection != null && linkState == "connected") {
            checkPcUpdate(silent = true)
        } else if (connection == null) {
            checkGitHubUpdate(silent = true)
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
            setStatus("Allow SpeakType to install updates, then tap Install update again.")
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                setStatus("Open Settings and allow installs from SpeakType.")
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

    private fun checkGitHubUpdate(silent: Boolean = false) {
        updateBusy = true
        refreshUi()
        if (!silent) setStatus("Checking GitHub for a newer build…")
        worker.execute {
            try {
                // `latest` is a prerelease tag, so /releases/latest (stable-only) would miss it.
                val url = URL("https://api.github.com/repos/foysalkhan288-rgb/voice-to-pc/releases/tags/latest")
                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "SpeakType")
                }
                val responseText = connection.inputStream.use { it.bufferedReader().readText() }
                connection.disconnect()
                val release = JSONObject(responseText)
                val assets = release.getJSONArray("assets")
                var assetUrl: String? = null
                var assetSha: String? = null
                var assetSize: Long = 0
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk")) {
                        assetUrl = asset.optString("browser_download_url")
                        assetSha = asset.optString("digest").removePrefix("sha256:")
                        assetSize = asset.optLong("size")
                        break
                    }
                }
                val installed = installedApkSha256()
                val found = if (assetUrl != null && assetSha != null && assetSha != installed) {
                    UpdateInfo(assetSha, assetSize)
                } else null
                runOnUiThread {
                    updateBusy = false
                    availableUpdate = found
                    githubUpdateUrl = assetUrl
                    refreshUi()
                    if (found != null) notifyUpdateAvailable(found, fromPc = false)
                    if (!silent) setStatus(if (found != null) "A newer build is on GitHub. Tap Install update." else "Already up to date.")
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateBusy = false
                    refreshUi()
                    if (!silent) setStatus("Could not check GitHub: ${error.message ?: "connection error"}")
                }
            }
        }
    }

    private fun installGitHubUpdate() {
        val url = githubUpdateUrl ?: return
        val expected = availableUpdate?.sha256
        updateBusy = true
        refreshUi()
        setStatus("Downloading the update from GitHub…")
        worker.execute {
            try {
                val bytes = URL(url).openStream().use { it.readBytes() }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (expected != null && digest != expected) {
                    throw IllegalStateException("The downloaded file did not match.")
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
        // Android rejects a downgrade with a bare "App not installed"; catch it here instead.
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (archive != null) {
            val installed = packageManager.getPackageInfo(packageName, 0)
            val archiveCode = PackageInfoCompat.getLongVersionCode(archive)
            val installedCode = PackageInfoCompat.getLongVersionCode(installed)
            if (archiveCode < installedCode) {
                file.delete()
                availableUpdate = null
                refreshUi()
                setStatus(
                    "That build (version $archiveCode) is older than the installed app " +
                        "(version $installedCode), so it was not installed."
                )
                return
            }
        }
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
        if (::statusLiveDot.isInitialized) {
            val color = when {
                message.startsWith("Listening") || message.startsWith("Speak now") ||
                    message.contains("sent to your PC", ignoreCase = true) ||
                    message.contains("Paired with") ||
                    message.contains("already sent") -> Palette.SUCCESS
                message.contains("failed", ignoreCase = true) ||
                    message.contains("Could not") ||
                    message.contains("No speech", ignoreCase = true) ||
                    message.contains("No text was recognized") ||
                    message.contains("Didn't hear", ignoreCase = true) ||
                    message.contains("network error", ignoreCase = true) -> Palette.DANGER
                message.contains("Checking") || message.contains("Sending") ||
                    message.contains("Preparing") || message.contains("Finishing") ||
                    message.contains("Downloading") || message.contains("Speech ended") ||
                    message.contains("may be partially", ignoreCase = true) -> Palette.WARNING
                else -> Palette.MUTED
            }
            statusLiveDot.setTextColor(color)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchQrScan()
                else setStatus("Camera permission is required to scan the PC pairing code.")
            }
            MIC_PERMISSION_REQUEST -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                // Only start dictation when the request came from the talk button; from the
                // Settings row the user just wanted the permission granted.
                if (granted && pendingMicStart) {
                    beginDictation()
                } else if (granted) {
                    refreshUi()
                    setStatus("Microphone permission granted.")
                } else {
                    setStatus("Microphone permission is required for voice recognition.")
                }
                pendingMicStart = false
            }
            // Denial is fine: dictation still works, the foreground service just stays silent.
            NOTIFICATION_PERMISSION_REQUEST -> Unit
        }
    }

    override fun onDestroy() {
        micSession?.cancel()
        stopMicSession()
        worker.shutdownNow()
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
    return openJson(trustManager, host, port, path, body.toString(), sessionToken, "POST")
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
        readTimeout = if (method == "GET") 4_000 else 30_000
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

