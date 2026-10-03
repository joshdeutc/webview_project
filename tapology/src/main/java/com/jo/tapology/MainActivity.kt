package com.jo.tapology

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.ByteArrayInputStream
import java.net.URLDecoder

/**
 * Watch Wrestling & UFC Embedded — Streaming de catch et MMA (WWE, AEW, UFC, etc.)
 *
 * App double-onglet :
 * - Onglet 1 : Watch Wrestling (https://watchwrestling.ae/)
 * - Onglet 2 : UFC Embedded (https://www.ufc.com/embedded)
 * - Filtrage agressif des publicités, popups et popunders
 * - Décodage direct des liens de redirection vidéo (afiyukent.one/away.php)
 * - Support plein écran pour les lecteurs vidéo intégrés avec rotation paysage
 * - Maintien de l'écran allumé pendant la lecture vidéo
 */
class MainActivity : AppCompatActivity() {

    enum class AppTab {
        WATCH_WRESTLING,
        UFC
    }

    companion object {
        private const val WATCHWRESTLING_URL = "https://watchwrestling.ae/"
        private const val UFC_EMBEDDED_URL   = "https://www.ufc.com/embedded"

        private const val TAG_NAV    = "WW_NAV"
        private const val TAG_BLOCK  = "WW_BLOCK"
        private const val TAG_INTENT = "WW_INTENT"
        private const val TAG_AD     = "WW_AD"

        /**
         * Liste des domaines autorisés : Watch Wrestling, UFC et leurs passerelles/hébergeurs vidéo.
         */
        private val ALLOWED_DOMAINS = listOf(
            // Watch Wrestling sites
            "watchwrestling.ae",
            "watchwrestling.in",
            "watchwrestling.ai",
            "watchwrestling.so",
            "watchwrestling.to",

            // Video Gateways
            "afiyukent.one",

            // Video Streaming Hosts
            "fastvid.xyz",
            "dailymotion.com",
            "dmcdn.net",
            "ok.ru",
            "odnoklassniki.ru",
            "vk.com",
            "userapi.com",
            "streamwish.to",
            "streamwish.com",
            "swishsrv.com",
            "wishembed.pro",
            "netu.tv",
            "hqq.tv",
            "hqq.to",
            "waaw.to",
            "dood.to",
            "doodstream.com",
            "dood.so",
            "dood.ws",
            "dood.watch",
            "filemoon.sx",
            "filemoon.to",
            "filemoon.in",
            "mixdrop.co",
            "mixdrop.to",

            // UFC & Video Embeds
            "ufc.com",
            "youtube.com",
            "youtube-nocookie.com",
            "youtu.be",
            "googlevideo.com",
            "ytimg.com",
            "imggaming.com",
            "pub.network",
            "lndg.page",
            "addtoany.com",

            // CDN & Essential Libraries
            "cloudflare.com",
            "cloudflareinsights.com",
            "cloudfront.net",
            "jquery.com",
            "bootstrapcdn.com",
            "jsdelivr.net",
            "cdnjs.cloudflare.com",
            "gstatic.com",
            "googleapis.com",
            "google.com"
        )

        /**
         * Mots-clés pour bloquer les scripts publicitaires et traceurs dans les requêtes réseau.
         */
        private val AD_KEYWORDS = listOf(
            "googleads", "doubleclick.net", "adsystem", "adserver",
            "popads", "popcash", "exoclick", "propellerads", "adsterra",
            "onclickads", "scorecardresearch", "taboola", "outbrain",
            "criteo", "amazon-adsystem", "adnxs", "bidswitch",
            "serving-sys.com", "media.net", "yieldmo.com", "popunder",
            "histats.com", "googlesyndication.com", "trafficjunky",
            "juicyads", "adreactor", "monetag.com", "hilltopads",
            "clickadu.com", "richpush.com", "admaven.com", "mgid.com",
            "tsyndicate.com", "yllix.com", "bet365", "1xbet", "1win"
        )
    }

