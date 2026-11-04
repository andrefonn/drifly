package com.rotacore.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupManager {

    private const val BACKUP_DIR_NAME = "DriFly_Backups"

    fun exportBackup(context: Context): File {
        val db = DatabaseHelper(context)
        val prefs = PreferencesManager(context)

        val rootJson = JSONObject()
        rootJson.put("app", "DriFly")
        rootJson.put("backup_version", 1)
        val now = System.currentTimeMillis()
        rootJson.put("timestamp", now)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        rootJson.put("date_formatted", dateFormat.format(Date(now)))

        // 1. Preferências e Metas
        val prefsJson = JSONObject().apply {
            put("fuel_price", prefs.fuelPrice.toDouble())
            put("fuel_consumption", prefs.fuelConsumption.toDouble())
            put("maintenance_cost_km", prefs.maintenanceCostPerKm.toDouble())
            put("auto_accept_enabled", prefs.autoAcceptEnabled)
            put("auto_decline_enabled", prefs.autoDeclineEnabled)
            put("min_rate_km", prefs.minRatePerKm.toDouble())
            put("max_distance_km", prefs.maxDistanceKm.toDouble())
            put("min_passenger_rating", prefs.minPassengerRating.toDouble())
            put("is_motorcycle_mode", prefs.isMotorcycleMode)
            put("overlay_position", prefs.overlayPosition)
            put("goal_type", prefs.goalType)
            put("goal_target_amount", prefs.goalTargetAmount.toDouble())
        }
        rootJson.put("preferences", prefsJson)

        // 2. Histórico de Corridas
        val rides = db.getAllRides()
        val ridesArray = JSONArray()
        for (r in rides) {
            val rObj = JSONObject().apply {
                put("platform", r.platform)
                put("gross_price", r.grossPrice)
                put("distance_km", r.distanceKm)
                put("net_profit", r.netProfit)
                put("timestamp", r.timestamp)
            }
            ridesArray.put(rObj)
        }
        rootJson.put("rides", ridesArray)

        // 3. Histórico de Despesas
        val expenses = db.getAllExpenses()
        val expArray = JSONArray()
        for (e in expenses) {
            val eObj = JSONObject().apply {
                put("category", e.category)
                put("amount", e.amount)
                put("description", e.description)
                put("timestamp", e.timestamp)
            }
            expArray.put(eObj)
        }
        rootJson.put("expenses", expArray)

        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val fileName = "drifly_backup_${fileTimestamp}.json"
        val jsonString = rootJson.toString(2)

        // Salva na pasta pública Downloads/DriFly_Backups
        val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val backupDir = File(publicDownloadsDir, BACKUP_DIR_NAME)
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }

        val targetFile = File(backupDir, fileName)
        try {
            FileOutputStream(targetFile).use { fos ->
                fos.write(jsonString.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // Fallback para armazenamento de arquivos do app caso permissão externa falhe
            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), BACKUP_DIR_NAME)
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val fallbackFile = File(fallbackDir, fileName)
            fallbackFile.writeText(jsonString, Charsets.UTF_8)
            prefs.lastBackupTimestamp = now
            prefs.lastBackupPath = fallbackFile.absolutePath
            return fallbackFile
        }

        // Indexa o arquivo para aparecer imediatamente no app Arquivos e na pasta Downloads sem gerar cópia duplicada
        try {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf("application/json"),
                null
            )
        } catch (ignored: Exception) {}

        prefs.lastBackupTimestamp = now
        prefs.lastBackupPath = targetFile.absolutePath
        return targetFile
    }

    fun importBackup(context: Context, jsonString: String): Result<Int> {
        return try {
            val root = JSONObject(jsonString)
            if (!root.has("rides") && !root.has("expenses") && !root.has("preferences")) {
                return Result.failure(IllegalArgumentException("Arquivo de backup inválido ou incompatível."))
            }

            val db = DatabaseHelper(context)
            val prefs = PreferencesManager(context)

            // Restaura preferências se existirem
            if (root.has("preferences")) {
                val p = root.getJSONObject("preferences")
                if (p.has("fuel_price")) prefs.fuelPrice = p.getDouble("fuel_price").toFloat()
                if (p.has("fuel_consumption")) prefs.fuelConsumption = p.getDouble("fuel_consumption").toFloat()
                if (p.has("maintenance_cost_km")) prefs.maintenanceCostPerKm = p.getDouble("maintenance_cost_km").toFloat()
                if (p.has("auto_accept_enabled")) prefs.autoAcceptEnabled = p.getBoolean("auto_accept_enabled")
                if (p.has("auto_decline_enabled")) prefs.autoDeclineEnabled = p.getBoolean("auto_decline_enabled")
                if (p.has("min_rate_km")) prefs.minRatePerKm = p.getDouble("min_rate_km").toFloat()
                if (p.has("max_distance_km")) prefs.maxDistanceKm = p.getDouble("max_distance_km").toFloat()
                if (p.has("min_passenger_rating")) prefs.minPassengerRating = p.getDouble("min_passenger_rating").toFloat()
                if (p.has("is_motorcycle_mode")) prefs.isMotorcycleMode = p.getBoolean("is_motorcycle_mode")
                if (p.has("overlay_position")) prefs.overlayPosition = p.getString("overlay_position")
                if (p.has("goal_type")) prefs.goalType = p.getString("goal_type")
                if (p.has("goal_target_amount")) prefs.goalTargetAmount = p.getDouble("goal_target_amount").toFloat()
            }

            var totalRestored = 0

            // Restaura Corridas
            if (root.has("rides")) {
                val ridesArray = root.getJSONArray("rides")
                val ridesList = mutableListOf<RideRecord>()
                for (i in 0 until ridesArray.length()) {
                    val item = ridesArray.getJSONObject(i)
                    ridesList.add(
                        RideRecord(
                            platform = item.optString("platform", "CORRIDA"),
                            grossPrice = item.optDouble("gross_price", 0.0),
                            distanceKm = item.optDouble("distance_km", 0.0),
                            netProfit = item.optDouble("net_profit", 0.0),
                            timestamp = item.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
                db.restoreRides(ridesList, clearExisting = false)
                totalRestored += ridesList.size
            }

            // Restaura Despesas
            if (root.has("expenses")) {
                val expArray = root.getJSONArray("expenses")
                val expList = mutableListOf<ExpenseItem>()
                for (i in 0 until expArray.length()) {
                    val item = expArray.getJSONObject(i)
                    expList.add(
                        ExpenseItem(
                            category = item.optString("category", "Geral"),
                            amount = item.optDouble("amount", 0.0),
                            description = item.optString("description", ""),
                            timestamp = item.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
                db.restoreExpenses(expList, clearExisting = false)
                totalRestored += expList.size
            }

            Result.success(totalRestored)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun readTextFromUri(context: Context, uri: Uri): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
