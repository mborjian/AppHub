package com.mimskydo.apphub

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import java.util.Locale

class BrowserActivity : BaseActivity() {

    private lateinit var headerTitle: TextView
    private lateinit var headerTrailing: TextView
    private lateinit var banner: View
    private lateinit var address: EditText
    private lateinit var backButton: ImageView
    private lateinit var forwardButton: ImageView
    private lateinit var actionButton: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var start: View
    private lateinit var page: WebView

    private var current: String? = null

    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        applyMargins()

        headerTitle = findViewById(R.id.headerTitle)
        headerTrailing = findViewById(R.id.headerTrailing)
        banner = findViewById(R.id.webBanner)
        address = findViewById(R.id.webAddress)
        backButton = findViewById(R.id.webBack)
        forwardButton = findViewById(R.id.webForward)
        actionButton = findViewById(R.id.webAction)
        progress = findViewById(R.id.webProgress)
        start = findViewById(R.id.webStart)
        page = findViewById(R.id.webPage)

        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }
        backButton.setOnClickListener { if (page.canGoBack()) page.goBack() }
        forwardButton.setOnClickListener { if (page.canGoForward()) page.goForward() }
        actionButton.setOnClickListener { if (loading) page.stopLoading() else reload() }
        findViewById<ImageView>(R.id.webMore).setOnClickListener { showMenu() }
        findViewById<View>(R.id.webStartBookmarks).setOnClickListener { showBookmarks() }
        findViewById<View>(R.id.webStartHistory).setOnClickListener { showHistory() }

        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go(address.text.toString())
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (page.canGoBack()) page.goBack() else finish()
                }
            },
        )

        configure()
        wireBanner()

        val restored = savedInstanceState?.getString(KEY_URL)
        val handedIn = intent?.data?.toString().orEmpty()
        when {
            !restored.isNullOrEmpty() -> go(restored)
            handedIn.isNotEmpty() -> go(handedIn)
            else -> showStart()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        val settings = page.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.hub_page))
        page.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        CookieManager.getInstance().setAcceptCookie(true)

        page.webViewClient = client()
        page.webChromeClient = chrome()

        page.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val cookie = CookieManager.getInstance().getCookie(url)
            val op = BrowserDownloads.start(
                context = applicationContext,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                cookie = cookie,
            )
            val name = url.substringAfterLast('/')
            Log.i(TAG, "download $url $op")
            toast(DownloadReport.text(this, op, name))
        }
    }

    private fun client(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase(Locale.US) ?: return true
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") {
                return false
            }
            handToPlatform(url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url.startsWith("http")) {
                loading = true
                hideBanner()
                start.isVisible = false
                page.isVisible = true
                progress.isVisible = true
                refresh(url)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            loading = false
            progress.isVisible = false
            BrowserStore.record(this@BrowserActivity, view.title.orEmpty(), url)
            Log.i(TAG, "page done $url")
            refresh(url)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            loading = false
            progress.isVisible = false
            refresh(view.url ?: current)
            showBanner(describe(error))
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            loading = false
            progress.isVisible = false
            Log.i(TAG, "ssl refused: ${error.url}")
            page.stopLoading()
            refresh(current)
            showBanner(getString(R.string.browser_error_secure))
        }
    }

    private fun chrome(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            refresh(view.url ?: current)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            headerTitle.text = title?.takeIf { it.isNotBlank() }
                ?: getString(R.string.browser_title)
        }

        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            toast(getString(R.string.browser_upload_refused))
            return false
        }
    }

    private fun go(typed: String) {
        val target = Web.target(typed)
        if (target.isEmpty()) return
        val scheme = Uri.parse(target).scheme?.lowercase(Locale.US)
        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") {
            load(target)
        } else {
            handToPlatform(target)
        }
    }

    private fun load(url: String) {
        address.clearFocus()
        if (::page.isInitialized) page.loadUrl(url)
    }

    private fun reload() {
        val url = current ?: return
        load(url)
    }

    private fun handToPlatform(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            toast(getString(R.string.browser_link_handed))
        } catch (t: Throwable) {
            Log.i(TAG, "link refused: $url $t")
            toast(getString(R.string.browser_link_refused))
        }
    }

    private fun showStart() {
        current = null
        start.isVisible = true
        page.isVisible = false
        headerTitle.text = getString(R.string.browser_title)
        headerTrailing.isVisible = false
        refresh(null)
    }

    private fun refresh(url: String?) {
        if (url != null && url.startsWith("http")) current = url

        val dim = 0.4f
        backButton.isEnabled = page.canGoBack()
        backButton.alpha = if (backButton.isEnabled) 1f else dim
        forwardButton.isEnabled = page.canGoForward()
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else dim

        actionButton.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_reload)
        actionButton.contentDescription =
            getString(if (loading) R.string.browser_stop else R.string.browser_reload)
        actionButton.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.text_primary)
        )

        val shown = current ?: ""
        if (!address.hasFocus()) address.setText(shown)

        headerTrailing.isVisible = current != null
        headerTrailing.text = current?.let { Web.host(it) }.orEmpty()
        if (headerTitle.text.isNullOrBlank()) headerTitle.text = getString(R.string.browser_title)
    }

    private fun showMenu() {
        val url = current
        val rows = ArrayList<SheetRow>(7)

        if (url != null) {
            val kept = BrowserStore.bookmarked(this, url)
            rows += SheetRow(
                label = getString(
                    if (kept) R.string.browser_bookmark_remove else R.string.browser_bookmark_add
                ),
                icon = ContextCompat.getDrawable(this, R.drawable.ic_bookmark),
                onClick = { if (kept) dropBookmark(url) else keepBookmark(url) },
            )
        }

        rows += SheetRow(
            label = getString(R.string.browser_bookmarks),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_bookmark),
            onClick = { showBookmarks() },
        )
        rows += SheetRow(
            label = getString(R.string.browser_history),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_tasks),
            onClick = { showHistory() },
        )
        if (url != null) {
            rows += SheetRow(
                label = getString(R.string.browser_copy_address),
                icon = ContextCompat.getDrawable(this, R.drawable.ic_edit),
                onClick = { copyAddress(url) },
            )
        }
        rows += SheetRow(
            label = getString(R.string.browser_show_downloads),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_folder),
            onClick = {
                if (!BrowserToFiles.showDownloads(this)) {
                    toast(getString(R.string.settings_unavailable))
                }
            },
        )
        rows += SheetRow(
            label = getString(R.string.browser_history_clear),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_uninstall),
            danger = true,
            groupStart = true,
            onClick = { confirmClearHistory() },
        )
        rows += SheetRow(
            label = getString(android.R.string.cancel),
            groupStart = true,
        )

        Sheet.showBottom(
            context = this,
            title = getString(R.string.browser_title),
            subtitle = current?.let { Web.host(it) },
            rows = rows,
        )
    }

    private fun keepBookmark(url: String) {
        val title = page.title.orEmpty()
        BrowserStore.keep(this, title, url)
        toast(getString(R.string.browser_bookmark_kept, title.ifBlank { Web.host(url) }))
    }

    private fun dropBookmark(url: String) {
        BrowserStore.forget(this, url)
        toast(getString(R.string.browser_bookmark_dropped))
    }

    private fun copyAddress(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.browser_title), url))
        toast(getString(R.string.browser_address_copied))
    }

    private fun confirmClearHistory() {
        Sheet.show(
            context = this,
            title = getString(R.string.browser_history_clear_title),
            subtitle = getString(R.string.browser_history_clear_message),
            rows = listOf(
                SheetRow(
                    label = getString(R.string.browser_history_clear_confirm),
                    destroy = true,
                    onClick = {
                        BrowserStore.clearHistory(this)
                        toast(getString(R.string.browser_history_cleared))
                    },
                ),
                SheetRow(label = getString(android.R.string.cancel)),
            ),
        )
    }

    private fun showBookmarks() {
        val places = BrowserStore.bookmarks(this)
        places(
            title = getString(R.string.browser_bookmarks),
            places = places,
            emptyTitle = getString(R.string.browser_bookmarks_empty_title),
            emptyMessage = getString(R.string.browser_bookmarks_empty_message),
            icon = R.drawable.ic_bookmark,
        )
    }

    private fun showHistory() {
        val places = BrowserStore.history(this)
        places(
            title = getString(R.string.browser_history),
            places = places,
            emptyTitle = getString(R.string.browser_history_empty_title),
            emptyMessage = getString(R.string.browser_history_empty_message),
            icon = R.drawable.ic_web,
        )
    }

    private fun places(
        title: String,
        places: List<Place>,
        emptyTitle: String,
        emptyMessage: String,
        icon: Int,
    ): List<SheetRow> {
        if (places.isEmpty()) {
            Sheet.showBottom(
                context = this,
                title = emptyTitle,
                subtitle = emptyMessage,
                rows = listOf(SheetRow(label = getString(android.R.string.cancel))),
            )
            return emptyList()
        }
        val rows = places.take(SHEET_ROWS).map { place ->
            SheetRow(
                label = place.title,
                subtitle = place.url,
                icon = ContextCompat.getDrawable(this, icon),
                onClick = { load(place.url) },
            )
        } + SheetRow(label = getString(android.R.string.cancel), groupStart = true)
        Sheet.showBottom(context = this, title = title, rows = rows)
        return rows
    }

    private fun wireBanner() {
        banner.findViewById<TextView>(R.id.bannerAction).setOnClickListener {
            hideBanner()
            reload()
        }
    }

    private fun showBanner(message: String) {
        banner.findViewById<TextView>(R.id.bannerTitle).text = getString(R.string.browser_error_title)
        banner.findViewById<TextView>(R.id.bannerMessage).text = message
        banner.isVisible = true
    }

    private fun hideBanner() {
        banner.isVisible = false
    }

    private fun describe(error: WebResourceError): String = try {
        error.description?.toString()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.browser_error_message)
    } catch (t: Throwable) {
        getString(R.string.browser_error_message)
    }

    override fun onResume() {
        super.onResume()
        page.onResume()
    }

    override fun onPause() {
        page.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        page.stopLoading()
        page.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_URL, current)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        private const val TAG = "AppHub"

        private const val KEY_URL = "browser_url"

        private const val SHEET_ROWS = 40
    }
}
