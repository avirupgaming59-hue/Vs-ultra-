package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.FileProvider
import com.example.data.local.AppDatabase
import com.example.security.SecureVault
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "StartupDiagnostics"
private const val PREFS_NAME = "vsshell_startup_diagnostics"
private const val KEY_LAST_ERROR_STAGE = "last_error_stage"
private const val KEY_LAST_ERROR_MESSAGE = "last_error_message"
private const val KEY_LAST_STACK_TRACE = "last_stack_trace"
private const val KEY_LAST_KNOWN_STATE = "last_known_state"
private const val KEY_CONSECUTIVE_CRASHES = "consecutive_crashes"
private const val KEY_LAST_CRASH_TIME_MS = "last_crash_time_ms"
private const val LOGS_DIR_NAME = "logs"
private const val LAST_CRASH_FILE_NAME = "last_crash.log"
private const val CRASH_HISTORY_FILE_NAME = "crash_history.log"
private const val EXPORT_SHARE_FILE_NAME = "vsshell-diagnostic-log.txt"
private const val MAX_HISTORY_BYTES = 128L * 1024L

data class StartupCrashReport(
    val stage: String,
    val message: String,
    val stackTrace: String,
    val lastKnownState: String,
    val consecutiveCrashes: Int,
    val timestampMs: Long,
    val logFilePath: String,
)

object StartupDiagnostics {

    @Volatile
    private var handlerInstalled = false

    @Volatile
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var lastKnownStateSnapshot: String = "Process starting"

