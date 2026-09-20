package com.jo.notubeplayer

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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.app.Activity
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val NOTUBE_URL = "https://notube.net/"
        private const val BERSERK_URL = "https://readberserk.com/"
        private const val DOCTOLIB_URL = "https://www.doctolib.fr/"
        private const val SNCF_URL = "https://www.sncf-connect.com/"
        private const val MARMITON_URL = "https://www.marmiton.org/"
        private const val INSTAGRAM_URL = "https://www.instagram.com/"

        // Le fil des comptes suivis, chronologique, au lieu de l'accueil algorithmique.
        // Imposé par l'URL d'entrée plutôt qu'en JS : une redirection répétée en boucle
        // risquerait de se battre avec le routeur d'Instagram et de figer la page.
        private const val INSTAGRAM_FEED_URL = "https://www.instagram.com/?variant=following"
        private const val ANTIGRAVITY_URL = "https://antigravity.google/"
        private const val NOTIFICATION_PERMISSION_CODE = 1001

        // Logging tags — filter with: adb logcat -s NTP_NAV,NTP_BLOCK,NTP_INTENT,NTP_AD
        private const val TAG_NAV     = "NTP_NAV"
        private const val TAG_BLOCK   = "NTP_BLOCK"
        private const val TAG_INTENT  = "NTP_INTENT"
        private const val TAG_AD      = "NTP_AD"

        // Discord vit désormais dans son app dédiée (com.jo.discordpersonal), avec ses
        // propres quotas. Plus rien ici ne le concerne : ni domaine, ni bouton, ni timer.
        private val ALLOWED_DOMAINS = listOf(
            "notube.net",
            "notube.io",
            "readberserk.com",
            "doctolib.fr",
            "appconsent.io",
            "didomi.io",
            "consensu.org",
            "cloudflare.com",
            "google.com",
            "sncf-connect.com",
            "sncf.com",
            "marmiton.org",
            // Instagram + ses CDN/hôtes de connexion (les médias sont filtrés plus bas,
            // mais les domaines doivent rester joignables pour le login et le HTML.)
            "instagram.com",
            "cdninstagram.com",
            "fbcdn.net",
            "facebook.com",
            // Antigravity Remote Control + services d'authentification Google
            "antigravity.google",
            "gstatic.com",
            "googleapis.com",
            "googleusercontent.com"
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
    private lateinit var blockedMessage: TextView
    private lateinit var blockedIcon: TextView
    private lateinit var bottomMenuScroll: HorizontalScrollView

    // Bottom Buttons
    private lateinit var btnNavNotube: Button
    private lateinit var btnNavBerserk: Button
    private lateinit var btnNavDoctolib: Button
    private lateinit var btnNavSncf: Button
    private lateinit var btnNavMarmiton: Button
    private lateinit var btnNavInstagram: Button
    private lateinit var btnNavAntigravity: Button

    private var webViewBasePaddingBottom: Int = 0
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
        if (result.resultCode == Activity.RESULT_OK) {
            val intentData = result.data
            val uris: Array<Uri>? = when {
                intentData?.clipData != null -> {
                    val clip = intentData.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
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
     *   - allowed → load it
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

        webView.loadUrl(url)
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
        blockedMessage = findViewById(R.id.blockedMessage)
        blockedIcon = findViewById(R.id.blockedIcon)
        bottomMenuScroll = findViewById(R.id.bottomMenuScroll)

        btnNavNotube = findViewById(R.id.btnNavNotube)
        btnNavBerserk = findViewById(R.id.btnNavBerserk)
        btnNavDoctolib = findViewById(R.id.btnNavDoctolib)
        btnNavSncf = findViewById(R.id.btnNavSncf)
        btnNavMarmiton = findViewById(R.id.btnNavMarmiton)
        btnNavInstagram = findViewById(R.id.btnNavInstagram)
        btnNavAntigravity = findViewById(R.id.btnNavAntigravity)

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
        btnNavBerserk.setOnClickListener { webView.loadUrl(BERSERK_URL) }
        btnNavDoctolib.setOnClickListener { webView.loadUrl(DOCTOLIB_URL) }
        btnNavSncf.setOnClickListener { webView.loadUrl(SNCF_URL) }
        btnNavMarmiton.setOnClickListener { webView.loadUrl(MARMITON_URL) }
        btnNavInstagram.setOnClickListener { webView.loadUrl(INSTAGRAM_FEED_URL) }
        btnNavAntigravity.setOnClickListener { webView.loadUrl(ANTIGRAVITY_URL) }
    }

    private fun loadNotube() {
        webView.loadUrl(NOTUBE_URL)
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

    /**
     * Instagram est le seul site de cette app dont on coupe images et vidéos.
     */
    private fun isCurrentUrlInstagram(urlToCheck: String = currentMainUrl): Boolean {
        return urlToCheck.contains("instagram.com") || urlToCheck.contains("cdninstagram.com")
    }

    /**
     * Vrai uniquement pour une vraie ressource média (photo/vidéo).
     *
     * On teste l'extension du *chemin* et non l'URL entière : Instagram sert ses
     * bundles JS/CSS depuis static.cdninstagram.com, et un `url.contains(".png")`
     * naïf finissait par tuer des scripts dont l'URL contenait ces caractères.
     * Sans son JS, Instagram se charge mais reste figé sur l'accueil.
     */
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

    /**
     * Hôtes qui ne servent QUE des médias — on peut les couper entièrement.
     * Attention : ni `cdninstagram.com` ni `fbcdn.net` en entier, ils servent
     * aussi le JS/CSS. Seuls les sous-domaines `scontent*` portent les photos.
     */
    private fun isMediaOnlyHost(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase() ?: return false
        } catch (e: Exception) {
            return false
        }
        return host.startsWith("scontent")
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

    private fun isAudioRequest(request: WebResourceRequest): Boolean {
        val headers = request.requestHeaders ?: emptyMap()
        val secFetchDest = (headers["Sec-Fetch-Dest"] ?: headers["sec-fetch-dest"] ?: "").lowercase()
        if (secFetchDest == "audio") return true

        val accept = (headers["Accept"] ?: headers["accept"] ?: "").lowercase()
        if (accept.contains("audio")) return true

        val url = request.url.toString().lowercase()
        return url.contains("audioclip") ||
               url.contains("audio_clip") ||
               url.contains("/audio") ||
               url.contains("audio") ||
               url.contains(".m4a") ||
               url.contains(".aac") ||
               url.contains(".mp3") ||
               url.contains(".wav") ||
               url.contains(".ogg") ||
               url.contains("voice_message") ||
               url.contains("voicenote")
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

            // Sur Instagram : on coupe images et vidéos pour ne garder que le texte.
            // On autorise impérativement les messages oraux / vocaux (audio).
            // Le JS et le CSS doivent passer, sinon Instagram (SPA React) reste figé.
            if (isCurrentUrlInstagram(currentMainUrl)) {
                if (isAudioRequest(request)) {
                    android.util.Log.d(TAG_NAV, "ALLOWED audio resource on Instagram: $url")
                    return super.shouldInterceptRequest(view, request)
                }
                if (isMediaOnlyHost(url) || isMediaResource(url)) {
                    android.util.Log.d(TAG_AD, "BLOCKED media (site sans images): $url")
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

            // Coupe le chargement des images côté WebView sur Instagram.
            webView.settings.blockNetworkImage = isCurrentUrlInstagram(url)
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
