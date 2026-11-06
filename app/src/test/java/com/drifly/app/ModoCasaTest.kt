package com.drifly.app

import org.junit.Assert.*
import org.junit.Test

class ModoCasaTest {

    @Test
    fun testCalculateDistanceHaversine() {
        // Marco Zero da Sé (-23.55052, -46.633308) até MASP na Av Paulista (-23.561414, -46.655882)
        // Distância geográfica real em linha reta ~2.6 km
        val distance = ModoCasaHelper.calculateDistanceKm(
            lat1 = -23.550520,
            lon1 = -46.633308,
            lat2 = -23.561414,
            lon2 = -46.655882
        )
        assertEquals(2.61, distance, 0.15)
    }

    @Test
    fun testCalculateDeltaKmAproximaDeCasa() {
        // Casa do motorista: Bairro Pinheiros (-23.5670, -46.7000)
        val homeLat = -23.5670
        val homeLng = -46.7000

        // Motorista está longe na Zona Leste (-23.5400, -46.5500) (~15.7 km de casa)
        val driverLat = -23.5400
        val driverLng = -46.5500

        // Destino da corrida: Av. Rebouças (-23.5650, -46.6850) (~1.5 km de casa)
        val destLat = -23.5650
        val destLng = -46.6850

        val delta = ModoCasaHelper.calculateDeltaKm(
            driverLat = driverLat,
            driverLng = driverLng,
            destLat = destLat,
            destLng = destLng,
            homeLat = homeLat,
            homeLng = homeLng
        )

        // Delta deve ser nitidamente negativo (aproximou mais de 10 km de casa)
        assertTrue("Delta deve ser negativo ao aproximar de casa, foi $delta", delta < 0.0)
        assertEquals("Perfeita Direção ✅", ModoCasaHelper.getVerdictForDelta(delta))
    }

    @Test
    fun testCalculateDeltaKmAfastandoDeCasa() {
        // Casa do motorista: Bairro Pinheiros (-23.5670, -46.7000)
        val homeLat = -23.5670
        val homeLng = -46.7000

        // Motorista está relativamente perto de casa (-23.5700, -46.7100) (~1.1 km)
        val driverLat = -23.5700
        val driverLng = -46.7100

        // Destino da corrida: Litoral / Rodovia Imigrantes (-23.7500, -46.6000) (~22.7 km)
        val destLat = -23.7500
        val destLng = -46.6000

        val delta = ModoCasaHelper.calculateDeltaKm(
            driverLat = driverLat,
            driverLng = driverLng,
            destLat = destLat,
            destLng = destLng,
            homeLat = homeLat,
            homeLng = homeLng
        )

        // Delta deve ser fortemente positivo (afastou mais de 20 km)
        assertTrue("Delta deve ser positivo ao afastar de casa, foi $delta", delta > 0.0)
        assertEquals("Evitar Direção ❌", ModoCasaHelper.getVerdictForDelta(delta))
    }

    @Test
    fun testVerdictsForApproachingBands() {
        // Banda 1: delta <= -8.0 km -> Perfeita Direção ✅
        assertEquals("Perfeita Direção ✅", ModoCasaHelper.getVerdictForDelta(-12.0))
        assertEquals("Perfeita Direção ✅", ModoCasaHelper.getVerdictForDelta(-8.0))

        // Banda 2: -8.0 < delta <= -4.0 km -> Excelente Direção ✅
        assertEquals("Excelente Direção ✅", ModoCasaHelper.getVerdictForDelta(-7.9))
        assertEquals("Excelente Direção ✅", ModoCasaHelper.getVerdictForDelta(-6.5))
        assertEquals("Excelente Direção ✅", ModoCasaHelper.getVerdictForDelta(-4.0))

        // Banda 3: -4.0 < delta <= 0.0 km -> Boa Direção ✅
        assertEquals("Boa Direção ✅", ModoCasaHelper.getVerdictForDelta(-3.9))
        assertEquals("Boa Direção ✅", ModoCasaHelper.getVerdictForDelta(-1.5))
        assertEquals("Boa Direção ✅", ModoCasaHelper.getVerdictForDelta(0.0))
    }

    @Test
    fun testVerdictsForMovingAwayBands() {
        // Banda 1: 0 < delta <= 4.0 km -> Ruim Direção ❌
        assertEquals("Ruim Direção ❌", ModoCasaHelper.getVerdictForDelta(0.1))
        assertEquals("Ruim Direção ❌", ModoCasaHelper.getVerdictForDelta(2.5))
        assertEquals("Ruim Direção ❌", ModoCasaHelper.getVerdictForDelta(4.0))

        // Banda 2: 4.0 < delta <= 9.0 km -> Péssima Direção ❌
        assertEquals("Péssima Direção ❌", ModoCasaHelper.getVerdictForDelta(4.1))
        assertEquals("Péssima Direção ❌", ModoCasaHelper.getVerdictForDelta(7.5))
        assertEquals("Péssima Direção ❌", ModoCasaHelper.getVerdictForDelta(9.0))

        // Banda 3: delta > 9.0 km -> Evitar Direção ❌
        assertEquals("Evitar Direção ❌", ModoCasaHelper.getVerdictForDelta(9.1))
        assertEquals("Evitar Direção ❌", ModoCasaHelper.getVerdictForDelta(15.0))
        assertEquals("Evitar Direção ❌", ModoCasaHelper.getVerdictForDelta(30.0))
    }

    @Test
    fun testRideOfferModoCasaDefaultValues() {
        val standardOffer = RideOffer(
            platform = "UBER",
            price = 25.0,
            distanceKm = 5.0,
            durationMinutes = 10,
            ratePerKm = 5.0
        )
        assertFalse(standardOffer.isHeadingHome)
        assertEquals(0.0, standardOffer.homeDistanceDeltaKm, 0.001)

        val homeOffer = standardOffer.copy(
            isHeadingHome = true,
            homeDistanceDeltaKm = -6.5
        )
        assertTrue(homeOffer.isHeadingHome)
        assertEquals(-6.5, homeOffer.homeDistanceDeltaKm, 0.001)
    }
}
