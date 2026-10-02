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
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    data class Tab(
        val id: String = UUID.randomUUID().toString(),
        val webView: WebView,
        var title: String = "Nouvel onglet",
        var url: String = "",
        val parentTabId: String? = null
    )

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
            "qwant.com",

            // Scolaire / ENT bloqué
            "monlycee.net",
            "monlycée.net",
            "xn--monlyce-hya.net"
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
    private lateinit var tabScrollView: HorizontalScrollView
    private lateinit var tabStrip: LinearLayout
    private lateinit var progressContainer: FrameLayout
    private lateinit var progressBar: View
    private lateinit var webContainer: FrameLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var errorOverlay: FrameLayout
    private lateinit var blockedOverlay: FrameLayout
    private lateinit var txtBlockedTitle: TextView
    private lateinit var txtBlockedDesc: TextView
    private lateinit var btnBlockedClose: View

    private val tabs = mutableListOf<Tab>()
    private var activeTab: Tab? = null
    private val currentWebView: WebView? get() = activeTab?.webView

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
        tabScrollView = findViewById(R.id.tabScrollView)
        tabStrip = findViewById(R.id.tabStrip)
        progressContainer = findViewById(R.id.progressContainer)
        progressBar = findViewById(R.id.progressBar)
        webContainer = findViewById(R.id.webContainer)
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

    private fun createNewTab(parentTab: Tab? = null): Tab {
        val newWebView = WebView(this)
        val tab = Tab(
            id = UUID.randomUUID().toString(),
            webView = newWebView,
            title = "Nouvel onglet",
            url = "",
            parentTabId = parentTab?.id
        )

        configureWebView(newWebView, tab)

        webContainer.addView(
            newWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        tabs.add(tab)
        renderTabs()
        return tab
    }

    private fun selectTab(tab: Tab) {
        activeTab = tab

        for (t in tabs) {
            t.webView.visibility = if (t == tab) View.VISIBLE else View.GONE
        }

        emptyState.visibility = View.GONE
        hideBlockedOverlay()
        errorOverlay.visibility = View.GONE

        val host = try { Uri.parse(tab.url).host } catch (_: Exception) { null }
        txtHost.text = host ?: tab.title.ifEmpty { "Link Trash" }

        val progress = tab.webView.progress
        if (progress in 1..99) {
            updateProgress(progress)
        } else {
            hideProgress()
        }

        renderTabs()
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index == -1) return

        webContainer.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.loadUrl("about:blank")
        tab.webView.clearHistory()
        tab.webView.removeAllViews()
        tab.webView.destroy()

        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            activeTab = null
            emptyState.visibility = View.VISIBLE
            txtHost.text = getString(R.string.app_name)
            hideProgress()
            renderTabs()
            finish()
        } else {
            if (activeTab == tab) {
                val parentTab = tabs.find { it.id == tab.parentTabId }
                val nextTab = parentTab ?: if (index < tabs.size) tabs[index] else tabs.last()
                selectTab(nextTab)
            } else {
                renderTabs()
            }
        }
    }

    private fun renderTabs() {
        tabStrip.removeAllViews()

        if (tabs.size <= 1) {
            tabScrollView.visibility = View.GONE
            return
        }

        tabScrollView.visibility = View.VISIBLE

        for (tab in tabs) {
            val tabView = layoutInflater.inflate(R.layout.item_tab, tabStrip, false)
            val txtTabTitle = tabView.findViewById<TextView>(R.id.txtTabTitle)
            val btnTabClose = tabView.findViewById<ImageButton>(R.id.btnTabClose)

            val isActive = (tab == activeTab)
            tabView.setBackgroundResource(if (isActive) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
            txtTabTitle.text = tab.title.ifEmpty {
                try {
                    Uri.parse(tab.url).host ?: "Onglet"
                } catch (_: Exception) {
                    "Onglet"
                }
            }
            txtTabTitle.setTextColor(
                ContextCompat.getColor(this, if (isActive) R.color.text_primary else R.color.text_secondary)
            )

            tabView.setOnClickListener {
                selectTab(tab)
            }

            btnTabClose.setOnClickListener {
                closeTab(tab)
            }

            tabStrip.addView(tabView)
        }

        val activeIndex = tabs.indexOf(activeTab)
        if (activeIndex >= 0) {
            tabScrollView.post {
                val child = tabStrip.getChildAt(activeIndex)
                if (child != null) {
                    tabScrollView.smoothScrollTo(child.left, 0)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView, tab: Tab) {
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.background))

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            val currentAgent = userAgentString ?: ""
            userAgentString = currentAgent.replace("; wv", "")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.setOnLongClickListener {
            val result = wv.hitTestResult
            val type = result.type
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val extra = result.extra
                if (!extra.isNullOrEmpty()) {
                    val popup = PopupMenu(this@MainActivity, wv)
                    popup.menu.add("Ouvrir dans un nouvel onglet")
                    popup.setOnMenuItemClickListener { menuItem ->
                        if (menuItem.title == "Ouvrir dans un nouvel onglet") {
                            val newTab = createNewTab(parentTab = tab)
                            selectTab(newTab)
                            newTab.webView.loadUrl(extra)
                            true
                        } else false
                    }
                    popup.show()
                    return@setOnLongClickListener true
                }
            }
            false
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme?.lowercase() ?: ""

                if (scheme != "http" && scheme != "https") {
                    return try {
                        val parsedIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(parsedIntent)
                        if (scheme == "com.fabernovel.idfsubventionjeunes" || scheme == "labaz") {
                            finish()
                        }
                        true
                    } catch (_: Exception) {
                        try {
                            val parsedIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
                            if (!fallbackUrl.isNullOrEmpty()) {
                                view.loadUrl(fallbackUrl)
                                return true
                            }
                        } catch (_: Exception) {}

                        try {
                            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addCategory(Intent.CATEGORY_BROWSABLE)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(fallbackIntent)
                            if (scheme == "com.fabernovel.idfsubventionjeunes" || scheme == "labaz") {
                                finish()
                            }
                            true
                        } catch (_: Exception) {
                            true
                        }
                    }
                }

                val (isBlocked, reason) = isUrlBlocked(url)
                if (isBlocked) {
                    if (activeTab == tab) {
                        showBlockedOverlay(reason)
                    }
                    return true
                }

                if (request.isForMainFrame) {
                    tab.url = url
                    if (activeTab == tab) {
                        txtHost.text = request.url.host ?: url
                    }
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
                    if (activeTab == tab) {
                        showBlockedOverlay(reason)
                    }
                    return
                }

                tab.url = url
                try {
                    val host = Uri.parse(url).host
                    if (!host.isNullOrEmpty()) {
                        tab.title = host
                    }
                } catch (_: Exception) {}

                if (activeTab == tab) {
                    hideBlockedOverlay()
                    txtHost.text = tab.title
                    showProgress()
                    errorOverlay.visibility = View.GONE
                }
                renderTabs()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (activeTab == tab) {
                    hideProgress()
                }

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
                if (request.isForMainFrame && activeTab == tab) {
                    hideProgress()
                    if (!isNetworkAvailable()) {
                        errorOverlay.visibility = View.VISIBLE
                    }
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (activeTab == tab) {
                    updateProgress(newProgress)
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrEmpty() && title != "about:blank") {
                    tab.title = title
                    if (activeTab == tab) {
                        txtHost.text = title
                    }
                    renderTabs()
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val newTab = createNewTab(parentTab = tab)
                selectTab(newTab)
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newTab.webView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                closeTab(tab)
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

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
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
                Toast.makeText(this@MainActivity, "Téléchargement lancé...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Erreur téléchargement: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupActions() {
        btnBack.setOnClickListener {
            handleBackPress()
        }

        btnRefresh.setOnClickListener {
            currentWebView?.reload()
        }

        btnClose.setOnClickListener {
            val tab = activeTab
            if (tabs.size > 1 && tab != null) {
                closeTab(tab)
            } else {
                finish()
            }
        }

        btnBlockedClose.setOnClickListener {
            val tab = activeTab
            if (tabs.size > 1 && tab != null) {
                closeTab(tab)
            } else {
                finish()
            }
        }

        findViewById<View>(R.id.btnRetry).setOnClickListener {
            val tab = activeTab
            if (tab != null && tab.url.isNotEmpty()) {
                errorOverlay.visibility = View.GONE
                tab.webView.loadUrl(tab.url)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https")) {
            val targetUrl = uri.toString()
            val (isBlocked, reason) = isUrlBlocked(targetUrl)
            if (isBlocked) {
                showBlockedOverlay(reason)
                return
            }

            hideBlockedOverlay()
            emptyState.visibility = View.GONE

            if (tabs.isNotEmpty() && activeTab != null && activeTab!!.url.isNotEmpty() && activeTab!!.url != "about:blank") {
                val newTab = createNewTab()
                selectTab(newTab)
                newTab.webView.loadUrl(targetUrl)
            } else {
                val tab = if (tabs.isEmpty()) createNewTab().also { selectTab(it) } else activeTab!!
                tab.webView.loadUrl(targetUrl)
            }
        } else {
            if (tabs.isEmpty()) {
                hideBlockedOverlay()
                emptyState.visibility = View.VISIBLE
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

        // Exception vitale : comptes Google, FranceConnect et connexion Labaz / IdP
        if (host == "accounts.google.com" || host == "myaccount.google.com" ||
            host == "franceconnect.gouv.fr" || host.endsWith(".franceconnect.gouv.fr") ||
            host == "iledefrance.fr" || host.endsWith(".iledefrance.fr")
        ) {
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
        if (host.contains("monlycee") || host.contains("monlycée") || host.contains("xn--monlyce")) {
            return Pair(true, "Site bloqué : monlycée.net")
        }
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
        activeTab?.webView?.visibility = View.GONE
        emptyState.visibility = View.GONE
        hideProgress()
    }

    private fun hideBlockedOverlay() {
        blockedOverlay.visibility = View.GONE
        activeTab?.webView?.visibility = View.VISIBLE
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
        if (totalWidth > 0) {
            val params = progressBar.layoutParams
            params.width = (totalWidth * (progress / 100f)).toInt()
            progressBar.layoutParams = params
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun handleBackPress() {
        when {
            blockedOverlay.visibility == View.VISIBLE -> {
                val tab = activeTab
                if (tabs.size > 1 && tab != null) {
                    closeTab(tab)
                } else {
                    finish()
                }
            }
            errorOverlay.visibility == View.VISIBLE -> {
                errorOverlay.visibility = View.GONE
                val tab = activeTab
                if (tab != null && tab.url.isNotEmpty()) {
                    tab.webView.loadUrl(tab.url)
                } else if (tabs.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                }
            }
            currentWebView?.canGoBack() == true -> {
                currentWebView?.goBack()
            }
            tabs.size > 1 && activeTab != null -> {
                activeTab?.let { closeTab(it) }
            }
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackPress()
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { it.webView.onResume() }
    }

    override fun onPause() {
        super.onPause()
        tabs.forEach { it.webView.onPause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        for (tab in tabs) {
            tab.webView.stopLoading()
            tab.webView.removeAllViews()
            tab.webView.destroy()
        }
        tabs.clear()
    }
}
