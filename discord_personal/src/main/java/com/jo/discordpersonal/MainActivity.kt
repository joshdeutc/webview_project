package com.jo.discordpersonal

import android.Manifest
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
import android.widget.FrameLayout
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Discord Personal — variante mono-site de NoTube Player.
 *
 * Différences avec NoTube Player :
 *   - un seul site (Discord), donc pas de menu de navigation ni d'allowlist multi-domaines
 *   - images et vidéos coupées en permanence, pas seulement sur une page donnée
 *   - quotas propres : 5 sessions de 3 minutes par jour, 3 h de cooldown entre chacune
 *   - l'app ne se déclare pas navigateur générique (cf. AndroidManifest)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val DISCORD_URL = "https://discord.com/app"
        private const val NOTIFICATION_PERMISSION_CODE = 1001

        // Logging tags — filter with: adb logcat -s DP_NAV,DP_BLOCK,DP_INTENT,DP_SESSION,DP_AD
        private const val TAG_NAV     = "DP_NAV"
        private const val TAG_BLOCK   = "DP_BLOCK"
        private const val TAG_INTENT  = "DP_INTENT"
        private const val TAG_SESSION = "DP_SESSION"
        private const val TAG_AD      = "DP_AD"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val PREFS_NAME = "DiscordPersonalPrefs"
        private const val PREF_LAST_DATE = "last_date"
        private const val PREF_SESSION_START_MS = "session_start_ms"
        private const val PREF_SESSIONS_USED = "sessions_used"

        private const val SESSION_LIMIT_MS = 3 * 60 * 1000L          // 3 minutes
        private const val WAIT_TIME_MS = 3 * 60 * 60 * 1000L         // 3 heures
        private const val DAILY_SESSIONS_LIMIT = 5                   // 5 x 3 min = 15 min / jour
        private val ALLOWED_DOMAINS = listOf(
            "discord.com",
            "discord.gg",
            "discordapp.com",
            "discordapp.net",
            "cloudflare.com",
            "cloudflareinsights.com",
            "google.com",
            "gstatic.com",
            "googleapis.com",
            "hcaptcha.com",
            "recaptcha.net",
            "arkoselabs.com",
            "funcaptcha.com"
        )

        private val EMPTY_1X1_PNG = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0A.toByte(), 0x49.toByte(), 0x44.toByte(), 0x54.toByte(),
            0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x05.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x2D.toByte(), 0xB4.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(), 0x42.toByte(),
            0x60.toByte(), 0x82.toByte()
        )
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

    private var progressAnimator: android.animation.ValueAnimator? = null
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private var isTimerRunning = false
    private var currentMainUrl: String = ""

    private var isSelectingFile = false
    private var isRequestingPermission = false

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
            val sessionStart = prefs.getLong(PREF_SESSION_START_MS, 0L)
            val sessionsUsed = prefs.getInt(PREF_SESSIONS_USED, 0)
            val sessionEnd = sessionStart + SESSION_LIMIT_MS

            if (blockedOverlay.visibility == View.VISIBLE) {
                val cooldownEnd = sessionEnd + WAIT_TIME_MS
                if (sessionsUsed >= DAILY_SESSIONS_LIMIT) {
                    blockedMessage.text = getString(R.string.discord_daily_limit_reached)
                } else if (currentTime < cooldownEnd) {
                    val remaining = cooldownEnd - currentTime
                    val hours = (remaining / 1000) / 3600
                    val minutes = ((remaining / 1000) % 3600) / 60
                    val seconds = (remaining / 1000) % 60
                    val timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    blockedMessage.text = getString(R.string.discord_session_limit_reached, timeStr)
                } else {
                    blockedMessage.text = getString(R.string.discord_ready)
                }
            } else if (sessionStart == 0L) {
                // Aucune session ouverte (ex. : lancement sans réseau). Sans ce cas,
                // sessionEnd tomberait en 1970 et déclencherait un blocage fantôme.
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

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isSelectingFile = false
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
        isRequestingPermission = false
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

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        checkAndResetDailyTimer()

        setupEdgeToEdge()
        bindViews()

        setupWebView()
        requestNotificationPermission()
        registerDownloadReceiver()

        if (!isNetworkAvailable()) {
            showError()
            return
        }
        if (!handleViewIntent(intent)) {
            loadDiscord()
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
            loadDiscord()
        }
    }

    /**
     * Le manifeste ne route ici que des liens Discord, mais on repasse quand même
     * par l'allowlist : un lien peut rediriger ailleurs une fois ouvert.
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
        android.util.Log.i(TAG_INTENT, "RECEIVED intent url=$url")

        if (!isUrlAllowed(url)) {
            android.util.Log.w(TAG_INTENT, "BLOCKED external intent (hors Discord): $url")
            loadDiscord()
            return true
        }

        // Toute ouverture passe par la logique de session.
        loadDiscord(url)
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
        timerIndicator = findViewById(R.id.timerIndicator)
        blockedMessage = findViewById(R.id.blockedMessage)
        blockedIcon = findViewById(R.id.blockedIcon)

        // Session terminée : il n'y a aucun autre site vers lequel se replier,
        // le seul geste possible est de fermer l'app.
        findViewById<View>(R.id.btnCloseApp).setOnClickListener {
            finish()
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

    private fun loadDiscord(url: String = DISCORD_URL) {
        val accessState = checkAccess()
        android.util.Log.i(TAG_SESSION, "loadDiscord state=$accessState url=$url")
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
            mediaPlaybackRequiresUserGesture = false

            // App mono-site : les médias sont filtrés dynamiquement dans shouldInterceptRequest
            // pour permettre aux images de vérification Captcha de s'afficher lors des connexions.
            blockNetworkImage = false

            userAgentString = DESKTOP_USER_AGENT
        }

        webView.webViewClient = DiscordWebViewClient()
        webView.webChromeClient = DiscordChromeClient()

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun isCaptchaDomain(host: String): Boolean {
        return host.contains("hcaptcha") ||
               host.contains("recaptcha") ||
               host.contains("turnstile") ||
               host.contains("arkoselabs") ||
               host.contains("funcaptcha") ||
               host.contains("geetest")
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

    private fun isUrlAllowed(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme == "about" || scheme == "data" || scheme == "javascript" || scheme == "blob") {
                return true
            }
            val host = uri.host?.lowercase() ?: return false
            if (ALLOWED_DOMAINS.any { domain ->
                host == domain || host.endsWith(".$domain")
            }) {
                return true
            }
            isCaptchaDomain(host)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Vrai uniquement pour une vraie ressource média (photo/vidéo).
     * On teste l'extension du *chemin* et non l'URL entière, pour ne pas couper
     * des scripts dont l'URL contiendrait ces suites de caractères.
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
     * Vrai pour une vraie page Discord — par opposition à `about:blank`, que
     * l'app charge elle-même quand elle bloque une session.
     */
    private fun isDiscordUrl(url: String): Boolean {
        return url.contains("discord.com") || url.contains("discord.gg") || url.contains("discordapp")
    }

    /** Hôtes qui ne servent que des médias : coupables en entier sans risque. */
    private fun isMediaOnlyHost(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase() ?: return false
        } catch (e: Exception) {
            return false
        }
        return url.contains("cdn.discordapp.com/attachments") || host == "media.discordapp.net"
    }

    private fun isAudioRequest(request: WebResourceRequest): Boolean {
        val accept = request.requestHeaders?.get("Accept")?.lowercase() ?: ""
        val secFetchDest = request.requestHeaders?.get("Sec-Fetch-Dest")?.lowercase() ?: ""
        if (secFetchDest == "audio" || accept.contains("audio/")) {
            return true
        }
        val url = request.url.toString().lowercase()
        val path = try { request.url.path?.lowercase() ?: "" } catch (_: Exception) { "" }
        val audioExtensions = listOf(".m4a", ".aac", ".mp3", ".ogg", ".opus", ".wav")
        return audioExtensions.any { path.endsWith(it) || url.contains(it) } || url.contains("audioclip")
    }

    private inner class DiscordWebViewClient : WebViewClient() {

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

            // Exception essentielle : Toujours laisser passer les requêtes de Captcha (hCaptcha, Turnstile, reCAPTCHA...)
            if (isCaptchaRequest(url, request)) {
                return super.shouldInterceptRequest(view, request)
            }

            // Images et vidéos coupées en permanence. Remplacées par un PNG transparent 1x1.
            // On autorise explicitement les flux audio (messages vocaux / vocaux Discord).
            if (!isAudioRequest(request) && (isMediaOnlyHost(url) || isMediaResource(url))) {
                android.util.Log.d(TAG_AD, "BLOCKED media: $url")
                return WebResourceResponse("image/png", "UTF-8", ByteArrayInputStream(EMPTY_1X1_PNG))
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
            injectDesktopViewport(view)

            when (checkAccess()) {
                AccessState.ALLOWED, AccessState.CAN_START_NEW_SESSION -> {
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
            injectDesktopViewport(view)
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

    private inner class DiscordChromeClient : WebChromeClient() {
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
                isSelectingFile = true
                fileChooserLauncher.launch(intent)
                true
            } catch (e: Exception) {
                isSelectingFile = false
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
                        isRequestingPermission = true
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

    private fun showBlockedOverlay(message: String, icon: String = "⏳") {
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

    // ─── Downloads ───────────────────────────────────────────────────────

    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

            if (mimeType.startsWith("video/") || fileName.endsWith(".mp4") ||
                fileName.endsWith(".webm") || fileName.endsWith(".mkv")
            ) {
                Toast.makeText(this, R.string.video_download_blocked, Toast.LENGTH_LONG).show()
                return
            }

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription(getString(R.string.loading))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
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

    private fun injectDesktopViewport(view: WebView) {
        val js = """
            (function() {
                function applyDesktopLayout() {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        document.head.appendChild(meta);
                    }
                    var desiredContent = 'width=1280, initial-scale=0.32, minimum-scale=0.2, maximum-scale=3.0, user-scalable=yes';
                    if (meta.getAttribute('content') !== desiredContent) {
                        meta.setAttribute('content', desiredContent);
                    }

                    var style = document.getElementById('agy-desktop-style');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'agy-desktop-style';
                        style.textContent = 'html, body, #app-mount { min-width: 1280px !important; width: 1280px !important; overflow-x: auto !important; }';
                        document.head.appendChild(style);
                    }
                }
                applyDesktopLayout();
                if (!window.__agyViewportObserver && document.head) {
                    window.__agyViewportObserver = new MutationObserver(function() {
                        applyDesktopLayout();
                    });
                    window.__agyViewportObserver.observe(document.head, { childList: true, attributes: true, subtree: true });
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
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
        val sessionsLeft = DAILY_SESSIONS_LIMIT - sessionsUsed
        timerIndicator.text = getString(R.string.time_remaining, timeStr, sessionsLeft)
    }

    private fun blockSessionDueToTime() {
        // Idempotent : une fois l'overlay affiché, ne pas recharger about:blank.
        // Le timerRunnable se charge seul de rafraîchir le compte à rebours.
        if (blockedOverlay.visibility == View.VISIBLE) {
            if (!isTimerRunning) startTimer()
            return
        }

        webView.loadUrl("about:blank")
        if (!isTimerRunning) startTimer()
        timerIndicator.visibility = View.GONE

        val sessionsUsed = prefs.getInt(PREF_SESSIONS_USED, 0)
        if (sessionsUsed >= DAILY_SESSIONS_LIMIT) {
            showBlockedOverlay(getString(R.string.discord_daily_limit_reached))
        } else {
            showBlockedOverlay(getString(R.string.loading))
        }
    }

    // ─── Back Navigation ─────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            // Session terminée : aucun repli possible, on quitte.
            blockedOverlay.visibility == View.VISIBLE -> finish()
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

        // Si l'application passe temporairement en arrière-plan car l'utilisateur
        // a ouvert le sélecteur de fichier / photo ou la boîte de dialogue système,
        // on ne doit SURTOUT PAS forcer la fin de session !
        if (isSelectingFile || isRequestingPermission) {
            android.util.Log.i(TAG_SESSION, "onPause ignoré (sélecteur externe actif)")
            return
        }

        // Quitter l'app pendant une session la termine immédiatement et
        // déclenche le cooldown — sinon il suffirait de faire des allers-retours.
        if (checkAccess() == AccessState.ALLOWED) {
            val forcedExpiration = System.currentTimeMillis() - SESSION_LIMIT_MS
            prefs.edit().putLong(PREF_SESSION_START_MS, forcedExpiration).apply()
            android.util.Log.i(TAG_SESSION, "SESSION_FORCED_END (app mise en arrière-plan)")
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
