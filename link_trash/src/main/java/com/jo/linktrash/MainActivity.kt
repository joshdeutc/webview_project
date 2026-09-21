package com.jo.linktrash

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private val BLOCKED_HOSTS = setOf(
            // Plateformes de créateurs & financement
            "patreon.com",
            "throne.com",
            "throne.me",
            "fansly.com",
            "onlyfans.com",
            "loyalfans.com",
            "subscribestar.com",
            "subscribestar.adult",
            "ko-fi.com",
            "buymeacoffee.com",
            "fanvue.com",
            "manyvids.com",
            "clips4sale.com",
            "iwantclips.com",
            "mym.fans",
            "candr.link",
            "allmylinks.com",

            // Réseaux sociaux & plateformes de flux/vidéo
            "x.com",
            "twitter.com",
            "t.co",
            "tumblr.com",
            "bsky.app",
            "bsky.social",
            "reddit.com",
            "redd.it",
            "tiktok.com",
            "instagram.com",
            "threads.net",
            "facebook.com",
            "fb.com",
            "pinterest.com",
            "youtube.com",
            "youtu.be",
            "vimeo.com",
            "dailymotion.com",
            "twitch.tv",
            "kick.com",
            "4chan.org",

            // Sites adultes / hypnose
            "hypnoporn.net",
            "hypnohub.net",
            "hypno.tube",
            "hypno-fetish.com",
            "hypnotube.com",
            "pornhub.com",
            "xvideos.com",
            "xnxx.com",
            "redtube.com",
            "youporn.com",
            "spankbang.com",
            "xhamster.com",
            "erome.com",
            "chaturbate.com",
            "camsoda.com",
            "stripchat.com",
            "rule34.xxx",
            "e621.net",
            "literotica.com",
            "archiveofourown.org",
            "ao3.org",

            // Moteurs de recherche généraux
            "duckduckgo.com",
            "ecosia.org",
            "qwant.com"
        )

        private val BLOCKED_KEYWORDS = listOf(
            "hypno",
            "hypnosis",
            "hypnotic",
            "trance",
            "mindbreak",
            "mindcontrol",
            "brainwash",
            "bimbo",
            "bimbofication",
            "fetish",
            "kink",
            "erotic",
            "erotica",
            "hentai",
            "camgirl",
            "nsfw",
            "onlyfans",
            "fansly",
            "patreon",
            "throne",
            "subscribestar",
            "loyalfans"
        )

        // 1x1 transparent PNG bytes pour remplacer les images bloquées sans casser la page
        private val EMPTY_1X1_PNG = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0A.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
            0x54.toByte(), 0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x00.toByte(), 0x01.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x2D.toByte(), 0xB4.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(),
            0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var txtHost: TextView
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var progressContainer: FrameLayout
    private lateinit var progressBar: View
    private lateinit var webView: WebView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorOverlay: FrameLayout
    private lateinit var blockedOverlay: FrameLayout
    private lateinit var txtBlockedTitle: TextView
    private lateinit var txtBlockedDesc: TextView
    private lateinit var btnBlockedClose: View

    private var currentUrl: String = ""
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val intentData = result.data
            val uris = when {
                intentData?.clipData != null -> {
                    val count = intentData.clipData!!.itemCount
                    Array(count) { i -> intentData.clipData!!.getItemAt(i).uri }
                }
                intentData?.data != null -> arrayOf(intentData.data!!)
                else -> null
            }
            filePathCallback?.onReceiveValue(uris)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private var pendingPermissionRequest: PermissionRequest? = null
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingPermissionRequest?.grant(pendingPermissionRequest?.resources)
        } else {
            pendingPermissionRequest?.deny()
        }
        pendingPermissionRequest = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        initViews()
        setupInsets()
        setupWebView()
        setupActions()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun initViews() {
        rootLayout = findViewById(R.id.rootLayout)
        topBar = findViewById(R.id.topBar)
        btnBack = findViewById(R.id.btnBack)
        txtHost = findViewById(R.id.txtHost)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnClose = findViewById(R.id.btnClose)
        progressContainer = findViewById(R.id.progressContainer)
        progressBar = findViewById(R.id.progressBar)
        webView = findViewById(R.id.webView)
        emptyState = findViewById(R.id.emptyState)
        errorOverlay = findViewById(R.id.errorOverlay)
        blockedOverlay = findViewById(R.id.blockedOverlay)
        txtBlockedTitle = findViewById(R.id.txtBlockedTitle)
        txtBlockedDesc = findViewById(R.id.txtBlockedDesc)
        btnBlockedClose = findViewById(R.id.btnBlockedClose)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(
                topBar.paddingLeft,
                systemBars.top,
                topBar.paddingRight,
                topBar.paddingBottom
            )
            topBar.layoutParams.height = resources.getDimensionPixelSize(android.R.dimen.app_icon_size) + systemBars.top
            rootLayout.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme?.lowercase() ?: ""

                if (scheme != "http" && scheme != "https") {
                    return try {
                        val parsedIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        startActivity(parsedIntent)
                        true
                    } catch (_: Exception) {
                        true
                    }
                }

                val (isBlocked, reason) = isUrlBlocked(url)
                if (isBlocked) {
                    showBlockedOverlay(reason)
                    return true
                }

                if (request.isForMainFrame) {
                    currentUrl = url
                    txtHost.text = request.url.host ?: url
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()

                // 1. Sous-requête vers un domaine ou mot-clé interdit
                val (isBlocked, _) = isUrlBlocked(url)
                if (isBlocked) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }

                // 2. Exception Captcha : Laisser passer les captchas et contrôles de sécurité
                if (isCaptchaRequest(url, request)) {
                    return super.shouldInterceptRequest(view, request)
                }

                // 3. Bloquer toutes les images (remplacer par un PNG transparent 1x1 sans casser la mise en page)
                if (isImageResource(url, request)) {
                    return WebResourceResponse("image/png", "UTF-8", ByteArrayInputStream(EMPTY_1X1_PNG))
                }

                // 4. Bloquer les médias audio et vidéo
                if (isMediaResource(url, request)) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val (isBlocked, reason) = isUrlBlocked(url)
                if (isBlocked) {
                    view.stopLoading()
                    showBlockedOverlay(reason)
                    return
                }

                hideBlockedOverlay()
                currentUrl = url
                try {
                    val host = Uri.parse(url).host
                    if (!host.isNullOrEmpty()) {
                        txtHost.text = host
                    }
                } catch (_: Exception) {}

                showProgress()
                errorOverlay.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                hideProgress()

                // Neutralisation dynamique complète des balises vidéo et audio côté DOM
                view.evaluateJavascript(
                    """
                    (function() {
                        try {
                            var style = document.createElement('style');
                            style.textContent = 'video, audio, object[type*="video"], embed[type*="video"] { display: none !important; visibility: hidden !important; width: 0 !important; height: 0 !important; pointer-events: none !important; }';
                            (document.head || document.documentElement).appendChild(style);
                            function neutralize() {
                                document.querySelectorAll('video, audio').forEach(function(v) {
                                    try {
                                        v.pause();
                                        v.removeAttribute('src');
                                        v.src = '';
                                        v.load();
                                    } catch(e) {}
                                });
                            }
                            neutralize();
                            var observer = new MutationObserver(neutralize);
                            observer.observe(document.documentElement, { childList: true, subtree: true });
                        } catch(e) {}
                    })();
                    """.trimIndent(),
                    null
                )
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    hideProgress()
                    if (!isNetworkAvailable()) {
                        errorOverlay.visibility = View.VISIBLE
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                updateProgress(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrEmpty() && txtHost.text.isNullOrEmpty()) {
                    txtHost.text = title
                }
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams.createIntent()
                try {
                    filePickerLauncher.launch(intent)
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val audioRequested = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (audioRequested) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        pendingPermissionRequest = request
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    request.grant(request.resources)
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    addRequestHeader("cookie", cookies)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("Téléchargement...")
                    val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    setTitle(filename)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Téléchargement lancé...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Erreur téléchargement: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupActions() {
        btnBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }

        btnRefresh.setOnClickListener {
            if (currentUrl.isNotEmpty()) {
                webView.reload()
            }
        }

        btnClose.setOnClickListener {
            finish()
        }

        btnBlockedClose.setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.btnRetry).setOnClickListener {
            if (currentUrl.isNotEmpty()) {
                errorOverlay.visibility = View.GONE
                webView.loadUrl(currentUrl)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https")) {
            val targetUrl = uri.toString()
            val (isBlocked, reason) = isUrlBlocked(targetUrl)
            if (isBlocked) {
                currentUrl = targetUrl
                txtHost.text = uri.host ?: targetUrl
                showBlockedOverlay(reason)
                return
            }

            hideBlockedOverlay()
            currentUrl = targetUrl
            emptyState.visibility = View.GONE
            webView.visibility = View.VISIBLE
            txtHost.text = uri.host ?: targetUrl
            webView.loadUrl(targetUrl)
        } else {
            if (currentUrl.isEmpty()) {
                hideBlockedOverlay()
                emptyState.visibility = View.VISIBLE
                webView.visibility = View.GONE
                txtHost.text = getString(R.string.app_name)
            }
        }
    }

    private fun isUrlBlocked(url: String): Pair<Boolean, String> {
        val uri = try { Uri.parse(url) } catch (_: Exception) { return Pair(false, "") }
        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme != "http" && scheme != "https") {
            return Pair(false, "")
        }
        val host = uri.host?.lowercase() ?: ""
        val path = uri.path?.lowercase() ?: ""
        val query = uri.query?.lowercase() ?: ""

        // Exception vitale : comptes Google et connexion
        if (host == "accounts.google.com" || host == "myaccount.google.com") {
            return Pair(false, "")
        }

        // 1. Moteurs de recherche (requêtes de recherche)
        if ((host.endsWith("google.com") || host.endsWith("google.fr")) && path.startsWith("/search")) {
            return Pair(true, "Moteur de recherche non autorisé")
        }
        if (host.endsWith("bing.com") && path.startsWith("/search")) {
            return Pair(true, "Moteur de recherche non autorisé")
        }
        if (host.endsWith("yahoo.com") && path.startsWith("/search")) {
            return Pair(true, "Moteur de recherche non autorisé")
        }

        // 2. Vérification des domaines bloqués
        for (blocked in BLOCKED_HOSTS) {
            if (host == blocked || host.endsWith(".$blocked")) {
                return Pair(true, "Site bloqué : $blocked")
            }
        }

        // 3. Vérification des mots-clés dans le chemin ou la requête
        val pathAndQuery = "$path?$query"
        for (kw in BLOCKED_KEYWORDS) {
            if (pathAndQuery.contains(kw)) {
                return Pair(true, "Contenu bloqué (mot-clé '$kw')")
            }
        }

        return Pair(false, "")
    }

    private fun isCaptchaRequest(url: String, request: WebResourceRequest): Boolean {
        val lowerUrl = url.lowercase()
        val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        val path = try { Uri.parse(url).path?.lowercase() ?: "" } catch (_: Exception) { "" }

        // 1. Domaines dédiés aux captchas et défis de sécurité
        if (host.contains("recaptcha") ||
            host.contains("hcaptcha") ||
            host.contains("arkoselabs") ||
            host.contains("funcaptcha") ||
            host.contains("geetest") ||
            host.contains("turnstile") ||
            (host.endsWith("cloudflare.com") && (path.contains("turnstile") || path.contains("challenge"))) ||
            ((host.endsWith("google.com") || host.endsWith("gstatic.com")) && path.contains("recaptcha"))
        ) {
            return true
        }

        // 2. Mots-clés Captcha dans le chemin ou l'URL
        if (path.contains("captcha") ||
            path.contains("recaptcha") ||
            path.contains("hcaptcha") ||
            path.contains("turnstile") ||
            path.contains("challenge-platform") ||
            lowerUrl.contains("captcha") ||
            lowerUrl.contains("recaptcha")
        ) {
            return true
        }

        // 3. Header Referer
        val referer = request.requestHeaders?.get("Referer")
            ?: request.requestHeaders?.get("referer")
            ?: ""
        val lowerReferer = referer.lowercase()
        if (lowerReferer.contains("recaptcha") ||
            lowerReferer.contains("hcaptcha") ||
            lowerReferer.contains("turnstile") ||
            lowerReferer.contains("arkoselabs") ||
            lowerReferer.contains("captcha")
        ) {
            return true
        }

        return false
    }

    private fun isImageResource(url: String, request: WebResourceRequest): Boolean {
        val headers = request.requestHeaders ?: emptyMap()
        val secFetchDest = (headers["Sec-Fetch-Dest"] ?: headers["sec-fetch-dest"] ?: "").lowercase()
        if (secFetchDest == "image") return true

        val path = try { Uri.parse(url).path?.lowercase() ?: "" } catch (_: Exception) { "" }
        val imageExtensions = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".avif", ".tif", ".tiff"
        )
        return imageExtensions.any { path.endsWith(it) }
    }

    private fun isMediaResource(url: String, request: WebResourceRequest): Boolean {
        if (isImageResource(url, request)) return true

        val headers = request.requestHeaders ?: emptyMap()
        val secFetchDest = (headers["Sec-Fetch-Dest"] ?: headers["sec-fetch-dest"] ?: "").lowercase()
        if (secFetchDest == "video" || secFetchDest == "audio") return true

        val accept = (headers["Accept"] ?: headers["accept"] ?: "").lowercase()
        if (accept.contains("video/") || accept.contains("audio/")) return true

        val path = try { Uri.parse(url).path?.lowercase() ?: "" } catch (_: Exception) { "" }
        val lowerUrl = url.lowercase()
        val mediaExtensions = listOf(
            ".mp4", ".webm", ".mov", ".m4v", ".m3u8", ".ts", ".flv", ".avi", ".mkv", ".ogv",
            ".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac", ".opus"
        )
        return mediaExtensions.any { path.endsWith(it) || lowerUrl.contains(it) }
    }

    private fun showBlockedOverlay(reason: String) {
        txtBlockedDesc.text = reason.ifEmpty { getString(R.string.blocked_site_desc) }
        blockedOverlay.visibility = View.VISIBLE
        webView.visibility = View.GONE
        emptyState.visibility = View.GONE
        hideProgress()
    }

    private fun hideBlockedOverlay() {
        blockedOverlay.visibility = View.GONE
    }

    private fun showProgress() {
        progressContainer.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        progressContainer.visibility = View.GONE
    }

    private fun updateProgress(progress: Int) {
        if (progress >= 100) {
            hideProgress()
            return
        }
        showProgress()
        val totalWidth = progressContainer.width
        val params = progressBar.layoutParams
        params.width = (totalWidth * (progress / 100f)).toInt()
        progressBar.layoutParams = params
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            blockedOverlay.visibility == View.VISIBLE -> {
                finish()
            }
            errorOverlay.visibility == View.VISIBLE -> {
                errorOverlay.visibility = View.GONE
                if (currentUrl.isNotEmpty()) {
                    webView.loadUrl(currentUrl)
                } else {
                    emptyState.visibility = View.VISIBLE
                    webView.visibility = View.GONE
                }
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