    private lateinit var tabBar: LinearLayout
    private lateinit var tabWatchWrestling: FrameLayout
    private lateinit var tabUfc: FrameLayout
    private lateinit var txtTabWW: TextView
    private lateinit var txtTabUfc: TextView
    private lateinit var indicatorWW: View
    private lateinit var indicatorUfc: View

    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var webViewUfc: WebView
    private lateinit var fabRefresh: FloatingActionButton
    private lateinit var progressContainer: FrameLayout
    private lateinit var progressBar: View
    private lateinit var splashOverlay: FrameLayout
    private lateinit var blockedOverlay: FrameLayout
    private lateinit var errorOverlay: FrameLayout
    private lateinit var blockedMessage: TextView
    private lateinit var blockedIcon: TextView

    private var currentTab: AppTab = AppTab.WATCH_WRESTLING
    private val activeWebView: WebView
        get() = if (currentTab == AppTab.UFC) webViewUfc else webView

    private var progressAnimator: android.animation.ValueAnimator? = null
    private var currentMainUrl: String = ""

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val intentData = result.data
            val clipData = intentData?.clipData
            val uris = when {
                clipData != null -> {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                }
                intentData?.data != null -> {
                    arrayOf(intentData.data!!)
                }
                else -> null
            }
            fileUploadCallback?.onReceiveValue(uris)
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    private var pendingAudioPermissionRequest: PermissionRequest? = null
    private val requestAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingAudioPermissionRequest?.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            pendingAudioPermissionRequest?.deny()
        }
        pendingAudioPermissionRequest = null
    }

    // Video Fullscreen State
    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupEdgeToEdge()
        bindViews()
        setupWebViews()
        setupTabListeners()

        if (!isNetworkAvailable()) {
            showError()
            return
        }
        if (!handleViewIntent(intent)) {
            loadHome()
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
            loadHome()
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
            loadHome()
            showBlockedOverlay(getString(R.string.external_url_not_allowed, uri.host ?: url))
            return true
        }

        val host = uri.host?.lowercase() ?: ""
        if (host.contains("ufc.com")) {
            switchTab(AppTab.UFC)
            webViewUfc.loadUrl(url)
        } else {
            switchTab(AppTab.WATCH_WRESTLING)
            webView.loadUrl(url)
        }
        return true
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
        tabBar = findViewById(R.id.tabBar)
        tabWatchWrestling = findViewById(R.id.tabWatchWrestling)
        tabUfc = findViewById(R.id.tabUfc)
        txtTabWW = findViewById(R.id.txtTabWW)
        txtTabUfc = findViewById(R.id.txtTabUfc)
        indicatorWW = findViewById(R.id.indicatorWW)
        indicatorUfc = findViewById(R.id.indicatorUfc)

        webContainer = findViewById(R.id.webContainer)
        webView = findViewById(R.id.webView)
        webViewUfc = findViewById(R.id.webViewUfc)

        fabRefresh = findViewById(R.id.fabRefresh)
        progressContainer = findViewById(R.id.progressContainer)
        progressBar = findViewById(R.id.progressBar)
        splashOverlay = findViewById(R.id.splashOverlay)
        blockedOverlay = findViewById(R.id.blockedOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        blockedMessage = findViewById(R.id.blockedMessage)
        blockedIcon = findViewById(R.id.blockedIcon)

        fabRefresh.setOnClickListener { activeWebView.reload() }

        findViewById<View>(R.id.btnGoHome).setOnClickListener {
            blockedOverlay.visibility = View.GONE
            if (currentTab == AppTab.WATCH_WRESTLING) {
                loadHome()
            } else {
                loadUfc()
            }
        }

        findViewById<View>(R.id.btnRetry).setOnClickListener {
            if (isNetworkAvailable()) {
                errorOverlay.visibility = View.GONE
                activeWebView.reload()
            } else {
                Toast.makeText(this, R.string.no_internet, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupTabListeners() {
        tabWatchWrestling.setOnClickListener { switchTab(AppTab.WATCH_WRESTLING) }
        tabUfc.setOnClickListener { switchTab(AppTab.UFC) }
    }

    private fun switchTab(tab: AppTab) {
        if (currentTab == tab) return
        currentTab = tab

        if (tab == AppTab.WATCH_WRESTLING) {
            txtTabWW.setTextColor(ContextCompat.getColor(this, R.color.white))
            txtTabWW.setTypeface(null, Typeface.BOLD)
            indicatorWW.visibility = View.VISIBLE

            txtTabUfc.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            txtTabUfc.setTypeface(null, Typeface.NORMAL)
            indicatorUfc.visibility = View.INVISIBLE

            webView.visibility = View.VISIBLE
            webViewUfc.visibility = View.GONE
        } else {
            txtTabUfc.setTextColor(ContextCompat.getColor(this, R.color.white))
            txtTabUfc.setTypeface(null, Typeface.BOLD)
            indicatorUfc.visibility = View.VISIBLE

            txtTabWW.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            txtTabWW.setTypeface(null, Typeface.NORMAL)
            indicatorWW.visibility = View.INVISIBLE

            webViewUfc.visibility = View.VISIBLE
            webView.visibility = View.GONE

            if (webViewUfc.url.isNullOrEmpty()) {
                loadUfc()
            }
        }

        hideSplash()
        hideProgress()
    }

    private fun loadHome() {
        webView.loadUrl(WATCHWRESTLING_URL)
    }

    private fun loadUfc() {
        webViewUfc.loadUrl(UFC_EMBEDDED_URL)
    }

    private fun setupWebViews() {
        configureWebView(webView, isUfcTab = false)
        configureWebView(webViewUfc, isUfcTab = true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView, isUfcTab: Boolean) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
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
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false

            val currentAgent = userAgentString ?: ""
            userAgentString = currentAgent.replace("; wv", "")
        }

        wv.webViewClient = WatchWrestlingWebViewClient(isUfcTab)
        wv.webChromeClient = WatchWrestlingChromeClient()
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

    /**
     * Décode les redirections de type afiyukent.one/away.php?to=<url>
     * pour charger directement la page cible et contourner les pubs interstitielles.
     */
    private fun resolveDirectVideoUrl(url: String): String? {
        if (!url.contains("away.php?to=")) return null
        return try {
            val uri = Uri.parse(url)
            val targetParam = uri.getQueryParameter("to")
            if (!targetParam.isNullOrBlank()) {
                URLDecoder.decode(targetParam, "UTF-8")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private inner class WatchWrestlingWebViewClient(private val isUfcTab: Boolean) : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()

            // Bloquer toutes les ressources publicitaires et traceurs identifiés
            if (!request.isForMainFrame) {
                if (AD_KEYWORDS.any { url.contains(it, ignoreCase = true) }) {
                    android.util.Log.d(TAG_AD, "BLOCKED ad resource: $url")
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
                }
            }

            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val rawUrl = request.url.toString()
            val frameType = if (request.isForMainFrame) "MAIN" else "SUB"

            // 1. Détection et contournement des redirections away.php
            val directUrl = resolveDirectVideoUrl(rawUrl)
            if (directUrl != null) {
                android.util.Log.i(TAG_NAV, "BYPASS away.php -> direct url: $directUrl")
                if (isUrlAllowed(directUrl)) {
                    view.loadUrl(directUrl)
                    return true
                }
            }

            // 2. Vérification de l'allowlist
            return if (isUrlAllowed(rawUrl)) {
                android.util.Log.i(TAG_NAV, "ALLOW [$frameType] $rawUrl")
                if (request.isForMainFrame) {
                    currentMainUrl = rawUrl
                }
                false
            } else {
                // Silencieusement ignoré pour neutraliser les popups/popunders et liens malveillants
                android.util.Log.w(TAG_BLOCK, "BLOCK [$frameType] $rawUrl (from page: $currentMainUrl)")
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (view == activeWebView) {
                currentMainUrl = url
                android.util.Log.i(TAG_NAV, "PAGE_START $url")
                showProgress()
                errorOverlay.visibility = View.GONE
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (view == activeWebView) {
                android.util.Log.i(TAG_NAV, "PAGE_DONE $url")
                hideProgress()
                hideSplash()
            }
            injectAntiAdScript(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            val frameType = if (request.isForMainFrame) "MAIN" else "SUB"
            android.util.Log.e(TAG_NAV, "PAGE_ERROR [$frameType] url=${request.url} code=${error.errorCode} desc=${error.description}")
            if (request.isForMainFrame && view == activeWebView) {
                showError()
            }
        }
    }

    private inner class WatchWrestlingChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (view == activeWebView) {
                updateProgress(newProgress)
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customVideoView != null) {
                onHideCustomView()
                return
            }

            customVideoView = view
            customVideoCallback = callback

            // Plein écran immersif et orientation paysage pour le lecteur vidéo
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

            val decorView = window.decorView as FrameLayout
            decorView.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            tabBar.visibility = View.GONE
            webContainer.visibility = View.GONE
            fabRefresh.visibility = View.GONE
        }

        override fun onHideCustomView() {
            customVideoView?.let { view ->
                val decorView = window.decorView as FrameLayout
                decorView.removeView(view)
                tabBar.visibility = View.VISIBLE
                webContainer.visibility = View.VISIBLE
                fabRefresh.visibility = View.VISIBLE
                customVideoCallback?.onCustomViewHidden()

                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            }
            customVideoView = null
            customVideoCallback = null
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = filePathCallback

            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }

            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (e: Exception) {
                android.util.Log.e(TAG_NAV, "Erreur sélecteur de fichier", e)
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val resources = request.resources
                if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingAudioPermissionRequest = request
                        requestAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    request.deny()
                }
            }
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
        hideSplash()
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

    // ─── Anti-Ad & Anti-Popup JS Injection ────────────────────────────────

    private fun injectAntiAdScript(view: WebView) {
        val js = """
            (function() {
                // 1. Empêcher les scripts de déclencher window.open (pop-ups et pop-unders)
                window.open = function() { return null; };

                // 2. Nettoyer les éléments publicitaires connus sans toucher aux lecteurs vidéo
                var badSelectors = [
                    '#popunder', '.ad-box', '.ad-banner', '.adsbygoogle',
                    'iframe[src*="ad"]', 'iframe[src*="bet"]', 'iframe[src*="traffic"]',
                    'div[id*="adsterra"]', 'div[id*="propeller"]', 'div[class*="ad_"]',
                    '#onetrust-consent-sdk', '#onetrust-banner-sdk'
                ];
                badSelectors.forEach(function(sel) {
                    try {
                        document.querySelectorAll(sel).forEach(function(el) {
                            if (!el.querySelector('video') && 
                                !el.querySelector('iframe[src*="fastvid"]') && 
                                !el.querySelector('iframe[src*="dailymotion"]') &&
                                !el.querySelector('iframe[src*="youtube"]')) {
                                el.remove();
                            }
                        });
                    } catch(e){}
                });

                // 3. Forcer la couleur du thème sombre de la barre système
                var meta = document.querySelector('meta[name="theme-color"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'theme-color';
                    document.head.appendChild(meta);
                }
                meta.content = '#121212';
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ─── Back Navigation ─────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customVideoView != null -> {
                // Quitter le plein écran vidéo si actif
                activeWebView.webChromeClient?.onHideCustomView()
            }
            blockedOverlay.visibility == View.VISIBLE -> {
                blockedOverlay.visibility = View.GONE
                if (currentTab == AppTab.WATCH_WRESTLING) loadHome() else loadUfc()
            }
            activeWebView.canGoBack() -> {
                activeWebView.goBack()
            }
            currentTab == AppTab.UFC -> {
                // Retour à l'onglet Watch Wrestling si au début de l'historique UFC
                switchTab(AppTab.WATCH_WRESTLING)
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
        webView.onResume()
        webViewUfc.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webViewUfc.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
        webViewUfc.destroy()
    }
}
