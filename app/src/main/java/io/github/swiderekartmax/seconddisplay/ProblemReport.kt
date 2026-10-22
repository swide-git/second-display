package io.github.swiderekartmax.seconddisplay

import android.content.Context
import android.net.Uri
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local diagnostics behind "Report a problem". The last crash is kept in a private file and
 * the report is only built when the user asks; nothing leaves the device unless they share it.
 */
object ProblemReport {
    private const val CRASH_FILE = "last-crash.txt"
    private const val ISSUES_URL = "https://github.com/swide-git/second-display/issues/new"
    private const val LOG_LINES = 600
    private const val MAX_LOG_CHARS = 200_000

    /** Records an uncaught exception before the default handler kills the process. */
    fun installCrashRecorder(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashRecorder) return
        Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(File(context.filesDir, CRASH_FILE), previous))
    }

    private class CrashRecorder(
        private val file: File,
        private val next: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                file.writeText("${timestamp()} on thread \"${thread.name}\"\n${error.stackTraceToString()}")
            }
            next?.uncaughtException(thread, error)
        }
    }

    fun hasLastCrash(context: Context): Boolean = File(context.filesDir, CRASH_FILE).exists()

    fun clearLastCrash(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
    }

    /** Version, device and Android release: short enough for an issue URL. */
    fun summary(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(0L)
        return buildString {
            appendLine("App: Second Display $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), page size ${pageSize / 1024} KB")
        }
    }

    /** Full report: summary, the last crash and this app's recent log. Blocking; call off the main thread. */
    fun build(context: Context): String = buildString {
        append(summary(context))
        appendLine("Created: ${timestamp()}")
        appendLine()
        val crash = File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
        appendLine("== Last crash ==")
        appendLine(crash ?: "none recorded")
        appendLine()
        appendLine("== Recent log (this app only) ==")
        appendLine(recentLog())
    }

    /** New GitHub issue with the summary filled in; the full report goes through the clipboard. */
    fun issueUri(context: Context, crashed: Boolean): Uri {
        val title = if (crashed) "The app stopped unexpectedly" else "Problem: "
        val body = buildString {
            appendLine("**What happened, and what did you expect?**")
            appendLine()
            appendLine()
            appendLine("**Device**")
            appendLine("```")
            append(summary(context))
            appendLine("```")
            appendLine()
            appendLine("**Diagnostics** (copied to the clipboard by the app; paste them here):")
            appendLine()
        }
        return Uri.parse("$ISSUES_URL?title=${encode(title)}&body=${encode(body)}")
    }

    // Since Android 4.1 an app reads only its own log entries, without READ_LOGS.
    private fun recentLog(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOG_LINES.toString())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        if (text.length > MAX_LOG_CHARS) text.takeLast(MAX_LOG_CHARS) else text
    }.getOrElse { "log unavailable: ${it.message}" }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
}
