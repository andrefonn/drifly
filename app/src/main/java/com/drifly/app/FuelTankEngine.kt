package com.drifly.app

import kotlin.math.max

/**
 * Tanque virtual da bolinha flutuante.
 *
 * O nível sai de dois números que o app já conhece: quanto foi abastecido
 * (despesa de combustível convertida em litros) e quantos km foram rodados
 * (odômetro do turno), divididos pelo consumo do veículo.
 */
object FuelTankEngine {

    private const val FALLBACK_REFERENCE_LITERS = 30.0

    fun litersFromAmount(amount: Double, pricePerLiter: Double): Double {
        if (pricePerLiter <= 0.0) return 0.0
        return (amount / pricePerLiter).coerceAtLeast(0.0)
    }

    fun consumedLiters(distanceKm: Double, kmPerLiter: Double): Double {
        if (kmPerLiter <= 0.0) return 0.0
        return (distanceKm / kmPerLiter).coerceAtLeast(0.0)
    }

    /** Desconta do tanque os km rodados desde a última sincronização. */
    fun sync(prefs: PreferencesManager) {
        val reference = prefs.tankReferenceLiters
        if (reference <= 0.0) return

        val odometer = prefs.shiftDistanceKm.toDouble()
        val anchor = prefs.tankOdometerKm
        val deltaKm = when {
            odometer >= anchor -> odometer - anchor
            // odômetro reiniciado (novo turno): recomeça a contagem
            else -> 0.0
        }

        if (deltaKm > 0.0) {
            val consumed = consumedLiters(deltaKm, prefs.fuelConsumption.toDouble())
            prefs.tankLiters = (prefs.tankLiters - consumed).coerceAtLeast(0.0)
        }
        prefs.tankOdometerKm = odometer
    }

    /** Registra um abastecimento (litros) e reancora o odômetro. */
    fun registerFill(prefs: PreferencesManager, liters: Double) {
        if (liters <= 0.0) return
        sync(prefs)
        val total = prefs.tankLiters + liters
        prefs.tankLiters = total
        if (total > prefs.tankReferenceLiters) prefs.tankReferenceLiters = total
        prefs.tankOdometerKm = prefs.shiftDistanceKm.toDouble()
    }

    /** Nível de 0 a 1 (cai para o ajuste manual quando não há abastecimento). */
    fun level(prefs: PreferencesManager): Float {
        sync(prefs)
        return levelOf(prefs.tankLiters, prefs.tankReferenceLiters, prefs.fuelLevel)
    }

    /** Regra pura do percentual, usada também nos testes. */
    fun levelOf(remainingLiters: Double, referenceLiters: Double, fallbackLevel: Float): Float {
        if (referenceLiters <= 0.0) return fallbackLevel.coerceIn(0f, 1f)
        return (remainingLiters / referenceLiters).toFloat().coerceIn(0f, 1f)
    }

    /** Ajuste manual do slider: mexe no tanque quando já existe referência. */
    fun setManualLevel(prefs: PreferencesManager, level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        prefs.fuelLevel = clamped
        if (prefs.tankReferenceLiters <= 0.0) {
            prefs.tankReferenceLiters = FALLBACK_REFERENCE_LITERS
            prefs.tankOdometerKm = prefs.shiftDistanceKm.toDouble()
        }
        prefs.tankLiters = prefs.tankReferenceLiters * clamped
    }

    fun remainingLiters(prefs: PreferencesManager): Double = max(0.0, prefs.tankLiters)
}
