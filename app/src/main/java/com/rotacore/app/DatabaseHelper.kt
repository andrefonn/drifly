package com.rotacore.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    val timestamp: Long
)

data class FinancialSummary(
    val totalGross: Double,
    val totalExpenses: Double,
    val totalNet: Double,
    val rideCount: Int
)

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "drifly_finance.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE rides (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                platform TEXT,
                gross_price REAL,
                distance_km REAL,
                net_profit REAL,
                timestamp INTEGER
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
        db.execSQL("DROP TABLE IF EXISTS rides")
        db.execSQL("DROP TABLE IF EXISTS expenses")
        onCreate(db)
    }

    fun insertRide(platform: String, gross: Double, distance: Double, net: Double): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("platform", platform)
            put("gross_price", gross)
            put("distance_km", distance)
            put("net_profit", net)
            put("timestamp", System.currentTimeMillis())
        }
        return db.insert("rides", null, cv)
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
        val cursor = db.rawQuery("SELECT id, platform, gross_price, distance_km, net_profit, timestamp FROM rides ORDER BY timestamp ASC", null)
        while (cursor.moveToNext()) {
            list.add(
                RideRecord(
                    id = cursor.getLong(0),
                    platform = cursor.getString(1),
                    grossPrice = cursor.getDouble(2),
                    distanceKm = cursor.getDouble(3),
                    netProfit = cursor.getDouble(4),
                    timestamp = cursor.getLong(5)
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
                // Previne duplicação de corridas caso já existam no banco
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
                // Previne duplicação de despesas caso já existam no banco
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
        var count = 0

        val rideCursor = db.rawQuery(
            "SELECT gross_price, net_profit FROM rides WHERE timestamp BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString())
        )
        while (rideCursor.moveToNext()) {
            gross += rideCursor.getDouble(0)
            net += rideCursor.getDouble(1)
            count++
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

        val finalNet = (gross - expenses).coerceAtLeast(-999999.0)

        return FinancialSummary(
            totalGross = gross,
            totalExpenses = expenses,
            totalNet = finalNet,
            rideCount = count
        )
    }

    private fun getPeriodRange(period: String): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis

        when (period) {
            "DAILY" -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return Pair(cal.timeInMillis, now)
            }
            "WEEKLY" -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return Pair(cal.timeInMillis, now)
            }
            "MONTHLY" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return Pair(cal.timeInMillis, now)
            }
            "YEARLY" -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return Pair(cal.timeInMillis, now)
            }
            else -> return Pair(0L, now)
        }
    }
}
