package com.rotacore.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String
)

object UpdateManager {

    // Repositório oficial para releases e atualizações contínuas
    private const val GITHUB_REPO = "andrefonn/drifly"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    var pendingApkFile: File? = null

    fun checkForUpdates(
        context: Context,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onUpToDate: (currentVersion: String) -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentVersion = getCurrentVersionName(context)
                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "DriFly-App")

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    val tagName = json.optString("tag_name", "").removePrefix("v")
                    val releaseName = json.optString("name", "Nova Versão")
                    val body = json.optString("body", "Melhorias de desempenho e estabilidade.")

                    var apkUrl = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    if (apkUrl.isNotEmpty() && isNewerVersion(tagName, currentVersion)) {
                        val updateInfo = UpdateInfo(
                            versionName = tagName,
                            title = releaseName,
                            changelog = body,
                            downloadUrl = apkUrl
                        )
                        withContext(Dispatchers.Main) {
                            onUpdateAvailable(updateInfo)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onUpToDate(currentVersion)
                        }
                    }
                } else if (responseCode == 404) {
                    // Sem releases públicos no momento
                    withContext(Dispatchers.Main) {
                        onUpToDate(currentVersion)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError("Servidor de atualizações retornou código $responseCode")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Não foi possível verificar atualizações: ${e.localizedMessage ?: "Sem conexão"}")
                }
            }
        }
    }

    fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.connect()

                if (conn.responseCode !in 200..299) {
                    withContext(Dispatchers.Main) {
                        onError("Falha ao baixar arquivo (código ${conn.responseCode})")
                    }
                    return@launch
                }

                val fileLength = conn.contentLength
                val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val apkFile = File(downloadsDir, "DriFly_Update.apk")

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val data = ByteArray(8192)
                        var total: Long = 0
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) {
                                val progress = ((total * 100) / fileLength).toInt()
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                            output.write(data, 0, count)
                        }
                        output.flush()
                    }
                }

                withContext(Dispatchers.Main) {
                    onSuccess()
                    promptInstall(context, apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Erro ao baixar atualização: ${e.localizedMessage}")
                }
            }
        }
    }

    fun promptInstall(context: Context, apkFile: File) {
        if (!apkFile.exists()) return

        // Verifica se o app tem permissão para instalar outros apps no Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                pendingApkFile = apkFile
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        pendingApkFile = null

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }

    fun checkAndResumePendingInstall(context: Context): Boolean {
        val file = pendingApkFile ?: return false
        if (!file.exists()) {
            pendingApkFile = null
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (context.packageManager.canRequestPackageInstalls()) {
                promptInstall(context, file)
                return true
            }
        }
        return false
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val localParts = local.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
