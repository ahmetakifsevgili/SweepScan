package com.sweepscan

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a short text file for every crash, so a coworker can send it from Ayarlar → Hata raporu
 * instead of only saying "it closed". Nothing leaves the phone unless they share it.
 */
object CrashLog {

    private const val DIR = "crashes"
    const val KEEP = 5

    /** Wraps the default handler: the report is written first, then Android shows its usual dialog. */
    fun install(context: Context) {
        val app = context.applicationContext
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val now = System.currentTimeMillis()
                val text = format(now, version, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT, thread.name, error)
                write(dir(app), now, text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun reports(context: Context): List<File> = reports(dir(context))

    fun clear(context: Context) {
        reports(context).forEach { it.delete() }
    }

    /** All reports in one file under the shared cache folder, newest first, ready for a share sheet. */
    fun bundle(context: Context): File? {
        val files = reports(context).takeIf { it.isNotEmpty() } ?: return null
        val out = File(File(context.cacheDir, "share").apply { mkdirs() }, "sweepscan-hata-${stamp(System.currentTimeMillis())}.txt")
        out.writeText(files.joinToString("\n\n") { it.readText() })
        return out
    }

    // ---- Pure parts (unit-tested) -------------------------------------------------------------

    fun format(time: Long, version: String, device: String, sdk: Int, thread: String, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
        return buildString {
            appendLine("SweepScan $version · $device · Android API $sdk")
            appendLine("$when_ · thread $thread")
            appendLine(error.toString())
            append(trace.trimEnd())
            appendLine()
        }
    }

    /** Writes one report and keeps only the newest [KEEP]. */
    fun write(dir: File, time: Long, text: String): File {
        dir.mkdirs()
        val file = File(dir, "crash-${stamp(time)}-%03d.txt".format(time % 1000))
        file.writeText(text)
        reports(dir).drop(KEEP).forEach { it.delete() }
        return file
    }

    /** Newest first. */
    fun reports(dir: File): List<File> =
        dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }.orEmpty()

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun stamp(time: Long) = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(time))
}
