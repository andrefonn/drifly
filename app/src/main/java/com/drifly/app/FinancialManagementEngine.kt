package com.drifly.app

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

enum class ObligationUrgency {
    OVERDUE,
    DUE_SOON,
    UPCOMING,
    PAID
}

enum class IcrLevel {
    HEALTHY,
    MODERATE,
    CRITICAL
}

data class ObligationUiItem(
    val entryId: Long,
    val obligationId: Long,
    val title: String,
    val category: String,
    val categoryLabel: String,
    val categoryEmoji: String,
    val type: String,
    val typeBadgeText: String,
    val yearMonth: String,
    val amount: Double,
    val defaultAmount: Double,
    val dueDay: Int,
    val installmentNumber: Int,
    val initialInstallment: Int,
    val totalInstallments: Int,
    val installmentProgressPercent: Int,
    val remainingInstallments: Int,
    val estimatedEndMonthLabel: String?,
    val status: String,
    val isPaid: Boolean,
    val paidAt: Long,
    val computedUrgency: ObligationUrgency,
    val dueStatusText: String,
    val notes: String
)

data class MonthlyManagementReport(
    val year: Int,
    val month: Int,
    val yearMonth: String,
    val monthDisplayLabel: String,
    val monthStatusPillText: String,
    val isCurrentMonth: Boolean,
    val isPastMonth: Boolean,
    val isFutureMonth: Boolean,

    // 1. Cascata do Dinheiro (Pista -> Contas -> Bolso)
    val grossRides: Double,
    val autoKmCost: Double,
    val streetExpenses: Double,
    val totalStreetCosts: Double,
    val operationalProfit: Double,
    val rideCount: Int,
    val activeWorkDays: Int,

    // 2. Obrigações do Mês
    val totalObligations: Double,
    val paidObligations: Double,
    val unpaidObligations: Double,
    val totalEntriesCount: Int,
    val paidEntriesCount: Int,
    val pendingEntriesCount: Int,

    // 3. Salário Mensal Real, Caixa Livre Hoje e Projeção de Fechamento
    val realSalary: Double,
    val heroDisplaySalary: Double,
    val isPhase2ProfitInPocket: Boolean,
    val pocketCashToday: Double,
    val reservedForUnpaid: Double,
    val pocketCashSubtitle: String,
    val avgNetPerWorkDay: Double,
    val estimatedRemainingWorkDays: Int,
    val projectedOpProfit: Double,
    val projectedSalary: Double,
    val projectedContextLabel: String,

    // 4. Média Salarial Mensal Estabilizada
    val averageMonthlySalary: Double,
    val averageContextLabel: String,
    val salaryTrendPercent: Double,
    val salaryTrendBadgeText: String,
    val outlierNote: String?,

    // 5. Ponto de Equilíbrio / Dia da Alforria
    val breakEvenPercent: Int,
    val isBreakEvenReached: Boolean,
    val missingForBreakEven: Double,
    val daysToBreakEven: Int,
    val freedomDayOfMonth: Int?,
    val freedomHeaderLabel: String,
    val freedomStatusText: String,

    // 6. Índice de Comprometimento da Renda (ICR)
    val icrPercent: Int,
    val icrLevel: IcrLevel,
    val icrLabel: String,

    // 7. Radar de 7 Dias
    val next7DaysPressureAmount: Double,
    val next7DaysPressureCount: Int,
    val next7DaysWorkDaysEquivalent: Double,
    val overdueCount: Int,
    val overdueAmount: Double,
    val radarTitleText: String,
    val radarSubtitleText: String,

    // 8. Lista Enriquecida e Contadores de Filtro
    val entries: List<ObligationUiItem>,
    val countAll: Int,
    val countInstallments: Int,
    val countFixed: Int,
    val countDebts: Int,
    val countVariable: Int,

    // 9. Simulador de Liberdade Financeira & Meta Diária Sugerida
    val freedomInsights: List<String>,
    val debtFreePotentialSalary: Double,
    val dailyBillBurden: Double,
    val suggestedDailyGoal: Double,
    val syncDailyGoalLabel: String
)

object FinancialManagementEngine {

    const val WEEKLY_TO_MONTHLY_MULTIPLIER = 4.333333333333333

    private val ptBrLocale = Locale("pt", "BR")

    private val monthNamesUpper = arrayOf(
        "JANEIRO", "FEVEREIRO", "MARÇO", "ABRIL", "MAIO", "JUNHO",
        "JULHO", "AGOSTO", "SETEMBRO", "OUTUBRO", "NOVEMBRO", "DEZEMBRO"
    )

    private val monthNamesShort = arrayOf(
        "Jan", "Fev", "Mar", "Abr", "Mai", "Jun",
        "Jul", "Ago", "Set", "Out", "Nov", "Dez"
    )

    private val monthNamesTitle = arrayOf(
        "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
        "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"
    )

    fun formatCurrency(value: Double): String {
        val nf = NumberFormat.getCurrencyInstance(ptBrLocale)
        return nf.format(value)
    }

    fun getMonthFullLabel(year: Int, month: Int): String {
        val idx = (month - 1).coerceIn(0, 11)
        return "${monthNamesUpper[idx]} $year"
    }

    fun getMonthShortYearLabel(yearMonth: String): String {
        val (y, m) = DatabaseHelper.parseYearMonth(yearMonth)
        val idx = (m - 1).coerceIn(0, 11)
        return "${monthNamesShort[idx]}/$y"
    }

