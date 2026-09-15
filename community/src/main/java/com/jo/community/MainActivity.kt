package com.jo.community

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deux sites, deux traitements différents :
 *   - FetLife    → images et vidéos coupées
 *   - BDSMsutra  → contenu complet, les illustrations font partie du propos
 *
 * Le basculement se fait par page dans [onPageStarted], pas une fois pour toutes
 * au démarrage : c'est la page courante qui décide, exactement comme NoTube Player
 * le fait pour Instagram.
 *
 * Quotas identiques à Discord Personal : 5 sessions de 3 minutes par jour, 3 h
 * d'attente entre chacune. Le budget est commun aux deux sites — passer de FetLife
 * à BDSMsutra ne remet pas le compteur à zéro.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // ── LIEN #1 : les destinations ───────────────────────────────────
        private const val FETLIFE_URL = "https://fetlife.com/"
        private const val SUTRA_URL = "https://bdsmsutra.com/"

        // Logging tags — filter with: adb logcat -s CMY_NAV,CMY_BLOCK,CMY_INTENT,CMY_SESSION,CMY_AD
        private const val TAG_NAV     = "CMY_NAV"
        private const val TAG_BLOCK   = "CMY_BLOCK"
        private const val TAG_INTENT  = "CMY_INTENT"
        private const val TAG_SESSION = "CMY_SESSION"
        private const val TAG_AD      = "CMY_AD"

        private const val PREFS_NAME = "CommunityPrefs"
        private const val PREF_LAST_DATE = "last_date"
        private const val PREF_SESSION_START_MS = "session_start_ms"
        private const val PREF_SESSIONS_USED = "sessions_used"

        private const val SESSION_LIMIT_MS = 3 * 60 * 1000L      // 3 minutes
        private const val WAIT_TIME_MS = 3 * 60 * 60 * 1000L     // 3 heures
        private const val DAILY_SESSIONS_LIMIT = 5               // 5 x 3 min = 15 min / jour

        // ── LIEN #2 : l'allowlist ────────────────────────────────────────
        // flcdn.net est le CDN de FetLife. Il est autorisé ici et filtré plus bas
        // au niveau des ressources : le couper en entier tuerait aussi son JS/CSS
        // et figerait la page — l'erreur commise sur Instagram.
        private val ALLOWED_DOMAINS = listOf(
            "fetlife.com",
            "flcdn.net",
            "bdsmsutra.com",
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
    private lateinit var bottomMenu: LinearLayout

    private lateinit var btnNavFetlife: Button
    private lateinit var btnNavSutra: Button
    private lateinit var btnGoHome: Button

    private var progressAnimator: android.animation.ValueAnimator? = null
    private var currentMainUrl: String = ""

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var isTimerRunning = false

    /**
     * L'overlay de blocage sert à deux choses : quota épuisé, ou domaine hors
     * allowlist. Le bouton n'a pas le même sens dans les deux cas — sur quota il
     * n'y a nulle part où se replier, donc il ferme l'app.
     */
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

    /**
     * Toute navigation demandée par l'utilisateur passe par ici : les deux boutons
     * du menu comme les liens entrants. Le budget est commun aux deux sites, donc
     * basculer de FetLife à BDSMsutra consomme la même session.
     */
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
        // Idempotent : une fois l'overlay affiché, ne pas recharger about:blank.
        // Le timerRunnable rafraîchit seul le compte à rebours.
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
        bottomMenu = findViewById(R.id.bottomMenu)

        btnNavFetlife = findViewById(R.id.btnNavFetlife)
        btnNavSutra = findViewById(R.id.btnNavSutra)
        btnGoHome = findViewById(R.id.btnGoHome)

        fabRefresh.setOnClickListener { webView.reload() }

        // ── LIEN #3 : le câblage bouton → URL ────────────────────────────
        btnNavFetlife.setOnClickListener { loadSite(FETLIFE_URL) }
        btnNavSutra.setOnClickListener { loadSite(SUTRA_URL) }

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
            mediaPlaybackRequiresUserGesture = false

            val currentAgent = userAgentString ?: ""
            userAgentString = currentAgent.replace("; wv", "")
        }

        webView.webViewClient = CommunityWebViewClient()
        webView.webChromeClient = CommunityChromeClient()
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

    /** Seul FetLife est privé d'images ; BDSMsutra garde son contenu entier. */
    private fun isCurrentUrlFetlife(urlToCheck: String = currentMainUrl): Boolean {
        return urlToCheck.contains("fetlife.com") || urlToCheck.contains("flcdn.net")
    }

    /**
     * Vrai pour une vraie page de contenu — par opposition à `about:blank`, que
     * l'app charge elle-même quand elle bloque une session.
     *
     * Sans cette garde : blocage → about:blank → onPageStarted → blocage →
     * about:blank… boucle infinie et chargement qui tourne sans fin.
     */
    private fun isContentUrl(url: String): Boolean {
        return url.contains("fetlife.com") || url.contains("bdsmsutra.com")
    }

    /**
     * Vrai uniquement pour une vraie ressource média.
     *
     * On teste l'extension du *chemin*, jamais l'URL entière : un `contains(".png")`
     * naïf attrape aussi des scripts dont le nom ou les paramètres contiennent ces
     * caractères, et une SPA privée de son JS se charge sans jamais s'afficher.
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

    private inner class CommunityWebViewClient : WebViewClient() {

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

            // Coupe les médias sur FetLife uniquement. Aucun hôte n'est bloqué en
            // entier : flcdn.net sert aussi des scripts et des feuilles de style.
            if (isCurrentUrlFetlife(currentMainUrl) && !isAudioRequest(request) && isMediaResource(url)) {
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

            // Le réglage suit la page courante : passer de FetLife à BDSMsutra
            // rallume les images, et l'inverse les éteint.
            webView.settings.blockNetworkImage = isCurrentUrlFetlife(url)

            // La machine à états ne concerne que les vraies pages de contenu.
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

    private inner class CommunityChromeClient : WebChromeClient() {
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
            bottomMenu.visibility = View.GONE
        }

        override fun onHideCustomView() {
            customView?.let {
                val decorView = window.decorView as FrameLayout
                decorView.removeView(it)
                webView.visibility = View.VISIBLE
                bottomMenu.visibility = View.VISIBLE
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
            // Quota épuisé : aucun repli possible, on quitte.
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

        // Quitter l'app pendant une session la termine immédiatement et déclenche
        // le cooldown — sinon il suffirait de faire des allers-retours.
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
