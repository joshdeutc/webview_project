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
        private const val NOVETHIC_URL = "https://www.novethic.fr/"
        private const val COMUTEC_URL = "https://cvtheque.comutec.org/authenticated"
        private const val MESSENGER_URL = "https://www.messenger.com/"
        private const val GEMINI_URL = "https://gemini.google.com/app"
        private const val ANTIGRAVITY_URL = "https://accounts.google.com/AccountChooser?Email=joshuanancey%40gmail.com&continue=https%3A%2F%2Fantigravity.google.com%2Fr%2F8fdefe5a-83a4-439f-9225-a025f4dcdee9-v2"
        private const val NOTIFICATION_PERMISSION_CODE = 1001

        // User-Agent Desktop requis pour Messenger Web (avec un UA mobile, messenger.com
        // masque le formulaire de connexion et facebook.com/messages redirige vers l'app native).
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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
            "appconsent.io",
            "didomi.io",
            "consensu.org",
            "cloudflare.com",
            "google.com",
            // Novethic & polices / consent
            "novethic.fr",
            "axeptio.eu",
            "typekit.net",
            "use.typekit.net",
            // Comutec / CVthèque & UTC
            "comutec.org",
            "utc.fr",
            // Messenger / Facebook + ses CDN/hôtes de connexion et pièces jointes audio
            // (les images et vidéos sont filtrées plus bas, mais ces domaines doivent rester
            // joignables pour le login, le JS/CSS et les messages vocaux.)
            "messenger.com",
            "facebook.com",
            "fbcdn.net",
            "fbsbx.com",
            "meta.com",
            "fb.com",
            "m.me",
            // Antigravity Remote Control + services d'authentification Google
            "antigravity.google",
            "gstatic.com",
            "googleapis.com",
            "googleusercontent.com",
            // Gemini & Google AI (pour compagnon IA sans distraction)
            "gemini.google.com",
            "bard.google.com",
            "deepmind.google",
            "ai.google.dev",
            "generativeai.google"
        )

        private val CAPTCHA_DOMAINS = listOf(
            "hcaptcha.com",
            "recaptcha.net",
            "arkoselabs.com",
            "funcaptcha.com",
            "geetest.com"
        )

        // 1x1 transparent PNG pour remplacer les images bloquées sans casser la mise en page
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
    private lateinit var btnNavNovethic: Button
    private lateinit var btnNavBerserk: Button
    private lateinit var btnNavComutec: Button
    private lateinit var btnNavMessenger: Button
    private lateinit var btnNavGemini: Button
    private lateinit var btnNavAntigravity: Button

    private var webViewBasePaddingBottom: Int = 0
    private var progressAnimator: android.animation.ValueAnimator? = null

    private var defaultMobileUserAgent: String = ""
    private var currentMainUrl: String = ""
    private val chromeClient = NoTubeChromeClient()

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

        loadUrlWithSettings(url)
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
        btnNavNovethic = findViewById(R.id.btnNavNovethic)
        btnNavBerserk = findViewById(R.id.btnNavBerserk)
        btnNavComutec = findViewById(R.id.btnNavComutec)
        btnNavMessenger = findViewById(R.id.btnNavMessenger)
        btnNavGemini = findViewById(R.id.btnNavGemini)
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
        btnNavNovethic.setOnClickListener { loadUrlWithSettings(NOVETHIC_URL) }
        btnNavBerserk.setOnClickListener { loadUrlWithSettings(BERSERK_URL) }
        btnNavComutec.setOnClickListener { loadUrlWithSettings(COMUTEC_URL) }
        btnNavMessenger.setOnClickListener { loadUrlWithSettings(MESSENGER_URL) }
        btnNavGemini.setOnClickListener { loadUrlWithSettings(GEMINI_URL) }
        btnNavAntigravity.setOnClickListener { loadUrlWithSettings(ANTIGRAVITY_URL) }
    }

    private fun loadNotube() {
        loadUrlWithSettings(NOTUBE_URL)
    }

    private fun applySettingsForUrl(url: String) {
        val desiredUa = if (isCurrentUrlMessenger(url)) DESKTOP_USER_AGENT else defaultMobileUserAgent
        if (desiredUa.isNotEmpty() && webView.settings.userAgentString != desiredUa) {
            webView.settings.userAgentString = desiredUa
        }
    }

    private fun loadUrlWithSettings(url: String) {
        currentMainUrl = url
        applySettingsForUrl(url)
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
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
            // Les médias sur Messenger/Facebook sont filtrés dynamiquement dans shouldInterceptRequest
            // et côté DOM/Blob pour préserver les captchas de connexion et les icônes UI statiques.
            blockNetworkImage = false

            val currentAgent = userAgentString ?: ""
            defaultMobileUserAgent = currentAgent.replace("; wv", "")
            userAgentString = defaultMobileUserAgent
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = NoTubeWebViewClient()
        webView.webChromeClient = chromeClient

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun isUrlAllowed(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme == "about" || scheme == "data" || scheme == "javascript" || scheme == "blob") {
                return true
            }
            val host = uri.host?.lowercase() ?: return false
            // NoTube fait tourner ses domaines en permanence (notube.net/.io/.li/.land/.im/.app/...)
            // pour échapper aux blocages. On autorise donc tout TLD "notube.<xx>" (et ses
            // sous-domaines) pour que le flux de conversion/téléchargement ne casse pas à chaque
            // rotation. La frontière (^|.) évite d'autoriser "evilnotube.com" ou "notube.evil.com".
            if (NOTUBE_HOST_REGEX.containsMatchIn(host)) {
                return true
            }
            ALLOWED_DOMAINS.any { domain ->
                host == domain || host.endsWith(".$domain")
            } || isCaptchaDomain(host)
        } catch (e: Exception) {
            false
        }
    }

    private fun getHeaderIgnoreCase(request: WebResourceRequest, name: String): String {
        val headers = request.requestHeaders ?: return ""
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: ""
    }

    private fun isCaptchaDomain(host: String): Boolean {
        return CAPTCHA_DOMAINS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    private fun isCaptchaRequest(url: String, request: WebResourceRequest): Boolean {
        val lowerUrl = url.lowercase()
        val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        val path = try { Uri.parse(url).path?.lowercase() ?: "" } catch (_: Exception) { "" }

        if (isCaptchaDomain(host)) return true

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

        val lowerReferer = getHeaderIgnoreCase(request, "Referer").lowercase()
        return lowerReferer.contains("recaptcha") ||
            lowerReferer.contains("hcaptcha") ||
            lowerReferer.contains("turnstile") ||
            lowerReferer.contains("arkoselabs") ||
            lowerReferer.contains("captcha")
    }

    /**
     * Messenger / Facebook : mode texte + vocaux uniquement (images et vidéos bloquées,
     * UA Desktop actif pour permettre la connexion et le chat web).
     */
    private fun isCurrentUrlMessenger(urlToCheck: String = currentMainUrl): Boolean {
        val host = try {
            Uri.parse(urlToCheck).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }
        return host == "messenger.com" || host.endsWith(".messenger.com") ||
               host == "facebook.com" || host.endsWith(".facebook.com") ||
               host == "fb.com" || host.endsWith(".fb.com") ||
               host == "m.me" || host.endsWith(".m.me") ||
               host == "meta.com" || host.endsWith(".meta.com")
    }

    /**
     * Ressources statiques d'interface (JS, CSS, sprites d'icônes et emojis Unicode)
     * servies par `static.*.fbcdn.net/rsrc.php/` ou `/images/emoji.php/`.
     * Ne contiennent jamais de photos utilisateur ni de stickers.
     */
    private fun isMessengerStaticUiAsset(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            val path = uri.path?.lowercase() ?: return false
            host.startsWith("static.") && host.endsWith(".fbcdn.net") &&
                (path.startsWith("/rsrc.php/") || path.startsWith("/images/emoji.php/"))
        } catch (_: Exception) {
            false
        }
    }

    private fun isImageRequest(request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) return false
        val secFetchDest = getHeaderIgnoreCase(request, "Sec-Fetch-Dest").lowercase()
        if (secFetchDest == "image") return true
        if (secFetchDest == "document" || secFetchDest == "iframe" ||
            secFetchDest == "script" || secFetchDest == "style" ||
            secFetchDest == "font" || secFetchDest == "worker" ||
            secFetchDest == "sharedworker" || secFetchDest == "serviceworker"
        ) {
            return false
        }
        val accept = getHeaderIgnoreCase(request, "Accept").lowercase()
        if (accept.startsWith("text/html")) return false
        return accept.startsWith("image/")
    }

    /**
     * Vrai uniquement pour une vraie ressource média (photo/vidéo).
     *
     * On teste l'extension du *chemin* et non l'URL entière : Meta sert ses
     * bundles JS/CSS depuis static.xx.fbcdn.net, et un `url.contains(".png")`
     * naïf risquerait de casser des scripts dont l'URL contiendrait ces caractères.
     */
    private fun isMediaResource(url: String): Boolean {
        val path = try {
            Uri.parse(url).path?.lowercase() ?: ""
        } catch (e: Exception) {
            return false
        }
        val mediaExtensions = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".bmp",
            ".mp4", ".webm", ".mov", ".m4v"
        )
        return mediaExtensions.any { path.endsWith(it) }
    }

    /**
     * Hôtes dédiés aux aperçus/images (jamais de JS/CSS ni de messages vocaux E2EE).
     * Note : on ne coupe pas tout `scontent*` / `cdn.fbsbx.com` sur les requêtes `fetch`
     * chiffrées (E2EE) afin de laisser passer les vocaux chiffrés ; les photos/vidéos non
     * chiffrées sont coupées via `isImageRequest`/`isMediaResource`/`isVideoRequest`, et les
     * photos/vidéos E2EE déchiffrées sont bloquées côté Blob/DOM par `injectMessengerMediaBlocker`.
     */
    private fun isMediaOnlyHost(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase() ?: return false
        } catch (e: Exception) {
            return false
        }
        return host.startsWith("external") || host == "lookaside.fbsbx.com"
    }

    private fun isVideoOnlyHost(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase() ?: return false
        } catch (e: Exception) {
            return false
        }
        return host.startsWith("video")
    }

    private fun isAudioRequest(request: WebResourceRequest): Boolean {
        val secFetchDest = getHeaderIgnoreCase(request, "Sec-Fetch-Dest").lowercase()
        if (secFetchDest == "audio") return true

        val accept = getHeaderIgnoreCase(request, "Accept").lowercase()
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
               url.contains(".opus") ||
               url.contains("voice_message") ||
               url.contains("voicenote")
    }

    /**
     * Gemini : mode compagnon IA sans distraction (sans photo générée/web, sans vidéo).
     */
    private fun isCurrentUrlGemini(urlToCheck: String = currentMainUrl): Boolean {
        val lower = urlToCheck.lowercase()
        return lower.contains("gemini.google.com") || lower.contains("bard.google.com")
    }

    private fun isVideoRequest(request: WebResourceRequest): Boolean {
        val secFetchDest = getHeaderIgnoreCase(request, "Sec-Fetch-Dest").lowercase()
        if (secFetchDest == "video") return true

        val accept = getHeaderIgnoreCase(request, "Accept").lowercase()
        if (accept.contains("video/")) return true

        return false
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = try {
            Uri.parse(url).path?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }
        return lower.contains("googlevideo.com") ||
               lower.contains("youtube.com/embed") ||
               lower.contains("youtube-nocookie.com") ||
               path.endsWith(".mp4") ||
               path.endsWith(".webm") ||
               path.endsWith(".mov") ||
               path.endsWith(".m4v") ||
               path.endsWith(".m3u8") ||
               path.endsWith(".mpd")
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

            // Sur Messenger / Facebook : on coupe images et vidéos pour ne garder que le texte
            // et les messages vocaux (audio). Le JS, le CSS, les captchas et les icônes UI
            // statiques (rsrc.php / emoji.php) passent pour ne pas casser l'interface.
            if (!request.isForMainFrame && isCurrentUrlMessenger(currentMainUrl)) {
                if (isCaptchaRequest(url, request)) {
                    return super.shouldInterceptRequest(view, request)
                }
                if (isAudioRequest(request)) {
                    android.util.Log.d(TAG_NAV, "ALLOWED audio resource on Messenger: $url")
                    return super.shouldInterceptRequest(view, request)
                }
                if (isMessengerStaticUiAsset(url)) {
                    return super.shouldInterceptRequest(view, request)
                }
                if (isVideoRequest(request) || isVideoUrl(url) || isVideoOnlyHost(url)) {
                    android.util.Log.d(TAG_AD, "BLOCKED video on Messenger: $url")
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (isImageRequest(request) || isMediaOnlyHost(url) || isMediaResource(url)) {
                    android.util.Log.d(TAG_AD, "BLOCKED media on Messenger: $url")
                    return WebResourceResponse("image/png", "UTF-8", ByteArrayInputStream(EMPTY_1X1_PNG))
                }
            }

            // Sur Gemini : on coupe les flux vidéo (YouTube / HTML5 video) pour un compagnon IA sans vidéo
            if (isCurrentUrlGemini(currentMainUrl)) {
                if (isVideoRequest(request) || isVideoUrl(url)) {
                    android.util.Log.d(TAG_AD, "BLOCKED video resource on Gemini: $url")
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
                    val desiredUa = if (isCurrentUrlMessenger(url)) DESKTOP_USER_AGENT else defaultMobileUserAgent
                    val uaChanged = desiredUa.isNotEmpty() && view.settings.userAgentString != desiredUa
                    currentMainUrl = url
                    if (uaChanged && !request.isRedirect && request.method.equals("GET", ignoreCase = true)) {
                        view.settings.userAgentString = desiredUa
                        view.loadUrl(url)
                        return true
                    }
                    if (isCurrentUrlMessenger(url)) {
                        injectMessengerMediaBlocker(view)
                    } else if (isCurrentUrlGemini(url)) {
                        injectGeminiDistractionFree(view)
                    }
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
            applySettingsForUrl(url)
            if (isCurrentUrlMessenger(url)) {
                injectMessengerMediaBlocker(view)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            android.util.Log.i(TAG_NAV, "PAGE_DONE $url")
            hideProgress()
            hideSplash()
            injectDarkModeEnhancements(view)
            if (isCurrentUrlMessenger(url)) {
                injectMessengerMediaBlocker(view)
            } else if (isCurrentUrlGemini(url)) {
                injectGeminiDistractionFree(view)
            }
            CookieManager.getInstance().flush()
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

        fun isCustomViewShowing(): Boolean = customView != null
        fun hideCustomView() = onHideCustomView()

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            updateProgress(newProgress)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (isCurrentUrlMessenger(currentMainUrl) || isCurrentUrlGemini(currentMainUrl)) {
                callback.onCustomViewHidden()
                return
            }
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

            val intent = try {
                fileChooserParams?.createIntent()
            } catch (e: Exception) {
                null
            } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
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

                if (window.location.hostname.includes('novethic.fr')) {
                    setInterval(function() {
                        var cbAccept = document.getElementById('CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll');
                        if (cbAccept) { cbAccept.click(); }
                        var cbDialog = document.getElementById('CybotCookiebotDialog');
                        if (cbDialog) { cbDialog.remove(); }

                        var axeptio = document.getElementById('axeptio_overlay');
                        if (axeptio) { axeptio.remove(); }
                        var didomi = document.getElementById('didomi-host');
                        if (didomi) { didomi.remove(); }
                        var ot = document.getElementById('onetrust-consent-sdk');
                        if (ot) { ot.remove(); }
                        document.body.style.overflow = 'auto';
                        document.documentElement.style.overflow = 'auto';
                    }, 500);
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /**
     * Neutralise images et vidéos sur Messenger / Facebook (y compris médias chiffrés E2EE
     * chargés en `blob:` ou `data:image`, avatars SVG `<image>`, stickers et Reels) tout en
     * préservant les messages vocaux (`<audio>`), les captchas de connexion et les icônes UI
     * statiques (`rsrc.php` / `emoji.php`).
     */
    private fun injectMessengerMediaBlocker(view: WebView) {
        val js = """
            (function() {
                try {
                    var styleId = 'messenger-media-blocker-css';
                    if (!document.getElementById(styleId)) {
                        var style = document.createElement('style');
                        style.id = styleId;
                        style.textContent = `
                            video, [role="presentation"] video {
                                display: none !important;
                                visibility: hidden !important;
                                width: 0 !important;
                                height: 0 !important;
                                max-height: 0 !important;
                                pointer-events: none !important;
                            }
                            img:not([src*="captcha" i]):not([alt*="captcha" i]):not([class*="captcha" i]):not([id*="captcha" i]):not([src*="rsrc.php"]):not([src*="emoji.php"]),
                            picture,
                            svg image {
                                display: none !important;
                                visibility: hidden !important;
                                opacity: 0 !important;
                                width: 0 !important;
                                height: 0 !important;
                                pointer-events: none !important;
                            }
                        `;
                        (document.head || document.documentElement).appendChild(style);
                    }

                    if (!window.__messengerMediaBlockerInstalled) {
                        window.__messengerMediaBlockerInstalled = true;

                        var BLANK_IMG = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';

                        // Intercepter les Blobs image/* déchiffrés en E2EE avant création d'URL blob:
                        if (window.URL && typeof window.URL.createObjectURL === 'function') {
                            var origCreateObjectURL = window.URL.createObjectURL;
                            window.URL.createObjectURL = function(obj) {
                                try {
                                    if (obj && typeof obj.type === 'string' && obj.type.toLowerCase().indexOf('image/') === 0) {
                                        return BLANK_IMG;
                                    }
                                } catch (e) {}
                                return origCreateObjectURL.apply(this, arguments);
                            };
                        }

                        // Empêcher toute lecture de <video> sans jamais toucher à <audio> (messages vocaux)
                        if (window.HTMLVideoElement && HTMLVideoElement.prototype) {
                            HTMLVideoElement.prototype.play = function() {
                                try {
                                    this.pause();
                                    this.muted = true;
                                    this.removeAttribute('src');
                                    this.src = '';
                                    this.srcObject = null;
                                } catch (e) {}
                                return Promise.resolve();
                            };
                        }

                        function isExemptImage(el) {
                            var src = (el.getAttribute('src') || el.currentSrc || '').toLowerCase();
                            var alt = (el.getAttribute('alt') || '').toLowerCase();
                            var cls = (el.className || '').toString().toLowerCase();
                            var id = (el.id || '').toLowerCase();
                            return src.indexOf('captcha') !== -1 ||
                                   alt.indexOf('captcha') !== -1 ||
                                   cls.indexOf('captcha') !== -1 ||
                                   id.indexOf('captcha') !== -1 ||
                                   src.indexOf('rsrc.php') !== -1 ||
                                   src.indexOf('emoji.php') !== -1;
                        }

                        function neutralizeMedia() {
                            // 1. Neutraliser toutes les vidéos (sans .remove() pour ne pas casser React)
                            var vids = document.querySelectorAll('video');
                            for (var v = 0; v < vids.length; v++) {
                                var vid = vids[v];
                                try {
                                    if (!vid.paused) vid.pause();
                                    if (!vid.muted) vid.muted = true;
                                    if (vid.getAttribute('src')) {
                                        vid.removeAttribute('src');
                                        vid.src = '';
                                    }
                                    if (vid.srcObject) {
                                        vid.srcObject = null;
                                    }
                                    if (vid.style.display !== 'none') {
                                        vid.style.setProperty('display', 'none', 'important');
                                    }
                                } catch (e) {}
                            }

                            // 2. Neutraliser les <img> (y compris blob: / data:image E2EE)
                            var imgs = document.querySelectorAll('img');
                            for (var i = 0; i < imgs.length; i++) {
                                var img = imgs[i];
                                if (isExemptImage(img)) continue;
                                try {
                                    var cur = img.getAttribute('src') || '';
                                    if (img.hasAttribute('srcset')) {
                                        img.removeAttribute('srcset');
                                    }
                                    if (cur && cur !== BLANK_IMG) {
                                        img.setAttribute('src', BLANK_IMG);
                                    }
                                    if (img.style.display !== 'none') {
                                        img.style.setProperty('display', 'none', 'important');
                                    }
                                } catch (e) {}
                            }

                            // 3. Neutraliser les avatars/photos rendus via <svg><image xlink:href="..."/></svg>
                            var svgImgs = document.querySelectorAll('svg image');
                            for (var s = 0; s < svgImgs.length; s++) {
                                var simg = svgImgs[s];
                                try {
                                    if (simg.hasAttribute('href')) simg.removeAttribute('href');
                                    if (simg.hasAttribute('xlink:href')) simg.removeAttribute('xlink:href');
                                    if (simg.style.display !== 'none') {
                                        simg.style.setProperty('display', 'none', 'important');
                                    }
                                } catch (e) {}
                            }

                            // 4. Neutraliser les background-image hors icônes UI statiques (rsrc.php/emoji.php) et captchas
                            var bgEls = document.querySelectorAll('[style*="background-image"]');
                            for (var b = 0; b < bgEls.length; b++) {
                                var el = bgEls[b];
                                var bg = el.style.backgroundImage || '';
                                if (!bg || bg === 'none') continue;
                                var lowBg = bg.toLowerCase();
                                if (lowBg.indexOf('rsrc.php') !== -1 ||
                                    lowBg.indexOf('emoji.php') !== -1 ||
                                    lowBg.indexOf('captcha') !== -1) {
                                    continue;
                                }
                                el.style.setProperty('background-image', 'none', 'important');
                            }
                        }

                        var scheduled = false;
                        function scheduleNeutralize() {
                            if (scheduled) return;
                            scheduled = true;
                            setTimeout(function() {
                                scheduled = false;
                                neutralizeMedia();
                            }, 50);
                        }

                        neutralizeMedia();
                        var observer = new MutationObserver(scheduleNeutralize);
                        if (document.documentElement) {
                            observer.observe(document.documentElement, { childList: true, subtree: true });
                        }
                        setInterval(neutralizeMedia, 500);
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /**
     * Injecte le mode compagnon IA sans distraction sur Gemini :
     * - Supprime toutes les vidéos, lecteurs et iframes (YouTube, etc.).
     * - Supprime les images générées, photos web, carrousels et cartes dans les réponses.
     * - Préserve les miniatures dans la zone d'envoi et les requêtes envoyées par l'utilisateur (upload fonctionnel).
     */
    private fun injectGeminiDistractionFree(view: WebView) {
        val js = """
            (function() {
                var styleId = 'gemini-distraction-free-css';
                if (!document.getElementById(styleId)) {
                    var style = document.createElement('style');
                    style.id = styleId;
                    style.textContent = `
                        /* Supprimer toutes les vidéos, lecteurs et iframes (YouTube, etc.) */
                        video, iframe, model-viewer, youtube-video, .video-player, [data-test-id*="video"], [class*="video-player"] {
                            display: none !important;
                            visibility: hidden !important;
                            pointer-events: none !important;
                        }
                        /* Supprimer les images générées, photos web, carrousels et cartes dans les réponses de Gemini */
                        model-response img, model-response picture, message-content img, message-content picture,
                        .model-response-text img, .response-container img, .markdown img, .markdown picture,
                        .image-container, .image-wrapper, .generated-image, .media-carousel, .thumbnail-container,
                        .grounding-image, div[data-test-id*="image"], div[data-test-id*="carousel"],
                        div[class*="image-card"], div[class*="generated-image"], div[class*="media-container"] {
                            display: none !important;
                            visibility: hidden !important;
                            height: 0 !important;
                            min-height: 0 !important;
                            padding: 0 !important;
                            margin: 0 !important;
                        }
                        /* Préserver les miniatures dans la zone d'envoi et les requêtes envoyées par l'utilisateur */
                        rich-textarea img, .attachment-container img, .input-area img, .uploader img,
                        [class*="attachment"] img, [class*="preview"] img, [class*="upload"] img,
                        user-query img, [class*="user-query"] img, [class*="chip"] img,
                        button img, [role="button"] img {
                            display: inline-block !important;
                            visibility: visible !important;
                            max-height: 80px !important;
                            max-width: 80px !important;
                            object-fit: cover !important;
                            border-radius: 8px !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(style);
                }

                if (!window.__geminiSanitizerStarted) {
                    window.__geminiSanitizerStarted = true;
                    setInterval(function() {
                        // Stopper et supprimer tout élément vidéo
                        var vids = document.querySelectorAll('video');
                        for (var i = 0; i < vids.length; i++) {
                            try {
                                vids[i].pause();
                                vids[i].src = '';
                                vids[i].remove();
                            } catch(e) {}
                        }

                        // Supprimer les iframes vidéo ou YouTube
                        var ifrs = document.querySelectorAll('iframe');
                        for (var j = 0; j < ifrs.length; j++) {
                            var f = ifrs[j];
                            var s = (f.src || '').toLowerCase();
                            if (s.indexOf('youtube') !== -1 || s.indexOf('youtu.be') !== -1 || s.indexOf('googlevideo') !== -1) {
                                try {
                                    f.src = '';
                                    f.remove();
                                } catch(e) {}
                            }
                        }

                        // Cacher les images dans les réponses de Gemini et replier leurs conteneurs
                        var resps = document.querySelectorAll('model-response, message-content, .model-response-text, .response-container');
                        for (var k = 0; k < resps.length; k++) {
                            var imgs = resps[k].querySelectorAll('img, picture');
                            for (var m = 0; m < imgs.length; m++) {
                                imgs[m].style.setProperty('display', 'none', 'important');
                                var p = imgs[m].parentElement;
                                if (p && p !== resps[k] && (p.children.length === 1 || (p.className && (p.className.indexOf('image') !== -1 || p.className.indexOf('media') !== -1 || p.className.indexOf('card') !== -1)))) {
                                    p.style.setProperty('display', 'none', 'important');
                                }
                            }
                        }
                    }, 400);
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ─── Back Navigation ─────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            chromeClient.isCustomViewShowing() -> {
                chromeClient.hideCustomView()
            }
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
