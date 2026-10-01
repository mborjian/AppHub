package com.mimskydo.apphub

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.URLUtil
import org.json.JSONArray
import org.json.JSONObject

/** One page the browser has been to, or one the driver kept. */
data class Place(
    /** what the page called itself, or its address when it said nothing */
    val title: String,
    val url: String,
    /** when it was visited (or kept), newest first in every list here */
    val at: Long = 0L,
)

/**
 * The address bar's two jobs, in one place: what the driver typed is either an
 * address or a search, and only the screen can tell the difference - so the rule
 * that decides lives here, where it can be read on its own.
 */
object Web {

    /**
     * Where a search goes.
     *
     * DuckDuckGo's own HTML endpoint rather than the full page: it answers
     * without JavaScript, which is the difference between a result list and a
     * blank screen on the WebView a head unit ships with. One constant, so it is
     * one edit to move it.
     */
    const val SEARCH = "https://html.duckduckgo.com/html/?q="

    private const val SCHEME = "https://"

    /**
     * What was meant by what was typed.
     *
     * A scheme is a promise: if one is there it is obeyed, and a `file:` or an
     * `intent:` is left for the caller to refuse rather than being quietly
     * rewritten into a search. Without one, a single word that looks like a host
     * is a host; anything else - words with spaces in them, a bare word with no
     * dot - is a search.
     */
    fun target(typed: String): String {
        val text = typed.trim()
        if (text.isEmpty()) return ""
        if (text.contains("://") || text.startsWith("about:") || text.startsWith("data:")) return text
        val host = looksLikeHost(text)
        return if (host) SCHEME + text else SEARCH + encode(text)
    }

    /**
     * Percent-encode for a query parameter, by the same rule `Uri.encode(text)`
     * applies - unreserved ASCII kept, everything else UTF-8 and escaped.
     *
     * Written out rather than borrowed from `android.net.Uri` for one reason:
     * this rule is the one piece of the browser that has to be readable - and
     * testable - on its own, and a JVM test cannot touch an android.jar stub.
     * The two agree byte for byte; if that ever stops being true, the tests
     * that pin this file are the place the difference shows up.
     */
    private fun encode(text: String): String {
        val out = StringBuilder(text.length)
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_-.!~*'()") {
                out.append(c)
            } else {
                out.append('%').append("0123456789ABCDEF"[(byte.toInt() shr 4) and 0xF])
                    .append("0123456789ABCDEF"[byte.toInt() and 0xF])
            }
        }
        return out.toString()
    }

    /** the host of [url], for the header's trailing line */
    fun host(url: String): String = try {
        Uri.parse(url).host ?: url
    } catch (t: Throwable) {
        url
    }

    /**
     * Whether a typed word is an address rather than something to search for.
     *
     * A dot with letters after it: `example.com` is a host, `how do i` is not,
     * and a bare word is a search - which is the one case where guessing "host"
     * would send a driver to a domain squat instead of a result page.
     *
     * Two addresses get in by kind rather than by shape, because a head unit is
     * pointed at them more often than at any named site: `localhost` (with or
     * without a port), and an address that is only digits and dots - a router,
     * a dashcam, the factory interface. Sending `192.168.1.1` to a search
     * engine is the one wrong answer this rule must not give, and the
     * suffix-shaped rule above misses it: the address's last dot stands before
     * a one-letter final group.
     */
    private fun looksLikeHost(text: String): Boolean {
        if (text.any { it.isWhitespace() }) return false
        val authority = text.substringBefore('/').substringBefore('?').substringBefore('#')
        // a port says where to connect, not what was typed: the host is judged
        // without it, and the port rides along into the answer
        val host = authority.substringBefore(':')
        if (host.equals("localhost", ignoreCase = true)) return true
        if (host.contains('.') && host.any { it.isDigit() } && host.all { it.isDigit() || it == '.' }) {
            return true
        }
        val dot = host.lastIndexOf('.')
        if (dot <= 0 || dot == host.length - 1) return false
        val suffix = host.substring(dot + 1)
        return suffix.length >= 2 && suffix.all { it.isLetter() }
    }
}

/**
 * The browser's memory: the pages it has been to, and the pages the driver kept.
 *
 * Two lists in one preferences file as JSON, read defensively - a memory that
 * cannot be parsed is an empty one, because a browser that will not open is a
 * worse answer than a browser with no history. The framework's own `org.json` is
 * what reads it, for the same reason `Updater` uses it: no library is added for
 * two lists of three fields.
 *
 * Only `http` and `https` are written down at all: `about:blank`, a `data:` page
 * and the app's own start screen are not places the driver went.
 */
object BrowserStore {

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"

    private const val PREFS = "browser"
    private const val KEY_HISTORY = "history"
    private const val KEY_BOOKMARKS = "bookmarks"

    /** how many visits are kept: an afternoon of driving, not an archive */
    const val HISTORY_KEPT = 200

    fun history(context: Context): List<Place> = read(context, KEY_HISTORY)

    fun bookmarks(context: Context): List<Place> = read(context, KEY_BOOKMARKS)

