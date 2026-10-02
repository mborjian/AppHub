package com.mimskydo.apphub

import java.io.File
import java.util.concurrent.TimeUnit

object RootShell {

    private enum class Form { UNKNOWN, NONE, DASH_C, USER_ARG }

    @Volatile
    private var cached = Form.UNKNOWN

    private val suBinary: String by lazy {
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
            .firstOrNull { File(it).exists() } ?: "su"
    }

    fun isAvailable(): Boolean = form() != Form.NONE

    fun command(shellCommand: String): String? = when (form()) {
        Form.DASH_C -> exec(listOf(suBinary, "-c", shellCommand))
        Form.USER_ARG -> exec(listOf(suBinary, "0", "sh", "-c", shellCommand))
        else -> null
    }

    fun forceStop(packageName: String): Boolean =
        run("am force-stop $packageName", listOf("am", "force-stop", packageName)) != null

    fun uninstall(packageName: String): Boolean =
        run("pm uninstall $packageName", listOf("pm", "uninstall", packageName))
            ?.contains("Success") == true

    fun processNames(): Set<String> =
        run("ps -A -o NAME", listOf("ps", "-A", "-o", "NAME"))
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toHashSet()
            ?: emptySet()

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
        null
    }

    private const val TIMEOUT_SECONDS = 5L
}