    fun getMonthTitleName(yearMonth: String): String {
        val (_, m) = DatabaseHelper.parseYearMonth(yearMonth)
        val idx = (m - 1).coerceIn(0, 11)
        return monthNamesTitle[idx]
    }

    fun formatInstallmentEndPreview(
        startYearMonth: String,
        currentInstallment: Int,
        totalInstallments: Int
    ): String {
        val safeCur = currentInstallment.coerceAtLeast(1)
        val safeTot = max(safeCur, totalInstallments.coerceAtLeast(1))
        val remainingMonths = (safeTot - safeCur).coerceAtLeast(0)
        val endYm = DatabaseHelper.addMonths(startYearMonth, remainingMonths)
        val endLabel = getMonthShortYearLabel(endYm)
        return if (remainingMonths == 0) {
            "Última parcela neste mês ($endLabel)!"
        } else {
            "Faltam $remainingMonths ${if (remainingMonths == 1) "parcela" else "parcelas"} • Termina em $endLabel"
        }
    }

    fun convertWeeklyToMonthly(weeklyAmount: Double): Double {
        return Math.round(weeklyAmount * WEEKLY_TO_MONTHLY_MULTIPLIER * 100.0) / 100.0
    }

    fun buildMonthlyReport(
        db: DatabaseHelper,
        yearMonth: String,
        currentDailyGoal: Double = 250.0
    ): MonthlyManagementReport {
        val (y, m) = DatabaseHelper.parseYearMonth(yearMonth)
        return buildMonthlyReport(db, y, m, currentDailyGoal)
    }

