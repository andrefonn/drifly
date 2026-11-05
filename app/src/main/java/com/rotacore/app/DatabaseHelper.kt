package com.rotacore.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class ExpenseItem(
    val id: Long = 0,
    val category: String,
    val amount: Double,
    val description: String,
    val timestamp: Long
)

data class RideRecord(
    val id: Long = 0,
    val platform: String,
    val grossPrice: Double,
    val distanceKm: Double,
    val netProfit: Double,
    val timestamp: Long,
    val status: String = STATUS_ESTIMADO,
    val cancellationFee: Double = 0.0,
    val originalPrice: Double = grossPrice
) {
    companion object {
        const val STATUS_ESTIMADO = "ESTIMADO"
        const val STATUS_CONCLUIDO = "CONCLUIDO"
        const val STATUS_CANCELADO = "CANCELADO"
        const val STATUS_CANCELADO_TAXA = "CANCELADO_TAXA"
    }
}

data class FinancialSummary(
    val totalGross: Double,
    val totalExpenses: Double,
    val totalNet: Double,
    val rideCount: Int,
    val estimatedCount: Int = 0,
    val completedCount: Int = 0,
    val cancelledCount: Int = 0
)

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "drifly_finance.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE rides (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                platform TEXT,
                gross_price REAL,
                distance_km REAL,
                net_profit REAL,
                timestamp INTEGER,
                status TEXT DEFAULT 'ESTIMADO',
                cancellation_fee REAL DEFAULT 0.0,
                original_price REAL DEFAULT 0.0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT,
                amount REAL,
                description TEXT,
                timestamp INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE rides ADD COLUMN status TEXT DEFAULT 'ESTIMADO'")
            } catch (_: Exception) {}
            try {
                db.execSQL("ALTER TABLE rides ADD COLUMN cancellation_fee REAL DEFAULT 0.0")
            } catch (_: Exception) {}
            try {
                db.execSQL("ALTER TABLE rides ADD COLUMN original_price REAL DEFAULT 0.0")
            } catch (_: Exception) {}
            try {
                db.execSQL("UPDATE rides SET original_price = gross_price WHERE original_price = 0.0 OR original_price IS NULL")
            } catch (_: Exception) {}
        }
    }

    fun insertRide(
        platform: String,
        gross: Double,
        distance: Double,
        net: Double,
        status: String = RideRecord.STATUS_ESTIMADO,
        cancellationFee: Double = 0.0,
        originalPrice: Double = gross
    ): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("platform", platform)
            put("gross_price", gross)
            put("distance_km", distance)
            put("net_profit", net)
            put("timestamp", System.currentTimeMillis())
            put("status", status)
            put("cancellation_fee", cancellationFee)
            put("original_price", originalPrice)
        }
        return db.insert("rides", null, cv)
    }

    fun updateRideStatus(rideId: Long, newStatus: String, cancellationFee: Double = 0.0): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("status", newStatus)
            if (newStatus == RideRecord.STATUS_CANCELADO_TAXA) {
                put("cancellation_fee", cancellationFee)
            } else if (newStatus == RideRecord.STATUS_CANCELADO) {
                put("cancellation_fee", 0.0)
            }
        }
        return db.update("rides", cv, "id = ?", arrayOf(rideId.toString())) > 0
    }

    fun updateRidePrice(
        rideId: Long,
        newGrossPrice: Double,
        newNetProfit: Double,
        newStatus: String = RideRecord.STATUS_CONCLUIDO
    ): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("gross_price", newGrossPrice)
            put("net_profit", newNetProfit)
            put("status", newStatus)
        }
        return db.update("rides", cv, "id = ?", arrayOf(rideId.toString())) > 0
    }

    fun deleteRide(rideId: Long): Boolean {
        val db = writableDatabase
        return db.delete("rides", "id = ?", arrayOf(rideId.toString())) > 0
    }

    fun getLatestEstimatedRide(platform: String? = null): RideRecord? {
        val db = readableDatabase
        val sql = if (platform != null) {
            "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price FROM rides WHERE status = ? AND UPPER(platform) = UPPER(?) ORDER BY timestamp DESC LIMIT 1"
        } else {
            "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price FROM rides WHERE status = ? ORDER BY timestamp DESC LIMIT 1"
        }
        val args = if (platform != null) {
            arrayOf(RideRecord.STATUS_ESTIMADO, platform)
        } else {
            arrayOf(RideRecord.STATUS_ESTIMADO)
        }

        val cursor = db.rawQuery(sql, args)
        var ride: RideRecord? = null
        if (cursor.moveToFirst()) {
            ride = RideRecord(
                id = cursor.getLong(0),
                platform = cursor.getString(1),
                grossPrice = cursor.getDouble(2),
                distanceKm = cursor.getDouble(3),
                netProfit = cursor.getDouble(4),
                timestamp = cursor.getLong(5),
                status = cursor.getString(6) ?: RideRecord.STATUS_ESTIMADO,
                cancellationFee = cursor.getDouble(7),
                originalPrice = cursor.getDouble(8)
            )
        }
        cursor.close()
        return ride
    }

    fun insertExpense(category: String, amount: Double, desc: String): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("category", category)
            put("amount", amount)
            put("description", desc)
            put("timestamp", System.currentTimeMillis())
        }
        return db.insert("expenses", null, cv)
    }

    fun getAllRides(): List<RideRecord> {
        val list = mutableListOf<RideRecord>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price FROM rides ORDER BY timestamp ASC",
            null
        )
        while (cursor.moveToNext()) {
            list.add(
                RideRecord(
                    id = cursor.getLong(0),
                    platform = cursor.getString(1),
                    grossPrice = cursor.getDouble(2),
                    distanceKm = cursor.getDouble(3),
                    netProfit = cursor.getDouble(4),
                    timestamp = cursor.getLong(5),
                    status = cursor.getString(6) ?: RideRecord.STATUS_ESTIMADO,
                    cancellationFee = cursor.getDouble(7),
                    originalPrice = cursor.getDouble(8)
                )
            )
        }
        cursor.close()
        return list
    }

    fun getRidesForPeriod(period: String): List<RideRecord> {
        val (start, end) = getPeriodRange(period)
        val list = mutableListOf<RideRecord>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price FROM rides WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp DESC",
            arrayOf(start.toString(), end.toString())
        )
        while (cursor.moveToNext()) {
            list.add(
                RideRecord(
                    id = cursor.getLong(0),
                    platform = cursor.getString(1),
                    grossPrice = cursor.getDouble(2),
                    distanceKm = cursor.getDouble(3),
                    netProfit = cursor.getDouble(4),
                    timestamp = cursor.getLong(5),
                    status = cursor.getString(6) ?: RideRecord.STATUS_ESTIMADO,
                    cancellationFee = cursor.getDouble(7),
                    originalPrice = cursor.getDouble(8)
                )
            )
        }
        cursor.close()
        return list
    }

    fun getAllExpenses(): List<ExpenseItem> {
        val list = mutableListOf<ExpenseItem>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id, category, amount, description, timestamp FROM expenses ORDER BY timestamp ASC", null)
        while (cursor.moveToNext()) {
            list.add(
                ExpenseItem(
                    id = cursor.getLong(0),
                    category = cursor.getString(1),
                    amount = cursor.getDouble(2),
                    description = cursor.getString(3) ?: "",
                    timestamp = cursor.getLong(4)
                )
            )
        }
        cursor.close()
        return list
    }

    fun restoreRides(rides: List<RideRecord>, clearExisting: Boolean = false) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (clearExisting) {
                db.delete("rides", null, null)
            }
            for (r in rides) {
                val cursor = db.rawQuery(
                    "SELECT 1 FROM rides WHERE timestamp = ? AND platform = ? AND ABS(gross_price - ?) < 0.001 LIMIT 1",
                    arrayOf(r.timestamp.toString(), r.platform, r.grossPrice.toString())
                )
                val exists = cursor.moveToFirst()
                cursor.close()

                if (!exists) {
                    val cv = ContentValues().apply {
                        put("platform", r.platform)
                        put("gross_price", r.grossPrice)
                        put("distance_km", r.distanceKm)
                        put("net_profit", r.netProfit)
                        put("timestamp", r.timestamp)
                        put("status", r.status)
                        put("cancellation_fee", r.cancellationFee)
                        put("original_price", r.originalPrice)
                    }
                    db.insert("rides", null, cv)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun restoreExpenses(expenses: List<ExpenseItem>, clearExisting: Boolean = false) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (clearExisting) {
                db.delete("expenses", null, null)
            }
            for (e in expenses) {
                val cursor = db.rawQuery(
                    "SELECT 1 FROM expenses WHERE timestamp = ? AND category = ? AND ABS(amount - ?) < 0.001 LIMIT 1",
                    arrayOf(e.timestamp.toString(), e.category, e.amount.toString())
                )
                val exists = cursor.moveToFirst()
                cursor.close()

                if (!exists) {
                    val cv = ContentValues().apply {
                        put("category", e.category)
                        put("amount", e.amount)
                        put("description", e.description)
                        put("timestamp", e.timestamp)
                    }
                    db.insert("expenses", null, cv)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getSummary(period: String): FinancialSummary {
        val (start, end) = getPeriodRange(period)
        val db = readableDatabase

        var gross = 0.0
        var net = 0.0
        var totalCount = 0
        var estCount = 0
        var compCount = 0
        var cancCount = 0

        val rideCursor = db.rawQuery(
            "SELECT gross_price, net_profit, status, cancellation_fee FROM rides WHERE timestamp BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString())
        )
        while (rideCursor.moveToNext()) {
            val grossPrice = rideCursor.getDouble(0)
            val netProfit = rideCursor.getDouble(1)
            val status = rideCursor.getString(2) ?: RideRecord.STATUS_ESTIMADO
            val fee = rideCursor.getDouble(3)

            when (status) {
                RideRecord.STATUS_CONCLUIDO -> {
                    gross += grossPrice
                    net += netProfit
                    totalCount++
                    compCount++
                }
                RideRecord.STATUS_ESTIMADO -> {
                    gross += grossPrice
                    net += netProfit
                    totalCount++
                    estCount++
                }
                RideRecord.STATUS_CANCELADO_TAXA -> {
                    val effectiveFee = if (fee > 0) fee else 0.0
                    gross += effectiveFee
                    net += effectiveFee
                    totalCount++
                    cancCount++
                }
                RideRecord.STATUS_CANCELADO -> {
                    // Contribui R$ 0,00 no faturamento bruto e lucro líquido
                    cancCount++
                }
                else -> {
                    gross += grossPrice
                    net += netProfit
                    totalCount++
                }
            }
        }
        rideCursor.close()

        var expenses = 0.0
        val expCursor = db.rawQuery(
            "SELECT amount FROM expenses WHERE timestamp BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString())
        )
        while (expCursor.moveToNext()) {
            expenses += expCursor.getDouble(0)
        }
        expCursor.close()

        val finalNet = (net - expenses).coerceAtLeast(-999999.0)

        return FinancialSummary(
            totalGross = gross,
            totalExpenses = expenses,
            totalNet = finalNet,
            rideCount = totalCount,
            estimatedCount = estCount,
            completedCount = compCount,
            cancelledCount = cancCount
        )
    }

    fun getPeriodRange(period: String): Pair<Long, Long> {
        val cal = Calendar.getInstance()

        when (period) {
            "DAILY" -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                return Pair(start, end)
            }
            "WEEKLY" -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_WEEK, 6)
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                return Pair(start, end)
            }
            "MONTHLY" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                return Pair(start, end)
            }
            "YEARLY" -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.DAY_OF_YEAR, cal.getActualMaximum(Calendar.DAY_OF_YEAR))
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                return Pair(start, end)
            }
            else -> return Pair(0L, Long.MAX_VALUE)
        }
    }
}
