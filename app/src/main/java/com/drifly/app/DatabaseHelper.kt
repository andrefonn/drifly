package com.drifly.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
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
    val timestamp: Long,
    val status: String = STATUS_ESTIMADO,
    val cancellationFee: Double = 0.0,
    val originalPrice: Double = grossPrice,
    val acceptanceEvidence: String = EVIDENCE_LEGACY
) {
    companion object {
        const val STATUS_ESTIMADO = "ESTIMADO"
        /** Oferta detectada mas AINDA NÃO aceita: nunca entra em faturamento nem histórico. */
        const val STATUS_OFERTA = "OFERTA"
        const val STATUS_CONCLUIDO = "CONCLUIDO"
        const val STATUS_CANCELADO = "CANCELADO"
        const val STATUS_CANCELADO_TAXA = "CANCELADO_TAXA"
        /** Linha duplicada/falso aceite de versões antigas: visível no histórico, fora do faturamento. */
        const val STATUS_DUPLICADO = "DUPLICADO"

        // Evidência de aceite. Só ESTIMADO com evidência válida entra no Faturamento.
        const val EVIDENCE_LEGACY = "LEGACY"
        const val EVIDENCE_SUSPECT = "SUSPECT"
        const val EVIDENCE_OFFER = "OFFER"
        const val EVIDENCE_CLICK = "CLICK"
        const val EVIDENCE_GESTURE = "GESTURE"
        const val EVIDENCE_OVERLAY_TAP = "OVERLAY_TAP"
        const val EVIDENCE_PICKUP_TRANSITION = "PICKUP_TRANSITION"
        const val EVIDENCE_ACTIVE_RIDE = "ACTIVE_RIDE"
        const val EVIDENCE_HUD_FRESH = "HUD_FRESH"
        const val EVIDENCE_RECEIPT = "RECEIPT"
        const val EVIDENCE_NOTIFICATION = "NOTIFICATION"
        const val EVIDENCE_CANCEL = "CANCEL"
        const val EVIDENCE_EXPLICIT_FEE = "EXPLICIT_FEE"
        const val EVIDENCE_CANCEL_PROMPT = "CANCEL_PROMPT"
        const val EVIDENCE_MANUAL = "MANUAL"
        const val EVIDENCE_SIMULATED = "SIMULATED"
        const val EVIDENCE_REPAIRED_BANNER = "REPAIRED_BANNER"
        const val EVIDENCE_REPAIRED_FEE = "REPAIRED_FEE"
        const val EVIDENCE_DUPLICATE = "DUPLICATE"

        private val BILLABLE_ESTIMATE_EVIDENCE = setOf(
            EVIDENCE_CLICK,
            EVIDENCE_GESTURE,
            EVIDENCE_OVERLAY_TAP,
            EVIDENCE_PICKUP_TRANSITION,
            EVIDENCE_ACTIVE_RIDE,
            EVIDENCE_HUD_FRESH,
            EVIDENCE_RECEIPT,
            EVIDENCE_NOTIFICATION,
            EVIDENCE_MANUAL,
            EVIDENCE_SIMULATED
        )

        /**
         * Regra única usada pelo Faturamento e pelos relatórios.
         * - OFERTA/DUPLICADO/CANCELADO sem taxa nunca somam.
         * - ESTIMADO só soma com evidência real de aceite (nunca LEGACY/SUSPECT).
         * - CANCELADO_TAXA só soma se houver taxa explícita maior que zero.
         */
        fun isBillable(status: String?, evidence: String?, cancellationFee: Double): Boolean {
            return when (status) {
                STATUS_OFERTA, STATUS_DUPLICADO, STATUS_CANCELADO -> false
                STATUS_CANCELADO_TAXA -> cancellationFee > 0.0
                STATUS_ESTIMADO -> BILLABLE_ESTIMATE_EVIDENCE.contains(evidence ?: EVIDENCE_LEGACY)
                STATUS_CONCLUIDO -> evidence != EVIDENCE_SUSPECT
                else -> false
            }
        }
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

data class MonthlyObligation(
    val id: Long = 0,
    val title: String,
    val category: String,
    val type: String,
    val defaultAmount: Double,
    val dueDay: Int = 10,
    val startYearMonth: String,
    val endYearMonth: String? = null,
    val initialInstallment: Int = 1,
    val totalInstallments: Int = 1,
    val totalDebtBalance: Double = 0.0,
    val notes: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Naturezas de compromissos (type)
        const val TYPE_INSTALLMENT = "INSTALLMENT"
        const val TYPE_FIXED_RECURRING = "FIXED_RECURRING"
        const val TYPE_DEBT_OPEN = "DEBT_OPEN"
        const val TYPE_VARIABLE_MONTHLY = "VARIABLE_MONTHLY"

        // Categorias (category)
        const val CATEGORY_VEICULO = "VEICULO"
        const val CATEGORY_MORADIA = "MORADIA"
        const val CATEGORY_IMPOSTOS_MEI = "IMPOSTOS_MEI"
        const val CATEGORY_CARTAO_EMPRESTIMO = "CARTAO_EMPRESTIMO"
        const val CATEGORY_UTILIDADES = "UTILIDADES"
        const val CATEGORY_PESSOAL = "PESSOAL"
    }
}

data class MonthlyObligationEntry(
    val id: Long = 0,
    val obligationId: Long,
    val yearMonth: String,
    val amount: Double,
    val dueDay: Int,
    val installmentNumber: Int = 0,
    val totalInstallments: Int = 0,
    val status: String = STATUS_PENDING,
    val paidAt: Long = 0L
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_PAID = "PAID"
        const val STATUS_SKIPPED = "SKIPPED"
    }
}