    fun installUncaughtExceptionHandler(context: Context) {
        if (handlerInstalled) return
        synchronized(this) {
            if (handlerInstalled) return
            val appContext = context.applicationContext
            defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                val isMainThread = thread === Looper.getMainLooper().thread
                val isVmFatalError = throwable is VirtualMachineError

                if (!isMainThread && !isVmFatalError) {
                    // Prevent non-fatal background coroutine / socket / SDK threads from killing the app
                    Log.e(TAG, "Caught background thread exception on ${thread.name}", throwable)
                    recordCrash(
                        context = appContext,
                        stage = "Background (${thread.name})",
                        throwable = throwable,
                        incrementCrashCounter = false,
                    )
                    return@setDefaultUncaughtExceptionHandler
                }

                Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
                recordCrash(
                    context = appContext,
                    stage = if (isMainThread) "Main Thread" else "Fatal (${thread.name})",
                    throwable = throwable,
                    incrementCrashCounter = true,
                )

                val prev = defaultHandler
                if (prev != null) {
                    prev.uncaughtException(thread, throwable)
                }
            }
            handlerInstalled = true
        }
    }

    fun updateLastKnownState(context: Context, stateSummary: String, persistToPrefs: Boolean = false) {
        val clean = SecureVault.redactSensitiveText(stateSummary).trim().take(1_024)
        if (clean.isBlank()) return
        lastKnownStateSnapshot = clean
        if (persistToPrefs) {
            runCatching {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_KNOWN_STATE, clean)
                    .apply()
            }
        }
    }

    fun getLastKnownState(): String = lastKnownStateSnapshot

    fun getCrashLogPath(context: Context): String {
        val dir = File(context.applicationContext.filesDir, LOGS_DIR_NAME)
        return File(dir, LAST_CRASH_FILE_NAME).absolutePath
    }

    fun readLastCrashLogFile(context: Context): String {
        return runCatching {
            val file = File(File(context.applicationContext.filesDir, LOGS_DIR_NAME), LAST_CRASH_FILE_NAME)
            if (file.exists() && file.isFile) {
                file.readText(Charsets.UTF_8)
            } else {
                "No crash log recorded in ${file.absolutePath}"
            }
        }.getOrElse { err ->
            "Unable to read crash log: ${err.localizedMessage}"
        }
    }

    fun readCrashHistoryFile(context: Context): String {
        return runCatching {
            val file = File(File(context.applicationContext.filesDir, LOGS_DIR_NAME), CRASH_HISTORY_FILE_NAME)
            if (file.exists() && file.isFile) {
                file.readText(Charsets.UTF_8)
            } else {
                ""
            }
        }.getOrDefault("")
    }

    fun defaultExportFileName(nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        return "vsshell-crash-log-$stamp.txt"
    }

    fun formatDiagnosticReport(
        exportedAtMs: Long,
        packageName: String,
        versionName: String,
        versionCode: Long,
        deviceLabel: String,
        androidRelease: String,
        sdkInt: Int,
        lastKnownState: String,
        lastCrashLog: String,
        crashHistoryLog: String,
        extraRuntimeLogs: String = "",
    ): String {
        val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
            .format(Date(exportedAtMs))
        val safeState = SecureVault.redactSensitiveText(lastKnownState)
        val safeLastCrash = SecureVault.redactSensitiveText(lastCrashLog)
        val safeHistory = SecureVault.redactSensitiveText(crashHistoryLog)
        val safeRuntime = SecureVault.redactSensitiveText(extraRuntimeLogs)
        return buildString {
            appendLine("====================================================")
            appendLine("VS SHELL DIAGNOSTIC & CRASH LOG EXPORT")
            appendLine("====================================================")
            appendLine("Exported At: $formattedTime")
            appendLine("Package: $packageName ($versionName / code $versionCode)")
            appendLine("Device: $deviceLabel")
            appendLine("Android: $androidRelease (API $sdkInt)")
            appendLine("Last Known State: ${safeState.ifBlank { "Unavailable" }}")
            appendLine()
            appendLine("--- LAST CRASH / ERROR LOG ---")
            appendLine(safeLastCrash.ifBlank { "No crash log recorded." })
            if (safeHistory.isNotBlank()) {
                appendLine()
                appendLine("--- CRASH HISTORY LOG ---")
                appendLine(safeHistory.trim())
            }
            if (safeRuntime.isNotBlank()) {
                appendLine()
                appendLine("--- RUNTIME TERMINAL & CODE-SERVER LOGS ---")
                appendLine(safeRuntime.trim())
            }
            appendLine("====================================================")
        }
    }

    fun buildExportableDiagnosticReport(
        context: Context,
        extraRuntimeLogs: String = "",
    ): String {
        val appCtx = context.applicationContext
        val prefs = runCatching {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
        val persistedState = prefs?.getString(KEY_LAST_KNOWN_STATE, "").orEmpty()
        val effectiveState = lastKnownStateSnapshot.ifBlank { persistedState }.ifBlank { "Unavailable" }

        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.packageManager.getPackageInfo(
                    appCtx.packageName,
                    PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
            }
        }.getOrNull()
        val versionName = pkgInfo?.versionName ?: "unknown"
        val versionCode = if (pkgInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
        } else {
            0L
        }

        val pendingOrLastPrefsSummary = buildString {
            val stage = prefs?.getString(KEY_LAST_ERROR_STAGE, "").orEmpty()
            val message = prefs?.getString(KEY_LAST_ERROR_MESSAGE, "").orEmpty()
            val stack = prefs?.getString(KEY_LAST_STACK_TRACE, "").orEmpty()
            if (stage.isNotBlank() || message.isNotBlank()) {
                appendLine("Stage: $stage")
                appendLine("Error: $message")
                if (stack.isNotBlank()) {
                    appendLine(stack)
                }
            }
        }.trim()

        val lastCrashFileText = readLastCrashLogFile(appCtx)
        val combinedLastCrash = if (lastCrashFileText.startsWith("No crash log recorded") && pendingOrLastPrefsSummary.isNotBlank()) {
            pendingOrLastPrefsSummary
        } else {
            lastCrashFileText
        }

        return formatDiagnosticReport(
            exportedAtMs = System.currentTimeMillis(),
            packageName = appCtx.packageName,
            versionName = versionName,
            versionCode = versionCode,
            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            lastKnownState = effectiveState,
            lastCrashLog = combinedLastCrash,
            crashHistoryLog = readCrashHistoryFile(appCtx),
            extraRuntimeLogs = extraRuntimeLogs,
        )
    }

    fun exportReportToUri(
        context: Context,
        destinationUri: Uri,
        extraRuntimeLogs: String = "",
    ): Result<Int> {
        return runCatching {
            val report = buildExportableDiagnosticReport(context, extraRuntimeLogs)
            val bytes = report.toByteArray(Charsets.UTF_8)
            val resolver = context.applicationContext.contentResolver
            val stream = runCatching { resolver.openOutputStream(destinationUri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(destinationUri, "w")
                ?: error("Unable to open destination file for writing")
            stream.use { out ->
                out.write(bytes)
                out.flush()
            }
            bytes.size
        }
    }

    fun prepareShareableReportFile(
        context: Context,
        extraRuntimeLogs: String = "",
    ): Result<File> {
        return runCatching {
            val appCtx = context.applicationContext
            val logsDir = File(appCtx.filesDir, LOGS_DIR_NAME).apply { mkdirs() }
            val exportFile = File(logsDir, EXPORT_SHARE_FILE_NAME)
            val report = buildExportableDiagnosticReport(appCtx, extraRuntimeLogs)
            exportFile.writeText(report, Charsets.UTF_8)
            exportFile
        }
    }

    fun createShareCrashLogIntent(
        context: Context,
        extraRuntimeLogs: String = "",
    ): Result<Intent> {
        return runCatching {
            val appCtx = context.applicationContext
            val reportText = buildExportableDiagnosticReport(appCtx, extraRuntimeLogs)
            val logsDir = File(appCtx.filesDir, LOGS_DIR_NAME).apply { mkdirs() }
            val exportFile = File(logsDir, EXPORT_SHARE_FILE_NAME).apply {
                writeText(reportText, Charsets.UTF_8)
            }
            val authority = "${appCtx.packageName}.fileprovider"
            val fileUri = runCatching { FileProvider.getUriForFile(appCtx, authority, exportFile) }.getOrNull()

            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "VS Shell Crash & Diagnostic Log")
                putExtra(Intent.EXTRA_TEXT, reportText.take(64_000))
                if (fileUri != null) {
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    fun recordNonFatal(context: Context, stage: String, throwable: Throwable) {
        Log.e(TAG, "Non-fatal initialization error at $stage", throwable)
        recordCrash(
            context = context.applicationContext,
            stage = stage,
            throwable = throwable,
            incrementCrashCounter = false,
        )
    }

    fun recordStartupFailure(context: Context, stage: String, throwable: Throwable) {
        Log.e(TAG, "Startup failure at $stage", throwable)
        recordCrash(
            context = context.applicationContext,
            stage = stage,
            throwable = throwable,
            incrementCrashCounter = true,
        )
    }

    private fun recordCrash(
        context: Context,
        stage: String,
        throwable: Throwable,
        incrementCrashCounter: Boolean,
    ) {
        runCatching {
            val nowMs = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prevCount = prefs.getInt(KEY_CONSECUTIVE_CRASHES, 0)
            val stateSnapshot = SecureVault.redactSensitiveText(
                lastKnownStateSnapshot.ifBlank {
                    prefs.getString(KEY_LAST_KNOWN_STATE, "Unknown").orEmpty()
                },
            )
            val stack = SecureVault.redactSensitiveText(
                StringWriter().also { sw ->
                    throwable.printStackTrace(PrintWriter(sw))
                }.toString().take(8_192),
            )
            val msg = SecureVault.redactSensitiveText(
                (throwable.localizedMessage ?: throwable.javaClass.simpleName)
                    .ifBlank { "Unexpected startup error" },
            )

            prefs.edit()
                .putString(KEY_LAST_ERROR_STAGE, stage)
                .putString(KEY_LAST_ERROR_MESSAGE, msg)
                .putString(KEY_LAST_STACK_TRACE, stack)
                .putString(KEY_LAST_KNOWN_STATE, stateSnapshot)
                .putLong(KEY_LAST_CRASH_TIME_MS, nowMs)
                .apply {
                    if (incrementCrashCounter) {
                        putInt(KEY_CONSECUTIVE_CRASHES, prevCount + 1)
                    }
                }
                .commit()

            writeCrashLogFiles(
                context = context,
                timestampMs = nowMs,
                stage = stage,
                lastKnownState = stateSnapshot,
                message = msg,
                stackTrace = stack,
            )
        }
    }

    private fun writeCrashLogFiles(
        context: Context,
        timestampMs: Long,
        stage: String,
        lastKnownState: String,
        message: String,
        stackTrace: String,
    ) {
        runCatching {
            val logsDir = File(context.filesDir, LOGS_DIR_NAME).apply { mkdirs() }
            val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
                .format(Date(timestampMs))
            val reportText = buildString {
                appendLine("=== VS SHELL CRASH REPORT ===")
                appendLine("Timestamp: $formattedTime")
                appendLine("Stage: $stage")
                appendLine("Last Known State: $lastKnownState")
                appendLine("Error: $message")
                appendLine("--- Stack Trace ---")
                appendLine(stackTrace)
                appendLine("=============================")
            }

            File(logsDir, LAST_CRASH_FILE_NAME).writeText(reportText, Charsets.UTF_8)

            val historyFile = File(logsDir, CRASH_HISTORY_FILE_NAME)
            if (historyFile.exists() && historyFile.length() > MAX_HISTORY_BYTES) {
                val tail = historyFile.readText(Charsets.UTF_8).takeLast((MAX_HISTORY_BYTES / 2).toInt())
                historyFile.writeText(tail + "\n" + reportText, Charsets.UTF_8)
            } else {
                historyFile.appendText(reportText + "\n", Charsets.UTF_8)
            }
        }
    }

    fun getPendingCrashReport(context: Context): StartupCrashReport? {
        return runCatching {
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val count = prefs.getInt(KEY_CONSECUTIVE_CRASHES, 0)
            if (count <= 0) return@runCatching null
            val ts = prefs.getLong(KEY_LAST_CRASH_TIME_MS, 0L)
            // Ignore stale crash records older than 24 hours
            if (System.currentTimeMillis() - ts > 24L * 60L * 60L * 1000L) {
                clearCrashCounter(appCtx)
                return@runCatching null
            }
            StartupCrashReport(
                stage = prefs.getString(KEY_LAST_ERROR_STAGE, "Startup").orEmpty().ifBlank { "Startup" },
                message = prefs.getString(KEY_LAST_ERROR_MESSAGE, "Unknown error").orEmpty(),
                stackTrace = prefs.getString(KEY_LAST_STACK_TRACE, "").orEmpty(),
                lastKnownState = prefs.getString(KEY_LAST_KNOWN_STATE, "Unavailable").orEmpty(),
                consecutiveCrashes = count,
                timestampMs = ts,
                logFilePath = getCrashLogPath(appCtx),
            )
        }.getOrNull()
    }

    fun markStartupHealthy(context: Context) {
        clearCrashCounter(context)
    }

    fun clearCrashCounter(context: Context) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_CONSECUTIVE_CRASHES, 0)
                .commit()
        }
    }

    fun resetLocalCachesSafely(
        context: Context,
        resetCorruptedDatabase: Boolean = false,
    ) {
        val appCtx = context.applicationContext
        val clearWebAction = Runnable {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearWebAction.run()
        } else {
            runCatching { Handler(Looper.getMainLooper()).post(clearWebAction) }
        }
        runCatching {
            File(appCtx.filesDir, "datastore").deleteRecursively()
        }
        if (resetCorruptedDatabase) {
            runCatching {
                AppDatabase.resetInstanceForRecovery()
                appCtx.deleteDatabase("vsshell_database")
            }
        }
        runCatching {
            appCtx.cacheDir?.listFiles()?.forEach { child ->
                if (!child.name.equals("WebView", ignoreCase = true)) {
                    child.deleteRecursively()
                }
            }
        }
        clearCrashCounter(appCtx)
    }
}
