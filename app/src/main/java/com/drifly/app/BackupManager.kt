package com.drifly.app

import android.content.Context
import android.net.Uri
import android.os.Environment
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
    private const val CURRENT_BACKUP_VERSION = 2

    fun exportBackup(context: Context): File {
        val db = DatabaseHelper(context)
        val prefs = PreferencesManager(context)

        val rootJson = JSONObject()
        rootJson.put("app", "DriFly")
        rootJson.put("backup_version", CURRENT_BACKUP_VERSION)
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
            put("tap_to_accept_enabled", prefs.tapToAcceptEnabled)
            put("blacklist_enabled", prefs.blacklistEnabled)
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
                put("status", r.status)
                put("cancellation_fee", r.cancellationFee)
                put("original_price", r.originalPrice)
            }
            ridesArray.put(rObj)
        }
        rootJson.put("rides", ridesArray)

        // 3. Histórico de Despesas Rápidas de Rua
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

        // 4. Compromissos, Parcelas e Dívidas Mensais (v2)
        val obligations = db.getAllMonthlyObligations(onlyActive = false)
        val obligationsArray = JSONArray()
        for (ob in obligations) {
            val obObj = JSONObject().apply {
                put("id", ob.id)
                put("title", ob.title)
                put("category", ob.category)
                put("type", ob.type)
                put("default_amount", ob.defaultAmount)
                put("due_day", ob.dueDay)
                put("start_year_month", ob.startYearMonth)
                if (ob.endYearMonth != null) {
                    put("end_year_month", ob.endYearMonth)
                } else {
                    put("end_year_month", JSONObject.NULL)
                }
                put("initial_installment", ob.initialInstallment)
                put("total_installments", ob.totalInstallments)
                put("total_debt_balance", ob.totalDebtBalance)
                put("notes", ob.notes)
                put("is_active", ob.isActive)
                put("created_at", ob.createdAt)
            }
            obligationsArray.put(obObj)
        }
        rootJson.put("monthly_obligations", obligationsArray)

        // 5. Entradas Mensais Materializadas e Baixas (v2)
        val obligationEntries = db.getAllMonthlyObligationEntries()
        val entriesArray = JSONArray()
        for (entry in obligationEntries) {
            val entryObj = JSONObject().apply {
                put("id", entry.id)
                put("obligation_id", entry.obligationId)
                put("year_month", entry.yearMonth)
                put("amount", entry.amount)
                put("due_day", entry.dueDay)
                put("installment_number", entry.installmentNumber)
                put("total_installments", entry.totalInstallments)
                put("status", entry.status)
                put("paid_at", entry.paidAt)
            }
            entriesArray.put(entryObj)
        }
        rootJson.put("monthly_obligation_entries", entriesArray)

        // 6. Lista Negra de Bairros (v2)
        val blacklistItems = db.getAllBlacklistItems()
        val blacklistArray = JSONArray()
        for (item in blacklistItems) {
            val itemObj = JSONObject().apply {
                put("id", item.id)
                put("raw_term", item.rawTerm)
                put("normalized_term", item.normalizedTerm)
                if (item.latitude != null) put("lat", item.latitude) else put("lat", JSONObject.NULL)
                if (item.longitude != null) put("lng", item.longitude) else put("lng", JSONObject.NULL)
                put("created_at", item.createdAt)
            }
            blacklistArray.put(itemObj)
        }
        rootJson.put("blacklist", blacklistArray)

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

    /**
     * Valida a estrutura básica do arquivo de backup JSON:
     * - Rejeita strings vazias/nulas
     * - Garante que é um objeto JSON válido
     * - Se uma seção conhecida estiver presente, valida que seu container é do tipo esperado:
     *   `preferences` -> JSONObject
     *   `rides`, `expenses`, `monthly_obligations`, `blacklist` -> JSONArray
     * - Garante que ao menos uma seção de dados reconhecida está presente
     */
    fun validateBackupJson(jsonString: String?): Result<JSONObject> {
        if (jsonString.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Arquivo de backup vazio ou inválido."))
        }
        return try {
            val root = JSONObject(jsonString)

            val hasValidPreferences = root.optJSONObject("preferences") != null
            val hasValidRides = root.optJSONArray("rides") != null
            val hasValidExpenses = root.optJSONArray("expenses") != null
            val hasValidObligations = root.optJSONArray("monthly_obligations") != null
            val hasValidBlacklist = root.optJSONArray("blacklist") != null

            if (root.has("preferences") && !hasValidPreferences) {
                return Result.failure(IllegalArgumentException("Seção 'preferences' inválida ou não é um objeto JSON."))
            }
            if (root.has("rides") && !hasValidRides) {
                return Result.failure(IllegalArgumentException("Seção 'rides' inválida ou não é um array JSON."))
            }
            if (root.has("expenses") && !hasValidExpenses) {
                return Result.failure(IllegalArgumentException("Seção 'expenses' inválida ou não é um array JSON."))
            }
            if (root.has("monthly_obligations") && !hasValidObligations) {
                return Result.failure(IllegalArgumentException("Seção 'monthly_obligations' inválida ou não é um array JSON."))
            }
            if (root.has("blacklist") && !hasValidBlacklist) {
                return Result.failure(IllegalArgumentException("Seção 'blacklist' inválida ou não é um array JSON."))
            }

            if (!hasValidPreferences && !hasValidRides && !hasValidExpenses && !hasValidObligations && !hasValidBlacklist) {
                return Result.failure(IllegalArgumentException("Arquivo de backup inválido ou incompatível."))
            }

            Result.success(root)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun importBackup(context: Context, jsonString: String): Result<Int> {
        val root = validateBackupJson(jsonString).getOrElse { return Result.failure(it) }
        return try {
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
                if (p.has("tap_to_accept_enabled")) {
                    prefs.tapToAcceptEnabled = p.getBoolean("tap_to_accept_enabled")
                } else if (p.has("touch_to_accept_enabled")) {
                    prefs.tapToAcceptEnabled = p.getBoolean("touch_to_accept_enabled")
                }
                if (p.has("blacklist_enabled")) {
                    prefs.blacklistEnabled = p.getBoolean("blacklist_enabled")
                }
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
                            timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                            status = item.optString("status", RideRecord.STATUS_ESTIMADO),
                            cancellationFee = item.optDouble("cancellation_fee", 0.0),
                            originalPrice = item.optDouble("original_price", item.optDouble("gross_price", 0.0))
                        )
                    )
                }
                db.restoreRides(ridesList, clearExisting = false)
                totalRestored += ridesList.size
            }

            // Restaura Despesas Rápidas
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

            // Restaura Compromissos e Entradas Mensais (v2, retrocompatível com v1)
            if (root.has("monthly_obligations")) {
                val obArray = root.getJSONArray("monthly_obligations")
                val obList = mutableListOf<MonthlyObligation>()
                for (i in 0 until obArray.length()) {
                    val item = obArray.getJSONObject(i)
                    val endYm = if (item.isNull("end_year_month")) {
                        null
                    } else {
                        item.optString("end_year_month", "").takeIf { it.isNotBlank() && it != "null" }
                    }
                    obList.add(
                        MonthlyObligation(
                            id = item.optLong("id", (i + 1).toLong()),
                            title = item.optString("title", "Compromisso"),
                            category = item.optString("category", MonthlyObligation.CATEGORY_PESSOAL),
                            type = item.optString("type", MonthlyObligation.TYPE_FIXED_RECURRING),
                            defaultAmount = item.optDouble("default_amount", 0.0),
                            dueDay = item.optInt("due_day", 10).coerceIn(1, 31),
                            startYearMonth = item.optString("start_year_month", DatabaseHelper.getCurrentYearMonth()),
                            endYearMonth = endYm,
                            initialInstallment = item.optInt("initial_installment", 1),
                            totalInstallments = item.optInt("total_installments", 1),
                            totalDebtBalance = item.optDouble("total_debt_balance", 0.0),
                            notes = item.optString("notes", ""),
                            isActive = item.optBoolean("is_active", true),
                            createdAt = item.optLong("created_at", System.currentTimeMillis())
                        )
                    )
                }

                val entriesList = mutableListOf<MonthlyObligationEntry>()
                if (root.has("monthly_obligation_entries")) {
                    val entriesArray = root.getJSONArray("monthly_obligation_entries")
                    for (i in 0 until entriesArray.length()) {
                        val item = entriesArray.getJSONObject(i)
                        entriesList.add(
                            MonthlyObligationEntry(
                                id = item.optLong("id", 0L),
                                obligationId = item.optLong("obligation_id", 0L),
                                yearMonth = item.optString("year_month", DatabaseHelper.getCurrentYearMonth()),
                                amount = item.optDouble("amount", 0.0),
                                dueDay = item.optInt("due_day", 10).coerceIn(1, 31),
                                installmentNumber = item.optInt("installment_number", 0),
                                totalInstallments = item.optInt("total_installments", 0),
                                status = item.optString("status", MonthlyObligationEntry.STATUS_PENDING),
                                paidAt = item.optLong("paid_at", 0L)
                            )
                        )
                    }
                }

                db.restoreMonthlyObligations(obList, entriesList, clearExisting = false)
                totalRestored += obList.size
            }

            // Restaura Lista Negra de Bairros (v2 / Milestone 2)
            if (root.has("blacklist")) {
                val blArray = root.getJSONArray("blacklist")
                val blList = mutableListOf<BlacklistItem>()
                for (i in 0 until blArray.length()) {
                    val item = blArray.optJSONObject(i) ?: continue
                    val rawTerm = item.optString("raw_term", "").trim()
                    if (rawTerm.isEmpty()) continue
                    val normTerm = item.optString("normalized_term", "").ifBlank {
                        BlacklistManager.normalizeText(rawTerm)
                    }
                    val lat = if (item.isNull("lat")) null else item.optDouble("lat").takeIf { !it.isNaN() }
                    val lng = if (item.isNull("lng")) null else item.optDouble("lng").takeIf { !it.isNaN() }
                    blList.add(
                        BlacklistItem(
                            id = item.optLong("id", 0L),
                            rawTerm = rawTerm,
                            normalizedTerm = normTerm,
                            latitude = lat,
                            longitude = lng,
                            createdAt = item.optLong("created_at", System.currentTimeMillis())
                        )
                    )
                }
                db.restoreBlacklistItems(blList, clearExisting = false)
                totalRestored += blList.size
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
