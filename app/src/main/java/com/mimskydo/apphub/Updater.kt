package com.mimskydo.apphub

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

data class Version(val name: String, val code: Long)

object Updater {

    private const val LATEST_RELEASE =
        "https://api.github.com/repos/mborjian/AppHub/releases/latest"

    // SHA-256 of the cert releases are signed with (apksigner verify --print-certs); a platform-signed build must not match it
    private const val RELEASE_CERT =
        "248f8404a7b83b64a5cba7f2f88db69f2e000e1359fec6277a928840e73b2a0c"

    private const val FILE_NAME = "update.apk"

    class Release(
        val version: Version,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val digestUrl: String?,
        val pageUrl: String,
    )

    sealed interface Check {
        data class Newer(val release: Release) : Check
        data class Latest(val version: Version) : Check

        data object Nothing : Check

        data class Trouble(val detail: String) : Check
    }

    enum class Refusal(val message: Int) {
        NOT_AN_APK(R.string.update_refused_file),
        NOT_OURS(R.string.update_refused_signature),
        OTHER_APP(R.string.update_refused_package),
        NOT_NEWER(R.string.update_refused_version),
        DIGEST(R.string.update_refused_digest),
    }

    sealed interface Download {
        data class OnDisk(val file: File) : Download
        data class Refused(val refusal: Refusal) : Download

        data class Failed(val detail: String) : Download
    }

    sealed interface Install {
        data object Silent : Install

        data object AskingTheSystem : Install

        data object DifferentSignature : Install

        data object NeedsPermission : Install

        data class Failed(val detail: String) : Install
    }

    fun version(context: Context): Version {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return Version(info.versionName ?: "?", info.longVersionCode)
    }

    fun isReleasable(context: Context): Boolean =
        certificateOfSelf(context)?.equals(RELEASE_CERT, ignoreCase = true) == true

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun check(context: Context): Check {
        val installed = version(context)
        val body = try {
            get(context, LATEST_RELEASE, "application/vnd.github+json") { connection ->
                val code = connection.responseCode
                when {
                    code == HttpURLConnection.HTTP_OK -> connection.inputStream
                        .bufferedReader().use { it.readText() }
                    code == HttpURLConnection.HTTP_NOT_FOUND -> null
                    else -> throw IOException("HTTP $code")
                }
            }
        } catch (e: IOException) {
            return Check.Trouble(context.getString(R.string.update_offline, e.message.orEmpty()))
        }

        if (body == null) return Check.Nothing

        val release = try {
            parse(body) ?: return Check.Trouble(context.getString(R.string.update_no_apk))
        } catch (e: Exception) {
            return Check.Trouble(context.getString(R.string.update_bad_release))
        }

        return if (release.version.code > installed.code) Check.Newer(release)
        else Check.Latest(installed)
    }

    private fun parse(body: String): Release? {
        val json = JSONObject(body)
        val version = versionOf(json.optString("tag_name")) ?: return null

        var apk: String? = null
        var apkName: String? = null
        var digestUrl: String? = null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            when {
                name.endsWith(".apk") -> {
                    apk = asset.optString("browser_download_url")
                    apkName = name
                }
                name.endsWith(".apk.sha256") -> digestUrl = asset.optString("browser_download_url")
            }
        }
        val url = apk ?: return null

