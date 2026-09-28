package com.jo.entutc

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.*
import android.widget.FrameLayout
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

/**
 * ENT UTC — Environnement Numérique de Travail de l'Université de Technologie de Compiègne.
 *
 * Accès direct aux cours, emplois du temps, notes, messagerie, Moodle et services UTC.
 * App mono-site/institutionnelle sans quota de temps (outil de travail et d'études).
 * Les images, documents, cookies et téléchargements sont entièrement préservés.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Destination principale au démarrage
        private const val ENT_URL = "https://ngapplis.utc.fr/ent4/"

        // Logging tags — filtrables via : adb logcat -s ENT_NAV,ENT_BLOCK,ENT_INTENT,ENT_AD
        private const val TAG_NAV    = "ENT_NAV"
        private const val TAG_BLOCK  = "ENT_BLOCK"
        private const val TAG_INTENT = "ENT_INTENT"
        private const val TAG_AD     = "ENT_AD"

        // Liste blanche des domaines autorisés (inclut tous les sous-domaines via endsWith)
        private val ALLOWED_DOMAINS = listOf(
            "utc.fr",                  // ngapplis.utc.fr, cas.utc.fr, moodle.utc.fr, webapplis.utc.fr, etc.
            "renater.fr",              // Fédération d'identités académique
            "edugain.org",             // Fédération internationale
            "education.gouv.fr",
            "education.fr",
            "cloudflare.com",
            "cloudflareinsights.com",
            "gstatic.com",
            "googleapis.com",
            "google.com",
            "hcaptcha.com",
            "recaptcha.net"
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

    private var progressAnimator: android.animation.ValueAnimator? = null
    private var currentMainUrl: String = ""

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                Toast.makeText(context, R.string.download_complete, Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupEdgeToEdge()
        bindViews()

        setupWebView()
        registerDownloadReceiver()

        if (!isNetworkAvailable()) {
            showError()
            return
        }

        if (!handleViewIntent(intent)) {
            loadEnt()
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
            loadEnt()
        }
    }

    private fun handleViewIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            android.util.Log.i(TAG_INTENT, "IGNORED non-http intent: scheme=$scheme url=$uri")
            return false
        }
        val url = uri.toString()
        android.util.Log.i(TAG_INTENT, "RECEIVED intent url=$url")

        if (!isUrlAllowed(url)) {
            android.util.Log.w(TAG_INTENT, "BLOCKED external intent (hors UTC): $url")
            val host = uri.host ?: "inconnu"
            showBlockedOverlay(getString(R.string.external_url_not_allowed, host))
            return true
        }

        loadEnt(url)
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
        webView = findViewById(R.id.webView)
        fabRefresh = findViewById(R.id.fabRefresh)

        fabRefresh.setOnClickListener {
            webView.reload()
        }

        progressContainer = findViewById(R.id.progressContainer)
        progressBar = findViewById(R.id.progressBar)
        splashOverlay = findViewById(R.id.splashOverlay)
        blockedOverlay = findViewById(R.id.blockedOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        blockedMessage = findViewById(R.id.blockedMessage)
        blockedIcon = findViewById(R.id.blockedIcon)

        findViewById<View>(R.id.btnGoHome).setOnClickListener {
            blockedOverlay.visibility = View.GONE
            loadEnt()
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

    private fun loadEnt(url: String = ENT_URL) {
        android.util.Log.i(TAG_NAV, "loadEnt url=$url")
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Activation complète des cookies (requis pour CAS et sessions ENT)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

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
            blockNetworkImage = false
        }

        webView.webViewClient = EntWebViewClient()
        webView.webChromeClient = EntChromeClient()

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (cookie != null) {
                    addRequestHeader("Cookie", cookie)
                }
                setTitle(fileName)
                setDescription(getString(R.string.download_started))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e(TAG_NAV, "Erreur téléchargement", e)
            Toast.makeText(this, "Erreur lors du téléchargement", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, filter)
        }
    }

    private fun isUrlAllowed(url: String): Boolean {
        val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return ALLOWED_DOMAINS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    private inner class EntWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()

            if (!request.isForMainFrame) {
                val adKeywords = listOf(
                    "googleads", "doubleclick.net", "adsystem", "adserver",
                    "popads", "popcash", "exoclick", "propellerads", "adsterra",
                    "onclickads", "scorecardresearch", "taboola", "outbrain",
                    "criteo", "amazon-adsystem", "adnxs", "bidswitch",
                    "serving-sys.com", "media.net", "yieldmo.com"
                )
                if (adKeywords.any { url.contains(it, ignoreCase = true) }) {
                    android.util.Log.d(TAG_AD, "BLOCKED ad resource: $url")
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
                val host = request.url.host ?: "inconnu"
                showBlockedOverlay(getString(R.string.external_url_not_allowed, host))
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

    private inner class EntChromeClient : WebChromeClient() {
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
        }

        override fun onHideCustomView() {
            customView?.let {
                val decorView = window.decorView as FrameLayout
                decorView.removeView(it)
                webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
            }
            customView = null
            customViewCallback = null
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
                android.util.Log.e(TAG_NAV, "Erreur ouverture sélecteur de fichier", e)
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
                meta.content = '#016A7D';
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
                loadEnt()
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
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: Exception) { }
        webView.destroy()
    }
}
