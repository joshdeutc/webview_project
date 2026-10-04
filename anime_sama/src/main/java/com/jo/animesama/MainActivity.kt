package com.jo.animesama

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.ByteArrayInputStream

/**
 * Anime Sama — Application de streaming anime dédiée
 *
 * Fonctionnalités clés :
 * - Chargement direct d'Anime-Sama (https://anime-sama.to/)
 * - Blocage agressif des publicités, popups, redirections et popunders
 * - Lecteur vidéo plein écran immersif en mode paysage automatique avec FLAG_KEEP_SCREEN_ON
 * - Allowlist stricte des hébergeurs vidéo officiels (Sibnet, Sendvid, Vidmoly, Streamtape, Myvi, VK, etc.)
 * - Gestion du retour en arrière et rechargement facile
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val ANIME_SAMA_URL = "https://anime-sama.to/"

        private const val TAG_NAV   = "AS_NAV"
        private const val TAG_BLOCK = "AS_BLOCK"
        private const val TAG_AD    = "AS_AD"

        /**
         * Domaines autorisés pour Anime-Sama, son infrastructure et ses lecteurs vidéo.
         */
        private val ALLOWED_DOMAINS = listOf(
            // Anime Sama sites & passerelles officielles
            "anime-sama.to",
            "anime-sama.pw",
            "anime-sama.org",
            "anime-sama.fr",

            // Hébergeurs vidéo légitimes utilisés par Anime-Sama
            "sibnet.ru",
            "video.sibnet.ru",
            "sendvid.com",
            "vidmoly.me",
            "vidmoly.to",
            "vidmoly.net",
            "streamtape.com",
            "streamtape.to",
            "streamtape.net",
            "myvi.tv",
            "myvi.ru",
            "vk.com",
            "userapi.com",
            "dailymotion.com",
            "dmcdn.net",
            "oneupload.to",
            "movembed.cc",
            "doodstream.com",
            "dood.to",
            "dood.ws",
            "dood.so",

            // CDNs et librairies indispensables
            "cloudflare.com",
            "cloudflareinsights.com",
            "cdnjs.cloudflare.com",
            "jsdelivr.net",
            "google.com",
            "googleapis.com",
            "gstatic.com"
        )

        /**
         * Mots-clés pour intercepter et bloquer les scripts malveillants, traqueurs et popunders.
         */
        private val AD_KEYWORDS = listOf(
            "llvpn.com", "kingdomparlor.com", "acscdn.com", "a-zzz.com",
            "googleads", "doubleclick.net", "adsystem", "adserver",
            "popads", "popcash", "exoclick", "propellerads", "adsterra",
            "onclickads", "scorecardresearch", "taboola", "outbrain",
            "criteo", "amazon-adsystem", "adnxs", "bidswitch",
            "serving-sys.com", "media.net", "yieldmo.com", "popunder",
            "histats.com", "googlesyndication.com", "trafficjunky",
            "juicyads", "adreactor", "monetag.com", "hilltopads",
            "clickadu.com", "richpush.com", "admaven.com", "mgid.com",
            "tsyndicate.com", "yllix.com", "bet365", "1xbet", "1win",
            "vlitag.com", "alwingulla.com", "highcpmgate.com", "btag.min.js"
        )
    }

    private lateinit var topBar: LinearLayout
    private lateinit var btnHeaderHome: ImageView
    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fabRefresh: FloatingActionButton
    private lateinit var progressContainer: FrameLayout
    private lateinit var progressBar: View
    private lateinit var splashOverlay: FrameLayout
    private lateinit var blockedOverlay: FrameLayout
    private lateinit var errorOverlay: FrameLayout
    private lateinit var blockedMessage: TextView
    private lateinit var blockedIcon: TextView

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
        setupWebView()

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
        if (isUrlAllowed(url)) {
            webView.loadUrl(url)
            return true
        }
        return false
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    private fun bindViews() {
        topBar = findViewById(R.id.topBar)
        btnHeaderHome = findViewById(R.id.btnHeaderHome)
        webContainer = findViewById(R.id.webContainer)
        webView = findViewById(R.id.webView)
        fabRefresh = findViewById(R.id.fabRefresh)
        progressContainer = findViewById(R.id.progressContainer)
        progressBar = findViewById(R.id.progressBar)
        splashOverlay = findViewById(R.id.splashOverlay)
        blockedOverlay = findViewById(R.id.blockedOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        blockedMessage = findViewById(R.id.blockedMessage)
        blockedIcon = findViewById(R.id.blockedIcon)

        fabRefresh.setOnClickListener { webView.reload() }
        btnHeaderHome.setOnClickListener { loadHome() }

        findViewById<View>(R.id.btnGoHome).setOnClickListener {
            blockedOverlay.visibility = View.GONE
            loadHome()
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

    private fun loadHome() {
        webView.loadUrl(ANIME_SAMA_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

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
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false

            val currentAgent = userAgentString ?: ""
            userAgentString = currentAgent.replace("; wv", "")
        }

        webView.webViewClient = AnimeSamaWebViewClient()
        webView.webChromeClient = AnimeSamaChromeClient()
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

    private inner class AnimeSamaWebViewClient : WebViewClient() {

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

            return if (isUrlAllowed(rawUrl)) {
                android.util.Log.i(TAG_NAV, "ALLOW [$frameType] $rawUrl")
                if (request.isForMainFrame) {
                    currentMainUrl = rawUrl
                }
                false
            } else {
                // Silencieusement ignoré pour neutraliser les popups et redirections indésirables
                android.util.Log.w(TAG_BLOCK, "BLOCK [$frameType] $rawUrl (from page: $currentMainUrl)")
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            currentMainUrl = url
            android.util.Log.i(TAG_NAV, "PAGE_START $url")
            showProgress()
            errorOverlay.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            android.util.Log.i(TAG_NAV, "PAGE_DONE $url")
            hideProgress()
            hideSplash()
            injectAntiAdScript(view)
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

    private inner class AnimeSamaChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            updateProgress(newProgress)
            if (newProgress >= 30) {
                injectAntiAdScript(view)
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customVideoView != null) {
                onHideCustomView()
                return
            }

            customVideoView = view
            customVideoCallback = callback

            // Mode plein écran immersif en paysage pour les lecteurs vidéo
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
            topBar.visibility = View.GONE
            webContainer.visibility = View.GONE
            fabRefresh.visibility = View.GONE
        }

        override fun onHideCustomView() {
            customVideoView?.let { view ->
                val decorView = window.decorView as FrameLayout
                decorView.removeView(view)
                topBar.visibility = View.VISIBLE
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
                // 1. Bloquer window.open pour tuer les popups & redirections
                window.open = function() { return null; };

                // 2. CSS pour neutraliser les bannières de pubs et les faux overlays cliquables
                var styleId = 'as-anti-ad-style';
                if (!document.getElementById(styleId) && document.head) {
                    var style = document.createElement('style');
                    style.id = styleId;
                    style.innerHTML = `
                        #bandeau-pubs-nuit,
                        #div-gpt-ad,
                        .pub, .ads, .ad-banner, .ad-container,
                        iframe[src*="llvpn"],
                        iframe[src*="acscdn"],
                        iframe[src*="kingdomparlor"],
                        iframe[src*="btag"] {
                            display: none !important;
                            pointer-events: none !important;
                            visibility: hidden !important;
                        }
                    `;
                    document.head.appendChild(style);
                }

                // 3. Supprimer les éléments publicitaires connus sans toucher aux lecteurs vidéos
                function removeAds() {
                    var selectors = [
                        '#bandeau-pubs-nuit',
                        'iframe[src*="llvpn"]',
                        'iframe[src*="acscdn"]',
                        'iframe[src*="kingdomparlor"]',
                        'script[src*="a-zzz.com"]',
                        'script[src*="llvpn.com"]'
                    ];
                    selectors.forEach(function(sel) {
                        try {
                            document.querySelectorAll(sel).forEach(function(el) {
                                el.remove();
                            });
                        } catch(e){}
                    });
                }
                removeAds();

                // 4. Observer dynamique pour neutraliser les pubs injectées après chargement
                if (!window.__asAdObserver && document.documentElement) {
                    var __asTimer = null;
                    window.__asAdObserver = new MutationObserver(function() {
                        if (__asTimer) return;
                        __asTimer = setTimeout(function() { __asTimer = null; removeAds(); }, 400);
                    });
                    window.__asAdObserver.observe(document.documentElement, { childList: true, subtree: true });
                }

                // 5. Thème sombre système
                var meta = document.querySelector('meta[name="theme-color"]');
                if (!meta && document.head) {
                    meta = document.createElement('meta');
                    meta.name = 'theme-color';
                    document.head.appendChild(meta);
                }
                if (meta) meta.content = '#0F0F13';
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ─── Back Navigation ─────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customVideoView != null -> {
                // Quitter le mode plein écran vidéo
                webView.webChromeClient?.onHideCustomView()
            }
            blockedOverlay.visibility == View.VISIBLE -> {
                blockedOverlay.visibility = View.GONE
                loadHome()
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
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