    /**
     * Motor principal de Gestão Financeira e Salário Real.
     * @param year Ano com 4 dígitos (ex: 2026)
     * @param month Mês de 1 a 12 (1 = Janeiro ... 12 = Dezembro)
     * @param currentDailyGoal Meta diária atual configurada na Aba 1
     */
    fun buildMonthlyReport(
        db: DatabaseHelper,
        year: Int,
        month: Int,
        currentDailyGoal: Double = 250.0
    ): MonthlyManagementReport {
        val safeMonth = month.coerceIn(1, 12)
        val targetYm = DatabaseHelper.formatYearMonth(year, safeMonth)
        val currentYm = DatabaseHelper.getCurrentYearMonth()
        val deltaFromNow = DatabaseHelper.deltaMonths(currentYm, targetYm)

        val isCurrentMonth = (deltaFromNow == 0)
        val isPastMonth = (deltaFromNow < 0)
        val isFutureMonth = (deltaFromNow > 0)

        val monthStatusPillText = when {
            isCurrentMonth -> "MÊS ATUAL"
            isPastMonth -> "FECHADO"
            else -> "PLANEJADO"
        }

        val nowCal = Calendar.getInstance()
        val todayDay = nowCal.get(Calendar.DAY_OF_MONTH)

        val targetMonthCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, safeMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val totalDaysInMonth = targetMonthCal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // 1. Cascata Operacional da Pista (Sem Dupla Contagem)
        val summary = db.getSummaryForYearMonth(year, safeMonth)
        val ridesNetSum = db.getRidesNetSumForYearMonth(year, safeMonth)
        val grossRides = summary.totalGross
        val autoKmCost = (grossRides - ridesNetSum).coerceAtLeast(0.0)
        val streetExpenses = summary.totalExpenses
        val totalStreetCosts = autoKmCost + streetExpenses
        val operationalProfit = summary.totalNet // OpProfit_m = ridesNetSum - streetExpenses
        val activeWorkDays = db.getActiveWorkDaysInMonth(year, safeMonth)

        // 2. Materialização e Leitura das Obrigações do Mês
        val rawEntries = db.getObligationEntriesForMonth(targetYm)

        var totalObligations = 0.0
        var paidObligations = 0.0
        var unpaidObligations = 0.0
        var paidEntriesCount = 0
        var pendingEntriesCount = 0

        val enrichedEntries = mutableListOf<ObligationUiItem>()

        for (raw in rawEntries) {
            val isPaid = (raw.status == MonthlyObligationEntry.STATUS_PAID)
            totalObligations += raw.amount
            if (isPaid) {
                paidObligations += raw.amount
                paidEntriesCount++
            } else {
                unpaidObligations += raw.amount
                pendingEntriesCount++
            }

            val urgency = computeUrgency(
                isPaid = isPaid,
                isPastMonth = isPastMonth,
                isCurrentMonth = isCurrentMonth,
                dueDay = raw.dueDay,
                todayDay = todayDay
            )

            val (catLabel, catEmoji) = getCategoryMetadata(raw.category)
            val isInstallment = (raw.type == MonthlyObligation.TYPE_INSTALLMENT && raw.totalInstallments > 0)
            val instNum = if (isInstallment) raw.installmentNumber.coerceAtLeast(1) else 0
            val totInst = if (isInstallment) raw.totalInstallments.coerceAtLeast(1) else 0
            val instPct = if (isInstallment) ((instNum.toDouble() / totInst.toDouble()) * 100.0).roundToInt().coerceIn(1, 100) else 0
            val remInst = if (isInstallment) (totInst - instNum).coerceAtLeast(0) else 0
            val endMonthLabel = if (isInstallment) {
                val endYm = raw.endYearMonth ?: DatabaseHelper.addMonths(raw.yearMonth, remInst)
                getMonthShortYearLabel(endYm)
            } else null

            val typeBadgeText = when (raw.type) {
                MonthlyObligation.TYPE_INSTALLMENT -> String.format(Locale.US, "%02d/%02d", instNum, totInst)
                MonthlyObligation.TYPE_FIXED_RECURRING -> "FIXO"
                MonthlyObligation.TYPE_DEBT_OPEN -> "DÍVIDA"
                MonthlyObligation.TYPE_VARIABLE_MONTHLY -> "DO MÊS"
                else -> "CONTA"
            }

            val dueStatusText = buildDueStatusText(
                raw = raw,
                month = safeMonth,
                urgency = urgency,
                isCurrentMonth = isCurrentMonth,
                isPastMonth = isPastMonth,
                todayDay = todayDay
            )

            enrichedEntries.add(
                ObligationUiItem(
                    entryId = raw.entryId,
                    obligationId = raw.obligationId,
                    title = raw.title,
                    category = raw.category,
                    categoryLabel = catLabel,
                    categoryEmoji = catEmoji,
                    type = raw.type,
                    typeBadgeText = typeBadgeText,
                    yearMonth = raw.yearMonth,
                    amount = raw.amount,
                    defaultAmount = raw.defaultAmount,
                    dueDay = raw.dueDay,
                    installmentNumber = instNum,
                    initialInstallment = raw.initialInstallment,
                    totalInstallments = totInst,
                    installmentProgressPercent = instPct,
                    remainingInstallments = remInst,
                    estimatedEndMonthLabel = endMonthLabel,
                    status = raw.status,
                    isPaid = isPaid,
                    paidAt = raw.paidAt,
                    computedUrgency = urgency,
                    dueStatusText = dueStatusText,
                    notes = raw.notes
                )
            )
        }

        // Ordena: Atrasadas primeiro, depois Vence Logo, depois No Prazo, e Pagas por último
        val sortedEntries = enrichedEntries.sortedWith(
            compareBy<ObligationUiItem> {
                when (it.computedUrgency) {
                    ObligationUrgency.OVERDUE -> 0
                    ObligationUrgency.DUE_SOON -> 1
                    ObligationUrgency.UPCOMING -> 2
                    ObligationUrgency.PAID -> 3
                }
            }.thenBy { it.dueDay }.thenByDescending { it.amount }
        )

        // 3. Salário Mensal Real, Caixa em Mãos Hoje e Projeção de Fechamento
        val realSalary = operationalProfit - totalObligations
        val isPhase2ProfitInPocket = (operationalProfit >= totalObligations)
        val heroDisplaySalary = if (isCurrentMonth && totalObligations > 0.0 && !isPhase2ProfitInPocket) {
            0.0
        } else {
            realSalary
        }

        val pocketCashToday = operationalProfit - paidObligations
        val reservedForUnpaid = unpaidObligations
        val pocketCashSubtitle = when {
            isFutureMonth -> "Mês planejado: ${formatCurrency(totalObligations)} em compromissos previstos"
            unpaidObligations > 0.0 -> "Caixa em mãos hoje: ${formatCurrency(pocketCashToday)} (${formatCurrency(reservedForUnpaid)} reservados p/ contas)"
            totalObligations > 0.0 -> "Caixa livre no bolso: ${formatCurrency(pocketCashToday)} (100% das contas do mês pagas!)"
            else -> "Caixa acumulado da pista: ${formatCurrency(pocketCashToday)} (nenhuma conta cadastrada)"
        }

        val safeDailyGoal = if (currentDailyGoal > 0.0) currentDailyGoal else 200.0
        val daysElapsed = when {
            isCurrentMonth -> todayDay.coerceIn(1, totalDaysInMonth)
            isPastMonth -> totalDaysInMonth
            else -> 0
        }
        val daysRemaining = (totalDaysInMonth - daysElapsed).coerceAtLeast(0)

        val avgNetPerWorkDay = when {
            activeWorkDays > 0 && operationalProfit > 0.0 -> operationalProfit / activeWorkDays.toDouble()
            isCurrentMonth || isFutureMonth -> safeDailyGoal
            else -> 0.0
        }

        val workFreq = when {
            isPastMonth -> if (totalDaysInMonth > 0) (activeWorkDays.toDouble() / totalDaysInMonth.toDouble()).coerceIn(0.0, 1.0) else 0.0
            isFutureMonth -> 6.0 / 7.0
            daysElapsed <= 3 -> 6.0 / 7.0
            else -> (activeWorkDays.toDouble() / daysElapsed.toDouble()).coerceIn(0.45, 1.0)
        }

        val estimatedRemainingWorkDays = when {
            isPastMonth -> 0
            else -> (daysRemaining * workFreq).roundToInt().coerceAtLeast(0)
        }

        val projectedOpProfit = when {
            isPastMonth -> operationalProfit
            isCurrentMonth && activeWorkDays > 0 -> operationalProfit + (avgNetPerWorkDay * estimatedRemainingWorkDays)
            isCurrentMonth && activeWorkDays == 0 -> safeDailyGoal * estimatedRemainingWorkDays
            else -> safeDailyGoal * estimatedRemainingWorkDays
        }

        val projectedSalary = projectedOpProfit - totalObligations
        val projectedContextLabel = when {
            isPastMonth -> "Mês encerrado ($activeWorkDays ${if (activeWorkDays == 1) "dia rodado" else "dias rodados"})"
            activeWorkDays > 0 -> "Mantendo ritmo (${formatCurrency(avgNetPerWorkDay)}/dia • +$estimatedRemainingWorkDays dias)"
            else -> "Baseado na sua meta (${formatCurrency(safeDailyGoal)}/dia)"
        }

        // 4. Ponto de Equilíbrio / Dia da Alforria
        val isBreakEvenReached = (totalObligations <= 0.0 || operationalProfit >= totalObligations)
        val breakEvenPercent = when {
            totalObligations <= 0.0 -> if (operationalProfit > 0.0) 100 else 0
            operationalProfit >= totalObligations -> 100
            operationalProfit <= 0.0 -> 0
            else -> ((operationalProfit / totalObligations) * 100.0).toInt().coerceIn(1, 99)
        }
        val missingForBreakEven = (totalObligations - operationalProfit).coerceAtLeast(0.0)
        val effectiveDailyReference = max(avgNetPerWorkDay, safeDailyGoal).coerceAtLeast(50.0)
        val daysToBreakEven = if (missingForBreakEven <= 0.0) {
            0
        } else {
            ceil(missingForBreakEven / effectiveDailyReference).toInt().coerceAtLeast(1)
        }

        val freedomDayOfMonth: Int? = when {
            totalObligations <= 0.0 -> 1
            operationalProfit >= totalObligations -> {
                db.getDayOfMonthWhenProfitReached(year, safeMonth, totalObligations) ?: daysElapsed.coerceAtLeast(1)
            }
            isPastMonth -> null
            else -> {
                val calDaysNeeded = ceil(daysToBreakEven / workFreq.coerceAtLeast(0.5)).toInt()
                val estDay = (if (isCurrentMonth) todayDay else 1) + calDaysNeeded
                if (estDay <= totalDaysInMonth) estDay else null
            }
        }

        val freedomHeaderLabel = when {
            totalObligations <= 0.0 -> "PONTO DE EQUILÍBRIO (\"DIA DA ALFORRIA\"): LIVRE"
            isBreakEvenReached -> "PONTO DE EQUILÍBRIO (\"DIA DA ALFORRIA\"): 100% QUITADO!"
            else -> "PONTO DE EQUILÍBRIO (\"DIA DA ALFORRIA\"): $breakEvenPercent% COBERTO"
        }

        val freedomStatusText = when {
            totalObligations <= 0.0 -> "Cadastre suas contas, parcelas ou aluguel do veículo para descobrir o seu Dia da Alforria no mês."
            isBreakEvenReached -> {
                val d = freedomDayOfMonth ?: todayDay
                "🎉 Alforria atingida no Dia $d! Todos os compromissos do mês já foram cobertos pela pista. Tudo que você rodar agora é 100% Salário Livre."
            }
            isPastMonth -> "Mês fechado faltando ${formatCurrency(missingForBreakEven)} para cobrir 100% das obrigações lançadas."
            freedomDayOfMonth != null -> {
                "Faltam ${formatCurrency(missingForBreakEven)} (~$daysToBreakEven ${if (daysToBreakEven == 1) "dia" else "dias"} de pista) para quitar o mês • Sua Alforria será no Dia $freedomDayOfMonth!"
            }
            else -> {
                "⚠️ Atenção: Faltam ${formatCurrency(missingForBreakEven)} (~$daysToBreakEven dias de pista) para cobrir as contas deste mês. Sincronize sua meta diária!"
            }
        }

        // 5. Média Salarial Mensal Estabilizada (1º Mês + Anti-Distorção + Detecção de Outliers via Mediana/MAD)
        val avgCalc = computeStabilizedAverageSalary(
            db = db,
            currentYm = currentYm,
            currentReportRealSalary = realSalary,
            currentReportProjectedSalary = projectedSalary,
            currentActiveDays = activeWorkDays,
            currentTotalObligations = totalObligations,
            isBreakEvenReached = isBreakEvenReached
        )

        // 6. Índice de Comprometimento da Renda (ICR)
        val incomeReference = max(operationalProfit, projectedOpProfit)
        val icrPercent: Int
        val icrLevel: IcrLevel
        val icrLabel: String

        if (totalObligations <= 0.0) {
            icrPercent = 0
            icrLevel = IcrLevel.HEALTHY
            icrLabel = "Comprometimento da Renda (ICR): 0% • Sem contas lançadas"
        } else if (incomeReference <= 0.0) {
            icrPercent = 100
            icrLevel = IcrLevel.CRITICAL
            icrLabel = "Comprometimento da Renda (ICR): Aguardando corridas na pista"
        } else {
            icrPercent = ((totalObligations / incomeReference) * 100.0).roundToInt().coerceIn(1, 999)
            when {
                icrPercent <= 40 -> {
                    icrLevel = IcrLevel.HEALTHY
                    val freeShare = (100 - icrPercent).coerceIn(0, 100)
                    icrLabel = "Comprometimento da Renda (ICR): $icrPercent% • Saudável ($freeShare% fica livre no bolso)"
                }
                icrPercent <= 65 -> {
                    icrLevel = IcrLevel.MODERATE
                    icrLabel = "Comprometimento da Renda (ICR): $icrPercent% • Equilibrado (mantenha o ritmo)"
                }
                else -> {
                    icrLevel = IcrLevel.CRITICAL
                    val centsPerReal = (totalObligations / incomeReference).coerceAtMost(1.0)
                    icrLabel = String.format(
                        ptBrLocale,
                        "Comprometimento da Renda (ICR): %d%% • Crítico (R$ %.2f de cada R$ 1,00 vão p/ contas)",
                        icrPercent,
                        centsPerReal
                    )
                }
            }
        }

        // 7. Radar de Pressão de 7 Dias
        val overdueItems = sortedEntries.filter { it.computedUrgency == ObligationUrgency.OVERDUE }
        val dueSoonItems = sortedEntries.filter { it.computedUrgency == ObligationUrgency.DUE_SOON }
        val overdueCount = overdueItems.size
        val overdueAmount = overdueItems.sumOf { it.amount }
        val next7DaysPressureCount = overdueCount + dueSoonItems.size
        val next7DaysPressureAmount = overdueAmount + dueSoonItems.sumOf { it.amount }
        val next7DaysWorkDaysEquivalent = if (next7DaysPressureAmount > 0.0) {
            next7DaysPressureAmount / effectiveDailyReference
        } else {
            0.0
        }

        val (radarTitleText, radarSubtitleText) = buildRadarTexts(
            overdueCount = overdueCount,
            overdueAmount = overdueAmount,
            pressureCount = next7DaysPressureCount,
            pressureAmount = next7DaysPressureAmount,
            workDaysEquivalent = next7DaysWorkDaysEquivalent,
            unpaidObligations = unpaidObligations,
            totalEntriesCount = sortedEntries.size
        )

        // 8. Contadores de Filtro Rápido
        val countAll = sortedEntries.size
        val countInstallments = sortedEntries.count { it.type == MonthlyObligation.TYPE_INSTALLMENT }
        val countFixed = sortedEntries.count { it.type == MonthlyObligation.TYPE_FIXED_RECURRING }
        val countDebts = sortedEntries.count { it.type == MonthlyObligation.TYPE_DEBT_OPEN }
        val countVariable = sortedEntries.count { it.type == MonthlyObligation.TYPE_VARIABLE_MONTHLY }

        // 9. Simulador de Liberdade Financeira & Sincronizador de Meta Diária
        val remWorkDaysForGoal = max(1, estimatedRemainingWorkDays)
        val dailyBillBurden = if (missingForBreakEven > 0.0) {
            missingForBreakEven / remWorkDaysForGoal.toDouble()
        } else {
            0.0
        }

        val desiredTargetSalary = when {
            avgCalc.averageMonthlySalary > 1200.0 -> avgCalc.averageMonthlySalary
            safeDailyGoal * 16.0 > 1500.0 -> safeDailyGoal * 16.0
            else -> 2500.0
        }
        val rawSuggestedDailyGoal = ((totalObligations + desiredTargetSalary - operationalProfit).coerceAtLeast(safeDailyGoal * remWorkDaysForGoal)) / remWorkDaysForGoal.toDouble()
        val suggestedDailyGoal = (ceil(rawSuggestedDailyGoal / 5.0) * 5.0).coerceIn(80.0, 2000.0)
        val syncDailyGoalLabel = "Sincronizar Meta Diária da Pista (Sugerido: ${formatCurrency(suggestedDailyGoal)}/dia)"

        val debtInstallmentSum = sortedEntries
            .filter { it.type == MonthlyObligation.TYPE_INSTALLMENT || it.type == MonthlyObligation.TYPE_DEBT_OPEN }
            .sumOf { it.amount }
        val baseSalaryForPotential = if (isCurrentMonth) max(realSalary, projectedSalary) else realSalary
        val debtFreePotentialSalary = baseSalaryForPotential + debtInstallmentSum

        val freedomInsights = buildFreedomInsights(
            entries = sortedEntries,
            effectiveDailyReference = effectiveDailyReference,
            debtInstallmentSum = debtInstallmentSum,
            debtFreePotentialSalary = debtFreePotentialSalary,
            outlierNote = avgCalc.outlierNote,
            missingForBreakEven = missingForBreakEven,
            daysToBreakEven = daysToBreakEven,
            totalObligations = totalObligations
        )

        return MonthlyManagementReport(
            year = year,
            month = safeMonth,
            yearMonth = targetYm,
            monthDisplayLabel = getMonthFullLabel(year, safeMonth),
            monthStatusPillText = monthStatusPillText,
            isCurrentMonth = isCurrentMonth,
            isPastMonth = isPastMonth,
            isFutureMonth = isFutureMonth,
            grossRides = grossRides,
            autoKmCost = autoKmCost,
            streetExpenses = streetExpenses,
            totalStreetCosts = totalStreetCosts,
            operationalProfit = operationalProfit,
            rideCount = summary.rideCount,
            activeWorkDays = activeWorkDays,
            totalObligations = totalObligations,
            paidObligations = paidObligations,
            unpaidObligations = unpaidObligations,
            totalEntriesCount = countAll,
            paidEntriesCount = paidEntriesCount,
            pendingEntriesCount = pendingEntriesCount,
            realSalary = realSalary,
            heroDisplaySalary = heroDisplaySalary,
            isPhase2ProfitInPocket = isPhase2ProfitInPocket,
            pocketCashToday = pocketCashToday,
            reservedForUnpaid = reservedForUnpaid,
            pocketCashSubtitle = pocketCashSubtitle,
            avgNetPerWorkDay = avgNetPerWorkDay,
            estimatedRemainingWorkDays = estimatedRemainingWorkDays,
            projectedOpProfit = projectedOpProfit,
            projectedSalary = projectedSalary,
            projectedContextLabel = projectedContextLabel,
            averageMonthlySalary = avgCalc.averageMonthlySalary,
            averageContextLabel = avgCalc.averageContextLabel,
            salaryTrendPercent = avgCalc.salaryTrendPercent,
            salaryTrendBadgeText = avgCalc.salaryTrendBadgeText,
            outlierNote = avgCalc.outlierNote,
            breakEvenPercent = breakEvenPercent,
            isBreakEvenReached = isBreakEvenReached,
            missingForBreakEven = missingForBreakEven,
            daysToBreakEven = daysToBreakEven,
            freedomDayOfMonth = freedomDayOfMonth,
            freedomHeaderLabel = freedomHeaderLabel,
            freedomStatusText = freedomStatusText,
            icrPercent = icrPercent,
            icrLevel = icrLevel,
            icrLabel = icrLabel,
            next7DaysPressureAmount = next7DaysPressureAmount,
            next7DaysPressureCount = next7DaysPressureCount,
            next7DaysWorkDaysEquivalent = next7DaysWorkDaysEquivalent,
            overdueCount = overdueCount,
            overdueAmount = overdueAmount,
            radarTitleText = radarTitleText,
            radarSubtitleText = radarSubtitleText,
            entries = sortedEntries,
            countAll = countAll,
            countInstallments = countInstallments,
            countFixed = countFixed,
            countDebts = countDebts,
            countVariable = countVariable,
            freedomInsights = freedomInsights,
            debtFreePotentialSalary = debtFreePotentialSalary,
            dailyBillBurden = dailyBillBurden,
            suggestedDailyGoal = suggestedDailyGoal,
            syncDailyGoalLabel = syncDailyGoalLabel
        )
    }

