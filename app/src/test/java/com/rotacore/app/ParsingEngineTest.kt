package com.rotacore.app

import org.junit.Assert.*
import org.junit.Test

class ParsingEngineTest {

    @Test
    fun testParseValidUberOffer() {
        val texts = listOf(
            "UberX",
            "R$ 32,50",
            "8,4 km",
            "19 min",
            "★ 4.88"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = texts,
            costPerKm = 0.78,
            minRatingThreshold = 4.75f
        )

        assertNotNull("Offer should not be null", offer)
        assertEquals("UBER", offer!!.platform)
        assertEquals(32.50, offer.price, 0.001)
        assertEquals(8.4, offer.distanceKm, 0.001)
        assertEquals(19, offer.durationMinutes)
        assertEquals(3.87, offer.ratePerKm, 0.01)
        assertEquals(6.55, offer.estimatedFuelCost, 0.01)
        assertEquals(25.95, offer.netProfit, 0.01)
        assertEquals(4.88f, offer.passengerRating ?: 0f, 0.01f)
        assertFalse(offer.isLowRating)
    }

    @Test
    fun testParseLowRatingTriggersAlert() {
        val texts = listOf(
            "99Pop",
            "R$ 20,00",
            "5.0 km",
            "12 min",
            "4.60"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.80,
            minRatingThreshold = 4.75f
        )

        assertNotNull(offer)
        assertEquals("99", offer!!.platform)
        assertEquals(4.60f, offer.passengerRating ?: 0f, 0.01f)
        assertTrue("Rating 4.60 should be marked as low rating when threshold is 4.75", offer.isLowRating)
    }

    @Test
    fun testParseZeroOrNegativeDistanceReturnsNull() {
        val texts = listOf("R$ 15,00", "0 km")
        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = texts,
            costPerKm = 0.80,
            minRatingThreshold = 4.70f
        )
        assertNull("Zero distance should return null offer", offer)
    }

    @Test
    fun testParseEmptyTextsReturnsNull() {
        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = emptyList(),
            costPerKm = 0.80,
            minRatingThreshold = 4.70f
        )
        assertNull("Empty list should return null offer", offer)
    }
}
