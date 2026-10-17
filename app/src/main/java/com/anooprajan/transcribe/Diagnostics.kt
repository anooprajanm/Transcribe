package com.anooprajan.transcribe

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash information the user can share without adb. Native crashes in the speech engine
 * bypass Java exception handlers, so this relies on the OS exit history and the app's own
 * logcat, which survives the crash and is readable by the app without extra permissions.
 */
object Diagnostics {
    private const val PREFS = "diagnostics"
    private const val KEY_SEEN = "lastSeenCrash"

    /** Description of a crash the user has not dismissed yet, or null. */
    fun unseenCrash(context: Context): String? {
        val info = lastCrashInfo(context) ?: return null
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SEEN, 0)
        return if (info.timestamp > seen) describe(info) else null
    }

    fun dismissCrash(context: Context) {
        val ts = lastCrashInfo(context)?.timestamp ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SEEN, ts).apply()
    }

    /** Device info, recent exit reasons and the tail of the app's log. Blocking; call off the main thread. */
    fun report(context: Context): String = buildString {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("Transcribe ${pkg.versionName} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        appendLine("RAM total ${mem.totalMem / (1 shl 20)} MB, available ${mem.availMem / (1 shl 20)} MB")
        appendLine()
        if (Build.VERSION.SDK_INT >= 30) {
            appendLine("Recent exits:")
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { appendLine("- " + describe(it)) }
            appendLine()
        }
        appendLine("Log:")
        append(
            runCatching {
                val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText()
            }.getOrElse { "Could not read log: ${it.message}" },
        )
    }

    /** Writes the report to a file and opens the share sheet. Blocking part runs on the caller's thread. */
    fun shareIntent(context: Context): Intent {
        val file = File(context.filesDir, "diagnostics/transcribe-diagnostics.txt")
        file.parentFile!!.mkdirs()
        file.writeText(report(context))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Transcribe diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share diagnostics")
    }

    private fun lastCrashInfo(context: Context): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = context.getSystemService(ActivityManager::class.java)
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 5).firstOrNull {
            it.reason == ApplicationExitInfo.REASON_CRASH ||
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                it.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                it.reason == ApplicationExitInfo.REASON_ANR ||
                // The native engine calls exit(-1) on fatal errors.
                (it.reason == ApplicationExitInfo.REASON_EXIT_SELF && it.status != 0)
        }
    }

    private fun describe(info: ApplicationExitInfo): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "app crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
            ApplicationExitInfo.REASON_ANR -> "not responding"
            ApplicationExitInfo.REASON_SIGNALED -> "signal ${info.status}"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by user"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited (code ${info.status})"
            else -> "reason ${info.reason}"
        }
        val time = SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date(info.timestamp))
        val pss = info.pss / 1024
        return "$time $reason, ${pss} MB" + (info.description?.let { " · $it" } ?: "")
    }
}
