package com.mimskydo.apphub

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.util.Log
import android.widget.Toast

/** What a finished download turned out to be, once the platform said so. */
enum class DownloadEnd {

    /** the downloader's own record says the file is in Downloads */
    LANDED,

    /** the record says it was not successful, whatever the reason was */
    FAILED,

    /** the downloader has no record at all: the id is not one of its own */
    FORGOTTEN,
}

/**
 * The other half of [BrowserDownloads]: what the platform's downloader says when
 * it is done.
 *
 * Everything before this point is honest but incomplete: the browser says
 * *downloading*, which is true the moment the request is taken and tells the
 * driver nothing about the ending. The platform's notification says when a file
 * lands, but the app itself never checks - and a download that fails quietly is
 * exactly the kind of ending a driver finds out about an hour later, looking in
 * the folder for a file that is not there. `ACTION_DOWNLOAD_COMPLETE` is the
 * downloader naming this app as the receiver of its own answer, which is why the
 * broadcast is exempt from the implicit-broadcast ban and safe to declare.
 *
 * What it is allowed to say comes only from the downloader's own record, queried
 * by the id the broadcast carries. Nothing is assumed and nothing is guessed:
 * a status the record does not hold is [DownloadEnd.FORGOTTEN], not a failure.
 * A download that landed says nothing here - the platform's own completion
 * notification already did, and a second voice saying the same thing is noise.
 */
class DownloadReports : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0) return

        val end = describe(context, id)
        Log.i(TAG, "download complete id=$id $end")
        if (end != DownloadEnd.LANDED) toast(context, R.string.browser_download_failed)
    }

    /**
     * What the downloader's own record says about [id].
     *
     * A query that throws, or a record that answers nothing, is
     * [DownloadEnd.FORGOTTEN]: "the platform has no note of this" is a different
     * answer from "the platform says it failed", and the two must not be
     * collapsed - one is a missing row, the other is a real ending.
     */
    fun describe(context: Context, id: Long): DownloadEnd {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return DownloadEnd.FORGOTTEN
        val cursor: Cursor = try {
            manager.query(DownloadManager.Query().setFilterById(id))
        } catch (t: Throwable) {
            return DownloadEnd.FORGOTTEN
        }
        cursor.use {
            if (!it.moveToFirst()) return DownloadEnd.FORGOTTEN
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return if (status == DownloadManager.STATUS_SUCCESSFUL) DownloadEnd.LANDED
            else DownloadEnd.FAILED
        }
    }

    private fun toast(context: Context, text: Int) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // a receiver can be woken with no screen in front to show anything on
        }
    }

    private companion object {
        /** the tag `adb logcat -s AppHub` filters by */
        private const val TAG = "AppHub"
    }
}