        return Release(
            version = version,
            notes = notes(json.optString("body")),
            apkUrl = url,
            apkName = apkName ?: "AppHub.apk",
            digestUrl = digestUrl,
            pageUrl = json.optString("html_url"),
        )
    }

    fun versionOf(tag: String?): Version? {
        val parts = (tag ?: "").removePrefix("v").split('.')
        if (parts.size != 3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 || it > 99 }) return null
        return Version(
            name = numbers.joinToString("."),
            code = numbers[0] * 10_000L + numbers[1] * 100L + numbers[2],
        )
    }

    private fun notes(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) return ""
        val first = text.split("\n\n").first().trim()
        return if (first.length <= NOTES_LIMIT) first else first.take(NOTES_LIMIT).trimEnd() + "…"
    }

    fun download(context: Context, release: Release, onPercent: (Int) -> Unit): Download {
        val file = File(context.cacheDir, FILE_NAME)
        try {
            get(context, release.apkUrl, "application/octet-stream") { connection ->
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                val total = connection.contentLengthLong
                connection.inputStream.use { source ->
                    file.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        var last = -2
                        while (true) {
                            val read = source.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            written += read
                            val percent = if (total > 0) ((written * 100) / total).toInt() else -1
                            if (percent != last) {
                                last = percent
                                onPercent(percent)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            file.delete()
            return Download.Failed(
                context.getString(R.string.update_download_failed, e.message.orEmpty())
            )
        }

        val published = release.digestUrl?.let { url ->
            try {
                get(context, url, "text/plain") { it.inputStream.bufferedReader().use { it.readText() } }
            } catch (e: IOException) {
                null
            }
        }?.let { publishedDigestOf(it) }
        if (published != null && published != digestOf(file)) {
            file.delete()
            return Download.Refused(Refusal.DIGEST)
        }

        val installed = version(context)
        val refusal = when {
            !isOurs(context, file) -> Refusal.NOT_AN_APK
            !packageNameOf(context, file).equals(context.packageName) -> Refusal.OTHER_APP
            !certificateOf(context, file).equals(RELEASE_CERT, ignoreCase = true) ->
                Refusal.NOT_OURS
            versionOfArchive(context, file).code <= installed.code -> Refusal.NOT_NEWER
            else -> null
        }
        if (refusal != null) {
            file.delete()
            return Download.Refused(refusal)
        }
        return Download.OnDisk(file)
    }

    fun install(context: Context, file: File, version: String): Install {
        if (!isReleasable(context)) return Install.DifferentSignature

        if (RootShell.isAvailable()) {
            Prefs(context).pendingUpdate = version
            val shared = "/data/local/tmp/apphub-update.apk"
            val output = RootShell.command(
                "cp '${file.absolutePath}' $shared" +
                    " && chmod 644 $shared" +
                    " && pm install -r $shared" +
                    " ; rm -f $shared"
            )
            if (output != null && output.contains("Success")) return Install.Silent

            Prefs(context).pendingUpdate = null
            if (output != null) return Install.Failed(failureOf(output))
        }

        if (!canInstallPackages(context)) return Install.NeedsPermission
        return askTheSystem(context, file)
    }

    private fun failureOf(output: String): String =
        output.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("Failure") || it.contains("Exception") }
            ?: output.trim().lineSequence().lastOrNull().orEmpty()

    private fun askTheSystem(context: Context, file: File): Install = try {
        val apk = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(apk, PACKAGE_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Install.AskingTheSystem
    } catch (t: Throwable) {
        Install.Failed(t.message ?: t.javaClass.simpleName)
    }

    private const val PACKAGE_MIME = "application/vnd.android.package-archive"

    fun askForInstallPermission(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        return open(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri))
    }

    fun openPage(context: Context, release: Release): Boolean =
        open(context, Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun signersOf(context: Context, file: File) =
        context.packageManager
            .getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?.signingInfo
            ?.apkContentsSigners

    private fun isOurs(context: Context, file: File): Boolean = signersOf(context, file) != null

    private fun packageNameOf(context: Context, file: File): String? =
        context.packageManager
            .getPackageArchiveInfo(file.absolutePath, 0)
            ?.packageName

    private fun versionOfArchive(context: Context, file: File): Version {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        return Version(info?.versionName ?: "?", info?.longVersionCode ?: 0L)
    }

    private fun certificateOf(context: Context, file: File): String? =
        signersOf(context, file)?.firstOrNull()?.let { digestOf(it.toByteArray()) }

    private fun certificateOfSelf(context: Context): String? = try {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { digestOf(it.toByteArray()) }
    } catch (t: Throwable) {
        null
    }

    private fun <T> get(
        context: Context,
        url: String,
        accept: String,
        read: (HttpURLConnection) -> T,
    ): T {
        if (!isGithub(url)) throw IOException("not a GitHub address: $url")
        val factory = GithubTls.socketFactory(context)
        var target = url
        var hops = 0
        while (true) {
            val connection = open(target, accept, factory)
            try {
                val code = connection.responseCode
                val location = if (code in REDIRECTS) connection.getHeaderField("Location") else null
                if (location == null) return read(connection)
                if (++hops > MAX_HOPS) throw IOException("too many redirects")
                target = redirectTarget(target, location)
                    ?: throw IOException("redirected off GitHub: $location")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun open(url: String, accept: String, factory: SSLSocketFactory?): HttpURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "AppHub")
            setRequestProperty("Accept", accept)
            if (factory != null) sslSocketFactory = factory
        }

    internal fun redirectTarget(from: String, location: String): String? = try {
        val target = URL(URL(from), location).toString()
        if (isGithub(target)) target else null
    } catch (t: Throwable) {
        null
    }

    internal fun isGithub(url: String): Boolean = try {
        val parsed = URL(url)
        val host = parsed.host?.lowercase(Locale.US).orEmpty()
        parsed.protocol == "https" && (
            host == "github.com" ||
                host == "api.github.com" ||
                host == "githubusercontent.com" ||
                host.endsWith(".githubusercontent.com")
            )
    } catch (t: Throwable) {
        false
    }

    private const val MAX_HOPS = 5

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    private fun publishedDigestOf(asset: String): String? =
        asset.trim().split(Regex("\\s+")).firstOrNull()
            ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
            ?.lowercase()

    private fun digestOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val NOTES_LIMIT = 420
}
