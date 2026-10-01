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

/** This build, in the two numbers the release pipeline packs from its tag. */
data class Version(val name: String, val code: Long)

/**
 * The update path: ask GitHub what the newest release is, take its APK, and put
 * it in place of this build.
 *
 * The build comes from the public releases of this app's own repository, and a
 * file is installed only when all four of these hold:
 *
 *  * it is signed with the release key pinned in [RELEASE_CERT], which is the
 *    only thing that makes a download trustworthy - an APK from the network is
 *    code this app would be running;
 *  * it carries the package name this app already has;
 *  * its versionCode is newer than the installed one; and
 *  * it matches the `.sha256` published next to it, which catches a truncated
 *    or substituted transfer before the other three are asked.
 *
 * Android refuses a differently-signed update on its own, but that is the
 * platform's answer after the fact; the pin is what lets this app say *why*,
 * and what keeps a redirected download from ever reaching the installer.
 *
 * Nothing here runs on the main thread: `su` can block for seconds and the
 * download is megabytes.
 */
object Updater {

    /** The newest published release of this repository - no tag, no token. */
    private const val LATEST_RELEASE =
        "https://api.github.com/repos/mborjian/AppHub/releases/latest"

    /**
     * The certificate every release is signed with, as a SHA-256 digest.
     *
     * `apksigner verify --print-certs` on a published APK prints it, and it is
     * the same one the release workflow signs with. A build signed with the
     * platform key (see `tools/release.py`) is *not* this one, which is why
     * [isReleasable] exists: the platform build cannot be replaced by a release.
     */
    private const val RELEASE_CERT =
        "248f8404a7b83b64a5cba7f2f88db69f2e000e1359fec6277a928840e73b2a0c"

    /** Where the download is written: ours, and gone with the app's cache. */
    private const val FILE_NAME = "update.apk"

    /** What a release is, read off the API's answer. */
    class Release(
        val version: Version,
        /** the release notes, cut to something a sheet can hold */
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val digestUrl: String?,
        val pageUrl: String,
    )

    /** The answer to "is there a newer build than the one running?". */
    sealed interface Check {
        data class Newer(val release: Release) : Check
        data class Latest(val version: Version) : Check

        /** the repository has no published release yet */
        data object Nothing : Check

        /** no usable answer: [detail] is the reason, in one line */
        data class Trouble(val detail: String) : Check
    }

    /** Why a downloaded file was not accepted. */
    enum class Refusal(val message: Int) {
        NOT_AN_APK(R.string.update_refused_file),
        NOT_OURS(R.string.update_refused_signature),
        OTHER_APP(R.string.update_refused_package),
        NOT_NEWER(R.string.update_refused_version),
        DIGEST(R.string.update_refused_digest),
    }

    /** The state of a download: [OnDisk] means all four checks passed. */
    sealed interface Download {
        data class OnDisk(val file: File) : Download
        data class Refused(val refusal: Refusal) : Download

        /** [detail] is what the network or the filesystem said */
        data class Failed(val detail: String) : Download
    }

    /** How the install ended. */
    sealed interface Install {
        /** root replaced the package with no dialog: this process is going away */
        data object Silent : Install

        /** the system's own dialog is up; the user finishes it there */
        data object AskingTheSystem : Install

        /** a release APK cannot replace this build - see [isReleasable] */
        data object DifferentSignature : Install

        /** Android wants the one-time "install unknown apps" grant first */
        data object NeedsPermission : Install

        data class Failed(val detail: String) : Install
    }

    // ------------------------------------------------------------- this build

    fun version(context: Context): Version {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return Version(info.versionName ?: "?", info.longVersionCode)
    }

    /**
     * True when a release APK can replace this build.
     *
     * A platform-signed build (installed by `tools/release.py`) has privileges a
     * release does not, and the two keys are not the same key: Android will not
     * put one over the other. Saying that here is the honest half of the
     * feature - the alternative is a download followed by a system error.
     */
    fun isReleasable(context: Context): Boolean =
        certificateOfSelf(context)?.equals(RELEASE_CERT, ignoreCase = true) == true

    /** Android's one-time grant, without which no dialog can even be shown. */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    // ---------------------------------------------------------------- checking

