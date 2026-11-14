package com.drifly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceRulesTest {

    private val t0 = 1_000_000L

    @Test
    fun clickWithFreshPendingCommits() {
        assertTrue(AcceptanceRules.canCommitOnAcceptClick(true, t0, t0 + 10_000))
    }

    @Test
    fun clickWithoutPendingNeverCommits() {
        assertFalse(AcceptanceRules.canCommitOnAcceptClick(false, t0, t0 + 1_000))
    }

    @Test
    fun staleOfferNeverCommits() {
        assertFalse(AcceptanceRules.canCommitOnAcceptClick(true, t0, t0 + 46_000))
        assertFalse(AcceptanceRules.canCommitOnActiveRideScreen(true, t0, t0 + 46_000, false))
    }

    @Test
    fun activeRideScreenProvesAcceptWhenOfferWasNotDuringRide() {
        assertTrue(AcceptanceRules.canCommitOnActiveRideScreen(true, t0, t0 + 5_000, false))
    }

    @Test
    fun activeRideScreenDoesNotProveAcceptWhenOfferArrivedDuringRide() {
        assertFalse(AcceptanceRules.canCommitOnActiveRideScreen(true, t0, t0 + 5_000, true))
    }

    @Test
    fun activeRideScreenWithoutPendingNeverCommits() {
        assertFalse(AcceptanceRules.canCommitOnActiveRideScreen(false, t0, t0 + 5_000, false))
    }

    @Test
    fun classifyPostTapVerificationRejectsOfferIdleErrorsAndEmptyFinalAttempt() {
        val sampleTexts = listOf("Moto", "R$ 7,90", "3,2 km", "8 min")

        assertEquals(
            AcceptanceRules.PostTapOutcome.FAILED,
            AcceptanceRules.classifyPostTapVerification(
                texts = sampleTexts,
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                stillSameOffer = true,
                attempt = 0
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.FAILED,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("Buscando", "Recompensas", "Moto", "99"),
                offerPrice = 7.90,
                isIdleScreen = true,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 0
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.FAILED,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("Corrida aceita por outro motorista", "OK", "99", "Moto"),
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 0
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.FAILED,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("Histórico de corridas", "Ganhos", "Hoje", "R$ 21,00"),
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = true,
                isActiveRide = false,
                attempt = 0
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.RETRY,
            AcceptanceRules.classifyPostTapVerification(
                texts = null,
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 0,
                maxAttempts = 2
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.FAILED,
            AcceptanceRules.classifyPostTapVerification(
                texts = null,
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 2,
                maxAttempts = 2
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.RETRY,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("Carregando...", "99", "Moto", "14:18"),
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 0,
                maxAttempts = 2
            )
        )
    }

    @Test
    fun classifyPostTapVerificationConfirmsActiveRideOrGpsTransition() {
        assertEquals(
            AcceptanceRules.PostTapOutcome.CONFIRMED,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("Cheguei", "R. Jacob Richlin", "12 km/h", "Ajustes"),
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = true,
                attempt = 0
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.CONFIRMED,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("120 m", "Rua Corupá, 386", "0 km/h", "Chegada prevista: 14:25"),
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 0
            )
        )
    }

    @Test
    fun doesGpsHudMatchPendingOfferOnlyWhenOutsideDemandNavAndWithSpeedCue() {
        val pendingWithPickup = RideOffer(
            platform = "99",
            price = 7.90,
            distanceKm = 3.2,
            durationMinutes = 8,
            ratePerKm = 2.47,
            pickupAddress = "Rua Corupá, 386 - Itaum",
            destinationAddress = "Rua Guanabara, 910"
        )
        val hudTexts = listOf("109", "m", "Rua Corupá, 386 - Itaum", "Ir", "0", "km/h")

        assertTrue(
            AcceptanceRules.doesGpsHudMatchPendingOffer(
                texts = hudTexts,
                pendingOffer = pendingWithPickup,
                hasRecentDemandNavigation = false
            )
        )

        assertFalse(
            AcceptanceRules.doesGpsHudMatchPendingOffer(
                texts = hudTexts,
                pendingOffer = pendingWithPickup,
                hasRecentDemandNavigation = true
            )
        )

        assertFalse(
            AcceptanceRules.doesGpsHudMatchPendingOffer(
                texts = listOf("109", "m", "Rua Corupá, 386 - Itaum", "Ir"),
                pendingOffer = pendingWithPickup,
                hasRecentDemandNavigation = false
            )
        )

        assertFalse(
            AcceptanceRules.doesGpsHudMatchPendingOffer(
                texts = listOf("Buscando", "Rua Corupá, 386", "0", "km/h"),
                pendingOffer = pendingWithPickup,
                hasRecentDemandNavigation = false
            )
        )
    }
}
