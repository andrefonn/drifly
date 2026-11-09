package com.drifly.app

import android.content.Context
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
 */
object AppLogger {
    private const val TAG = "DriFlyDiag"
    private const val MAX_LOG_SIZE_BYTES = 1024 * 1024 // 1 MB
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            logFile = File(context.applicationContext.filesDir, "drifly_diag.log")
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
                "ERROR" -> Log.e(tag, message)
                "WARN" -> Log.w(tag, message)
                "DEBUG" -> Log.d(tag, message)
                else -> Log.i(tag, message)
            }
        } catch (_: Throwable) {}

        try {
            val file = logFile ?: return
            synchronized(this) {
                if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                    val backup = File(file.parent, "drifly_diag.log.old")
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                file.appendText("[$time] [$level] [$tag] $message\n")
            }
        } catch (_: Throwable) {}
    }

    fun getLogContent(): String {
        return try {
            val file = logFile ?: return ""
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun clear() {
        try {
            logFile?.delete()
        } catch (_: Exception) {}
    }
}
