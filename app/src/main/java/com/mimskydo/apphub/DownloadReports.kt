package com.mimskydo.apphub

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.util.Log
import android.widget.Toast

enum class DownloadEnd {

    LANDED,

    FAILED,

    FORGOTTEN,
}

class DownloadReports : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0) return

        val end = describe(context, id)
        Log.i(TAG, "download complete id=$id $end")
        if (end != DownloadEnd.LANDED) toast(context, R.string.browser_download_failed)
    }

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
        }
    }

    private companion object {
        private const val TAG = "AppHub"
    }
}
