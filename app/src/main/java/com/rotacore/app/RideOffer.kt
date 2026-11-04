package com.rotacore.app

data class RideOffer(
    val platform: String,
    val price: Double,
    val distanceKm: Double,
    val durationMinutes: Int,
    val ratePerKm: Double,
    val estimatedFuelCost: Double = 0.0,
    val netProfit: Double = 0.0,
    val passengerRating: Float? = null,
    val isLowRating: Boolean = false
)