    /**
     * Write a visit down, newest first.
     *
     * A page that is already the newest one is replaced rather than repeated: a
     * reload, or coming back to the tab, is not a new visit, and a history that
     * says otherwise is a history nobody can read.
     */
    fun record(context: Context, title: String, url: String) {
        if (!web(url)) return
        val next = ArrayList<Place>(HISTORY_KEPT + 1)
        next += Place(title.ifBlank { url }, url, System.currentTimeMillis())
        next += history(context).filter { it.url != url }
        save(context, KEY_HISTORY, next.take(HISTORY_KEPT))
    }

    fun clearHistory(context: Context) = save(context, KEY_HISTORY, emptyList())

    fun bookmarked(context: Context, url: String): Boolean = bookmarks(context).any { it.url == url }

    /** keep this page, or move what was kept for it back to the top */
    fun keep(context: Context, title: String, url: String) {
        if (!web(url)) return
        val next = ArrayList<Place>(16)
        next += Place(title.ifBlank { url }, url, System.currentTimeMillis())
        next += bookmarks(context).filter { it.url != url }
        save(context, KEY_BOOKMARKS, next)
    }

    fun forget(context: Context, url: String) =
        save(context, KEY_BOOKMARKS, bookmarks(context).filter { it.url != url })

    /** whether a visit is a place this browser went */
    private fun web(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    private fun read(context: Context, key: String): List<Place> = try {
        val array = JSONArray(prefs(context).getString(key, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val url = row.optString("url")
            if (url.isEmpty()) null
            else Place(row.optString("title").ifEmpty { url }, url, row.optLong("at"))
        }
    } catch (t: Throwable) {
        Log.i(TAG, "browser $key unreadable: $t")
        emptyList()
    }

    private fun save(context: Context, key: String, places: List<Place>) {
        val array = JSONArray()
        places.forEach { place ->
            array.put(
                JSONObject()
                    .put("title", place.title)
                    .put("url", place.url)
                    .put("at", place.at)
            )
        }
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** What handing a link to the platform's own downloader did. */
enum class DownloadOp {

    /** the platform has taken it, and the file is on its way to Downloads */
    STARTED,

    /** not a link that can be fetched at all: a `blob:`, a `data:`, a script */
    UNNAMED,

    /** the downloader refused it, or there is no downloader on this unit */
    REFUSED,
}

/**
 * Taking a link off the web and putting it on the unit.
 *
 * The bytes are the platform's business, not this app's: `DownloadManager` fetches
 * the file, survives the browser being closed under it, and - the reason it is the
 * right tool here - leaves the file in the unit's *Downloads* folder, which is the
 * folder the file manager draws. A driver downloads a map and then opens it, with
 * the two features meeting where they would both look.
 *
 * The browser's own cookie and user agent are copied onto the request. Without
 * them a file behind a login arrives as a sign-in page: the downloader has its own
 * `HttpClient`, and it has never seen the session the WebView is holding.
 */
object BrowserDownloads {

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"

    fun start(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        cookie: String?,
    ): DownloadOp {
        // a link the platform cannot fetch is not a failure to report as one:
        // pages build `blob:` and `data:` addresses for downloads that only
        // exist while the page does
        if (!URLUtil.isNetworkUrl(url)) return DownloadOp.UNNAMED
        val name = try {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (t: Throwable) {
            null
        }
        if (name.isNullOrBlank()) return DownloadOp.UNNAMED

        return try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription(Web.host(url))
                .setMimeType(mimeType)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            val id = manager?.enqueue(request) ?: return DownloadOp.REFUSED
            Log.i(TAG, "download id=$id name=$name url=$url")
            DownloadOp.STARTED
        } catch (t: Throwable) {
            Log.i(TAG, "download $url refused: $t")
            DownloadOp.REFUSED
        }
    }
}

/**
 * What to tell the driver about a download.
 *
 * "Downloading" and not "downloaded": the downloader has the request and the file
 * is on its way, which is not the same as being on the unit - the platform's own
 * notification is what says when it lands.
 */
object DownloadReport {

    fun text(context: Context, op: DownloadOp, name: String): String = when (op) {
        DownloadOp.STARTED -> context.getString(R.string.browser_download_started, name)
        DownloadOp.UNNAMED -> context.getString(R.string.browser_download_unnamed)
        DownloadOp.REFUSED -> context.getString(R.string.browser_download_refused, name)
    }
}

/**
 * Where the two features meet: the browser's downloads and the file manager's
 * rows are the same folder, so one screen can hand the driver to the other.
 *
 * The file manager reads real paths and takes no address, so the hand-over is
 * the folder and not the file: [Environment.DIRECTORY_DOWNLOADS] on the primary
 * volume is where [BrowserDownloads] aims every request, and the receiver that
 * listens for the download landing is what makes the timing make sense - the
 * browser knows a file is *in* there before it opens the door to it.
 */
object BrowserToFiles {

    /**
     * Open the file manager on the unit's Downloads folder.
     *
     * Answering where it could not is as much a part of this as opening it: a
     * row that does nothing when tapped is a row that lies.
     */
    fun showDownloads(context: Context): Boolean {
        val downloads = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val intent = Intent(context, FileManagerActivity::class.java)
            .putExtra(FileManagerActivity.EXTRA_START_DIR, downloads.absolutePath)
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.i(TAG, "show downloads refused: $t")
            false
        }
    }

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"
}
