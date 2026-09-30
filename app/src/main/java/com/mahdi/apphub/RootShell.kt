package com.mahdi.apphub

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional root access. Nothing in the app requires it: every call returns
 * null/false when there is no usable `su` and the caller falls back to the
 * unprivileged path (`killBackgroundProcesses`).
 *
 * Two `su` flavours exist in the wild, so the probe tries both and remembers
 * which one answered:
 *
 *  * `su -c "<command>"`  - Magisk and most toolbox builds
 *  * `su <uid> <command…>` - AOSP's `su` (what `adb shell su 0 id` uses)
 *
 * Never call this on the main thread: a `su` that decides to ask a human
 * blocks until [TIMEOUT_SECONDS] elapse.
 */
object RootShell {

    private enum class Form { UNKNOWN, NONE, DASH_C, USER_ARG }

    @Volatile
    private var cached = Form.UNKNOWN

    private val suBinary: String by lazy {
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
            .firstOrNull { File(it).exists() } ?: "su"
    }

    fun isAvailable(): Boolean = form() != Form.NONE

    /**
     * Runs a whole shell command line as root and returns its output.
     *
     * Pipes and redirection are the point: the window-margins watcher reads the
     * resumed activity with a `dumpsys … | grep …` one-liner, and both `su`
     * flavours are handed a shell rather than a pre-split argv. Null means "no
     * usable root, or the command failed", which is the only distinction the
     * callers need - every root path here either falls back or does nothing.
     */
    fun command(shellCommand: String): String? = when (form()) {
        Form.DASH_C -> exec(listOf(suBinary, "-c", shellCommand))
        Form.USER_ARG -> exec(listOf(suBinary, "0", "sh", "-c", shellCommand))
        else -> null
    }

    fun forceStop(packageName: String): Boolean =
        run("am force-stop $packageName", listOf("am", "force-stop", packageName)) != null

    /** `ps -A -o NAME`, one process name per line (empty when no root). */
    fun processNames(): Set<String> =
        run("ps -A -o NAME", listOf("ps", "-A", "-o", "NAME"))
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toHashSet()
            ?: emptySet()

    /**
     * @param shellCommand the command line for the `su -c` form
     * @param argv         the same command pre-split for the `su <uid>` form
     */
    private fun run(shellCommand: String, argv: List<String>): String? = when (form()) {
        Form.DASH_C -> exec(listOf(suBinary, "-c", shellCommand))
        Form.USER_ARG -> exec(listOf(suBinary, "0") + argv)
        else -> null
    }

    private fun form(): Form {
        if (cached != Form.UNKNOWN) return cached
        synchronized(this) {
            if (cached == Form.UNKNOWN) {
                cached = when {
                    exec(listOf(suBinary, "-c", "id"))?.contains("uid=0") == true -> Form.DASH_C
                    exec(listOf(suBinary, "0", "id"))?.contains("uid=0") == true -> Form.USER_ARG
                    else -> Form.NONE
                }
            }
            return cached
        }
    }

    private fun exec(argv: List<String>): String? = try {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        // wait first, read afterwards: a `su` waiting for a confirmation
        // prompt produces no output at all, and readText() would block forever
        if (process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            if (process.exitValue() == 0) {
                process.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } else {
            process.destroyForcibly()
            null
        }
    } catch (t: Throwable) {
        null          // no su binary, no permission, seccomp, …
    }

    private const val TIMEOUT_SECONDS = 5L
}
