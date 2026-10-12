package com.jo.fetlife

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application FetLife dédiée :
 *   - FetLife (https://fetlife.com/)
 *   - Images et vidéos coupées pour préserver l'usage sobre
 *   - Quotas : 5 sessions de 3 minutes par jour, 3 h d'attente entre chacune
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val FETLIFE_URL = "https://fetlife.com/"

        // Logging tags — filter with: adb logcat -s FL_NAV,FL_BLOCK,FL_INTENT,FL_SESSION,FL_AD
        private const val TAG_NAV     = "FL_NAV"
        private const val TAG_BLOCK   = "FL_BLOCK"
        private const val TAG_INTENT  = "FL_INTENT"
        private const val TAG_SESSION = "FL_SESSION"
        private const val TAG_AD      = "FL_AD"

        private const val PREFS_NAME = "FetLifePrefs"
        private const val PREF_LAST_DATE = "last_date"
        private const val PREF_SESSION_START_MS = "session_start_ms"
        private const val PREF_SESSIONS_USED = "sessions_used"

        private const val SESSION_LIMIT_MS = 3 * 60 * 1000L      // 3 minutes
        private const val WAIT_TIME_MS = 3 * 60 * 60 * 1000L     // 3 heures
        private const val DAILY_SESSIONS_LIMIT = 5               // 5 x 3 min = 15 min / jour

        // Allowlist : flcdn.net est le CDN de FetLife (autorisé mais ressources médias coupées)
        private val ALLOWED_DOMAINS = listOf(
            "fetlife.com",
            "flcdn.net",
            "cloudflare.com",
            "cloudflareinsights.com",
            "gstatic.com",
            "googleapis.com",
            "google.com"
        )
    }

    private lateinit var webView: WebView
    private lateinit var fabRefresh: FloatingActionButton
    private lateinit var progressContainer: FrameLayout
    private lateinit var progressBar: View
    private lateinit var splashOverlay: FrameLayout
    private lateinit var blockedOverlay: FrameLayout
    private lateinit var errorOverlay: FrameLayout
    private lateinit var blockedMessage: TextView
    private lateinit var blockedIcon: TextView
    private lateinit var timerIndicator: TextView
    private lateinit var btnGoHome: Button

    private var progressAnimator: android.animation.ValueAnimator? = null
    private var currentMainUrl: String = ""

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var isTimerRunning = false

    private var blockedByQuota = false

    private val timerRunnable = object : Runnable {
        @SuppressLint("DefaultLocale")
        override fun run() {
            val currentTime = System.currentTimeMillis()
            val sessionStart = prefs.getLong(PREF_SESSION_START_MS, 0L)
            val sessionsUsed = prefs.getInt(PREF_SESSIONS_USED, 0)
            val sessionEnd = sessionStart + SESSION_LIMIT_MS

            if (blockedOverlay.visibility == View.VISIBLE && blockedByQuota) {
                val cooldownEnd = sessionEnd + WAIT_TIME_MS
                if (sessionsUsed >= DAILY_SESSIONS_LIMIT) {
                    blockedMessage.text = getString(R.string.daily_limit_reached)
                } else if (currentTime < cooldownEnd) {
                    val remaining = cooldownEnd - currentTime
                    val hours = (remaining / 1000) / 3600
                    val minutes = ((remaining / 1000) % 3600) / 60
                    val seconds = (remaining / 1000) % 60
                    val timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    blockedMessage.text = getString(R.string.session_limit_reached, timeStr)
                } else {
                    blockedMessage.text = getString(R.string.session_ready)
                }
            } else if (sessionStart == 0L) {
                // Aucune session ouverte
            } else if (currentTime >= sessionEnd) {
                blockSessionDueToTime()
            } else {
                updateTimerUI(sessionEnd - currentTime, sessionsUsed)
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

        if (!isNetworkAvailable()) {
            showError()
            return
        }
        if (!handleViewIntent(intent)) {
            loadSite(FETLIFE_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!isNetworkAvailable()) {
            showError()
            return
        }
        if (!handleViewIntent(intent)) {
            loadSite(FETLIFE_URL)
        }
    }

    private fun handleViewIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false

        val url = uri.toString()
        android.util.Log.i(TAG_INTENT, "RECEIVED intent url=$url")

        if (!isUrlAllowed(url)) {
            android.util.Log.w(TAG_INTENT, "BLOCKED external intent: $url")
            loadSite(FETLIFE_URL)
            showBlockedOverlay(getString(R.string.external_url_not_allowed, uri.host ?: url))
            return true
        }

        loadSite(url)
        return true
    }

    // ─── Quotas ──────────────────────────────────────────────────────────

    private fun checkAndResetDailyTimer() {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString(PREF_LAST_DATE, "")

        if (currentDate != lastDate) {
            android.util.Log.i(TAG_SESSION, "NEW_DAY reset quotas (was=$lastDate now=$currentDate)")
            prefs.edit()
                .putString(PREF_LAST_DATE, currentDate)
                .putLong(PREF_SESSION_START_MS, 0L)
                .putInt(PREF_SESSIONS_USED, 0)
                .apply()
        }
    }

    enum class AccessState { ALLOWED, COOLDOWN, DAILY_LIMIT_REACHED, CAN_START_NEW_SESSION }

    private fun checkAccess(): AccessState {
        val currentTime = System.currentTimeMillis()
        val sessionStart = prefs.getLong(PREF_SESSION_START_MS, 0L)
        val sessionsUsed = prefs.getInt(PREF_SESSIONS_USED, 0)

        if (sessionStart == 0L) return AccessState.CAN_START_NEW_SESSION

        val sessionEnd = sessionStart + SESSION_LIMIT_MS
        val cooldownEnd = sessionEnd + WAIT_TIME_MS

        if (currentTime < sessionEnd) return AccessState.ALLOWED
        if (currentTime < cooldownEnd) return AccessState.COOLDOWN
        if (sessionsUsed >= DAILY_SESSIONS_LIMIT) return AccessState.DAILY_LIMIT_REACHED

        return AccessState.CAN_START_NEW_SESSION
    }

    private fun loadSite(url: String) {
        val accessState = checkAccess()
        android.util.Log.i(TAG_SESSION, "loadSite state=$accessState url=$url")
        when (accessState) {
            AccessState.CAN_START_NEW_SESSION -> {
                val sessionsUsed = prefs.getInt(PREF_SESSIONS_USED, 0)
                prefs.edit()
                    .putLong(PREF_SESSION_START_MS, System.currentTimeMillis())
                    .putInt(PREF_SESSIONS_USED, sessionsUsed + 1)
                    .apply()
                android.util.Log.i(TAG_SESSION, "SESSION_START used=${sessionsUsed + 1}/$DAILY_SESSIONS_LIMIT")
                webView.loadUrl(url)
            }
            AccessState.ALLOWED -> {
                android.util.Log.i(TAG_SESSION, "SESSION_RESUME (fenêtre de 3 min encore ouverte)")
                webView.loadUrl(url)
            }
            AccessState.COOLDOWN -> {
                val sessionStart = prefs.getLong(PREF_SESSION_START_MS, 0L)
                val cooldownEnd = sessionStart + SESSION_LIMIT_MS + WAIT_TIME_MS
                android.util.Log.w(TAG_SESSION, "BLOCKED cooldown, remaining=${(cooldownEnd - System.currentTimeMillis()) / 1000}s")
                blockSessionDueToTime()
            }
            AccessState.DAILY_LIMIT_REACHED -> {
                android.util.Log.w(TAG_SESSION, "BLOCKED daily limit ($DAILY_SESSIONS_LIMIT sessions)")
                blockSessionDueToTime()
            }
        }
    }

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
        val sessionsLeft = DAILY_SESSIONS_LIMIT - sessionsUsed
        timerIndicator.text = getString(R.string.time_remaining, timeStr, sessionsLeft)
    }

    private fun blockSessionDueToTime() {
        if (blockedOverlay.visibility == View.VISIBLE && blockedByQuota) {
            if (!isTimerRunning) startTimer()
            return
        }

        webView.loadUrl("about:blank")
        if (!isTimerRunning) startTimer()
        timerIndicator.visibility = View.GONE

        blockedByQuota = true
        btnGoHome.setText(R.string.close_app)

        val sessionsUsed = prefs.getInt(PREF_SESSIONS_USED, 0)
        if (sessionsUsed >= DAILY_SESSIONS_LIMIT) {
            showBlockedOverlay(getString(R.string.daily_limit_reached), "⏳", quota = true)
        } else {
            showBlockedOverlay(getString(R.string.loading), "⏳", quota = true)
        }
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val rootLayout = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
    }

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        fabRefresh = findViewById(R.id.fabRefresh)
        progressContainer = findViewById(R.id.progressContainer)
        progressBar = findViewById(R.id.progressBar)
        splashOverlay = findViewById(R.id.splashOverlay)
        blockedOverlay = findViewById(R.id.blockedOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        blockedMessage = findViewById(R.id.blockedMessage)
        blockedIcon = findViewById(R.id.blockedIcon)
        timerIndicator = findViewById(R.id.timerIndicator)
        btnGoHome = findViewById(R.id.btnGoHome)

        fabRefresh.setOnClickListener { webView.reload() }

        btnGoHome.setOnClickListener {
            if (blockedByQuota) {
                finish()
            } else {
                blockedOverlay.visibility = View.GONE
                loadSite(FETLIFE_URL)
            }
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
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

            val currentAgent = userAgentString ?: ""
            userAgentString = currentAgent.replace("; wv", "")
        }

        webView.webViewClient = FetLifeWebViewClient()
        webView.webChromeClient = FetLifeChromeClient()
    }

    private fun isUrlAllowed(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme == "about" || scheme == "data" || scheme == "javascript" || scheme == "blob") {
                return true
            }
            val host = uri.host?.lowercase() ?: return false
            ALLOWED_DOMAINS.any { domain ->
                host == domain || host.endsWith(".$domain")
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isContentUrl(url: String): Boolean {
        return url.contains("fetlife.com")
    }

    private fun isMediaResource(url: String): Boolean {
        val path = try {
            Uri.parse(url).path?.lowercase() ?: ""
        } catch (e: Exception) {
            return false
        }
        val mediaExtensions = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp",
            ".mp4", ".webm", ".mov", ".m4v"
        )
        return mediaExtensions.any { path.endsWith(it) }
    }

    private inner class FetLifeWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()

            if (!request.isForMainFrame) {
                val adKeywords = listOf(
                    "googleads", "doubleclick.net", "adsystem", "adserver",
                    "popads", "popcash", "exoclick", "propellerads", "adsterra",
                    "onclickads", "scorecardresearch", "taboola", "outbrain",
                    "criteo", "amazon-adsystem", "adnxs", "bidswitch",
                    "serving-sys.com", "media.net", "yieldmo.com", "popunder"
                )
                if (adKeywords.any { url.contains(it, ignoreCase = true) }) {
                    android.util.Log.d(TAG_AD, "BLOCKED ad resource: $url")
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
                }
            }

            // Coupe les médias sur FetLife. flcdn.net sert aussi du JS/CSS donc on filtre seulement les médias.
            if (isMediaResource(url)) {
                android.util.Log.d(TAG_AD, "BLOCKED media (FetLife): $url")
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
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

            // Blocage des images réseau dans FetLife
            webView.settings.blockNetworkImage = true

            if (!isContentUrl(url)) return

            when (checkAccess()) {
                AccessState.CAN_START_NEW_SESSION -> {
                    val sessionsUsed = prefs.getInt(PREF_SESSIONS_USED, 0)
                    prefs.edit()
                        .putLong(PREF_SESSION_START_MS, System.currentTimeMillis())
                        .putInt(PREF_SESSIONS_USED, sessionsUsed + 1)
                        .apply()

                    timerIndicator.visibility = View.VISIBLE
                    updateTimerUI(SESSION_LIMIT_MS, sessionsUsed + 1)
                    if (!isTimerRunning) startTimer()
                }
                AccessState.ALLOWED -> {
                    timerIndicator.visibility = View.VISIBLE
                    val sessionStart = prefs.getLong(PREF_SESSION_START_MS, 0L)
                    val sessionsUsed = prefs.getInt(PREF_SESSIONS_USED, 0)
                    updateTimerUI((sessionStart + SESSION_LIMIT_MS) - System.currentTimeMillis(), sessionsUsed)
                    if (!isTimerRunning) startTimer()
                }
                AccessState.COOLDOWN, AccessState.DAILY_LIMIT_REACHED -> {
                    view.stopLoading()
                    blockSessionDueToTime()
                }
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            android.util.Log.i(TAG_NAV, "PAGE_DONE $url")
            hideProgress()
            hideSplash()
            injectThemeColor(view)
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

    private inner class FetLifeChromeClient : WebChromeClient() {
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
            fabRefresh.visibility = View.GONE
        }

        override fun onHideCustomView() {
            customView?.let {
                val decorView = window.decorView as FrameLayout
                decorView.removeView(it)
                webView.visibility = View.VISIBLE
                fabRefresh.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
            }
            customView = null
            customViewCallback = null
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

    private fun showBlockedOverlay(message: String, icon: String = "🚫", quota: Boolean = false) {
        hideSplash()
        if (!quota) {
            blockedByQuota = false
            btnGoHome.setText(R.string.go_home)
        }
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

    // ─── Network ─────────────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ─── Custom JS Injections ────────────────────────────────────────────

    private fun injectThemeColor(view: WebView) {
        val js = """
            (function() {
                var meta = document.querySelector('meta[name="theme-color"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'theme-color';
                    document.head.appendChild(meta);
                }
                meta.content = '#0D0D1A';
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ─── Back Navigation ─────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            blockedOverlay.visibility == View.VISIBLE && blockedByQuota -> finish()
            blockedOverlay.visibility == View.VISIBLE -> {
                blockedOverlay.visibility = View.GONE
                loadSite(FETLIFE_URL)
            }
            webView.canGoBack() -> webView.goBack()
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
        when (checkAccess()) {
            AccessState.COOLDOWN, AccessState.DAILY_LIMIT_REACHED -> blockSessionDueToTime()
            else -> startTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        stopTimer()

        if (checkAccess() == AccessState.ALLOWED) {
            val forcedExpiration = System.currentTimeMillis() - SESSION_LIMIT_MS
            prefs.edit().putLong(PREF_SESSION_START_MS, forcedExpiration).apply()
            android.util.Log.i(TAG_SESSION, "SESSION_FORCED_END (app mise en arrière-plan)")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        webView.destroy()
    }
}