data class MonthlyObligationJoined(
    val entryId: Long,
    val obligationId: Long,
    val title: String,
    val category: String,
    val type: String,
    val yearMonth: String,
    val amount: Double,
    val defaultAmount: Double,
    val dueDay: Int,
    val installmentNumber: Int,
    val initialInstallment: Int,
    val totalInstallments: Int,
    val startYearMonth: String,
    val endYearMonth: String?,
    val totalDebtBalance: Double,
    val status: String,
    val paidAt: Long,
    val notes: String,
    val isActive: Boolean
)

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "drifly_finance.db"
        const val DATABASE_VERSION = 5

        fun formatYearMonth(year: Int, month: Int): String {
            val safeMonth = month.coerceIn(1, 12)
            return String.format(Locale.US, "%04d-%02d", year, safeMonth)
        }

        fun getCurrentYearMonth(): String {
            val cal = Calendar.getInstance()
            return formatYearMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        }

        fun parseYearMonth(yearMonth: String): Pair<Int, Int> {
            val parts = yearMonth.trim().split("-")
            if (parts.size == 2) {
                val y = parts[0].toIntOrNull()
                val m = parts[1].toIntOrNull()
                if (y != null && m != null && m in 1..12) {
                    return Pair(y, m)
                }
            }
            val cal = Calendar.getInstance()
            return Pair(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        }

        fun deltaMonths(startYearMonth: String, targetYearMonth: String): Int {
            val (sy, sm) = parseYearMonth(startYearMonth)
            val (ty, tm) = parseYearMonth(targetYearMonth)
            return (ty - sy) * 12 + (tm - sm)
        }

        fun addMonths(startYearMonth: String, monthsToAdd: Int): String {
            val (sy, sm) = parseYearMonth(startYearMonth)
            val totalMonthsZeroBased = (sy * 12 + (sm - 1)) + monthsToAdd
            val newYear = totalMonthsZeroBased / 12
            val newMonth = (totalMonthsZeroBased % 12) + 1
            return formatYearMonth(newYear, newMonth)
        }

        fun clampDueDayForMonth(year: Int, month: Int, dueDay: Int): Int {
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            return dueDay.coerceIn(1, maxDay)
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

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
                original_price REAL DEFAULT 0.0,
                acceptance_evidence TEXT DEFAULT 'LEGACY'
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

        createObligationsTables(db)
        createBlacklistTable(db)
    }

    private fun createBlacklistTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS blacklist_neighborhoods (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                raw_term TEXT NOT NULL,
                normalized_term TEXT NOT NULL,
                latitude REAL,
                longitude REAL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_blacklist_norm ON blacklist_neighborhoods(normalized_term)"
        )
    }

    private fun createObligationsTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS monthly_obligations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                category TEXT NOT NULL,
                type TEXT NOT NULL,
                default_amount REAL NOT NULL,
                due_day INTEGER NOT NULL DEFAULT 10,
                start_year_month TEXT NOT NULL,
                end_year_month TEXT,
                initial_installment INTEGER DEFAULT 1,
                total_installments INTEGER DEFAULT 1,
                total_debt_balance REAL DEFAULT 0.0,
                notes TEXT DEFAULT '',
                is_active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS monthly_obligation_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                obligation_id INTEGER NOT NULL,
                year_month TEXT NOT NULL,
                amount REAL NOT NULL,
                due_day INTEGER NOT NULL,
                installment_number INTEGER DEFAULT 0,
                total_installments INTEGER DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'PENDING',
                paid_at INTEGER DEFAULT 0,
                UNIQUE(obligation_id, year_month),
                FOREIGN KEY(obligation_id) REFERENCES monthly_obligations(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_entries_ym ON monthly_obligation_entries(year_month)"
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
        if (oldVersion < 3) {
            createObligationsTables(db)
        }
        if (oldVersion < 4) {
            createBlacklistTable(db)
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE rides ADD COLUMN acceptance_evidence TEXT DEFAULT 'LEGACY'")
            } catch (_: Exception) {}
            repairRidesForV5(db)
        }
    }

    /**
     * Migração 1.2.0: separa o que tem evidência real de aceite do que era apenas oferta/estimativa antiga
     * e corrige os casos documentados nos logs de 13/11 e 19/11 (banner "pacote 0%", taxas fantasmas e
     * falsos aceites por HUD). Tudo é feito com assinatura exata (id + plataforma + valor + distância + janela
     * de tempo); se a base não tiver a linha, a reparação simplesmente não altera nada.
     */
    private fun repairRidesForV5(db: SQLiteDatabase) {
        // Ofertas continuam ocultas.
        db.execSQL("UPDATE rides SET acceptance_evidence = 'OFFER' WHERE status = 'OFERTA'")
        // Concluídas e canceladas antigas já passaram por recibo/baixa: ficam confiáveis.
        db.execSQL("UPDATE rides SET acceptance_evidence = 'RECEIPT' WHERE status = 'CONCLUIDO' AND (acceptance_evidence IS NULL OR acceptance_evidence = 'LEGACY')")
        db.execSQL("UPDATE rides SET acceptance_evidence = 'CANCEL' WHERE status IN ('CANCELADO','CANCELADO_TAXA') AND (acceptance_evidence IS NULL OR acceptance_evidence = 'LEGACY')")

        // Taxa de cancelamento nunca fica pendurada numa corrida concluída.
        db.execSQL("UPDATE rides SET cancellation_fee = 0.0 WHERE status = 'CONCLUIDO' AND cancellation_fee <> 0.0")

        // 5 linhas de 13/11 em que "Você ganhou R$X com o pacote 0%" virou tarifa. Valores exatos dos logs.
        repairBannerFare(db, oldGross = 27.45, original = 5.10)
        repairBannerFare(db, oldGross = 39.95, original = 5.10)
        repairBannerFare(db, oldGross = 45.05, original = 5.10)
        repairBannerFare(db, oldGross = 78.95, original = 33.00)
        repairBannerFare(db, oldGross = 96.65, original = 12.30)

        // Taxas fantasmas antigas: qualquer valor acima de R$25 ou o padrão 3,00/5,50 que o app antigo
        // inventava quando não havia taxa explícita. Sem prova de cobrança, vira CANCELADO com taxa 0.
        db.execSQL(
            "UPDATE rides SET status = 'CANCELADO', cancellation_fee = 0.0, acceptance_evidence = 'REPAIRED_FEE' " +
                "WHERE status = 'CANCELADO_TAXA' AND (cancellation_fee > 25.0 " +
                "OR (UPPER(platform) = '99' AND ABS(cancellation_fee - 3.0) < 0.001) " +
                "OR (UPPER(platform) = 'UBER' AND ABS(cancellation_fee - 5.5) < 0.001))"
        )

        // Falsos aceites documentados em 19/11: a tela era da corrida anterior e o HUD casou um endereço
        // da oferta. Ficam visíveis como DUPLICADO, mas fora de qualquer soma.
        markDuplicateBySignature(db, id = 193L, gross = 8.10, distance = 1.98, around = 1795091091343L)
        markDuplicateBySignature(db, id = 210L, gross = 5.50, distance = 3.55, around = 1795098767104L)
        markDuplicateBySignature(db, id = 212L, gross = 5.10, distance = 3.80, around = 1795100351325L)
        markDuplicateBySignature(db, id = 215L, gross = 9.20, distance = 8.00, around = 1795101375941L)

        // #225: aceite real às 12:31:15, mas o motorista abriu "Deseja mesmo cancelar esta corrida?"
        // às 12:31:47 e não houve recibo. Não pode continuar como ESTIMADO no Faturamento.
        db.execSQL(
            "UPDATE rides SET status = 'CANCELADO', cancellation_fee = 0.0, acceptance_evidence = 'CANCEL_PROMPT' " +
                "WHERE id = 225 AND UPPER(platform) = '99' AND ABS(gross_price - 10.90) < 0.01 " +
                "AND ABS(distance_km - 8.83) < 0.05 AND timestamp BETWEEN 1795102265000 AND 1795102290000"
        )

        // Tudo que restou como ESTIMADO é legado sem prova de aceite: fica visível, mas fora do Faturamento.
        db.execSQL("UPDATE rides SET acceptance_evidence = 'LEGACY' WHERE status = 'ESTIMADO'")
        db.execSQL("UPDATE rides SET acceptance_evidence = 'LEGACY' WHERE acceptance_evidence IS NULL OR acceptance_evidence = ''")
    }

    private fun repairBannerFare(db: SQLiteDatabase, oldGross: Double, original: Double) {
        val cursor = db.rawQuery(
            "SELECT id, net_profit FROM rides WHERE status = 'CONCLUIDO' AND UPPER(platform) = '99' " +
                "AND ABS(gross_price - ?) < 0.01 AND ABS(original_price - ?) < 0.01",
            arrayOf(oldGross.toString(), original.toString())
        )
        val ids = mutableListOf<Pair<Long, Double>>()
        while (cursor.moveToNext()) {
            ids.add(cursor.getLong(0) to cursor.getDouble(1))
        }
        cursor.close()
        for ((id, oldNet) in ids) {
            val newNet = Math.max(0.0, Math.round((oldNet - (oldGross - original)) * 100.0) / 100.0)
            val cv = ContentValues().apply {
                put("gross_price", original)
                put("net_profit", newNet)
                put("acceptance_evidence", RideRecord.EVIDENCE_REPAIRED_BANNER)
            }
            db.update("rides", cv, "id = ?", arrayOf(id.toString()))
        }
    }

    private fun markDuplicateBySignature(db: SQLiteDatabase, id: Long, gross: Double, distance: Double, around: Long) {
        val cv = ContentValues().apply {
            put("status", RideRecord.STATUS_DUPLICADO)
            put("cancellation_fee", 0.0)
            put("acceptance_evidence", RideRecord.EVIDENCE_DUPLICATE)
        }
        db.update(
            "rides",
            cv,
            "id = ? AND UPPER(platform) = '99' AND ABS(gross_price - ?) < 0.01 AND ABS(distance_km - ?) < 0.05 AND timestamp BETWEEN ? AND ?",
            arrayOf(id.toString(), gross.toString(), distance.toString(), (around - 10_000L).toString(), (around + 10_000L).toString())
        )
    }

    // =========================================================================
    // CORRIDAS & DESPESAS RÁPIDAS DE PISTA (ABA 1)
    // =========================================================================

    fun insertRide(
        platform: String,
        gross: Double,
        distance: Double,
        net: Double,
        status: String = RideRecord.STATUS_ESTIMADO,
        cancellationFee: Double = 0.0,
        originalPrice: Double = gross,
        acceptanceEvidence: String = RideRecord.EVIDENCE_LEGACY
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
            put("acceptance_evidence", acceptanceEvidence)
        }
        return db.insert("rides", null, cv)
    }

    fun updateRideStatus(
        rideId: Long,
        newStatus: String,
        cancellationFee: Double = 0.0,
        evidence: String? = null
    ): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("status", newStatus)
            when (newStatus) {
                RideRecord.STATUS_CANCELADO_TAXA -> put("cancellation_fee", cancellationFee)
                RideRecord.STATUS_CANCELADO, RideRecord.STATUS_CONCLUIDO -> put("cancellation_fee", 0.0)
            }
            if (!evidence.isNullOrBlank()) put("acceptance_evidence", evidence)
        }
        return db.update("rides", cv, "id = ?", arrayOf(rideId.toString())) > 0
    }

    fun updateRidePrice(
        rideId: Long,
        newGrossPrice: Double,
        newNetProfit: Double,
        newStatus: String = RideRecord.STATUS_CONCLUIDO,
        evidence: String? = null
    ): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("gross_price", newGrossPrice)
            put("net_profit", newNetProfit)
            put("status", newStatus)
            if (newStatus == RideRecord.STATUS_CONCLUIDO || newStatus == RideRecord.STATUS_CANCELADO) {
                put("cancellation_fee", 0.0)
            }
            if (!evidence.isNullOrBlank()) put("acceptance_evidence", evidence)
        }
        return db.update("rides", cv, "id = ?", arrayOf(rideId.toString())) > 0
    }

    fun deleteRide(rideId: Long): Boolean {
        val db = writableDatabase
        return db.delete("rides", "id = ?", arrayOf(rideId.toString())) > 0
    }

    fun getLatestRide(): RideRecord? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price, acceptance_evidence FROM rides ORDER BY id DESC LIMIT 1",
            null
        )
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
                originalPrice = cursor.getDouble(8),
                    acceptanceEvidence = cursor.getString(9)
            )
        }
        cursor.close()
        return ride
    }

    /**
     * Busca se já existe uma corrida registrada recentemente para a mesma plataforma com valor
     * e distância equivalentes. Evita que a mesma oferta gere duas linhas no banco quando tanto
     * o AccessibilityService quanto o NotificationListener a detectam.
     */
    fun findRecentDuplicateRide(
        platform: String,
        price: Double,
        distanceKm: Double,
        withinMs: Long = 25_000L
    ): RideRecord? {
        val db = readableDatabase
        val since = System.currentTimeMillis() - withinMs
        val cursor = db.rawQuery(
            "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price, acceptance_evidence " +
                    "FROM rides WHERE UPPER(platform) = UPPER(?) AND timestamp >= ? AND status != 'DUPLICADO' ORDER BY timestamp DESC",
            arrayOf(platform, since.toString())
        )
        var match: RideRecord? = null
        while (cursor.moveToNext()) {
            val gross = cursor.getDouble(2)
            val dist = cursor.getDouble(3)
            if (Math.abs(gross - price) < 0.05 && Math.abs(dist - distanceKm) < 0.2) {
                match = RideRecord(
                    id = cursor.getLong(0),
                    platform = cursor.getString(1),
                    grossPrice = gross,
                    distanceKm = dist,
                    netProfit = cursor.getDouble(4),
                    timestamp = cursor.getLong(5),
                    status = cursor.getString(6) ?: RideRecord.STATUS_ESTIMADO,
                    cancellationFee = cursor.getDouble(7),
                    originalPrice = cursor.getDouble(8),
                    acceptanceEvidence = cursor.getString(9)
                )
                break
            }
        }
        cursor.close()
        return match
    }

    /** Promove uma oferta (OFERTA) para corrida aceita (ESTIMADO). Só a partir daqui ela conta no faturamento. */
    fun acceptRide(id: Long, evidence: String = RideRecord.EVIDENCE_CLICK): Boolean {
        val cv = ContentValues().apply {
            put("status", RideRecord.STATUS_ESTIMADO)
            put("timestamp", System.currentTimeMillis())
            put("acceptance_evidence", evidence)
        }
        return writableDatabase.update(
            "rides", cv, "id = ? AND status = ?", arrayOf(id.toString(), RideRecord.STATUS_OFERTA)
        ) > 0
    }

    fun rideExists(id: Long): Boolean {
        val cursor = readableDatabase.rawQuery("SELECT 1 FROM rides WHERE id = ? LIMIT 1", arrayOf(id.toString()))
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }

    fun getRideById(id: Long): RideRecord? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price, acceptance_evidence FROM rides WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        )
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
                originalPrice = cursor.getDouble(8),
                    acceptanceEvidence = cursor.getString(9)
            )
        }
        cursor.close()
        return ride
    }

    fun getLatestEstimatedRide(platform: String? = null, maxAgeMs: Long? = null): RideRecord? {
        val db = readableDatabase
        val minTimestamp = if (maxAgeMs != null && maxAgeMs > 0L) System.currentTimeMillis() - maxAgeMs else null
        val sql: String
        val args: Array<String>
        if (platform != null && minTimestamp != null) {
            sql = "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price, acceptance_evidence FROM rides WHERE status = ? AND UPPER(platform) = UPPER(?) AND timestamp >= ? ORDER BY timestamp DESC LIMIT 1"
            args = arrayOf(RideRecord.STATUS_ESTIMADO, platform, minTimestamp.toString())
        } else if (platform != null) {
            sql = "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price, acceptance_evidence FROM rides WHERE status = ? AND UPPER(platform) = UPPER(?) ORDER BY timestamp DESC LIMIT 1"
            args = arrayOf(RideRecord.STATUS_ESTIMADO, platform)
        } else if (minTimestamp != null) {
            sql = "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price, acceptance_evidence FROM rides WHERE status = ? AND timestamp >= ? ORDER BY timestamp DESC LIMIT 1"
            args = arrayOf(RideRecord.STATUS_ESTIMADO, minTimestamp.toString())
        } else {
            sql = "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price, acceptance_evidence FROM rides WHERE status = ? ORDER BY timestamp DESC LIMIT 1"
            args = arrayOf(RideRecord.STATUS_ESTIMADO)
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
                originalPrice = cursor.getDouble(8),
                    acceptanceEvidence = cursor.getString(9)
            )
        }
        cursor.close()
        return ride
    }

    /** Retorna todas as corridas e ofertas de hoje (incluindo OFERTA) para auditoria no relatório de diagnóstico. */
    fun getTodayAllRidesForDiagnostic(): List<RideRecord> {
        val (start, end) = getPeriodRange("TODAY")
        val list = mutableListOf<RideRecord>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price, acceptance_evidence FROM rides WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp ASC",
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
                    originalPrice = cursor.getDouble(8),
                    acceptanceEvidence = cursor.getString(9)
                )
            )
        }
        cursor.close()
        return list
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
            "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price, acceptance_evidence FROM rides WHERE status IS NULL OR status != 'OFERTA' ORDER BY timestamp ASC",
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
                    originalPrice = cursor.getDouble(8),
                    acceptanceEvidence = cursor.getString(9)
                )
            )
        }
        cursor.close()
        return list
    }

    fun getRidesForPeriod(period: String): List<RideRecord> {
        val (start, end) = getPeriodRange(period)
        return getRidesForRange(start, end)
    }

    fun getRidesForRange(start: Long, end: Long): List<RideRecord> {
        val list = mutableListOf<RideRecord>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, platform, gross_price, distance_km, net_profit, timestamp, status, cancellation_fee, original_price, acceptance_evidence FROM rides WHERE timestamp BETWEEN ? AND ? AND (status IS NULL OR status != 'OFERTA') ORDER BY timestamp DESC",
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
                    originalPrice = cursor.getDouble(8),
                    acceptanceEvidence = cursor.getString(9)
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
                        put("acceptance_evidence", r.acceptanceEvidence)
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
        return getSummaryForRange(start, end)
    }

    fun getSummaryForRange(start: Long, end: Long): FinancialSummary {
        val db = readableDatabase

        var gross = 0.0
        var net = 0.0
        var totalCount = 0
        var estCount = 0
        var compCount = 0
        var cancCount = 0

        val rideCursor = db.rawQuery(
            "SELECT gross_price, net_profit, status, cancellation_fee, acceptance_evidence FROM rides WHERE timestamp BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString())
        )
        while (rideCursor.moveToNext()) {
            val grossPrice = rideCursor.getDouble(0)
            val netProfit = rideCursor.getDouble(1)
            val status = rideCursor.getString(2) ?: RideRecord.STATUS_ESTIMADO
            val fee = rideCursor.getDouble(3)
            val evidence = rideCursor.getString(4) ?: RideRecord.EVIDENCE_LEGACY

            if (!RideRecord.isBillable(status, evidence, fee)) {
                if (status == RideRecord.STATUS_CANCELADO || status == RideRecord.STATUS_CANCELADO_TAXA) cancCount++
                continue
            }
            when (status) {
                RideRecord.STATUS_CONCLUIDO, RideRecord.STATUS_ESTIMADO -> {
                    gross += grossPrice
                    net += netProfit
                    totalCount++
                    if (status == RideRecord.STATUS_CONCLUIDO) compCount++ else estCount++
                }
                RideRecord.STATUS_CANCELADO_TAXA -> {
                    gross += fee
                    net += fee
                    totalCount++
                    cancCount++
                }
                else -> Unit
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

    // =========================================================================
    // CONSULTAS HISTÓRICAS MENSAIS (PARA GESTÃO FINANCEIRA & SALÁRIO REAL)
    // =========================================================================

    /**
     * Retorna o intervalo [startMillis, endMillis] para um ano e mês (month de 1 a 12).
     */
    fun getMonthRange(year: Int, month: Int): Pair<Long, Long> {
        val safeMonthZeroBased = (month - 1).coerceIn(0, 11)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, safeMonthZeroBased)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis
        return Pair(start, end)
    }

    /**
     * Retorna o FinancialSummary de um mês específico (month de 1 a 12).
     * totalNet já representa o Lucro Operacional da Pista (OpProfit_m = NetRides_m - StreetExpenses_m).
     */
    fun getSummaryForYearMonth(year: Int, month: Int): FinancialSummary {
        val (start, end) = getMonthRange(year, month)
        return getSummaryForRange(start, end)
    }

    fun getSummaryForYearMonth(yearMonth: String): FinancialSummary {
        val (y, m) = parseYearMonth(yearMonth)
        return getSummaryForYearMonth(y, m)
    }

    /**
     * Soma apenas o lucro líquido das corridas do mês (antes das despesas manuais de rua),
     * permitindo calcular exatamente o Desgaste/Combustível por KM: AutoKmCost_m = totalGross - ridesNetSum.
     */
    fun getRidesNetSumForYearMonth(year: Int, month: Int): Double {
        val (start, end) = getMonthRange(year, month)
        val db = readableDatabase
        var net = 0.0
        val cursor = db.rawQuery(
            "SELECT gross_price, net_profit, status, cancellation_fee, acceptance_evidence FROM rides WHERE timestamp BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString())
        )
        while (cursor.moveToNext()) {
            val netProfit = cursor.getDouble(1)
            val status = cursor.getString(2) ?: RideRecord.STATUS_ESTIMADO
            val fee = cursor.getDouble(3)
            val evidence = cursor.getString(4) ?: RideRecord.EVIDENCE_LEGACY
            if (!RideRecord.isBillable(status, evidence, fee)) continue
            when (status) {
                RideRecord.STATUS_CANCELADO_TAXA -> net += fee
                else -> net += netProfit
            }
        }
        cursor.close()
        return net
    }

    /**
     * Retorna o número de dias distintos em que o motorista teve corridas válidas no mês (month de 1 a 12).
     */
    fun getActiveWorkDaysInMonth(year: Int, month: Int): Int {
        val (start, end) = getMonthRange(year, month)
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT timestamp, status, cancellation_fee, acceptance_evidence FROM rides WHERE timestamp BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString())
        )
        val activeDays = mutableSetOf<Int>()
        val cal = Calendar.getInstance()
        while (cursor.moveToNext()) {
            val ts = cursor.getLong(0)
            val status = cursor.getString(1) ?: RideRecord.STATUS_ESTIMADO
            val fee = cursor.getDouble(2)
            val evidence = cursor.getString(3) ?: RideRecord.EVIDENCE_LEGACY
            if (RideRecord.isBillable(status, evidence, fee)) {
                cal.timeInMillis = ts
                activeDays.add(cal.get(Calendar.DAY_OF_MONTH))
            }
        }
        cursor.close()
        return activeDays.size
    }

    /**
     * Retorna o dia do mês (1..31) em que o Lucro Operacional acumulado atingiu targetAmount ("Dia da Alforria").
     * Retorna null se o acumulado do mês não atingiu targetAmount.
     */
    fun getDayOfMonthWhenProfitReached(year: Int, month: Int, targetAmount: Double): Int? {
        if (targetAmount <= 0.0) return 1
        val (start, end) = getMonthRange(year, month)
        val db = readableDatabase
        val dailyDelta = DoubleArray(32)
        val cal = Calendar.getInstance()

        val rideCursor = db.rawQuery(
            "SELECT timestamp, net_profit, status, cancellation_fee, acceptance_evidence FROM rides WHERE timestamp BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString())
        )
        while (rideCursor.moveToNext()) {
            val ts = rideCursor.getLong(0)
            val netProfit = rideCursor.getDouble(1)
            val status = rideCursor.getString(2) ?: RideRecord.STATUS_ESTIMADO
            val fee = rideCursor.getDouble(3)
            val evidence = rideCursor.getString(4) ?: RideRecord.EVIDENCE_LEGACY
            cal.timeInMillis = ts
            val day = cal.get(Calendar.DAY_OF_MONTH).coerceIn(1, 31)
            if (!RideRecord.isBillable(status, evidence, fee)) continue
            if (status == RideRecord.STATUS_CANCELADO_TAXA) {
                dailyDelta[day] += fee
            } else {
                dailyDelta[day] += netProfit
            }
        }
        rideCursor.close()

        val expCursor = db.rawQuery(
            "SELECT timestamp, amount FROM expenses WHERE timestamp BETWEEN ? AND ?",
            arrayOf(start.toString(), end.toString())
        )
        while (expCursor.moveToNext()) {
            val ts = expCursor.getLong(0)
            val amount = expCursor.getDouble(1)
            cal.timeInMillis = ts
            val day = cal.get(Calendar.DAY_OF_MONTH).coerceIn(1, 31)
            dailyDelta[day] -= amount
        }
        expCursor.close()

        var cumulative = 0.0
        var reachedDay: Int? = null
        for (d in 1..31) {
            cumulative += dailyDelta[d]
            if (cumulative >= targetAmount && reachedDay == null) {
                reachedDay = d
            } else if (cumulative < targetAmount) {
                reachedDay = null
            }
        }
        return reachedDay
    }

    /**
     * Retorna o menor timestamp registrado entre corridas válidas e despesas para identificar a data de início de uso do app.
     */
    fun getFirstActivityTimestamp(): Long? {
        val db = readableDatabase
        var minTs: Long? = null

        val rCursor = db.rawQuery(
            "SELECT MIN(timestamp) FROM rides WHERE (status IS NULL OR status != 'OFERTA') AND timestamp > 0",
            null
        )
        if (rCursor.moveToFirst() && !rCursor.isNull(0)) {
            val ts = rCursor.getLong(0)
            if (ts > 0) minTs = ts
        }
        rCursor.close()

        val eCursor = db.rawQuery("SELECT MIN(timestamp) FROM expenses WHERE timestamp > 0", null)
        if (eCursor.moveToFirst() && !eCursor.isNull(0)) {
            val ts = eCursor.getLong(0)
            if (ts > 0) {
                minTs = if (minTs == null) ts else minOf(minTs!!, ts)
            }
        }
        eCursor.close()

        return minTs
    }

    /**
     * Retorna todos os meses "YYYY-MM" estritamente anteriores a beforeYearMonth (padrão: mês atual)
     * que possuem atividade registrada (corridas ou despesas), em ordem cronológica crescente.
     */
    fun getDistinctHistoricalYearMonths(beforeYearMonth: String = getCurrentYearMonth()): List<String> {
        val db = readableDatabase
        val monthsSet = sortedSetOf<String>()
        val cal = Calendar.getInstance()

        val rCursor = db.rawQuery(
            "SELECT timestamp FROM rides WHERE (status IS NULL OR status != 'OFERTA') AND timestamp > 0",
            null
        )
        while (rCursor.moveToNext()) {
            cal.timeInMillis = rCursor.getLong(0)
            val ym = formatYearMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
            if (deltaMonths(ym, beforeYearMonth) > 0) {
                monthsSet.add(ym)
            }
        }
        rCursor.close()

        val eCursor = db.rawQuery("SELECT timestamp FROM expenses WHERE timestamp > 0", null)
        while (eCursor.moveToNext()) {
            cal.timeInMillis = eCursor.getLong(0)
            val ym = formatYearMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
            if (deltaMonths(ym, beforeYearMonth) > 0) {
                monthsSet.add(ym)
            }
        }
        eCursor.close()

        return monthsSet.toList()
    }

    // =========================================================================
    // COMPROMISSOS, PARCELAS E DÍVIDAS DO MÊS (ABA 2 - GESTÃO FINANCEIRA)
    // =========================================================================

    /**
     * Materializa de forma determinística e idempotente (INSERT OR IGNORE) as entradas do mês YYYY-MM
     * para todas as obrigações ativas que se aplicam àquela competência.
     */
    fun ensureMonthMaterialized(yearMonth: String) {
        val (targetYear, targetMonth) = parseYearMonth(yearMonth)
        val activeObligations = getAllMonthlyObligations(onlyActive = true)
        if (activeObligations.isEmpty()) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            for (ob in activeObligations) {
                val delta = deltaMonths(ob.startYearMonth, yearMonth)
                if (delta < 0) continue
                if (ob.endYearMonth != null && deltaMonths(yearMonth, ob.endYearMonth) < 0) continue

                var installmentNum = 0
                var totalInst = ob.totalInstallments.coerceAtLeast(1)
                var shouldMaterialize = true

                when (ob.type) {
                    MonthlyObligation.TYPE_VARIABLE_MONTHLY -> {
                        shouldMaterialize = (yearMonth == ob.startYearMonth)
                    }
                    MonthlyObligation.TYPE_INSTALLMENT -> {
                        installmentNum = ob.initialInstallment + delta
                        if (installmentNum < 1 || installmentNum > totalInst) {
                            shouldMaterialize = false
                        }
                    }
                    MonthlyObligation.TYPE_DEBT_OPEN -> {
                        // Se a dívida aberta já foi quitada em um mês anterior, não gera cobrança em meses futuros
                        val paidCursor = db.rawQuery(
                            "SELECT 1 FROM monthly_obligation_entries WHERE obligation_id = ? AND status = ? AND year_month < ? LIMIT 1",
                            arrayOf(ob.id.toString(), MonthlyObligationEntry.STATUS_PAID, yearMonth)
                        )
                        val alreadyPaidEarlier = paidCursor.moveToFirst()
                        paidCursor.close()
                        if (alreadyPaidEarlier) {
                            shouldMaterialize = false
                        }
                    }
                    MonthlyObligation.TYPE_FIXED_RECURRING -> {
                        installmentNum = 0
                        totalInst = 0
                    }
                }

                if (shouldMaterialize) {
                    val effectiveDueDay = clampDueDayForMonth(targetYear, targetMonth, ob.dueDay)
                    val cv = ContentValues().apply {
                        put("obligation_id", ob.id)
                        put("year_month", yearMonth)
                        put("amount", ob.defaultAmount)
                        put("due_day", effectiveDueDay)
                        put("installment_number", installmentNum)
                        put("total_installments", if (ob.type == MonthlyObligation.TYPE_INSTALLMENT) totalInst else 0)
                        put("status", MonthlyObligationEntry.STATUS_PENDING)
                        put("paid_at", 0L)
                    }
                    db.insertWithOnConflict(
                        "monthly_obligation_entries",
                        null,
                        cv,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Cadastra uma nova obrigação mensal (Parcelamento, Conta Fixa, Dívida Aberta ou Gasto Variável do Mês)
     * e já materializa a entrada para a competência informada.
     */
    fun insertMonthlyObligation(
        title: String,
        category: String,
        type: String,
        defaultAmount: Double,
        dueDay: Int,
        startYearMonth: String,
        initialInstallment: Int = 1,
        totalInstallments: Int = 1,
        totalDebtBalance: Double = 0.0,
        notes: String = "",
        markPaidInStartMonth: Boolean = false
    ): Long {
        val safeInitial = initialInstallment.coerceAtLeast(1)
        val safeTotal = maxOf(safeInitial, totalInstallments.coerceAtLeast(1))
        val computedEndYearMonth: String? = when (type) {
            MonthlyObligation.TYPE_INSTALLMENT -> {
                val remainingAfterStart = (safeTotal - safeInitial).coerceAtLeast(0)
                addMonths(startYearMonth, remainingAfterStart)
            }
            MonthlyObligation.TYPE_VARIABLE_MONTHLY -> startYearMonth
            else -> null
        }

        val now = System.currentTimeMillis()
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("title", title.trim())
            put("category", category)
            put("type", type)
            put("default_amount", defaultAmount)
            put("due_day", dueDay.coerceIn(1, 31))
            put("start_year_month", startYearMonth)
            if (computedEndYearMonth != null) {
                put("end_year_month", computedEndYearMonth)
            } else {
                putNull("end_year_month")
            }
            put("initial_installment", if (type == MonthlyObligation.TYPE_INSTALLMENT) safeInitial else 1)
            put("total_installments", if (type == MonthlyObligation.TYPE_INSTALLMENT) safeTotal else 1)
            put("total_debt_balance", totalDebtBalance)
            put("notes", notes.trim())
            put("is_active", 1)
            put("created_at", now)
        }

        val obligationId = db.insert("monthly_obligations", null, cv)
        if (obligationId > 0) {
            ensureMonthMaterialized(startYearMonth)
            if (markPaidInStartMonth) {
                val entryUpdate = ContentValues().apply {
                    put("status", MonthlyObligationEntry.STATUS_PAID)
                    put("paid_at", now)
                }
                db.update(
                    "monthly_obligation_entries",
                    entryUpdate,
                    "obligation_id = ? AND year_month = ?",
                    arrayOf(obligationId.toString(), startYearMonth)
                )
            }
        }
        return obligationId
    }

    /**
     * Atualiza uma obrigação mensal existente e sincroniza a entrada do mês em foco (targetYearMonth).
     */
    fun updateMonthlyObligation(
        obligationId: Long,
        title: String,
        category: String,
        type: String,
        defaultAmount: Double,
        dueDay: Int,
        targetYearMonth: String,
        currentInstallmentInTargetMonth: Int = 1,
        totalInstallments: Int = 1,
        totalDebtBalance: Double = 0.0,
        notes: String = "",
        isPaidInTargetMonth: Boolean? = null
    ): Boolean {
        val safeCurrentInst = currentInstallmentInTargetMonth.coerceAtLeast(1)
        val safeTotalInst = maxOf(safeCurrentInst, totalInstallments.coerceAtLeast(1))

        val computedEndYearMonth: String? = when (type) {
            MonthlyObligation.TYPE_INSTALLMENT -> {
                val remaining = (safeTotalInst - safeCurrentInst).coerceAtLeast(0)
                addMonths(targetYearMonth, remaining)
            }
            MonthlyObligation.TYPE_VARIABLE_MONTHLY -> targetYearMonth
            else -> null
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("title", title.trim())
                put("category", category)
                put("type", type)
                put("default_amount", defaultAmount)
                put("due_day", dueDay.coerceIn(1, 31))
                put("start_year_month", targetYearMonth)
                if (computedEndYearMonth != null) {
                    put("end_year_month", computedEndYearMonth)
                } else {
                    putNull("end_year_month")
                }
                put("initial_installment", if (type == MonthlyObligation.TYPE_INSTALLMENT) safeCurrentInst else 1)
                put("total_installments", if (type == MonthlyObligation.TYPE_INSTALLMENT) safeTotalInst else 1)
                put("total_debt_balance", totalDebtBalance)
                put("notes", notes.trim())
            }
            val updated = db.update("monthly_obligations", cv, "id = ?", arrayOf(obligationId.toString())) > 0

            val (ty, tm) = parseYearMonth(targetYearMonth)
            val clampedDay = clampDueDayForMonth(ty, tm, dueDay)
            val entryCv = ContentValues().apply {
                put("amount", defaultAmount)
                put("due_day", clampedDay)
                put("installment_number", if (type == MonthlyObligation.TYPE_INSTALLMENT) safeCurrentInst else 0)
                put("total_installments", if (type == MonthlyObligation.TYPE_INSTALLMENT) safeTotalInst else 0)
                if (isPaidInTargetMonth != null) {
                    if (isPaidInTargetMonth) {
                        put("status", MonthlyObligationEntry.STATUS_PAID)
                        put("paid_at", System.currentTimeMillis())
                    } else {
                        put("status", MonthlyObligationEntry.STATUS_PENDING)
                        put("paid_at", 0L)
                    }
                }
            }
            val entryUpdated = db.update(
                "monthly_obligation_entries",
                entryCv,
                "obligation_id = ? AND year_month = ?",
                arrayOf(obligationId.toString(), targetYearMonth)
            )
            if (entryUpdated == 0) {
                ensureMonthMaterialized(targetYearMonth)
                if (isPaidInTargetMonth != null) {
                    db.update(
                        "monthly_obligation_entries",
                        entryCv,
                        "obligation_id = ? AND year_month = ?",
                        arrayOf(obligationId.toString(), targetYearMonth)
                    )
                }
            }

            // Atualiza entradas futuras ainda pendentes com o novo valor padrão e vencimento
            val futureCv = ContentValues().apply {
                put("amount", defaultAmount)
                put("due_day", dueDay.coerceIn(1, 28))
            }
            db.update(
                "monthly_obligation_entries",
                futureCv,
                "obligation_id = ? AND year_month > ? AND status = ?",
                arrayOf(obligationId.toString(), targetYearMonth, MonthlyObligationEntry.STATUS_PENDING)
            )

            db.setTransactionSuccessful()
            return updated
        } finally {
            db.endTransaction()
        }
    }

    fun deleteMonthlyObligation(obligationId: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete("monthly_obligation_entries", "obligation_id = ?", arrayOf(obligationId.toString()))
            val deleted = db.delete("monthly_obligations", "id = ?", arrayOf(obligationId.toString())) > 0
            db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    fun getObligationEntriesForMonth(yearMonth: String): List<MonthlyObligationJoined> {
        ensureMonthMaterialized(yearMonth)
        val list = mutableListOf<MonthlyObligationJoined>()
        val db = readableDatabase
        val sql = """
            SELECT 
                e.id,
                e.obligation_id,
                o.title,
                o.category,
                o.type,
                e.year_month,
                e.amount,
                o.default_amount,
                e.due_day,
                e.installment_number,
                o.initial_installment,
                e.total_installments,
                o.start_year_month,
                o.end_year_month,
                o.total_debt_balance,
                e.status,
                e.paid_at,
                o.notes,
                o.is_active
            FROM monthly_obligation_entries e
            INNER JOIN monthly_obligations o ON e.obligation_id = o.id
            WHERE e.year_month = ? AND e.status != ?
            ORDER BY 
                CASE WHEN e.status = 'PAID' THEN 1 ELSE 0 END ASC,
                e.due_day ASC,
                e.amount DESC
        """.trimIndent()

        val cursor = db.rawQuery(sql, arrayOf(yearMonth, MonthlyObligationEntry.STATUS_SKIPPED))
        while (cursor.moveToNext()) {
            list.add(
                MonthlyObligationJoined(
                    entryId = cursor.getLong(0),
                    obligationId = cursor.getLong(1),
                    title = cursor.getString(2) ?: "",
                    category = cursor.getString(3) ?: MonthlyObligation.CATEGORY_PESSOAL,
                    type = cursor.getString(4) ?: MonthlyObligation.TYPE_FIXED_RECURRING,
                    yearMonth = cursor.getString(5) ?: yearMonth,
                    amount = cursor.getDouble(6),
                    defaultAmount = cursor.getDouble(7),
                    dueDay = cursor.getInt(8),
                    installmentNumber = cursor.getInt(9),
                    initialInstallment = cursor.getInt(10),
                    totalInstallments = cursor.getInt(11),
                    startYearMonth = cursor.getString(12) ?: yearMonth,
                    endYearMonth = if (cursor.isNull(13)) null else cursor.getString(13),
                    totalDebtBalance = cursor.getDouble(14),
                    status = cursor.getString(15) ?: MonthlyObligationEntry.STATUS_PENDING,
                    paidAt = cursor.getLong(16),
                    notes = cursor.getString(17) ?: "",
                    isActive = cursor.getInt(18) == 1
                )
            )
        }
        cursor.close()
        return list
    }

    fun updateObligationEntryStatus(
        entryId: Long,
        status: String,
        paidAt: Long = if (status == MonthlyObligationEntry.STATUS_PAID) System.currentTimeMillis() else 0L
    ): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("status", status)
            put("paid_at", if (status == MonthlyObligationEntry.STATUS_PAID) paidAt else 0L)
        }
        return db.update("monthly_obligation_entries", cv, "id = ?", arrayOf(entryId.toString())) > 0
    }

    fun toggleObligationEntryPaid(entryId: Long): String {
        val db = writableDatabase
        var currentStatus = MonthlyObligationEntry.STATUS_PENDING
        val cursor = db.rawQuery(
            "SELECT status FROM monthly_obligation_entries WHERE id = ? LIMIT 1",
            arrayOf(entryId.toString())
        )
        if (cursor.moveToFirst()) {
            currentStatus = cursor.getString(0) ?: MonthlyObligationEntry.STATUS_PENDING
        }
        cursor.close()

        val newStatus = if (currentStatus == MonthlyObligationEntry.STATUS_PAID) {
            MonthlyObligationEntry.STATUS_PENDING
        } else {
            MonthlyObligationEntry.STATUS_PAID
        }
        updateObligationEntryStatus(entryId, newStatus)
        return newStatus
    }

    fun updateObligationEntryAmount(entryId: Long, amount: Double): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("amount", amount)
        }
        return db.update("monthly_obligation_entries", cv, "id = ?", arrayOf(entryId.toString())) > 0
    }

    fun getAllMonthlyObligations(onlyActive: Boolean = false): List<MonthlyObligation> {
        val list = mutableListOf<MonthlyObligation>()
        val db = readableDatabase
        val sql = if (onlyActive) {
            "SELECT id, title, category, type, default_amount, due_day, start_year_month, end_year_month, initial_installment, total_installments, total_debt_balance, notes, is_active, created_at FROM monthly_obligations WHERE is_active = 1 ORDER BY due_day ASC, id ASC"
        } else {
            "SELECT id, title, category, type, default_amount, due_day, start_year_month, end_year_month, initial_installment, total_installments, total_debt_balance, notes, is_active, created_at FROM monthly_obligations ORDER BY id ASC"
        }
        val cursor = db.rawQuery(sql, null)
        while (cursor.moveToNext()) {
            list.add(
                MonthlyObligation(
                    id = cursor.getLong(0),
                    title = cursor.getString(1) ?: "",
                    category = cursor.getString(2) ?: MonthlyObligation.CATEGORY_PESSOAL,
                    type = cursor.getString(3) ?: MonthlyObligation.TYPE_FIXED_RECURRING,
                    defaultAmount = cursor.getDouble(4),
                    dueDay = cursor.getInt(5),
                    startYearMonth = cursor.getString(6) ?: getCurrentYearMonth(),
                    endYearMonth = if (cursor.isNull(7)) null else cursor.getString(7),
                    initialInstallment = cursor.getInt(8),
                    totalInstallments = cursor.getInt(9),
                    totalDebtBalance = cursor.getDouble(10),
                    notes = cursor.getString(11) ?: "",
                    isActive = cursor.getInt(12) == 1,
                    createdAt = cursor.getLong(13)
                )
            )
        }
        cursor.close()
        return list
    }

    fun getActiveInstallmentObligations(): List<MonthlyObligation> {
        return getAllMonthlyObligations(onlyActive = true).filter {
            it.type == MonthlyObligation.TYPE_INSTALLMENT
        }
    }

    fun getAllMonthlyObligationEntries(): List<MonthlyObligationEntry> {
        val list = mutableListOf<MonthlyObligationEntry>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, obligation_id, year_month, amount, due_day, installment_number, total_installments, status, paid_at FROM monthly_obligation_entries ORDER BY year_month ASC, due_day ASC",
            null
        )
        while (cursor.moveToNext()) {
            list.add(
                MonthlyObligationEntry(
                    id = cursor.getLong(0),
                    obligationId = cursor.getLong(1),
                    yearMonth = cursor.getString(2) ?: "",
                    amount = cursor.getDouble(3),
                    dueDay = cursor.getInt(4),
                    installmentNumber = cursor.getInt(5),
                    totalInstallments = cursor.getInt(6),
                    status = cursor.getString(7) ?: MonthlyObligationEntry.STATUS_PENDING,
                    paidAt = cursor.getLong(8)
                )
            )
        }
        cursor.close()
        return list
    }

    fun restoreMonthlyObligations(
        obligations: List<MonthlyObligation>,
        entries: List<MonthlyObligationEntry>,
        clearExisting: Boolean = false
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (clearExisting) {
                db.delete("monthly_obligation_entries", null, null)
                db.delete("monthly_obligations", null, null)
            }

            val idMap = mutableMapOf<Long, Long>()

            for (ob in obligations) {
                val cursor = db.rawQuery(
                    "SELECT id FROM monthly_obligations WHERE title = ? AND type = ? AND start_year_month = ? AND ABS(default_amount - ?) < 0.01 LIMIT 1",
                    arrayOf(ob.title, ob.type, ob.startYearMonth, ob.defaultAmount.toString())
                )
                val existingId = if (cursor.moveToFirst()) cursor.getLong(0) else null
                cursor.close()

                if (existingId != null) {
                    idMap[ob.id] = existingId
                } else {
                    val cv = ContentValues().apply {
                        put("title", ob.title)
                        put("category", ob.category)
                        put("type", ob.type)
                        put("default_amount", ob.defaultAmount)
                        put("due_day", ob.dueDay)
                        put("start_year_month", ob.startYearMonth)
                        if (ob.endYearMonth != null) {
                            put("end_year_month", ob.endYearMonth)
                        } else {
                            putNull("end_year_month")
                        }
                        put("initial_installment", ob.initialInstallment)
                        put("total_installments", ob.totalInstallments)
                        put("total_debt_balance", ob.totalDebtBalance)
                        put("notes", ob.notes)
                        put("is_active", if (ob.isActive) 1 else 0)
                        put("created_at", ob.createdAt)
                    }
                    val newId = db.insert("monthly_obligations", null, cv)
                    if (newId > 0) {
                        idMap[ob.id] = newId
                    }
                }
            }

            for (entry in entries) {
                val mappedObligationId = idMap[entry.obligationId] ?: continue
                val cursor = db.rawQuery(
                    "SELECT id, status FROM monthly_obligation_entries WHERE obligation_id = ? AND year_month = ? LIMIT 1",
                    arrayOf(mappedObligationId.toString(), entry.yearMonth)
                )
                if (cursor.moveToFirst()) {
                    val existingEntryId = cursor.getLong(0)
                    val existingStatus = cursor.getString(1) ?: MonthlyObligationEntry.STATUS_PENDING
                    cursor.close()
                    if (existingStatus != MonthlyObligationEntry.STATUS_PAID && entry.status == MonthlyObligationEntry.STATUS_PAID) {
                        val updateCv = ContentValues().apply {
                            put("amount", entry.amount)
                            put("status", entry.status)
                            put("paid_at", entry.paidAt)
                        }
                        db.update(
                            "monthly_obligation_entries",
                            updateCv,
                            "id = ?",
                            arrayOf(existingEntryId.toString())
                        )
                    }
                } else {
                    cursor.close()
                    val cv = ContentValues().apply {
                        put("obligation_id", mappedObligationId)
                        put("year_month", entry.yearMonth)
                        put("amount", entry.amount)
                        put("due_day", entry.dueDay)
                        put("installment_number", entry.installmentNumber)
                        put("total_installments", entry.totalInstallments)
                        put("status", entry.status)
                        put("paid_at", entry.paidAt)
                    }
                    db.insertWithOnConflict(
                        "monthly_obligation_entries",
                        null,
                        cv,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // =========================================================================
    // LISTA NEGRA DE BAIRROS (ABA 3 & REQUISITO R2)
    // =========================================================================

    fun insertBlacklistItem(
        rawTerm: String,
        normalizedTerm: String,
        latitude: Double? = null,
        longitude: Double? = null,
        createdAt: Long = System.currentTimeMillis()
    ): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("raw_term", rawTerm)
            put("normalized_term", normalizedTerm)
            if (latitude != null) put("latitude", latitude) else putNull("latitude")
            if (longitude != null) put("longitude", longitude) else putNull("longitude")
            put("created_at", createdAt)
        }
        return db.insert("blacklist_neighborhoods", null, cv)
    }

    fun deleteBlacklistItem(id: Long): Boolean {
        val db = writableDatabase
        return db.delete("blacklist_neighborhoods", "id = ?", arrayOf(id.toString())) > 0
    }

    fun getAllBlacklistItems(): List<BlacklistItem> {
        val list = mutableListOf<BlacklistItem>()
        val db = readableDatabase
        val cursor = db.query(
            "blacklist_neighborhoods",
            null,
            null,
            null,
            null,
            null,
            "created_at DESC"
        )
        cursor.use { c ->
            val idxId = c.getColumnIndexOrThrow("id")
            val idxRaw = c.getColumnIndexOrThrow("raw_term")
            val idxNorm = c.getColumnIndexOrThrow("normalized_term")
            val idxLat = c.getColumnIndexOrThrow("latitude")
            val idxLng = c.getColumnIndexOrThrow("longitude")
            val idxCreated = c.getColumnIndexOrThrow("created_at")

            while (c.moveToNext()) {
                val lat = if (c.isNull(idxLat)) null else c.getDouble(idxLat)
                val lng = if (c.isNull(idxLng)) null else c.getDouble(idxLng)
                list.add(
                    BlacklistItem(
                        id = c.getLong(idxId),
                        rawTerm = c.getString(idxRaw),
                        normalizedTerm = c.getString(idxNorm),
                        latitude = lat,
                        longitude = lng,
                        createdAt = c.getLong(idxCreated)
                    )
                )
            }
        }
        return list
    }

    fun updateBlacklistItemCoordinates(id: Long, lat: Double, lng: Double): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("latitude", lat)
            put("longitude", lng)
        }
        return db.update("blacklist_neighborhoods", cv, "id = ?", arrayOf(id.toString())) > 0
    }

    fun clearAllBlacklistItems() {
        val db = writableDatabase
        db.delete("blacklist_neighborhoods", null, null)
    }

    fun restoreBlacklistItems(items: List<BlacklistItem>, clearExisting: Boolean = false) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (clearExisting) {
                db.delete("blacklist_neighborhoods", null, null)
            }
            for (item in items) {
                val cv = ContentValues().apply {
                    put("raw_term", item.rawTerm)
                    put("normalized_term", item.normalizedTerm)
                    if (item.latitude != null) put("latitude", item.latitude) else putNull("latitude")
                    if (item.longitude != null) put("longitude", item.longitude) else putNull("longitude")
                    put("created_at", item.createdAt)
                }
                db.insert("blacklist_neighborhoods", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
