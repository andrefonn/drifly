package com.drifly.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Envia o relatório de diagnóstico ([AppLogger.buildDiagnosticReport]) para um
 * endpoint HTTPS configurável (ex.: Google Apps Script conectado ao Google Drive
 * privado do usuário), permitindo que o Antigravity Nuvem consulte os logs
 * sem precisar de cabo USB/ADB.
 */
object CloudLogUploader {

    fun isConfigured(context: Context): Boolean {
        return PreferencesManager(context.applicationContext).cloudLogWebhookUrl.isNotBlank()
    }

    /**
     * Se houver um crash pendente da sessão anterior e o envio automático estiver
     * ativo com URL configurada, envia silenciosamente em segundo plano.
     */
    fun autoUploadPendingCrashIfConfigured(context: Context) {
        val appCtx = context.applicationContext
        val prefs = PreferencesManager(appCtx)
        if (!prefs.autoUploadCrashLogs || prefs.cloudLogWebhookUrl.isBlank()) return
        if (!AppLogger.hasPendingCrash(appCtx)) return

        uploadLogs(appCtx, reason = "auto_crash") { result ->
            result.onSuccess {
                AppLogger.i("CloudLogUploader", "Crash pendente enviado automaticamente para a nuvem.")
            }.onFailure { err ->
                AppLogger.w("CloudLogUploader", "Falha no envio automático de crash: ${err.message}")
            }
        }
    }

    fun uploadLogs(
        context: Context,
        reason: String = "manual",
        onResult: (Result<String>) -> Unit
    ) {
        val appCtx = context.applicationContext
        val prefs = PreferencesManager(appCtx)
        val webhookUrl = prefs.cloudLogWebhookUrl
        if (webhookUrl.isBlank()) {
            onResult(Result.failure(IllegalStateException("URL do webhook não configurada.")))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val report = AppLogger.buildDiagnosticReport(appCtx)
                val versionName = UpdateManager.getCurrentVersionName(appCtx)
                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

                val payload = JSONObject().apply {
                    put("token", prefs.cloudLogToken)
                    put("reason", reason)
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("appVersion", versionName)
                    put("timestamp", nowIso)
                    put("report", report)
                }.toString()

                val url = URL(webhookUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 15000
                    doOutput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("User-Agent", "DriFly-App/$versionName")
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                val code = conn.responseCode
                // Google Apps Script executa doPost() e responde 302 (redirect para script.googleusercontent.com) ou 200.
                val isAppsScriptRedirect = (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM) &&
                    webhookUrl.contains("script.google.com", ignoreCase = true)

                if (code in 200..299 || isAppsScriptRedirect) {
                    val body = try {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } catch (_: Exception) {
                        ""
                    }
                    // Se o Apps Script retornou JSON com { "ok": false, "error": "..." }, respeita o erro.
                    if (body.trimStart().startsWith("{")) {
                        val jsonResp = JSONObject(body)
                        if (jsonResp.has("ok") && !jsonResp.optBoolean("ok", true)) {
                            val errMsg = jsonResp.optString("error", "Erro retornado pelo webhook")
                            withContext(Dispatchers.Main) {
                                onResult(Result.failure(IllegalStateException(errMsg)))
                            }
                            return@launch
                        }
                    }

                    prefs.lastCloudLogUploadTimestamp = System.currentTimeMillis()
                    AppLogger.clearPendingCrash(appCtx)

                    val sizeKb = (report.toByteArray(Charsets.UTF_8).size / 1024).coerceAtLeast(1)
                    withContext(Dispatchers.Main) {
                        onResult(Result.success("Logs enviados (${sizeKb} KB)"))
                    }
                } else {
                    val errText = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    withContext(Dispatchers.Main) {
                        onResult(Result.failure(IllegalStateException("HTTP $code ${errText.take(120)}")))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            }
        }
    }
}
