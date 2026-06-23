package com.example.offlinetts

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught-exception handler.
 *
 * Instead of the app silently vanishing ("opens then closes in a second"),
 * every fatal crash is:
 *   1. written to a human-readable log file in the app's external files dir, and
 *   2. printed to Logcat with the tag [TAG] so you can grab it over adb.
 *
 * Pull the crash log from a connected device with:
 *   adb shell run-as com.example.offlinetts cat files/last_crash.txt
 * or (external, no root/run-as needed):
 *   adb pull /sdcard/Android/data/com.example.offlinetts/files/crash/last_crash.txt
 */
object CrashReporter {

    const val TAG = "OfflineTTS-Crash"
    private const val CRASH_DIR = "crash"
    private const val CRASH_FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(appContext, thread, throwable) }
            // Always surface it in Logcat too.
            Log.e(TAG, "FATAL on thread '${thread.name}'", throwable)
            // Hand off to the default handler so the system still shows its dialog
            // / records its own trace and terminates the process cleanly.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the directory where crash logs are stored (may be null very early). */
    fun crashDir(context: Context): File? =
        context.getExternalFilesDir(CRASH_DIR) ?: File(context.filesDir, CRASH_DIR)

    /** Convenience accessor used by an in-app "view last crash" affordance. */
    fun lastCrashText(context: Context): String? {
        val file = File(crashDir(context), CRASH_FILE)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context) ?: return
        if (!dir.exists()) dir.mkdirs()

        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString()

        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            appendLine("==== Offline Reader crash report ====")
            appendLine("Time      : $when_")
            appendLine("Thread    : ${thread.name}")
            appendLine("App ver   : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device    : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("-------------------------------------")
            appendLine(stack)
        }

        File(dir, CRASH_FILE).writeText(report)
    }
}
