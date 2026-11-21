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
    fun consecutiveOfferOnlyCommitsAfterRealPickupTransition() {
        val t = 100_000L
        assertTrue(
            AcceptanceRules.canCommitOnConsecutivePickupTransition(
                hasPending = true,
                pendingTimestamp = t,
                now = t + 30_000,
                offerArrivedDuringRide = true,
                activeRideSeenDropoffPhase = true,
                lastAcceptedTimestamp = t - 120_000,
                samePlatform = true,
                isPickupPhase = true
            )
        )
        // HUD da corrida anterior (sem fase de embarque) nunca confirma
        assertFalse(
            AcceptanceRules.canCommitOnConsecutivePickupTransition(
                hasPending = true,
                pendingTimestamp = t,
                now = t + 10_000,
                offerArrivedDuringRide = true,
                activeRideSeenDropoffPhase = true,
                lastAcceptedTimestamp = t - 120_000,
                samePlatform = true,
                isPickupPhase = false
            )
        )
        // Oferta que não chegou durante corrida usa a regra normal, não a consecutiva
        assertFalse(
            AcceptanceRules.canCommitOnConsecutivePickupTransition(
                hasPending = true,
                pendingTimestamp = t,
                now = t + 10_000,
                offerArrivedDuringRide = false,
                activeRideSeenDropoffPhase = true,
                lastAcceptedTimestamp = t - 120_000,
                samePlatform = true,
                isPickupPhase = true
            )
        )
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

    @Test
    fun isGpsHudTransitionFromFreshOfferConfirmsIntermediateStreetOnlyWhenOfferWasIdle() {
        val pendingOffer = RideOffer(
            platform = "99",
            price = 6.00,
            distanceKm = 2.4,
            durationMinutes = 7,
            ratePerKm = 2.50,
            pickupAddress = "Rua Corupá, 386",
            destinationAddress = "Rua Guanabara, 910"
        )
        // HUD da 99 mostrando rua intermediária da manobra ("R. Iririú") logo após aceitar oferta no "Buscando"
        val intermediateStreetHud = listOf(
            "11:26 9 99", "28", "km/h", "450 m", "R. Iririú", "4 min 1,8 km", "• Chegada prevista: 11:30"
        )

        assertTrue(
            AcceptanceRules.isGpsHudTransitionFromFreshOffer(
                texts = intermediateStreetHud,
                pendingOffer = pendingOffer,
                offerArrivedDuringRide = false,
                offerArrivedDuringFreeGps = false,
                hasRecentDemandNavigation = false
            )
        )

        // Nunca confirma se a oferta chegou durante outra corrida em andamento
        assertFalse(
            AcceptanceRules.isGpsHudTransitionFromFreshOffer(
                texts = intermediateStreetHud,
                pendingOffer = pendingOffer,
                offerArrivedDuringRide = true,
                offerArrivedDuringFreeGps = false,
                hasRecentDemandNavigation = false
            )
        )

        // Nunca confirma se o motorista já estava em navegação GPS livre antes da oferta
        assertFalse(
            AcceptanceRules.isGpsHudTransitionFromFreshOffer(
                texts = intermediateStreetHud,
                pendingOffer = pendingOffer,
                offerArrivedDuringRide = false,
                offerArrivedDuringFreeGps = true,
                hasRecentDemandNavigation = false
            )
        )

        // Nunca confirma se havia navegação recente para zona de demanda
        assertFalse(
            AcceptanceRules.isGpsHudTransitionFromFreshOffer(
                texts = intermediateStreetHud,
                pendingOffer = pendingOffer,
                offerArrivedDuringRide = false,
                offerArrivedDuringFreeGps = false,
                hasRecentDemandNavigation = true
            )
        )
    }

    private fun offerForAutomation(
        ratePerKm: Double = 3.00,
        distanceKm: Double = 5.0,
        rating: Float? = 4.90f,
        blacklistBlocked: Boolean = false,
        homeDeltaKm: Double = -1.0
    ) = RideOffer(
        platform = "99",
        price = ratePerKm * distanceKm,
        distanceKm = distanceKm,
        durationMinutes = 12,
        ratePerKm = ratePerKm,
        ratePerHour = 40.0,
        passengerRating = rating,
        destinationAddress = "Rua Teste, 100",
        isBlacklistBlocked = blacklistBlocked,
        isHeadingHome = homeDeltaKm <= 0.0,
        homeDistanceDeltaKm = homeDeltaKm
    )

    private fun decide(
        offer: RideOffer,
        minRate: Double = 2.20,
        maxDistance: Double = 12.0,
        minRating: Float = 4.75f,
        modoCasa: Boolean = false,
        accept: Boolean = true,
        decline: Boolean = true
    ) = AcceptanceRules.decideAutomation(
        offer = offer,
        minRatePerKm = minRate,
        maxDistanceKm = maxDistance,
        minPassengerRating = minRating,
        modoCasaActive = modoCasa,
        autoAcceptEnabled = accept,
        autoDeclineEnabled = decline
    )

    @Test
    fun automationAcceptsOnlyWhenEveryRideSettingPasses() {
        assertEquals(AcceptanceRules.AutoAction.ACCEPT, decide(offerForAutomation()))
    }

    @Test
    fun automationDeclinesWhenRateIsBelowTheConfiguredMinimum() {
        assertEquals(
            AcceptanceRules.AutoAction.DECLINE,
            decide(offerForAutomation(ratePerKm = 1.50))
        )
    }

    @Test
    fun automationDeclinesWhenDistanceIsAboveTheConfiguredMaximum() {
        assertEquals(
            AcceptanceRules.AutoAction.DECLINE,
            decide(offerForAutomation(distanceKm = 13.0))
        )
    }

    @Test
    fun automationDeclinesLowRatedPassengerInsteadOfReturningEarly() {
        assertEquals(
            AcceptanceRules.AutoAction.DECLINE,
            decide(offerForAutomation(rating = 4.20f))
        )
    }

    @Test
    fun automationDeclinesBlacklistedOfferEvenWhenMetricsAreGood() {
        assertEquals(
            AcceptanceRules.AutoAction.DECLINE,
            decide(offerForAutomation(blacklistBlocked = true))
        )
    }

    @Test
    fun automationHonorsModoCasaWhenActive() {
        val awayFromHome = offerForAutomation(homeDeltaKm = 2.5)
        val towardsHome = offerForAutomation(homeDeltaKm = -2.0)

        assertEquals(
            AcceptanceRules.AutoAction.DECLINE,
            decide(awayFromHome, modoCasa = true)
        )
        assertEquals(
            AcceptanceRules.AutoAction.ACCEPT,
            decide(towardsHome, modoCasa = true)
        )
        // Fora do Modo Casa a distância de casa não interfere
        assertEquals(
            AcceptanceRules.AutoAction.ACCEPT,
            decide(awayFromHome, modoCasa = false)
        )
    }

    @Test
    fun automationDoesNothingWhenTheRelevantSwitchIsOff() {
        assertEquals(
            AcceptanceRules.AutoAction.IGNORE,
            decide(offerForAutomation(ratePerKm = 1.50), decline = false)
        )
        assertEquals(
            AcceptanceRules.AutoAction.IGNORE,
            decide(offerForAutomation(), accept = false, decline = true)
        )
        assertEquals(
            AcceptanceRules.AutoAction.IGNORE,
            decide(offerForAutomation(), accept = false, decline = false)
        )
    }

    @Test
    fun automationDoesNotDeclineUnknownPassengerRating() {
        assertEquals(
            AcceptanceRules.AutoAction.ACCEPT,
            decide(offerForAutomation(rating = null))
        )
    }

    @Test
    fun declineControlRecognitionCovers99XAndMergedBadge() {
        assertTrue(ParsingEngine.isExactDeclineGlyph("X"))
        assertTrue(ParsingEngine.isExactDeclineGlyph("×"))
        assertTrue(ParsingEngine.isDeclineGlyphMergedWithBadge("X Não afeta a TA"))
        assertTrue(ParsingEngine.isDeclineActionText("Recusar"))
        assertTrue(ParsingEngine.isDeclineButtonText("Recusar"))
        assertTrue(ParsingEngine.isDeclineButtonText("Recusar corrida"))
        assertFalse(ParsingEngine.isDeclineButtonText("X"))
        assertFalse(ParsingEngine.isDeclineButtonText("Fechar"))
        assertFalse(ParsingEngine.isExactDeclineGlyph("X Não afeta a TA"))
        assertFalse(ParsingEngine.isDeclineActionText("Recusar novas corridas"))
    }
}
