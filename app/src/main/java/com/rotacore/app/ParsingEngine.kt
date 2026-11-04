package com.rotacore.app

import java.util.regex.Pattern

object ParsingEngine {

    private val REGEX_PRECO = Pattern.compile("""R\$\s*(\d{1,3}(?:\.\d{3})*,\d{2})""")
    private val REGEX_KM = Pattern.compile("""(?i)(\d{1,3}(?:[.,]\d{1,2})?)\s*km""")
    private val REGEX_MIN = Pattern.compile("""(?i)(\d{1,3})\s*min""")
    // Regex de nota: ex 4.85, 4,92, ★ 4.78
    private val REGEX_NOTA = Pattern.compile("""(?:★|\*|\b)?\s*([345][.,]\d{1,2})\b""")

    fun parseNodes(
        packageName: String,
        texts: List<String>,
        costPerKm: Double,
        minRatingThreshold: Float
    ): RideOffer? {
        val platform = when (packageName) {
            "com.ubercab.driver" -> "UBER"
            "com.app99.driver" -> "99"
            "br.com.ifood.driver.app" -> "IFOOD"
            else -> "CORRIDA"
        }

        var price: Double? = null
        var distance: Double? = null
        var duration: Int? = null
        var rating: Float? = null

        for (text in texts) {
            // Preço
            if (price == null) {
                val mPreco = REGEX_PRECO.matcher(text)
                if (mPreco.find()) {
                    val raw = mPreco.group(1)
                    if (raw != null) {
                        val cleaned = raw.replace(".", "").replace(",", ".")
                        price = cleaned.toDoubleOrNull()
                    }
                }
            }

            // Distância
            if (distance == null) {
                val mKm = REGEX_KM.matcher(text)
                if (mKm.find()) {
                    val raw = mKm.group(1)
                    if (raw != null) {
                        val cleaned = raw.replace(",", ".")
                        distance = cleaned.toDoubleOrNull()
                    }
                }
            }

            // Duração
            if (duration == null) {
                val mMin = REGEX_MIN.matcher(text)
                if (mMin.find()) {
                    val raw = mMin.group(1)
                    if (raw != null) {
                        duration = raw.toIntOrNull()
                    }
                }
            }

            // Nota do Passageiro
            if (rating == null && !text.contains("R$") && !text.contains("km")) {
                val mNota = REGEX_NOTA.matcher(text)
                if (mNota.find()) {
                    val raw = mNota.group(1)
                    if (raw != null) {
                        val parsed = raw.replace(",", ".").toFloatOrNull()
                        if (parsed != null && parsed in 3.0f..5.0f) {
                            rating = parsed
                        }
                    }
                }
            }
        }

        if (price != null && distance != null && distance > 0.0) {
            val rate = price / distance
            val roundedRate = Math.round(rate * 100.0) / 100.0

            val estimatedCost = distance * costPerKm
            val net = price - estimatedCost
            val roundedNet = Math.round(net * 100.0) / 100.0

            val isLow = rating != null && rating < minRatingThreshold

            return RideOffer(
                platform = platform,
                price = price,
                distanceKm = distance,
                durationMinutes = duration ?: 0,
                ratePerKm = roundedRate,
                estimatedFuelCost = Math.round(estimatedCost * 100.0) / 100.0,
                netProfit = roundedNet,
                passengerRating = rating,
                isLowRating = isLow
            )
        }

        return null
    }
}
