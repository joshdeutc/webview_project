package com.jo.novethic

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        const val URL_HOME = "https://www.novethic.fr/"
        const val URL_ENV = "https://www.novethic.fr/environnement"
        const val URL_ECO = "https://www.novethic.fr/economie-social"
        const val URL_FINANCE = "https://www.novethic.fr/finance-durable"
        const val URL_DECRYPTAGES = "https://www.novethic.fr/decryptages-dexpert"

        private val ALLOWED_DOMAINS = listOf(
            "novethic.fr",
            "aefinfo.fr",
            "wp.com",
            "wordpress.com",
            "cloudflare.com",
            "cloudflareinsights.com",
            "googleapis.com",
            "gstatic.com",
            "cdnjs.cloudflare.com",
            "jsdelivr.net"
        )
    }

    private lateinit var rootLayout: View
    private lateinit var btnHome: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnRefresh: ImageButton

    private lateinit var chipHome: TextView
    private lateinit var chipEnv: TextView
    private lateinit var chipEco: TextView
    private lateinit var chipFinance: TextView
    private lateinit var chipDecryptages: TextView

    private lateinit var progressContainer: FrameLayout
    private lateinit var progressBar: View
    private var progressAnimator: ValueAnimator? = null

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var splashOverlay: FrameLayout
    private lateinit var errorOverlay: FrameLayout
    private lateinit var btnRetry: Button

    private var currentActiveChip: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupEdgeToEdge()
        bindViews()
        setupCategoryChips()
        setupWebView()
        setupSwipeRefresh()
        setupActionButtons()

        val targetUrl = intent?.data?.toString() ?: URL_HOME
        webView.loadUrl(targetUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.data?.toString()
        if (!url.isNullOrBlank() && isUrlAllowed(url)) {
            webView.loadUrl(url)
        }
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        rootLayout = findViewById(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
    }

    private fun bindViews() {
        btnHome = findViewById(R.id.btnHome)
        btnShare = findViewById(R.id.btnShare)
        btnRefresh = findViewById(R.id.btnRefresh)

        chipHome = findViewById(R.id.chipHome)
        chipEnv = findViewById(R.id.chipEnv)
        chipEco = findViewById(R.id.chipEco)
        chipFinance = findViewById(R.id.chipFinance)
        chipDecryptages = findViewById(R.id.chipDecryptages)

        progressContainer = findViewById(R.id.progressContainer)
        progressBar = findViewById(R.id.progressBar)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        webView = findViewById(R.id.webView)
        splashOverlay = findViewById(R.id.splashOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        btnRetry = findViewById(R.id.btnRetry)

        currentActiveChip = chipHome
    }

    private fun setupCategoryChips() {
        chipHome.setOnClickListener {
            setActiveChip(chipHome)
            webView.loadUrl(URL_HOME)
        }
        chipEnv.setOnClickListener {
            setActiveChip(chipEnv)
            webView.loadUrl(URL_ENV)
        }
        chipEco.setOnClickListener {
            setActiveChip(chipEco)
            webView.loadUrl(URL_ECO)
        }
        chipFinance.setOnClickListener {
            setActiveChip(chipFinance)
            webView.loadUrl(URL_FINANCE)
        }
        chipDecryptages.setOnClickListener {
            setActiveChip(chipDecryptages)
            webView.loadUrl(URL_DECRYPTAGES)
        }
    }

    private fun setActiveChip(activeChip: TextView) {
        val chips = listOf(chipHome, chipEnv, chipEco, chipFinance, chipDecryptages)
        for (chip in chips) {
            if (chip == activeChip) {
                chip.setBackgroundResource(R.drawable.chip_bg_selected)
                chip.setTextColor(ContextCompat.getColor(this, R.color.white))
                chip.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                chip.setBackgroundResource(R.drawable.chip_bg_unselected)
                chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                chip.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        currentActiveChip = activeChip
    }

    private fun updateChipFromUrl(url: String) {
        when {
            url.contains("/environnement") -> setActiveChip(chipEnv)
            url.contains("/economie-social") -> setActiveChip(chipEco)
            url.contains("/finance-durable") -> setActiveChip(chipFinance)
            url.contains("/decryptages-dexpert") -> setActiveChip(chipDecryptages)
            url == URL_HOME || url == "https://www.novethic.fr" || url == "https://novethic.fr" -> setActiveChip(chipHome)
        }
    }

    private fun setupActionButtons() {
        btnHome.setOnClickListener {
            setActiveChip(chipHome)
            webView.loadUrl(URL_HOME)
        }
        btnShare.setOnClickListener {
            val currentUrl = webView.url ?: URL_HOME
            val shareTitle = webView.title ?: "Novethic - L'information de la transition durable"
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_SUBJECT, shareTitle)
                putExtra(Intent.EXTRA_TEXT, "$shareTitle\n$currentUrl")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
        }
        btnRefresh.setOnClickListener {
            errorOverlay.visibility = View.GONE
            showProgress()
            if (webView.url.isNullOrBlank()) {
                webView.loadUrl(URL_HOME)
            } else {
                webView.reload()
            }
        }
        btnRetry.setOnClickListener {
            errorOverlay.visibility = View.GONE
            showProgress()
            if (webView.url.isNullOrBlank()) {
                webView.loadUrl(URL_HOME)
            } else {
                webView.reload()
            }
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.accent),
            ContextCompat.getColor(this, R.color.primary)
        )
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.surface_dark)
        )
        swipeRefreshLayout.setOnRefreshListener {
            if (isNetworkAvailable()) {
                errorOverlay.visibility = View.GONE
                webView.reload()
            } else {
                swipeRefreshLayout.isRefreshing = false
                showError()
            }
        }
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

            val defaultAgent = userAgentString ?: ""
            userAgentString = defaultAgent.replace("; wv", "")
        }

        webView.webViewClient = NovethicWebViewClient()
        webView.webChromeClient = NovethicWebChromeClient()

        // Gestion du téléchargement des rapports et PDF de Novethic
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("Téléchargement du document Novethic")
                    setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimetype)
                    )
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Téléchargement en cours...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Fallback navigateur externe
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {}
            }
        }
    }

    private inner class NovethicWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return if (isUrlAllowed(url)) {
                updateChipFromUrl(url)
                false
            } else {
                // Ouvre les liens externes (réseaux sociaux, partenaires) dans le navigateur système
                try {
                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                    startActivity(intent)
                } catch (_: Exception) {}
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            updateChipFromUrl(url)
            showProgress()
            errorOverlay.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            hideProgress()
            hideSplash()
            swipeRefreshLayout.isRefreshing = false
            injectReaderEnhancements(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            android.util.Log.e("NOVETHIC", "PAGE_ERROR url=${request.url} code=${error.errorCode} desc=${error.description}")
            if (request.isForMainFrame) {
                showError()
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            android.util.Log.e("NOVETHIC", "SSL_ERROR: $error")
            handler.proceed()
        }
    }

    private inner class NovethicWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            updateProgress(newProgress)
            if (newProgress >= 40) {
                injectReaderEnhancements(view)
            }
        }
    }

    private fun injectReaderEnhancements(view: WebView) {
        val js = """
            (function() {
                // 1. Désactiver les ouvertures de fenêtres intrusives
                window.open = function() { return null; };

                // 2. Supprimer les bandeaux de consentement cookies (OneTrust, Axeptio, Didomi, Tarteaucitron)
                var cookieSelectors = [
                    '#onetrust-consent-sdk', '#onetrust-banner-sdk', '.onetrust-pc-dark',
                    '#didomi-host', '.axeptio_mount', '#tarteaucitronRoot', '#tarteaucitronAlertBig',
                    '.cookie-notice', '.cookie-banner', '#sp_message_container'
                ];
                cookieSelectors.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(el) { el.remove(); });
                });

                // 3. Masquer les popups marketing ou overlays bloquants pour préserver la lecture
                var overlaySelectors = [
                    '.c-modal', '.newsletter-overlay', '.popin-newsletter', '.popup-newsletter',
                    '.modale-abonnement', '.sp_veil'
                ];
                overlaySelectors.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(el) { el.remove(); });
                });

                // 4. Débloquer le scroll si jamais une modale avait verrouillé le body
                document.body.style.overflow = 'auto';
                document.documentElement.style.overflow = 'auto';

                // 5. Thème sombre cohérent pour la barre de statut web
                var meta = document.querySelector('meta[name="theme-color"]');
                if (!meta && document.head) {
                    meta = document.createElement('meta');
                    meta.name = 'theme-color';
                    document.head.appendChild(meta);
                }
                if (meta) meta.content = '#161616';
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
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
        } catch (_: Exception) {
            false
        }
    }

    private fun showProgress() {
        progressContainer.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        progressContainer.animate()
            .alpha(0f)
            .setDuration(250)
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
        progressAnimator = ValueAnimator.ofInt(progressBar.layoutParams.width, targetWidth).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val params = progressBar.layoutParams
                params.width = animator.animatedValue as Int
                progressBar.layoutParams = params
            }
            start()
        }
    }

    private fun hideSplash() {
        if (splashOverlay.visibility == View.VISIBLE) {
            splashOverlay.animate()
                .alpha(0f)
                .setDuration(350)
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
        swipeRefreshLayout.isRefreshing = false
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            errorOverlay.visibility == View.VISIBLE -> {
                errorOverlay.visibility = View.GONE
                webView.reload()
            }
            webView.canGoBack() -> {
                webView.goBack()
            }
            currentActiveChip != chipHome -> {
                setActiveChip(chipHome)
                webView.loadUrl(URL_HOME)
            }
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

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
