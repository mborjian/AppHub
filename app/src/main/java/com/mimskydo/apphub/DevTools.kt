package com.mimskydo.apphub

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

object DevTools {

    private const val TAG = "AppHub"

    // the unit's platform certificate, the same one tools/release.py signs with
    private const val PLATFORM_CERT = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"

    private val UPDATE_HOSTS = listOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
    )

    private val WEB_HOSTS = listOf("www.google.com", "duckduckgo.com")

    private val CLOCK_ENDPOINTS = listOf(
        "http://detectportal.firefox.com/success.txt",
        "http://captive.apple.com/hotspot-detect.html",
        "http://www.msftconnecttest.com/connecttest.txt",
        "http://connectivitycheck.gstatic.com/generate_204",
        "http://clients3.google.com/generate_204",
    )

    data class Clock(
        val device: Long,
        val network: Long?,
        val endpoint: String?,
        val autoTime: Boolean,
    ) {
        val skew: Long? get() = network?.let { device - it }
    }

    fun tls(context: Context): String {
        val factory = GithubTls.socketFactory(context)
        val out = StringBuilder()
        for (host in UPDATE_HOSTS) {
            val platform = probe("https://$host/", null)
            val union = if (factory == null) "no factory" else probe("https://$host/", factory)
            out.append(host).append('\n')
                .append("  platform: ").append(platform).append('\n')
                .append("  union:    ").append(union).append('\n')
            Log.i(TAG, "tls $host platform=$platform union=$union")
        }
        for (host in WEB_HOSTS) {
            val platform = probe("https://$host/", null)
            out.append(host).append('\n')
                .append("  platform: ").append(platform).append('\n')
            Log.i(TAG, "tls $host platform=$platform")
        }
        if (factory == null) {
            out.append("the bundled anchors produced no socket factory at all\n")
        }
        return out.toString().trimEnd()
    }

    fun clock(context: Context): Clock {
        val device = System.currentTimeMillis()
        var network: Long? = null
        var endpoint: String? = null
        for (url in CLOCK_ENDPOINTS) {
            val time = dateOf(url)
            if (time != null) {
                network = time
                endpoint = url
                break
            }
        }
        val clock = Clock(device, network, endpoint, autoTime(context))
        Log.i(TAG, "clock device=$device network=$network endpoint=$endpoint auto=${clock.autoTime}")
        return clock
    }

    fun clockText(clock: Clock): String {
        val out = StringBuilder()
        out.append("unit     ").append(timeText(clock.device)).append(' ').append(timeZone()).append('\n')
        if (clock.network != null) {
            out.append("network  ").append(timeText(clock.network)).append(' ')
                .append(clock.endpoint?.substringAfter("//")?.substringBefore('/')).append('\n')
            out.append("skew     ").append(skewText(clock.skew ?: 0L)).append('\n')
        } else {
            out.append("network  could not be read\n")
        }
        out.append("auto     ").append(if (clock.autoTime) "on" else "off").append('\n')
        return out.toString().trimEnd()
    }

    fun enableAutoTime(context: Context): Boolean {
        try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.AUTO_TIME, 1)
            if (autoTime(context)) return true
        } catch (t: Throwable) {
            Log.i(TAG, "automatic time refused by the settings provider: $t")
        }
        RootShell.command("settings put global auto_time 1")
        val on = autoTime(context)
        Log.i(TAG, "automatic time after root: $on")
        return on
    }

    fun device(context: Context): String {
        val version = Updater.version(context)
        val out = StringBuilder()
        out.append("App Hub ").append(version.name).append(" (").append(version.code).append(") · ")
            .append(signature(context)).append('\n')
        out.append("Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        out.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        out.append("build ").append(Build.FINGERPRINT).append('\n')
        out.append("clock ").append(timeText(System.currentTimeMillis())).append(' ')
            .append(timeZone()).append(", automatic time ")
            .append(if (autoTime(context)) "on" else "off").append('\n')
        out.append("package ").append(context.packageName).append('\n')
        out.append("root ").append(if (RootShell.isAvailable()) "yes" else "no").append('\n')
        out.append("storage ").append(
            if (Files.access(context) == Access.ALLOWED) "open to this app" else "closed to this app"
        ).append('\n')
        val report = out.toString().trimEnd()
        Log.i(TAG, "device report: ${report.replace("\n", "; ")}")
        return report
    }

    fun timeText(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    fun timeZone(): String = TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT)

    fun skewText(skew: Long): String {
        val sign = if (skew < 0) "-" else "+"
        val seconds = kotlin.math.abs(skew) / 1000
        return when {
            seconds < 60 -> "$sign${seconds}s"
            seconds < 3600 -> "$sign${seconds / 60}m"
            seconds < 86400 -> "$sign${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "$sign${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        }
    }

    private fun probe(url: String, factory: SSLSocketFactory?): String = try {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "AppHub")
            if (factory != null) sslSocketFactory = factory
        }
        val code = connection.responseCode
        connection.disconnect()
        "answered $code"
    } catch (t: Throwable) {
        "refused (${cause(t)})"
    }

    private fun dateOf(url: String): Long? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        val code = connection.responseCode
        val header = connection.getHeaderField("Date")
        connection.disconnect()
        Log.i(TAG, "clock probe $url -> $code date=$header")
        header?.let { httpDate(it) }
    } catch (t: Throwable) {
        Log.i(TAG, "clock probe $url refused: $t")
        null
    }

    private fun httpDate(header: String): Long? = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(header)?.time
    } catch (t: Throwable) {
        null
    }

    private fun autoTime(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0) == 1
    } catch (t: Throwable) {
        false
    }

    private fun signature(context: Context): String {
        if (Updater.isReleasable(context)) return "release-signed"
        val digest = try {
            context.packageManager
                .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
                ?.firstOrNull()
                ?.toByteArray()
                ?.let { bytes ->
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                }
        } catch (t: Throwable) {
            null
        }
        return when {
            digest == null -> "signature unreadable"
            digest.equals(PLATFORM_CERT, ignoreCase = true) -> "platform-signed"
            else -> "another key (${digest.take(12)}…)"
        }
    }

    private fun cause(t: Throwable): String {
        var node: Throwable = t
        while (node.cause != null && node.cause !== node) node = node.cause!!
        val message = node.message?.takeIf { it.isNotBlank() }
        return if (message == null) node.javaClass.simpleName
        else "${node.javaClass.simpleName}: $message"
    }

    private const val TIMEOUT_MILLIS = 10_000
}
