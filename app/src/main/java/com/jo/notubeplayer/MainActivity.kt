package com.jo.notubeplayer

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val NOTUBE_URL = "https://notube.net/"
        private const val DISCORD_URL = "https://discord.com/app"
        private const val BERSERK_URL = "https://readberserk.com/"
        private const val DOCTOLIB_URL = "https://www.doctolib.fr/"
        private const val SNCF_URL = "https://www.sncf-connect.com/"
        private const val MARMITON_URL = "https://www.marmiton.org/"
        private const val NOTIFICATION_PERMISSION_CODE = 1001

        // Logging tags — filter with: adb logcat -s NTP_NAV,NTP_BLOCK,NTP_INTENT,NTP_DISCORD,NTP_AD
        private const val TAG_NAV     = "NTP_NAV"
        private const val TAG_BLOCK   = "NTP_BLOCK"
        private const val TAG_INTENT  = "NTP_INTENT"
        private const val TAG_DISCORD = "NTP_DISCORD"
        private const val TAG_AD      = "NTP_AD"

        private const val PREFS_NAME = "NoTubePrefsV6"
        private const val PREF_LAST_DATE = "last_date"
        private const val PREF_DISCORD_SESSION_START_MS = "discord_session_start_ms"
        private const val PREF_DISCORD_SESSIONS_USED = "discord_sessions_used"

        private const val DISCORD_SESSION_LIMIT_MS = 5 * 60 * 1000L // 5 minutes
        private const val DISCORD_WAIT_TIME_MS = 4 * 60 * 60 * 1000L // 4 hours
        private const val DISCORD_DAILY_SESSIONS_LIMIT = 3 // 3 sessions of 5 mins = 15 mins total

        private val ALLOWED_DOMAINS = listOf(
            "notube.net",
            "notube.io",
            "discord.com",
            "discord.gg",
            "discordapp.com",
            "discordapp.net",
            "readberserk.com",
            "doctolib.fr",
            "appconsent.io",
            "didomi.io",
            "consensu.org",
            "cloudflare.com",
            "google.com",
            "sncf-connect.com",
            "sncf.com",
            "marmiton.org"
        )

        // Matche "notube.<tld>" et "<sous-domaine>.notube.<tld>", quel que soit le TLD,
        // sans matcher "evilnotube.com" ni "notube.evil.com".
        private val NOTUBE_HOST_REGEX = Regex("""(^|\.)notube\.[a-z]+$""")
    }

    private lateinit var webView: WebView
    private lateinit var fabRefresh: FloatingActionButton
    private lateinit var progressContainer: FrameLayout
    private lateinit var progressBar: View
    private lateinit var splashOverlay: FrameLayout
    private lateinit var blockedOverlay: FrameLayout
    private lateinit var errorOverlay: FrameLayout
    private lateinit var timerIndicator: TextView
    private lateinit var blockedMessage: TextView
    private lateinit var blockedIcon: TextView
    private lateinit var bottomMenuScroll: HorizontalScrollView
    
    // Bottom Buttons
    private lateinit var btnNavNotube: Button
    private lateinit var btnNavDiscord: Button
    private lateinit var btnNavBerserk: Button
    private lateinit var btnNavDoctolib: Button
    private lateinit var btnNavSncf: Button
    private lateinit var btnNavMarmiton: Button

    private var webViewBasePaddingBottom: Int = 0
    private var progressAnimator: android.animation.ValueAnimator? = null
    private lateinit var prefs: SharedPreferences

    // Timer components
    private val handler = Handler(Looper.getMainLooper())
    private var isTimerRunning = false
    private var currentMainUrl: String = ""

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                Toast.makeText(context, R.string.download_complete, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val timerRunnable = object : Runnable {
        @SuppressLint("DefaultLocale")
        override fun run() {
            val currentTime = System.currentTimeMillis()
            if (isCurrentUrlDiscord(currentMainUrl) && blockedOverlay.visibility == View.GONE) {
                val sessionStart = prefs.getLong(PREF_DISCORD_SESSION_START_MS, 0L)
                val sessionEnd = sessionStart + DISCORD_SESSION_LIMIT_MS
                
                if (currentTime >= sessionEnd) {
                    blockDiscordDueToTime()
                } else {
                    val sessionsUsed = prefs.getInt(PREF_DISCORD_SESSIONS_USED, 0)
                    updateTimerUI(sessionEnd - currentTime, sessionsUsed)
                }
            } else if (blockedOverlay.visibility == View.VISIBLE) {
                val sessionStart = prefs.getLong(PREF_DISCORD_SESSION_START_MS, 0L)
                val sessionsUsed = prefs.getInt(PREF_DISCORD_SESSIONS_USED, 0)
                val sessionEnd = sessionStart + DISCORD_SESSION_LIMIT_MS
                val cooldownEnd = sessionEnd + DISCORD_WAIT_TIME_MS
                
                if (sessionsUsed >= DISCORD_DAILY_SESSIONS_LIMIT) {
                    blockedMessage.text = getString(R.string.discord_daily_limit_reached)
                } else if (currentTime < cooldownEnd) {
                    val remainingCooldown = cooldownEnd - currentTime
                    val hours = (remainingCooldown / 1000) / 3600
                    val minutes = ((remainingCooldown / 1000) % 3600) / 60
                    val seconds = (remainingCooldown / 1000) % 60
                    val timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    blockedMessage.text = getString(R.string.discord_session_limit_reached, timeStr)
                } else {
                    // Cooldown has passed, they can go back to discord
                    blockedMessage.text = getString(R.string.discord_ready)
                }
            }
            if (isTimerRunning || blockedOverlay.visibility == View.VISIBLE) {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        checkAndResetDailyTimer()

        setupEdgeToEdge()
        bindViews()

        setupWebView()
        setupScrollableMenu()
        requestNotificationPermission()
        registerDownloadReceiver()

        if (!isNetworkAvailable()) {
            showError()
            return
        }
        // Launched via a link from another app? Route through the allowlist.
        // Otherwise, default to NoTube.
        if (!handleViewIntent(intent)) {
            loadNotube()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!isNetworkAvailable()) {
            showError()
            return
        }
        handleViewIntent(intent)
    }

    /**
     * If [intent] is an `ACTION_VIEW` for an http(s) URL, route it through the
     * domain allowlist:
     *   - allowed → load it (or trigger Discord session logic for Discord URLs)
     *   - blocked → show the "domain not allowed" overlay and load NoTube in the
     *     background so the user has something when they tap "Back".
     *
     * Returns true when the intent was handled (regardless of allow/block).
     */
    private fun handleViewIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            android.util.Log.i(TAG_INTENT, "IGNORED non-http intent: scheme=$scheme url=$uri")
            return false
        }
        val url = uri.toString()
        val callingPackage = intent.`package` ?: "unknown"
        android.util.Log.i(TAG_INTENT, "RECEIVED intent from=$callingPackage url=$url")

        if (!isUrlAllowed(url)) {
            android.util.Log.w(TAG_INTENT, "BLOCKED external intent (domain not allowed): $url")
            loadNotube()
            showBlockedOverlay(getString(R.string.external_url_not_allowed, uri.host ?: url), "🚫")
            return true
        }

        android.util.Log.i(TAG_INTENT, "ALLOWED external intent: $url")

        // Discord has its own per-unlock session logic — let loadDiscord() apply it.
        if (isCurrentUrlDiscord(url)) {
            android.util.Log.i(TAG_INTENT, "Routing to Discord session handler")
            loadDiscord()
            return true
        }

        webView.loadUrl(url)
        return true
    }

    private fun checkAndResetDailyTimer() {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString(PREF_LAST_DATE, "")

        if (currentDate != lastDate) {
            // New day, reset timer
            prefs.edit()
                .putString(PREF_LAST_DATE, currentDate)
                .putLong(PREF_DISCORD_SESSION_START_MS, 0L)
                .putInt(PREF_DISCORD_SESSIONS_USED, 0)
                .apply()
        }
    }

    enum class DiscordAccessState { ALLOWED, COOLDOWN, DAILY_LIMIT_REACHED, CAN_START_NEW_SESSION }

    private fun checkDiscordAccess(): DiscordAccessState {
        val currentTime = System.currentTimeMillis()
        val sessionStart = prefs.getLong(PREF_DISCORD_SESSION_START_MS, 0L)
        val sessionsUsed = prefs.getInt(PREF_DISCORD_SESSIONS_USED, 0)

        // No session ever started or very first time
        if (sessionStart == 0L) return DiscordAccessState.CAN_START_NEW_SESSION

        val sessionEnd = sessionStart + DISCORD_SESSION_LIMIT_MS
        val cooldownEnd = sessionEnd + DISCORD_WAIT_TIME_MS

        // Within the active 5 minute block
        if (currentTime < sessionEnd) {
            return DiscordAccessState.ALLOWED
        }

        // 5 minute block is over, but still in cooldown
        if (currentTime < cooldownEnd) {
            return DiscordAccessState.COOLDOWN
        }

        // Cooldown is over, checking daily limit
        if (sessionsUsed >= DISCORD_DAILY_SESSIONS_LIMIT) {
            return DiscordAccessState.DAILY_LIMIT_REACHED
        }

        // Cooldown over and daily limit not reached
        return DiscordAccessState.CAN_START_NEW_SESSION
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        webViewBasePaddingBottom = webView.paddingBottom
        fabRefresh = findViewById(R.id.fabRefresh)
        
        fabRefresh.setOnClickListener {
            webView.reload()
        }

        progressContainer = findViewById(R.id.progressContainer)
        progressBar = findViewById(R.id.progressBar)
        splashOverlay = findViewById(R.id.splashOverlay)
        blockedOverlay = findViewById(R.id.blockedOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        timerIndicator = findViewById(R.id.timerIndicator)
        blockedMessage = findViewById(R.id.blockedMessage)
        blockedIcon = findViewById(R.id.blockedIcon)
        bottomMenuScroll = findViewById(R.id.bottomMenuScroll)
        
        btnNavNotube = findViewById(R.id.btnNavNotube)
        btnNavDiscord = findViewById(R.id.btnNavDiscord)
        btnNavBerserk = findViewById(R.id.btnNavBerserk)
        btnNavDoctolib = findViewById(R.id.btnNavDoctolib)
        btnNavSncf = findViewById(R.id.btnNavSncf)
        btnNavMarmiton = findViewById(R.id.btnNavMarmiton)

        findViewById<View>(R.id.btnGoBack).setOnClickListener {
            blockedOverlay.visibility = View.GONE
            loadNotube()
        }

        findViewById<View>(R.id.btnRetry).setOnClickListener {
            if (isNetworkAvailable()) {
                errorOverlay.visibility = View.GONE
                webView.reload()
            } else {
                Toast.makeText(this, R.string.no_internet, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupScrollableMenu() {
        btnNavNotube.setOnClickListener { loadNotube() }
        btnNavDiscord.setOnClickListener { loadDiscord() }
        btnNavBerserk.setOnClickListener { webView.loadUrl(BERSERK_URL) }
        btnNavDoctolib.setOnClickListener { webView.loadUrl(DOCTOLIB_URL) }
        btnNavSncf.setOnClickListener { webView.loadUrl(SNCF_URL) }
        btnNavMarmiton.setOnClickListener { webView.loadUrl(MARMITON_URL) }
    }
    
    private fun loadNotube() {
        webView.loadUrl(NOTUBE_URL)
    }
    
    private fun loadDiscord() {
        val accessState = checkDiscordAccess()
        android.util.Log.i(TAG_DISCORD, "loadDiscord called, state=$accessState")
        when (accessState) {
            DiscordAccessState.CAN_START_NEW_SESSION -> {
                val sessionsUsed = prefs.getInt(PREF_DISCORD_SESSIONS_USED, 0)
                prefs.edit()
                    .putLong(PREF_DISCORD_SESSION_START_MS, System.currentTimeMillis())
                    .putInt(PREF_DISCORD_SESSIONS_USED, sessionsUsed + 1)
                    .apply()
                android.util.Log.i(TAG_DISCORD, "SESSION_START sessions_used=${sessionsUsed + 1}/$DISCORD_DAILY_SESSIONS_LIMIT")
                webView.loadUrl(DISCORD_URL)
            }
            DiscordAccessState.ALLOWED -> {
                android.util.Log.i(TAG_DISCORD, "SESSION_RESUME (still within 5min window)")
                webView.loadUrl(DISCORD_URL)
            }
            DiscordAccessState.COOLDOWN -> {
                val sessionStart = prefs.getLong(PREF_DISCORD_SESSION_START_MS, 0L)
                val cooldownEnd = sessionStart + DISCORD_SESSION_LIMIT_MS + DISCORD_WAIT_TIME_MS
                val remainingMs = cooldownEnd - System.currentTimeMillis()
                android.util.Log.w(TAG_DISCORD, "BLOCKED cooldown, remaining=${remainingMs / 1000}s")
                blockDiscordDueToTime()
            }
            DiscordAccessState.DAILY_LIMIT_REACHED -> {
                android.util.Log.w(TAG_DISCORD, "BLOCKED daily limit reached ($DISCORD_DAILY_SESSIONS_LIMIT sessions)")
                blockDiscordDueToTime()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false

            val currentAgent = userAgentString ?: ""
            userAgentString = currentAgent.replace("; wv", "")
        }

        webView.webViewClient = NoTubeWebViewClient()
        webView.webChromeClient = NoTubeChromeClient()

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun isUrlAllowed(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            val scheme = uri.scheme?.lowercase()
            if (scheme == "about" || scheme == "data" || scheme == "javascript" || scheme == "blob") {
                return true
            }
            // NoTube fait tourner ses domaines en permanence (notube.net/.io/.li/.land/.im/.app/...)
            // pour échapper aux blocages. On autorise donc tout TLD "notube.<xx>" (et ses
            // sous-domaines) pour que le flux de conversion/téléchargement ne casse pas à chaque
            // rotation. La frontière (^|.) évite d'autoriser "evilnotube.com" ou "notube.evil.com".
            if (NOTUBE_HOST_REGEX.containsMatchIn(host)) {
                return true
            }
            ALLOWED_DOMAINS.any { domain ->
                host == domain || host.endsWith(".$domain")
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isCurrentUrlDiscord(urlToCheck: String = currentMainUrl): Boolean {
        return urlToCheck.contains("discord.com") || urlToCheck.contains("discord.gg") || urlToCheck.contains("discordapp")
    }

    private fun applyMarmitonWindowInset(url: String) {
        val extraInset = resources.getDimensionPixelSize(R.dimen.marmiton_bottom_inset)
        val targetBottom = if (url.contains("marmiton.org")) {
            webViewBasePaddingBottom + extraInset
        } else {
            webViewBasePaddingBottom
        }

        if (webView.paddingBottom != targetBottom) {
            webView.setPadding(webView.paddingLeft, webView.paddingTop, webView.paddingRight, targetBottom)
        }
    }

    private inner class NoTubeWebViewClient : WebViewClient() {
        
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()

            try {
                val reqUri = Uri.parse(url)
                val mainUri = Uri.parse(currentMainUrl)
                val reqHost = reqUri.host ?: ""
                val mainHost = mainUri.host ?: ""

                if (reqHost.isNotEmpty() && mainHost.isNotEmpty() && !reqHost.endsWith(mainHost.replace("www.", ""))) {
                    android.util.Log.d(TAG_AD, "3RD_PARTY req=$reqHost on page=$mainHost")
                }
            } catch (e: Exception) {}

            // AdBlocker : Bloquer les requêtes vers des domaines publicitaires connus
            if (!request.isForMainFrame) {
                val adKeywords = listOf(
                    "googleads", "doubleclick.net", "adsystem", "adserver",
                    "popads", "popcash", "exoclick", "propellerads", "adsterra",
                    "onclickads", "analytics", "tracker", "scorecardresearch",
                    "taboola", "outbrain", "criteo", "amazon-adsystem", "adnxs",
                    "bidswitch", "/ads/", "?ad=", "&ad=", "banner", "popunder",
                    "ad.turn.com", "serving-sys.com", "media.net", "yieldmo.com",
                    "adskeeper.com", "idealmedia.io"
                )
                val isAd = adKeywords.any { url.contains(it, ignoreCase = true) }
                if (isAd) {
                    android.util.Log.d(TAG_AD, "BLOCKED ad resource: $url")
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
                }
            }
            
            // Block images and videos if on Discord
            if (isCurrentUrlDiscord(currentMainUrl)) {
                val isMediaUrl = url.contains("cdn.discordapp.com/attachments") ||
                                 url.contains(".png") || url.contains(".jpg") || 
                                 url.contains(".jpeg") || url.contains(".gif") || 
                                 url.contains(".webp") || url.contains(".mp4") || 
                                 url.contains(".webm") || url.contains("media.discordapp.net")
                
                if (isMediaUrl) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val frameType = if (request.isForMainFrame) "MAIN" else "SUB"
            return if (isUrlAllowed(url)) {
                android.util.Log.i(TAG_NAV, "ALLOW [$frameType] $url")
                if (request.isForMainFrame) {
                    currentMainUrl = url
                }
                false
            } else {
                android.util.Log.w(TAG_BLOCK, "BLOCK [$frameType] $url  (from page: $currentMainUrl)")
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            currentMainUrl = url
            android.util.Log.i(TAG_NAV, "PAGE_START $url")
            showProgress()
            errorOverlay.visibility = View.GONE
            applyMarmitonWindowInset(url)

            val isDiscord = isCurrentUrlDiscord(url)
            
            // Disable loading network images globally when on discord
            webView.settings.blockNetworkImage = isDiscord
            
            if (isDiscord) {
                val accessState = checkDiscordAccess()
                when (accessState) {
                    DiscordAccessState.CAN_START_NEW_SESSION -> {
                        val sessionsUsed = prefs.getInt(PREF_DISCORD_SESSIONS_USED, 0)
                        prefs.edit()
                            .putLong(PREF_DISCORD_SESSION_START_MS, System.currentTimeMillis())
                            .putInt(PREF_DISCORD_SESSIONS_USED, sessionsUsed + 1)
                            .apply()

                        timerIndicator.visibility = View.VISIBLE
                        updateTimerUI(DISCORD_SESSION_LIMIT_MS, sessionsUsed + 1)
                        if (!isTimerRunning) startTimer()
                    }
                    DiscordAccessState.ALLOWED -> {
                        timerIndicator.visibility = View.VISIBLE
                        val sessionStart = prefs.getLong(PREF_DISCORD_SESSION_START_MS, 0L)
                        val sessionsUsed = prefs.getInt(PREF_DISCORD_SESSIONS_USED, 0)
                        updateTimerUI((sessionStart + DISCORD_SESSION_LIMIT_MS) - System.currentTimeMillis(), sessionsUsed)
                        if (!isTimerRunning) startTimer()
                    }
                    DiscordAccessState.COOLDOWN, DiscordAccessState.DAILY_LIMIT_REACHED -> {
                        view.stopLoading()
                        blockDiscordDueToTime()
                    }
                }
            } else {
                timerIndicator.visibility = View.GONE
                stopTimer()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            android.util.Log.i(TAG_NAV, "PAGE_DONE $url")
            hideProgress()
            hideSplash()
            injectDarkModeEnhancements(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            val frameType = if (request.isForMainFrame) "MAIN" else "SUB"
            android.util.Log.e(TAG_NAV, "PAGE_ERROR [$frameType] url=${request.url} code=${error.errorCode} desc=${error.description}")
            if (request.isForMainFrame) {
                showError()
            }
        }
    }

    private inner class NoTubeChromeClient : WebChromeClient() {
        private var customView: View? = null
        private var customViewCallback: CustomViewCallback? = null

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            updateProgress(newProgress)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView = view
            customViewCallback = callback
            val decorView = window.decorView as FrameLayout
            decorView.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            webView.visibility = View.GONE
            bottomMenuScroll.visibility = View.GONE
        }

        override fun onHideCustomView() {
            customView?.let {
                val decorView = window.decorView as FrameLayout
                decorView.removeView(it)
                webView.visibility = View.VISIBLE
                bottomMenuScroll.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
            }
            customView = null
            customViewCallback = null
        }
    }

    // ─── Timer Logic ─────────────────────────────────────────────────────

    private fun startTimer() {
        if (!isTimerRunning) {
            isTimerRunning = true
            handler.post(timerRunnable)
        }
    }

    private fun stopTimer() {
        isTimerRunning = false
        handler.removeCallbacks(timerRunnable)
    }

    @SuppressLint("DefaultLocale")
    private fun updateTimerUI(remainingActiveMs: Long, sessionsUsed: Int) {
        val minutes = (remainingActiveMs / 1000) / 60
        val seconds = (remainingActiveMs / 1000) % 60
        val timeStr = String.format("%02d:%02d", minutes, seconds)
        val sessionsLeft = DISCORD_DAILY_SESSIONS_LIMIT - sessionsUsed
        timerIndicator.text = getString(R.string.time_remaining, timeStr, sessionsLeft)
    }

    private fun blockDiscordDueToTime() {
        if (!isTimerRunning) startTimer()
        timerIndicator.visibility = View.GONE
        
        val sessionsUsed = prefs.getInt(PREF_DISCORD_SESSIONS_USED, 0)
        if (sessionsUsed >= DISCORD_DAILY_SESSIONS_LIMIT) {
            showBlockedOverlay(getString(R.string.discord_daily_limit_reached), "⏳")
        } else {
            showBlockedOverlay(getString(R.string.loading), "⏳")
        }
    }

    // ─── Progress Bar ────────────────────────────────────────────────────

    private fun showProgress() {
        progressContainer.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        progressContainer.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                progressContainer.visibility = View.GONE
                progressContainer.alpha = 1f
                val params = progressBar.layoutParams
                params.width = 0
                progressBar.layoutParams = params
            }
            .start()
    }

    private fun updateProgress(progress: Int) {
        val parentWidth = progressContainer.width
        if (parentWidth == 0) return
        val targetWidth = (parentWidth * progress / 100f).toInt()

        progressAnimator?.cancel()
        progressAnimator = android.animation.ValueAnimator.ofInt(progressBar.layoutParams.width, targetWidth).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val params = progressBar.layoutParams
                params.width = animator.animatedValue as Int
                progressBar.layoutParams = params
            }
            start()
        }
    }

    // ─── Overlays ────────────────────────────────────────────────────────

    private fun hideSplash() {
        if (splashOverlay.visibility == View.VISIBLE) {
            splashOverlay.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction {
                    splashOverlay.visibility = View.GONE
                    splashOverlay.alpha = 1f
                }
                .start()
        }
    }

    private fun showBlockedOverlay(message: String, icon: String = "🚫") {
        blockedIcon.text = icon
        blockedMessage.text = message
        blockedOverlay.alpha = 0f
        blockedOverlay.visibility = View.VISIBLE
        blockedOverlay.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    private fun showError() {
        errorOverlay.visibility = View.VISIBLE
        hideSplash()
    }

    // ─── Downloads ───────────────────────────────────────────────────────

    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            var finalMimeType = mimeType
            var finalFileName = URLUtil.guessFileName(url, contentDisposition, finalMimeType)

            // Fix the .bin issue often caused by notube generating weird URLs or mime types
            if (finalFileName.endsWith(".bin") || finalMimeType == "application/octet-stream" || finalMimeType == "application/force-download") {
                if (url.contains(".mp3") || contentDisposition.contains(".mp3")) {
                    finalFileName = finalFileName.replace(".bin", ".mp3")
                    finalMimeType = "audio/mpeg"
                } else if (url.contains(".mp4") || contentDisposition.contains(".mp4")) {
                    finalFileName = finalFileName.replace(".bin", ".mp4")
                    finalMimeType = "video/mp4"
                } else if (!finalFileName.contains(".")) {
                     // Default to mp3 if we really can't figure it out from url but we know it's a download
                     finalFileName += ".mp3"
                     finalMimeType = "audio/mpeg"
                }
            }

            // Block video downloads
            if (finalMimeType.startsWith("video/") || finalFileName.endsWith(".mp4") || finalFileName.endsWith(".webm") || finalFileName.endsWith(".mkv")) {
                Toast.makeText(this, R.string.video_download_blocked, Toast.LENGTH_LONG).show()
                return
            }

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(finalMimeType)
                addRequestHeader("User-Agent", userAgent)
                setTitle(finalFileName)
                setDescription(getString(R.string.loading))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, finalFileName)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, filter)
        }
    }

    // ─── Permissions ─────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    // ─── Network ─────────────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ─── Custom JS Injections ──────────────────────────────────────────────

    private fun injectDarkModeEnhancements(view: WebView) {
        val js = """
            (function() {
                var meta = document.querySelector('meta[name="theme-color"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'theme-color';
                    document.head.appendChild(meta);
                }
                meta.content = '#0D0D1A';
                
                if (window.location.hostname.includes('marmiton.org')) {
                    // Supprimer par prévention tous les target="_blank" des liens pour qu'ils s'ouvrent dans la vue actuelle
                    setInterval(function() {
                        document.querySelectorAll('a[target="_blank"]').forEach(function(a) {
                            a.removeAttribute('target');
                        });
                        
                        // Accepter automatiquement les cookies didomi
                        var acceptBtn = document.querySelector('#didomi-notice-agree-button');
                        if (acceptBtn) {
                            acceptBtn.click();
                        }
                        
                        // Fermer la bannière intelligente d'application
                        var appBannerClose = document.querySelector('.af-smart-banner-closeBtn');
                        if (appBannerClose) {
                            appBannerClose.click();
                        }
                    }, 1000);
                    
                    var style = document.createElement('style');
                    style.innerHTML = `
                        .af-smart-banner { display: none !important; }
                    `;
                    document.head.appendChild(style);
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ─── Back Navigation ─────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            blockedOverlay.visibility == View.VISIBLE -> {
                blockedOverlay.visibility = View.GONE
                loadNotube()
            }
            webView.canGoBack() -> {
                webView.goBack()
            }
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        checkAndResetDailyTimer()
        webView.onResume()
        if (isCurrentUrlDiscord(currentMainUrl)) {
            startTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        stopTimer()
        
        // Si l'utilisateur quitte l'application pendant qu'une session est en cours, 
        // on la termine immédiatement pour déclencher le temps d'attente (cooldown).
        if (checkDiscordAccess() == DiscordAccessState.ALLOWED) {
            val forcedExpirationTime = System.currentTimeMillis() - DISCORD_SESSION_LIMIT_MS
            prefs.edit().putLong(PREF_DISCORD_SESSION_START_MS, forcedExpirationTime).apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: Exception) { }
        webView.destroy()
    }
}