    private data class HistoricalMonthSalary(
        val yearMonth: String,
        val salary: Double,
        val activeDays: Int
    )

    private data class AverageSalaryResult(
        val averageMonthlySalary: Double,
        val averageContextLabel: String,
        val salaryTrendPercent: Double,
        val salaryTrendBadgeText: String,
        val outlierNote: String?
    )

    private fun computeStabilizedAverageSalary(
        db: DatabaseHelper,
        currentYm: String,
        currentReportRealSalary: Double,
        currentReportProjectedSalary: Double,
        currentActiveDays: Int,
        currentTotalObligations: Double,
        isBreakEvenReached: Boolean
    ): AverageSalaryResult {
        val pastYearMonths = db.getDistinctHistoricalYearMonths(beforeYearMonth = currentYm)
        val firstActivityTs = db.getFirstActivityTimestamp()
        val fullClosedMonths = mutableListOf<HistoricalMonthSalary>()

        for (ym in pastYearMonths) {
            val (y, m) = DatabaseHelper.parseYearMonth(ym)
            val (_, endOfMonthTs) = db.getMonthRange(y, m)
            val activeDays = db.getActiveWorkDaysInMonth(y, m)
            val daysSinceFirstActivity = if (firstActivityTs != null) {
                ((endOfMonthTs - firstActivityTs) / 86_400_000L).toInt()
            } else {
                30
            }

            // Filtro anti-distorção de onboarding parcial (< 15 dias desde instalação e < 10 dias ativos)
            val isPartialOnboardingMonth = (daysSinceFirstActivity < 15 && activeDays < 10)
            if (!isPartialOnboardingMonth && activeDays >= 1) {
                val monthSummary = db.getSummaryForYearMonth(y, m)
                val monthEntries = db.getObligationEntriesForMonth(ym)
                val monthObligations = monthEntries.sumOf { it.amount }
                val monthRealSalary = monthSummary.totalNet - monthObligations
                fullClosedMonths.add(
                    HistoricalMonthSalary(
                        yearMonth = ym,
                        salary = monthRealSalary,
                        activeDays = activeDays
                    )
                )
            }
        }

        // Cenário A: 1º Mês de Uso (sem meses plenos anteriores fechados)
        if (fullClosedMonths.isEmpty()) {
            val firstMonthEstimate = if (currentActiveDays > 0) {
                currentReportProjectedSalary
            } else {
                currentReportRealSalary
            }
            val badge = when {
                currentTotalObligations > 0.0 && isBreakEvenReached -> "Alforria Atingida!"
                currentActiveDays > 0 -> "1º Mês em Curso"
                else -> "Novo Ciclo"
            }
            return AverageSalaryResult(
                averageMonthlySalary = firstMonthEstimate,
                averageContextLabel = "Estimada (1º mês em curso)",
                salaryTrendPercent = 0.0,
                salaryTrendBadgeText = badge,
                outlierNote = null
            )
        }

        // Cenário B: 1 ou 2 meses fechados
        if (fullClosedMonths.size < 3) {
            val avg = fullClosedMonths.map { it.salary }.average()
            val refCurrent = if (currentActiveDays > 0) currentReportProjectedSalary else currentReportRealSalary
            val trend = if (abs(avg) >= 1.0) ((refCurrent - avg) / abs(avg)) * 100.0 else 0.0
            val sign = if (trend >= 0) "+" else ""
            val badge = String.format(ptBrLocale, "%s%.0f%% vs. Média", sign, trend)
            val ctxLabel = if (fullClosedMonths.size == 1) {
                "Base: ${getMonthShortYearLabel(fullClosedMonths.first().yearMonth)}"
            } else {
                "Base: últimos 2 meses"
            }
            return AverageSalaryResult(
                averageMonthlySalary = avg,
                averageContextLabel = ctxLabel,
                salaryTrendPercent = trend,
                salaryTrendBadgeText = badge,
                outlierNote = null
            )
        }

        // Cenário C: >= 3 meses plenos fechados -> Mediana + MAD para detectar mês fora da curva
        val sortedSalaries = fullClosedMonths.map { it.salary }.sorted()
        val medianSalary = computeMedian(sortedSalaries)
        val absDeviations = sortedSalaries.map { abs(it - medianSalary) }.sorted()
        val mad = computeMedian(absDeviations)
        val threshold = max(2.5 * mad, max(0.20 * abs(medianSalary), 250.0))

        var weightedSum = 0.0
        var totalWeight = 0.0
        var extremeOutlier: HistoricalMonthSalary? = null
        var maxDeviation = 0.0

        for (item in fullClosedMonths) {
            val dev = abs(item.salary - medianSalary)
            if (dev > threshold) {
                // Suaviza o mês fora da curva com peso reduzido (Winsorization)
                weightedSum += item.salary * 0.45
                totalWeight += 0.45
                if (dev > maxDeviation) {
                    maxDeviation = dev
                    extremeOutlier = item
                }
            } else {
                weightedSum += item.salary
                totalWeight += 1.0
            }
        }

        val stabilizedAvg = if (totalWeight > 0.0) weightedSum / totalWeight else medianSalary
        val refCurrent = if (currentActiveDays > 0) currentReportProjectedSalary else currentReportRealSalary
        val trend = if (abs(stabilizedAvg) >= 1.0) ((refCurrent - stabilizedAvg) / abs(stabilizedAvg)) * 100.0 else 0.0
        val sign = if (trend >= 0) "+" else ""
        val badge = String.format(ptBrLocale, "%s%.0f%% vs. Média", sign, trend)

        val outlierNote = if (extremeOutlier != null) {
            val mName = getMonthTitleName(extremeOutlier.yearMonth)
            "$mName ficou fora da curva (${formatCurrency(extremeOutlier.salary)}) e foi suavizado na sua Média Salarial."
        } else {
            null
        }

        val ctxLabel = if (extremeOutlier != null) {
            "Base: ${fullClosedMonths.size} meses (suavizada)"
        } else {
            "Base: últimos ${fullClosedMonths.size} meses"
        }

        return AverageSalaryResult(
            averageMonthlySalary = stabilizedAvg,
            averageContextLabel = ctxLabel,
            salaryTrendPercent = trend,
            salaryTrendBadgeText = badge,
            outlierNote = outlierNote
        )
    }

