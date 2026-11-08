package org.scientificnet.mobile.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Catches uncaught exceptions, saves a report and shows [CrashActivity] instead of the
 * system "app has stopped" dialog. If the screen can't be shown right away (e.g. the crash
 * happened in the background), the saved report is shown on the next launch.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"

    private fun dir(context: Context) = File(context.filesDir, "crash").apply { mkdirs() }

    /** A crash the user hasn't seen yet. */
    fun pendingFile(context: Context) = File(dir(context), "pending.txt")

    /** The most recent crash the user has seen. */
    fun lastFile(context: Context) = File(dir(context), "last.txt")

    fun hasPending(context: Context) = pendingFile(context).exists()

    fun lastReport(context: Context): String? = lastFile(context).takeIf { it.exists() }?.readText()

    /** Marks the pending report as seen and returns it. */
    fun takePending(context: Context): String? {
        val pending = pendingFile(context)
        if (!pending.exists()) return null
        val text = pending.readText()
        val last = lastFile(context)
        last.delete()
        if (!pending.renameTo(last)) {
            last.writeText(text)
            pending.delete()
        }
        return text
    }

    fun isCrashProcess(context: Context): Boolean = processName(context).endsWith(":crash")

    private fun processName(context: Context): String =
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrDefault(context.packageName)
        }

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Log.e(TAG, "Uncaught exception in ${thread.name}", error)
                pendingFile(app).writeText(buildReport(app, thread, error))
                app.startActivity(
                    Intent(app, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } catch (inner: Throwable) {
                // Reporting failed: fall back to the normal system behaviour.
                previous?.uncaughtException(thread, error)
            }
        }
    }

    fun buildReport(context: Context, thread: Thread, error: Throwable): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()})"
        }.getOrDefault("?")
        return formatReport(
            time = ZonedDateTime.now(),
            appVersion = version,
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            threadName = thread.name,
            error = error,
        )
    }

    /** Pure formatting, unit-testable. */
    fun formatReport(
        time: ZonedDateTime,
        appVersion: String,
        android: String,
        device: String,
        threadName: String,
        error: Throwable,
    ): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("MySNS crash report")
            appendLine("Time:    ${time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
            appendLine("App:     $appVersion")
            appendLine("Android: $android")
            appendLine("Device:  $device")
            appendLine("Thread:  $threadName")
            appendLine("Error:   ${error.javaClass.name}: ${error.message}")
            appendLine()
            append(trace)
        }
    }
}