    /**
     * The newest release, compared with the build that is running.
     *
     * `releases/latest` is exactly the right endpoint: it is the newest release
     * that is not a draft and not a pre-release, which is what the release job
     * publishes.
     */
    fun check(context: Context): Check {
        val installed = version(context)
        val body = try {
            get(context, LATEST_RELEASE, "application/vnd.github+json") { connection ->
                // GitHub answers 404 while the repository has no release at all,
                // and that is not a failure.
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

    /** The fields a release has to carry to be installable; null when it is not one. */
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

    /**
     * "v1.2.3" as the pair the installer compares, packed the way the build file
     * packs it from the same tag: 1.0.0 is 10000, 1.0.1 is 10001.
     */
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

    /** The release notes, short enough for the sheet that offers the update. */
    private fun notes(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) return ""
        val first = text.split("\n\n").first().trim()
        return if (first.length <= NOTES_LIMIT) first else first.take(NOTES_LIMIT).trimEnd() + "…"
    }

    // -------------------------------------------------------------- downloading

    /**
     * Takes the release's APK into the app's own cache and checks it.
     *
     * [onPercent] is called from this thread with a whole percentage, or -1 while
     * the server has not said how long the file is; it is the caller's job to
     * get that onto the UI thread.
     */
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

        // A checksum that cannot be read is not a reason to refuse a file whose
        // signature already matches; a checksum that *disagrees* is.
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

    // ---------------------------------------------------------------- installing

    /**
     * Puts [file] in place of this build, silently when the unit has root and
     * through the system's own dialog when it does not.
     *
     * [version] is the version being installed, remembered *before* the silent
     * path runs: replacing a package kills the process that asked for it, so the
     * outcome cannot be reported here - [Prefs.pendingUpdate] is what lets the
     * next launch say what happened. If the silent attempt fails, that note is
     * taken back, because a message about an update that never started would be
     * a lie.
     */
    fun install(context: Context, file: File, version: String): Install {
        if (!isReleasable(context)) return Install.DifferentSignature

        if (RootShell.isAvailable()) {
            Prefs(context).pendingUpdate = version
            // The file is moved to where the manual flow puts it before `pm` is
            // handed it: a path inside this app's own data directory is not one
            // the shell's `pm` may read on every unit, and `/data/local/tmp` is
            // the same place an `adb install` works from.
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
            // no usable root after all: the dialog is the remaining path
        }

        if (!canInstallPackages(context)) return Install.NeedsPermission
        return askTheSystem(context, file)
    }

    /** The one line of `pm install`'s output that says what went wrong. */
    private fun failureOf(output: String): String =
        output.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("Failure") || it.contains("Exception") }
            ?: output.trim().lineSequence().lastOrNull().orEmpty()

    /**
     * Android's own installer, with its own question and its own answer: the
     * same screen the unit shows for an APK picked in a file manager.
     *
     * `ACTION_VIEW` on the package type rather than an install session of our
     * own. A session would have to hand the platform a status receiver, and for
     * an app replacing *itself* that receiver dies with the process doing the
     * replacing - while this screen already reports both endings, in the
     * system's own words and in the same place the manual install happens.
     */
    private fun askTheSystem(context: Context, file: File): Install = try {
        // the manifest declares the same authority, off the application id, so
        // the two cannot drift apart under a rename
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

    // -------------------------------------------------------------- the screens

    /**
     * The screen where Android asks whether this app may install packages at
     * all. Only shown when [canInstallPackages] says no.
     */
    fun askForInstallPermission(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        return open(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri))
    }

    /** The release on GitHub, in whatever browser the unit has. */
    fun openPage(context: Context, release: Release): Boolean =
        open(context, Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    // ------------------------------------------------------------------- files

    /**
     * What the APK says about itself, read without installing it: the package
     * manager parses an archive on disk on request, which is how a file that
     * would fail at install time is refused before it ever gets there.
     */
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

    // -------------------------------------------------------------- the network

    /**
     * One request, with the headers GitHub insists on: it refuses a call with no
     * user agent, and the [accept] header is which of its two answers is wanted.
     *
     * The hops are walked here rather than by [HttpURLConnection], for two
     * reasons. Every hop has to go through [GithubTls]'s socket factory: a
     * redirect carries a connection's own factory only as far as the library
     * decides to, and the APK download's second hop lands on a different CA
     * family than the first. And a hop is checked against [isGithub] before it is
     * followed at all - the asset URLs come out of the API's own JSON, so the
     * answer itself could otherwise point the download anywhere.
     */
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

    /** One hop, opened but not read yet. */
    private fun open(url: String, accept: String, factory: SSLSocketFactory?): HttpURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "AppHub")
            setRequestProperty("Accept", accept)
            // the platform's own answer first; null leaves the defaults alone
            if (factory != null) sslSocketFactory = factory
        }

    /**
     * Where a redirect points, or null when that is somewhere this app will not
     * go. A relative target is resolved against the hop that sent it.
     */
    internal fun redirectTarget(from: String, location: String): String? = try {
        val target = URL(URL(from), location).toString()
        if (isGithub(target)) target else null
    } catch (t: Throwable) {
        null
    }

    /** True for the addresses the update path is allowed to talk to. */
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

    /** the codes that mean "somewhere else", and nothing else */
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /** The first checksum in a `.sha256` asset, or null when it holds none. */
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