    private fun computeMedian(sortedValues: List<Double>): Double {
        if (sortedValues.isEmpty()) return 0.0
        val mid = sortedValues.size / 2
        return if (sortedValues.size % 2 == 1) {
            sortedValues[mid]
        } else {
            (sortedValues[mid - 1] + sortedValues[mid]) / 2.0
        }
    }

    private fun computeUrgency(
        isPaid: Boolean,
        isPastMonth: Boolean,
        isCurrentMonth: Boolean,
        dueDay: Int,
        todayDay: Int
    ): ObligationUrgency {
        if (isPaid) return ObligationUrgency.PAID
        if (isPastMonth) return ObligationUrgency.OVERDUE
        if (!isCurrentMonth) return ObligationUrgency.UPCOMING

        val diff = dueDay - todayDay
        return when {
            diff < 0 -> ObligationUrgency.OVERDUE
            diff <= 7 -> ObligationUrgency.DUE_SOON
            else -> ObligationUrgency.UPCOMING
        }
    }

    private fun buildDueStatusText(
        raw: MonthlyObligationJoined,
        month: Int,
        urgency: ObligationUrgency,
        isCurrentMonth: Boolean,
        isPastMonth: Boolean,
        todayDay: Int
    ): String {
        val dueFormatted = String.format(Locale.US, "%02d/%02d", raw.dueDay, month)
        if (urgency == ObligationUrgency.PAID) {
            return if (raw.paidAt > 0L) {
                val paidStr = SimpleDateFormat("dd/MM", Locale.US).format(Date(raw.paidAt))
                "Venc. $dueFormatted • Pago em $paidStr"
            } else {
                "Venc. $dueFormatted • Quitado no mês"
            }
        }

        if (isPastMonth) {
            return "Venceu em $dueFormatted • Em aberto"
        }
        if (!isCurrentMonth) {
            return "Vence dia $dueFormatted • Planejado"
        }

        val diff = raw.dueDay - todayDay
        return when {
            diff < -1 -> "Atrasada há ${abs(diff)} dias (Venceu $dueFormatted)"
            diff == -1 -> "Venceu ontem ($dueFormatted) • Atrasada"
            diff == 0 -> "Vence HOJE (Dia ${raw.dueDay}) • Atenção"
            diff == 1 -> "Vence amanhã (Dia ${raw.dueDay}) • Atenção"
            diff in 2..7 -> "Vence em $diff dias ($dueFormatted)"
            else -> "Vence dia $dueFormatted • No prazo"
        }
    }

