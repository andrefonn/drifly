package com.drifly.app

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
}
