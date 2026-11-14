package com.drifly.app

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registrador persistente de diagnóstico do DriFly.
 *
 * Além de emitir logs no Logcat do Android, grava um arquivo rotativo
 * em disco (files/drifly_diag.log) para que os logs de um turno inteiro
 * de corridas não sejam perdidos quando o buffer de memória do Android rotacionar.
 * Também captura crashes não tratados (UncaughtException) e gera relatório
 * completo para envio à nuvem (Google Drive / Antigravity Nuvem).
 */
object AppLogger {
    private const val TAG = "DriFlyDiag"
    private const val MAX_LOG_SIZE_BYTES = 1024 * 1024 // 1 MB
    private const val CRASH_PREFS = "drifly_diag_meta"
    private const val KEY_PENDING_CRASH = "has_pending_crash"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"

    private var logFile: File? = null
    private var oldLogFile: File? = null

    @Volatile
    private var crashHandlerInstalled = false

    fun init(context: Context) {
        try {
            val dir = context.applicationContext.filesDir
            logFile = File(dir, "drifly_diag.log")
            oldLogFile = File(dir, "drifly_diag.log.old")
        } catch (_: Exception) {}
    }

    /**
     * Instala um handler global para capturar exceções não tratadas (crashes)
     * e gravar a stacktrace no log antes do processo encerrar.
     */
    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        synchronized(this) {
            if (crashHandlerInstalled) return
            val appCtx = context.applicationContext
            init(appCtx)
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val stack = Log.getStackTraceString(throwable)
                    log("CrashHandler", "FATAL", "Uncaught exception on thread '${thread.name}':\n$stack")
                    appCtx.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_PENDING_CRASH, true)
                        .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
                        .commit()
                } catch (_: Throwable) {}
                previousHandler?.uncaughtException(thread, throwable)
            }
            crashHandlerInstalled = true
        }
    }

    fun hasPendingCrash(context: Context): Boolean {
        return try {
            context.applicationContext
                .getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PENDING_CRASH, false)
        } catch (_: Exception) {
            false
        }
    }

    fun clearPendingCrash(context: Context) {
        try {
            context.applicationContext
                .getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING_CRASH, false)
                .apply()
        } catch (_: Exception) {}
    }

    fun d(tag: String, message: String) {
        log(tag, "DEBUG", message)
    }

    fun i(tag: String, message: String) {
        log(tag, "INFO", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val extra = throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""
        log(tag, "WARN", "$message$extra")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val extra = throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""
        log(tag, "ERROR", "$message$extra")
    }

    private fun log(tag: String, level: String, message: String) {
        try {
            when (level) {
                "ERROR", "FATAL" -> Log.e(tag, message)
                "WARN" -> Log.w(tag, message)
                "DEBUG" -> Log.d(tag, message)
                else -> Log.i(tag, message)
            }
        } catch (_: Throwable) {}

        try {
            val file = logFile ?: return
            synchronized(this) {
                if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                    val backup = oldLogFile ?: File(file.parent, "drifly_diag.log.old")
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                file.appendText("[$time] [$level] [$tag] $message\n")
            }
        } catch (_: Throwable) {}
    }

    fun getLogSizeBytes(): Long {
        return try {
            val cur = logFile?.takeIf { it.exists() }?.length() ?: 0L
            val old = oldLogFile?.takeIf { it.exists() }?.length() ?: 0L
            cur + old
        } catch (_: Exception) {
            0L
        }
    }

    fun getLogContent(): String {
        return try {
            val file = logFile ?: return ""
            synchronized(this) {
                if (file.exists()) file.readText() else ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun readTail(file: File?, maxBytes: Int): String {
        if (file == null || !file.exists()) return ""
        return try {
            val len = file.length()
            if (len <= maxBytes) {
                file.readText()
            } else {
                file.inputStream().use { input ->
                    input.skip(len - maxBytes)
                    "... (truncado, mostrando últimos ${maxBytes / 1024} KB de ${len / 1024} KB) ...\n" +
                        input.bufferedReader().readText()
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Monta o relatório completo de diagnóstico (cabeçalho do aparelho, status
     * das permissões/serviços e logs recentes) pronto para envio à nuvem.
     */
    fun buildDiagnosticReport(context: Context): String {
        val appCtx = context.applicationContext
        init(appCtx)
        val prefs = PreferencesManager(appCtx)

        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val versionName = try {
            appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        val versionCode = try {
            val info = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (_: Exception) { -1L }

        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(appCtx) else true
        val hasBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(appCtx.packageName) ?: false
        } else true
        val hasNotif = try { OemCompatibilityHelper.isNotificationListenerEnabled(appCtx) } catch (_: Exception) { false }
        val hasA11y = try {
            val enabled = Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            enabled.contains(appCtx.packageName, ignoreCase = true)
        } catch (_: Exception) { false }

        val sb = StringBuilder()
        sb.appendLine("==========================================")
        sb.appendLine("DRIFLY DIAGNOSTIC REPORT")
        sb.appendLine("==========================================")
        sb.appendLine("GeneratedAt: $nowStr")
        sb.appendLine("AppVersion: $versionName (code $versionCode)")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("MonitoringActive: ${prefs.isMonitoringActive}")
        sb.appendLine("PendingCrashFlag: ${hasPendingCrash(appCtx)}")
        sb.appendLine("Permissions: overlay=$hasOverlay, a11y=$hasA11y, notif=$hasNotif, batteryUnrestricted=$hasBattery")
        sb.appendLine("==========================================")
        sb.appendLine()

        val oldText = synchronized(this) { readTail(oldLogFile, 150 * 1024) }
        if (oldText.isNotEmpty()) {
            sb.appendLine("--- PREVIOUS LOG (drifly_diag.log.old) ---")
            sb.appendLine(oldText)
            sb.appendLine()
        }

        val curText = synchronized(this) { readTail(logFile, 450 * 1024) }
        sb.appendLine("--- CURRENT LOG (drifly_diag.log) ---")
        if (curText.isNotEmpty()) {
            sb.appendLine(curText)
        } else {
            sb.appendLine("(vazio - nenhum evento registrado ainda)")
        }

        return sb.toString()
    }

    /**
     * Gera arquivo temporário em cacheDir para compartilhamento via FileProvider
     * (ex.: "Salvar no Google Drive" pelo menu de compartilhar do Android).
     */
    fun exportReportToCacheFile(context: Context): File {
        val report = buildDiagnosticReport(context)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.cacheDir, "drifly_diag_$stamp.txt")
        outFile.writeText(report)
        return outFile
    }

    fun clear() {
        try {
            synchronized(this) {
                logFile?.delete()
                oldLogFile?.delete()
            }
        } catch (_: Exception) {}
    }
}