    private fun buildRadarTexts(
        overdueCount: Int,
        overdueAmount: Double,
        pressureCount: Int,
        pressureAmount: Double,
        workDaysEquivalent: Double,
        unpaidObligations: Double,
        totalEntriesCount: Int
    ): Pair<String, String> {
        if (totalEntriesCount == 0) {
            return Pair(
                "Nenhuma conta ou parcela cadastrada",
                "Toque no botão acima para adicionar financiamento, aluguel, MEI ou contas fixas."
            )
        }
        if (unpaidObligations <= 0.0) {
            return Pair(
                "Tudo em dia! 100% das contas pagas",
                "Você não tem nenhum boleto pendente neste mês."
            )
        }
        val workDaysStr = String.format(ptBrLocale, "%.1f", workDaysEquivalent)
        if (overdueCount > 0) {
            return Pair(
                "Atenção: $overdueCount ${if (overdueCount == 1) "conta atrasada" else "contas atrasadas"} (${formatCurrency(overdueAmount)})",
                "Total urgente em 7 dias: ${formatCurrency(pressureAmount)} (~$workDaysStr dias de pista)"
            )
        }
        if (pressureCount > 0) {
            return Pair(
                "Próximos 7 dias: ${formatCurrency(pressureAmount)} em $pressureCount ${if (pressureCount == 1) "conta" else "contas"}",
                "Equivale a ~$workDaysStr dias de trabalho na pista • Prepare o caixa"
            )
        }
        return Pair(
            "Próximos 7 dias livres de vencimentos",
            "Restam ${formatCurrency(unpaidObligations)} para vencer mais adiante no mês."
        )
    }

