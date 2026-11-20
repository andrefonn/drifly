package com.drifly.app

data class RideOffer(
    val platform: String,
    val price: Double,
    val distanceKm: Double,
    val durationMinutes: Int,
    val ratePerKm: Double,
    val ratePerHour: Double = 0.0,
    val estimatedFuelCost: Double = 0.0,
    val netProfit: Double = 0.0,
    val passengerRating: Float? = null,
    val isLowRating: Boolean = false,
    val destinationAddress: String? = null,
    val isHeadingHome: Boolean = false,
    val homeDistanceDeltaKm: Double = 0.0,
    val pickupAddress: String? = null,
    val isBlacklistBlocked: Boolean = false,
    val blacklistAlert: String? = null,
    // Trechos detalhados (quando o card informa embarque e viagem separadamente).
    // A distância/tempo totais continuam em distanceKm/durationMinutes.
    val pickupDistanceKm: Double? = null,
    val tripDistanceKm: Double? = null,
    val pickupDurationMinutes: Int? = null,
    val tripDurationMinutes: Int? = null
)
