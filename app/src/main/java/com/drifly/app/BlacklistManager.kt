package com.drifly.app

import android.content.Context
import java.text.Normalizer
import java.util.Locale

data class BlacklistItem(
    val id: Long = 0L,
    val rawTerm: String,
    val normalizedTerm: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
)

object BlacklistManager {

    /**
     * Normaliza o texto removendo acentos/diacríticos, pontuação,
     * convertendo para minúsculas e eliminando múltiplos espaços.
     */
    fun normalizeText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val nfd = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        val withoutDiacritics = nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return withoutDiacritics
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Verifica se o endereço fornecido corresponde a algum item da Lista Negra.
     * Realiza verificação textual normalizada offline (<1ms) e, se coordenadas
     * estiverem disponíveis, verificação geográfica por raio de 1.5 km (Haversine).
     */
    fun matchesBlacklist(
        address: String?,
        items: List<BlacklistItem>,
        addressCoords: Pair<Double, Double>? = null
    ): BlacklistItem? {
        if (address.isNullOrBlank() || items.isEmpty()) return null

        val normAddr = normalizeText(address)
        if (normAddr.isEmpty()) return null

        val addrWords = normAddr.split(" ").filter { it.isNotBlank() }.toSet()

        for (item in items) {
            val normTerm = item.normalizedTerm.ifBlank { normalizeText(item.rawTerm) }
            if (normTerm.isEmpty()) continue

            // 1. Correspondência textual direta por substring
            if (normAddr.contains(normTerm)) {
                return item
            }

            // 2. Correspondência textual por tokens significativos (comprimento >= 2)
            val termTokens = normTerm.split(" ").filter { it.length >= 2 }
            if (termTokens.isNotEmpty()) {
                val allTokensFound = termTokens.all { token ->
                    normAddr.contains(token) || addrWords.contains(token)
                }
                if (allTokensFound) {
                    return item
                }
            }

            // 3. Substring reversa (caso o endereço extraído seja um fragmento menor contido no termo)
            if (normAddr.length >= 4 && normTerm.contains(normAddr)) {
                return item
            }

            // 4. Correspondência geográfica (se ambos possuírem coordenadas válidas)
            if (addressCoords != null && item.latitude != null && item.longitude != null) {
                val distanceKm = ModoCasaHelper.calculateDistanceKm(
                    lat1 = addressCoords.first,
                    lon1 = addressCoords.second,
                    lat2 = item.latitude,
                    lon2 = item.longitude
                )
                if (distanceKm < 1.5) {
                    return item
                }
            }
        }

        return null
    }

    /**
     * Formata mensagem de alerta de Lista Negra conforme especificações:
     * - Apenas embarque: "⛔ Embarque em Lista Negra: <Bairro>"
     * - Apenas destino: "⛔ Destino em Lista Negra: <Bairro>"
     * - Ambos: "⛔ Embarque e Destino em Lista Negra (<Bairros>)"
     */
    fun formatBlacklistAlert(pickupMatch: BlacklistItem?, destMatch: BlacklistItem?): String? {
        return when {
            pickupMatch != null && destMatch != null -> {
                if (pickupMatch.rawTerm.equals(destMatch.rawTerm, ignoreCase = true)) {
                    "⛔ Embarque e Destino em Lista Negra (${pickupMatch.rawTerm})"
                } else {
                    "⛔ Embarque e Destino em Lista Negra (${pickupMatch.rawTerm}, ${destMatch.rawTerm})"
                }
            }
            pickupMatch != null -> {
                "⛔ Embarque em Lista Negra: ${pickupMatch.rawTerm}"
            }
            destMatch != null -> {
                "⛔ Destino em Lista Negra: ${destMatch.rawTerm}"
            }
            else -> null
        }
    }

    /**
     * Avalia uma oferta contra a lista negra ativa.
     * Retorna uma nova instância de RideOffer com os campos de bloqueio devidamente preenchidos.
     */
    fun evaluateOffer(
        offer: RideOffer,
        blacklistItems: List<BlacklistItem>,
        pickupCoords: Pair<Double, Double>? = null,
        destCoords: Pair<Double, Double>? = null
    ): RideOffer {
        if (blacklistItems.isEmpty()) {
            return offer.copy(isBlacklistBlocked = false, blacklistAlert = null)
        }

        val pickupMatch = matchesBlacklist(offer.pickupAddress, blacklistItems, pickupCoords)
        val destMatch = matchesBlacklist(offer.destinationAddress, blacklistItems, destCoords)

        val isBlocked = pickupMatch != null || destMatch != null
        val alert = formatBlacklistAlert(pickupMatch, destMatch)

        return offer.copy(
            isBlacklistBlocked = isBlocked,
            blacklistAlert = alert
        )
    }

    /**
     * Geocodifica um termo textual de forma síncrona/utilitária em background via ModoCasaHelper.
     */
    fun geocodeTerm(context: Context, rawTerm: String): Pair<Double, Double>? {
        return ModoCasaHelper.geocodeAddress(context, rawTerm)
    }
}