    private fun buildFreedomInsights(
        entries: List<ObligationUiItem>,
        effectiveDailyReference: Double,
        debtInstallmentSum: Double,
        debtFreePotentialSalary: Double,
        outlierNote: String?,
        missingForBreakEven: Double,
        daysToBreakEven: Int,
        totalObligations: Double
    ): List<String> {
        val insights = mutableListOf<String>()

        val installments = entries.filter {
            it.type == MonthlyObligation.TYPE_INSTALLMENT && it.totalInstallments > 0
        }

        // Insight 1: Próxima parcela a acabar (menor remainingInstallments)
        val soonestFinishing = installments.minByOrNull { it.remainingInstallments }
        if (soonestFinishing != null) {
            val rem = soonestFinishing.remainingInstallments
            if (rem == 0) {
                insights.add(
                    "🎉 Última parcela de \"${soonestFinishing.title}\" neste mês! A partir do mês que vem seu salário sobe +${formatCurrency(soonestFinishing.amount)}/mês."
                )
            } else {
                val endLbl = soonestFinishing.estimatedEndMonthLabel ?: "breve"
                insights.add(
                    "Faltam só $rem ${if (rem == 1) "parcela" else "parcelas"} para acabar \"${soonestFinishing.title}\": seu salário vai subir +${formatCurrency(soonestFinishing.amount)}/mês após $endLbl!"
                )
            }
        }

        // Insight 2: Maior alívio mensal em dias de pista (maior valor entre parcelamentos ou contas)
        val heaviestItem = installments.maxByOrNull { it.amount } ?: entries.maxByOrNull { it.amount }
        if (heaviestItem != null && heaviestItem.amount > 0.0) {
            val daysSaved = heaviestItem.amount / effectiveDailyReference
            val daysSavedStr = String.format(ptBrLocale, "%.1f", daysSaved)
            if (heaviestItem.type == MonthlyObligation.TYPE_INSTALLMENT) {
                insights.add(
                    "Quando \"${heaviestItem.title}\" (${heaviestItem.typeBadgeText}) terminar, você trabalhará $daysSavedStr dias a menos por mês para pagar boletos (+${formatCurrency(heaviestItem.amount)}/mês no bolso)."
                )
            } else {
                insights.add(
                    "Seu maior compromisso do mês é \"${heaviestItem.title}\" (${formatCurrency(heaviestItem.amount)}), que exige cerca de $daysSavedStr dias de pista para ser coberto."
                )
            }
        }

        // Insight 3: Outlier histórico OU Salário Potencial Livre de Dívidas OU Alforria
        if (outlierNote != null) {
            insights.add("📊 Detector de Curva: $outlierNote")
        } else if (debtInstallmentSum > 0.0) {
            insights.add(
                "Sem os parcelamentos e dívidas atuais (${formatCurrency(debtInstallmentSum)}/mês), seu Salário Livre potencial neste ritmo seria de ${formatCurrency(debtFreePotentialSalary)}/mês."
            )
        } else if (totalObligations > 0.0 && missingForBreakEven > 0.0) {
            insights.add(
                "Mantendo sua meta diária, em ~$daysToBreakEven ${if (daysToBreakEven == 1) "dia" else "dias"} de trabalho você atinge 100% da Alforria deste mês."
            )
        } else {
            insights.add(
                "Cadastre financiamentos, aluguel do veículo, MEI e contas fixas para simular automaticamente quando suas dívidas terminam e quanto seu salário vai crescer."
            )
        }

        return insights.take(3)
    }

    fun getCategoryMetadata(category: String): Pair<String, String> {
        return when (category) {
            MonthlyObligation.CATEGORY_VEICULO -> Pair("Veículo & Frota", "🚗")
            MonthlyObligation.CATEGORY_MORADIA -> Pair("Casa & Moradia", "🏠")
            MonthlyObligation.CATEGORY_IMPOSTOS_MEI -> Pair("MEI & Impostos", "📄")
            MonthlyObligation.CATEGORY_CARTAO_EMPRESTIMO -> Pair("Cartão & Dívidas", "💳")
            MonthlyObligation.CATEGORY_UTILIDADES -> Pair("Água, Luz & Net", "⚡")
            MonthlyObligation.CATEGORY_PESSOAL -> Pair("Mercado & Família", "🛒")
            else -> Pair("Compromisso", "📌")
        }
    }
}
