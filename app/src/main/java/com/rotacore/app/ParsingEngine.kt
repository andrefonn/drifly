package com.rotacore.app

import java.util.regex.Pattern

object ParsingEngine {

    private val REGEX_PRECO = Pattern.compile("""(?i)R\$\s*([0-9]+(?:[\\.,][0-9]{3})*[\\.,][0-9]{2}|[0-9]+(?:[\\.,][0-9]{2})?)""")
    private val REGEX_KM = Pattern.compile("""(?i)(\d{1,3}(?:[.,]\d{1,2})?)\s*km""")
    private val REGEX_MIN = Pattern.compile("""(?i)(\d{1,3})\s*min""")
    // Regex de nota: ex 4.85, 4,92, ★ 4.78
    private val REGEX_NOTA = Pattern.compile("""(?:★|\*|\b)?\s*([345][.,]\d{1,2})\b""")

    private fun extractFirstPrice(text: String): Double? {
        val mPreco = REGEX_PRECO.matcher(text)
        if (mPreco.find()) {
            val raw = mPreco.group(1)?.replace(".", "")?.replace(",", ".")
            return raw?.toDoubleOrNull()
        }
        return null
    }

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
                val parsedPrice = extractFirstPrice(text)
                if (parsedPrice != null && parsedPrice > 0.0) {
                    price = parsedPrice
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

    fun parseReceipt(packageName: String, texts: List<String>): ReceiptResult? {
        val platform = when (packageName) {
            "com.ubercab.driver" -> "UBER"
            "com.app99.driver" -> "99"
            "br.com.ifood.driver.app" -> "IFOOD"
            else -> "CORRIDA"
        }

        val joined = texts.joinToString(" ").lowercase()

        // 1. Verifica Cancelamento em Tela
        val isCancelled = joined.contains("cancelada pelo passageiro") ||
                joined.contains("corrida cancelada") ||
                joined.contains("viagem cancelada") ||
                joined.contains("cancelado pelo usuário") ||
                joined.contains("cancelado pelo passageiro") ||
                joined.contains("passageiro cancelou") ||
                joined.contains("cancelou a corrida") ||
                joined.contains("cancelou a viagem") ||
                joined.contains("trip cancelled") ||
                joined.contains("ride cancelled")

        if (isCancelled) {
            val hasNoFee = joined.contains("sem taxa") ||
                    joined.contains("sem cobrança") ||
                    joined.contains("sem cobranca") ||
                    joined.contains("nenhuma taxa") ||
                    joined.contains("sem custos") ||
                    joined.contains("taxa: r$ 0") ||
                    joined.contains("taxa de r$ 0") ||
                    joined.contains("r$ 0,00") ||
                    joined.contains("r$ 0.00") ||
                    joined.contains("r$ 0")

            if (hasNoFee) {
                return ReceiptResult(
                    platform = platform,
                    isCompleted = false,
                    isCancelled = true,
                    finalPrice = 0.0,
                    cancellationFee = 0.0,
                    status = RideRecord.STATUS_CANCELADO
                )
            }

            var detectedFee: Double? = null
            for (text in texts) {
                val parsed = extractFirstPrice(text)
                if (parsed != null) {
                    if (parsed == 0.0) {
                        return ReceiptResult(
                            platform = platform,
                            isCompleted = false,
                            isCancelled = true,
                            finalPrice = 0.0,
                            cancellationFee = 0.0,
                            status = RideRecord.STATUS_CANCELADO
                        )
                    } else if (parsed > 0.0) {
                        detectedFee = parsed
                        break
                    }
                }
            }

            val defaultFee = when (platform) {
                "99" -> 3.00
                "UBER" -> 5.50
                else -> 0.0
            }

            val fee = detectedFee ?: defaultFee
            val status = if (fee > 0.0) RideRecord.STATUS_CANCELADO_TAXA else RideRecord.STATUS_CANCELADO

            return ReceiptResult(
                platform = platform,
                isCompleted = false,
                isCancelled = true,
                finalPrice = fee,
                cancellationFee = fee,
                status = status
            )
        }

        // 2. Verifica Conclusão / Recibo em Tela
        val isCompleted = joined.contains("você ganhou") ||
                joined.contains("voce ganhou") ||
                joined.contains("valor final") ||
                joined.contains("corrida finalizada") ||
                joined.contains("viagem finalizada") ||
                joined.contains("viagem concluída") ||
                joined.contains("viagem concluida") ||
                joined.contains("total recebido") ||
                joined.contains("ganhos da viagem") ||
                joined.contains("total da viagem") ||
                joined.contains("ganhos líquidos") ||
                joined.contains("ganhos liquidos")

        if (isCompleted) {
            val keyWords = listOf("você ganhou", "voce ganhou", "valor final", "total recebido", "ganhos da viagem", "total da viagem")
            var finalPrice: Double? = null

            // Prioridade 1: Preço na mesma linha ou linha imediatamente seguinte a uma palavra-chave de total
            for (i in texts.indices) {
                val lineLower = texts[i].lowercase()
                if (keyWords.any { lineLower.contains(it) }) {
                    val pInLine = extractFirstPrice(texts[i])
                    if (pInLine != null && pInLine > 0.0) {
                        finalPrice = pInLine
                        break
                    } else if (i + 1 < texts.size) {
                        val pNextLine = extractFirstPrice(texts[i + 1])
                        if (pNextLine != null && pNextLine > 0.0) {
                            finalPrice = pNextLine
                            break
                        }
                    }
                }
            }

            // Prioridade 2: Se não encontrou pelo fluxo acima, extrai o maior valor monetário encontrado na tela (evita taxas/descontos menores)
            if (finalPrice == null) {
                val allPrices = mutableListOf<Double>()
                for (text in texts) {
                    val p = extractFirstPrice(text)
                    if (p != null && p > 0.0) {
                        allPrices.add(p)
                    }
                }
                finalPrice = allPrices.maxOrNull()
            }

            return ReceiptResult(
                platform = platform,
                isCompleted = true,
                isCancelled = false,
                finalPrice = finalPrice,
                cancellationFee = 0.0,
                status = RideRecord.STATUS_CONCLUIDO
            )
        }

        return null
    }
}

data class ReceiptResult(
    val platform: String,
    val isCompleted: Boolean,
    val isCancelled: Boolean,
    val finalPrice: Double?,
    val cancellationFee: Double?,
    val status: String
)

