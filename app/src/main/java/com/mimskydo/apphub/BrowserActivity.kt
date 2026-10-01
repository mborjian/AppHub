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

/**
 * A web browser, standing on the board as a tile of this app while the switch in
 * the settings is on.
 *
 * The engine is the platform's own `WebView` - there is nothing to add for it,
 * which matters here: this app has two dependencies and no intention of a third.
 * What this screen is, then, is the app's own chrome around it: the header every
 * other screen has, a toolbar built out of the same row a setting is drawn in,
 * the same sheets for history and bookmarks, and the same empty state when there
 * is nothing on screen.
 *
 * Three rules are deliberate and worth keeping:
 *
 *  * **Only `http`, `https`, `about` and `data` load here.** Anything else - a
 *    `mailto:`, an `intent:`, a `market:` link - is handed to the platform, which
 *    is the only thing that knows what those are. `file:` is in that group too,
 *    so a page cannot ask this browser to open the unit's filesystem;
 *  * **An SSL error is never taken.** There is no "proceed anyway" anywhere in
 *    this file: a certificate the platform will not accept is the one thing a
 *    browser must not be talked out of;
 *  * **A page cannot open a window of its own.** New windows are off, so a link
 *    that wants one loads in the page it was tapped in - a popup this screen
 *    could not see again would be a popup the driver cannot close.
 *
 * What it does not do is worth as much: no tabs, no private mode, no password
 * store, no file upload (a page asking for a file is told so). Each of those is a
 * feature with its own screen, and this is the browser a driver uses for one
 * address at a time.
 */
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

    /** the address on screen, or null while the start block is up */
    private var current: String? = null

    /** true while the page is loading: the toolbar's one shared target changes job */
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

        // The system's own back: a page's history first, the way every browser on
        // the unit behaves, and only then out of the screen. A tile that left on
        // the first back press would make the history unreachable.
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

        // Where the first page comes from: a screen being put back together
        // keeps its page, an address handed in by whoever started this screen
        // (the release probe, or another screen of this app) is loaded, and
        // otherwise the start block. The data is gated through the same
        // address-or-search rule as a typed one, so a `file:` intent has no
        // more power here than a typed one does.
        val restored = savedInstanceState?.getString(KEY_URL)
        val handedIn = intent?.data?.toString().orEmpty()
        when {
            !restored.isNullOrEmpty() -> go(restored)
            handedIn.isNotEmpty() -> go(handedIn)
            else -> showStart()
        }
    }

    // ------------------------------------------------------------- the engine

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
        // a page cannot open a window of its own: see the class comment
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        // the page's own colours, not a white flash a driver sees at night
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.hub_page))
        // software rendering, deliberately: a head unit's GPU driver is where a
        // WebView meets hardware it was never tested against, and a page that
        // draws a little slower beats a page that draws wrong (see the manifest)
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

        /**
         * Whether the page itself loads this, or the platform does.
         *
         * Returning true means "handled": the WebView is told to leave it alone.
         * A scheme this browser has no business loading - `file:` above all - never
         * becomes a page here; something else on the unit may still know what to
         * do with it, and if nothing does, the driver is told.
         */
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
            // a page is a place the driver went: this is what the history is
            BrowserStore.record(this@BrowserActivity, view.title.orEmpty(), url)
            // one line per finished page, beside the download and ssl lines:
            // this is what the release probe reads to say a page really loads
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

        /**
         * A certificate the platform will not accept ends the load here.
         *
         * `handler.proceed()` is deliberately not called anywhere in this file: the
         * one thing a browser must never do is let a page talk the driver out of
         * the check that says who it is.
         */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            loading = false
            progress.isVisible = false
            Log.i(TAG, "ssl refused: ${error.url}")
            // The banner has a reload action of its own; while the loader is
            // being refused the page behind it may still be mid-flight, so the
            // spinner is stopped here as well - otherwise the action button is
            // still drawing its “stop” face while nothing is loading.
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

        /**
         * A page asking for a file is told that this browser has none.
         *
         * Uploading would mean handing a page a real file off the unit, through a
         * picker this app does not have - and it is the one direction a browser
         * with no tabs and no accounts has never needed.
         */
        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            toast(getString(R.string.browser_upload_refused))
            return false
        }
    }

    // ------------------------------------------------------------- the moves

    /** What the driver meant by what they typed: an address, or a search. */
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

    /**
     * Everything that is not a web page belongs to whatever on the unit claims it:
     * a map link to the map app, a shop link to whatever is installed.
     */
    private fun handToPlatform(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            toast(getString(R.string.browser_link_handed))
        } catch (t: Throwable) {
            Log.i(TAG, "link refused: $url $t")
            toast(getString(R.string.browser_link_refused))
        }
    }

    /** The start block: this screen's empty state, and never a web page. */
    private fun showStart() {
        current = null
        start.isVisible = true
        page.isVisible = false
        headerTitle.text = getString(R.string.browser_title)
        headerTrailing.isVisible = false
        refresh(null)
    }

    /** Everything the toolbar says about where this is and what it is doing. */
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

        // The page owns the field while the driver is typing in it: a page that
        // reports its own address a moment later must not erase what they wrote.
        val shown = current ?: ""
        if (!address.hasFocus()) address.setText(shown)

        headerTrailing.isVisible = current != null
        headerTrailing.text = current?.let { Web.host(it) }.orEmpty()
        if (headerTitle.text.isNullOrBlank()) headerTitle.text = getString(R.string.browser_title)
    }

    // ------------------------------------------------------------ the sheets

    private fun showMenu() {
        val url = current
        val rows = ArrayList<SheetRow>(7)

        if (url != null) {
            // one row, two readings: what is on screen is either kept already or
            // not, and the row says which is true rather than offering both
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
        // The two features meet here: a download goes to Downloads, and this
        // row is the way back to it, offered whether or not one is running -
        // because the question the row answers is “where did that file go”,
        // which is asked long after the tap that started it.
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

    /**
     * The one removal here that cannot be undone, so it asks - and it says what
     * stays, because losing the bookmarks as well would be a surprise.
     */
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
            // the empty state is the same sentence the list would carry, in the
            // same place: a sheet that says nothing yet is one the driver closes
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

    // ------------------------------------------------------------- the banner

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

    /** the platform's own words for what went wrong, where it gave any */
    private fun describe(error: WebResourceError): String = try {
        error.description?.toString()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.browser_error_message)
    } catch (t: Throwable) {
        getString(R.string.browser_error_message)
    }

    // ------------------------------------------------------------- life cycle

    override fun onResume() {
        super.onResume()
        page.onResume()
    }

    override fun onPause() {
        page.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // the engine holds a page's timers, its sound and its whole tree: the
        // screen going away is the moment to let go of it
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
        /** the tag `adb logcat -s AppHub` filters by */
        private const val TAG = "AppHub"

        private const val KEY_URL = "browser_url"

        /** how many places one sheet lists: a menu a driver scrolls is a menu they lost */
        private const val SHEET_ROWS = 40
    }
}
