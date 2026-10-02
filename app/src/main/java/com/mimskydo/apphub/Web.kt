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

data class Place(
    val title: String,
    val url: String,
    val at: Long = 0L,
)

object Web {

    const val SEARCH = "https://html.duckduckgo.com/html/?q="

    private const val SCHEME = "https://"

    fun target(typed: String): String {
        val text = typed.trim()
        if (text.isEmpty()) return ""
        if (text.contains("://") || text.startsWith("about:") || text.startsWith("data:")) return text
        val host = looksLikeHost(text)
        return if (host) SCHEME + text else SEARCH + encode(text)
    }

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

    fun host(url: String): String = try {
        Uri.parse(url).host ?: url
    } catch (t: Throwable) {
        url
    }

    private fun looksLikeHost(text: String): Boolean {
        if (text.any { it.isWhitespace() }) return false
        val authority = text.substringBefore('/').substringBefore('?').substringBefore('#')
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

object BrowserStore {

    private const val TAG = "AppHub"

    private const val PREFS = "browser"
    private const val KEY_HISTORY = "history"
    private const val KEY_BOOKMARKS = "bookmarks"

    const val HISTORY_KEPT = 200

    fun history(context: Context): List<Place> = read(context, KEY_HISTORY)

    fun bookmarks(context: Context): List<Place> = read(context, KEY_BOOKMARKS)

    fun record(context: Context, title: String, url: String) {
        if (!web(url)) return
        val next = ArrayList<Place>(HISTORY_KEPT + 1)
        next += Place(title.ifBlank { url }, url, System.currentTimeMillis())
        next += history(context).filter { it.url != url }
        save(context, KEY_HISTORY, next.take(HISTORY_KEPT))
    }

    fun clearHistory(context: Context) = save(context, KEY_HISTORY, emptyList())

    fun bookmarked(context: Context, url: String): Boolean = bookmarks(context).any { it.url == url }

    fun keep(context: Context, title: String, url: String) {
        if (!web(url)) return
        val next = ArrayList<Place>(16)
        next += Place(title.ifBlank { url }, url, System.currentTimeMillis())
        next += bookmarks(context).filter { it.url != url }
        save(context, KEY_BOOKMARKS, next)
    }

    fun forget(context: Context, url: String) =
        save(context, KEY_BOOKMARKS, bookmarks(context).filter { it.url != url })

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

enum class DownloadOp {

    STARTED,

    UNNAMED,

    REFUSED,
}

object BrowserDownloads {

    private const val TAG = "AppHub"

    fun start(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        cookie: String?,
    ): DownloadOp {
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

object DownloadReport {

    fun text(context: Context, op: DownloadOp, name: String): String = when (op) {
        DownloadOp.STARTED -> context.getString(R.string.browser_download_started, name)
        DownloadOp.UNNAMED -> context.getString(R.string.browser_download_unnamed)
        DownloadOp.REFUSED -> context.getString(R.string.browser_download_refused, name)
    }
}

object BrowserToFiles {

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

    private const val TAG = "AppHub"
}
