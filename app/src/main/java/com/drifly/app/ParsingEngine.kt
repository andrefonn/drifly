package com.drifly.app

import android.util.Log
import java.util.regex.Pattern

object ParsingEngine {

    private const val TAG = "ParsingEngine"

    // Valor mínimo real de uma oferta de corrida (filtra tarifas R$/km residuais como R$ 1,59 ou saldos R$ 0,00)
    const val MIN_VALID_RIDE_PRICE = 3.80

    // Regex para Preço: Suporta R$ 18,90 / R$ 18.90 / R$ 1.520,50 / R$ 25 (e ignora tarifas por km como R$ 2,50/km ou R$ 1,59 (por km))
    private val REGEX_PRECO_EXPLICITO = Pattern.compile(
        """(?i)R\$\s*([0-9]{1,4}(?:[.,][0-9]{3})*[.,][0-9]{2}|[0-9]{1,4}[.,][0-9]{2}|[0-9]{1,4})(?!\s*\(?\s*(?:/|por)\s*km)"""
    )
    private val REGEX_NUMERO_ISOLADO = Pattern.compile(
        """^([0-9]{1,4}(?:[.,][0-9]{3})*[.,][0-9]{2}|[0-9]{1,4}[.,][0-9]{2}|[0-9]{1,4})$"""
    )

    // Palavras exclusivas do próprio overlay do DriFly (para blindar contra auto-leitura via OCR ou janelas sobrepostas)
    private val SELF_OVERLAY_MARKERS = listOf(
        "lucro real",
        "lucro:",
        "gasto combustível",
        "combust.:",
        "r$ / km",
        "r$ / hora",
        "★ nota",
        "aceitar ✅",
        "recusar ❌",
        "boa direção ✅",
        "excelente direção ✅",
        "perfeita direção ✅",
        "ruim direção ❌",
        "péssima direção ❌",
        "evitar direção ❌",
        "ver fachada",
        "ocultar fachada"
    )

    // Indicadores inequívocos de que o motorista JÁ ESTÁ em uma corrida ativa ou tela de navegação GPS
    private val ACTIVE_RIDE_OR_NAVIGATION_MARKERS = listOf(
        "iniciar viagem",
        "iniciar corrida",
        "deslize para iniciar",
        "deslize para finalizar",
        "deslize para concluir",
        "deslize para encerrar",
        "deslize para chegar",
        "finalizar viagem",
        "finalizar corrida",
        "concluir viagem",
        "concluir corrida",
        "encerrar viagem",
        "cheguei ao local",
        "chegou ao local",
        "aguardando passageiro",
        "esperando passageiro",
        "em viagem com",
        "a caminho de",
        "embarque de ",
        "desembarque de ",
        "vire à esquerda",
        "vire a esquerda",
        "vire à direita",
        "vire a direita",
        "siga em frente",
        "na rotatória",
        "na rotatoria",
        "pegue a saída",
        "pegue a saida",
        "mantenha-se à",
        "mantenha-se a",
        "recalculando rota"
    )

    // Regex de Distância em KM e Metros
    private val REGEX_KM = Pattern.compile("""(?i)(\d{1,3}(?:[.,]\d{1,2})?)\s*km""")
    private val REGEX_METROS = Pattern.compile("""(?i)(\d{2,4})\s*(?:m|metros)\b(?!\s*in)""")

    // Regex de Duração
    private val REGEX_MIN = Pattern.compile("""(?i)(\d{1,3})\s*min""")

    // Regex de Nota do passageiro (3.00 a 5.00)
    private val REGEX_NOTA = Pattern.compile("""(?:★|\*|\b)?\s*([345][.,]\d{1,2})\b""")

    // Regex de Endereço de Destino (para visualização no Street View)
    private val REGEX_ENDERECO = Pattern.compile(
        """(?i)(?:Av\.?|Avenida|Rua|R\.?|Travessa|Alameda|Estrada|Rodovia|Praça)\s+[^\n]{4,80}"""
    )

    fun resolvePlatform(packageName: String, texts: List<String> = emptyList()): String {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("ubercab") || pkg.contains("uber") -> "UBER"
            pkg.contains("app99") || pkg.contains("taxis99") || pkg.contains("didi") || pkg.contains("99") -> "99"
            pkg.contains("ifood") -> "IFOOD"
            else -> {
                val joined = texts.joinToString(" ").lowercase()
                when {
                    joined.contains("99pop") || joined.contains("99moto") || joined.contains("99negocia") || joined.contains("99entrega") -> "99"
                    joined.contains("uberx") || joined.contains("uber comfort") || joined.contains("uber black") || joined.contains("uber moto") -> "UBER"
                    joined.contains("ifood") -> "IFOOD"
                    else -> "CORRIDA"
                }
            }
        }
    }

    fun parseMoneyValue(raw: String): Double? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null

        // Caso com ponto e vírgula (ex: 1.250,50 ou 1,250.50)
        if (clean.contains(".") && clean.contains(",")) {
            val lastDot = clean.lastIndexOf('.')
            val lastComma = clean.lastIndexOf(',')
            return if (lastComma > lastDot) {
                // Padrão Brasileiro (1.250,50 -> 1250.50)
                clean.replace(".", "").replace(",", ".").toDoubleOrNull()
            } else {
                // Padrão Internacional (1,250.50 -> 1250.50)
                clean.replace(",", "").toDoubleOrNull()
            }
        }

        // Caso apenas com vírgula (ex: 18,90)
        if (clean.contains(",")) {
            return clean.replace(",", ".").toDoubleOrNull()
        }

        // Caso apenas com ponto (ex: 15.50 ou 1.500)
        if (clean.contains(".")) {
            val parts = clean.split(".")
            if (parts.size == 2 && parts[1].length in 1..2) {
                // Decimal com ponto: 15.50 -> 15.50
                return clean.toDoubleOrNull()
            } else if (parts.size == 2 && parts[1].length == 3) {
                // Milhar sem centavos: 1.500 -> 1500.0
                return parts[0].toDoubleOrNull()?.times(1000.0)?.plus(parts[1].toDoubleOrNull() ?: 0.0)
            }
            return clean.toDoubleOrNull()
        }

        // Inteiro puro (ex: 20)
        return clean.toDoubleOrNull()
    }

    /**
     * Converte qualquer entrada numérica digitada pelo usuário (com vírgula "1,90" ou ponto "1.90",
     * ou com separador de milhar "1.250,50") para Float sem corromper casas decimais.
     */
    fun parseFlexibleFloat(raw: String, defaultVal: Float): Float {
        val parsed = parseMoneyValue(raw) ?: return defaultVal
        return parsed.toFloat()
    }

    /**
     * Identifica se um texto ou ID de botão corresponde a uma ação de ACEITAR corrida.
     */
    fun isAcceptActionText(textOrId: String): Boolean {
        val lower = textOrId.trim().lowercase()
        if (lower.isEmpty()) return false
        // Evita falso positivo com nosso próprio badge "Aceitar ✅"
        if (lower.contains("✅") || lower.contains("❌")) return false
        return lower == "aceitar" ||
                lower.contains("toque para aceitar") ||
                lower.contains("corresponder") ||
                lower.contains("aceitar corrida") ||
                lower.contains("aceitar viagem") ||
                lower == "pegar" ||
                lower.contains("deal_gesture") ||
                lower.contains("broad_order") ||
                lower.contains("btn_accept") ||
                lower.contains("accept_button")
    }

    /**
     * Identifica se um texto ou ID de botão corresponde a uma ação de RECUSAR / FECHAR oferta de corrida.
     */
    fun isDeclineActionText(textOrId: String): Boolean {
        val lower = textOrId.trim().lowercase()
        if (lower.isEmpty()) return false
        if (lower.contains("✅") || lower.contains("❌")) return false
        return lower == "recusar" ||
                lower == "rejeitar" ||
                lower == "x" ||
                lower == "fechar" ||
                lower == "ignorar" ||
                lower.contains("recusar corrida") ||
                lower.contains("btn_close") ||
                lower.contains("btn_decline") ||
                lower.contains("btn_reject") ||
                lower.contains("dismiss") ||
                lower.contains("decline") ||
                lower.contains("reject")
    }

    /**
     * Verifica se a lista de textos da tela indica que o motorista já está em uma corrida em andamento
     * ou em navegação passo-a-passo (evitando falsos overlays durante a corrida).
     */
    fun isActiveRideOrNavigationScreen(texts: List<String>): Boolean {
        val joined = texts.joinToString(" ").lowercase()
        return ACTIVE_RIDE_OR_NAVIGATION_MARKERS.any { joined.contains(it) }
    }

    /**
     * Verifica se os textos capturados contêm elementos do próprio overlay do DriFly.
     */
    fun containsSelfOverlayMarkers(texts: List<String>): Boolean {
        val joined = texts.joinToString(" ").lowercase()
        return SELF_OVERLAY_MARKERS.any { joined.contains(it) }
    }

    fun parseNodes(
        packageName: String,
        texts: List<String>,
        costPerKm: Double,
        minRatingThreshold: Float
    ): RideOffer? {
        if (texts.isEmpty()) return null
        if (packageName.contains("com.drifly.app", ignoreCase = true)) return null

        val platform = resolvePlatform(packageName, texts)

        // Limpa e normaliza os textos brutos, removendo quaisquer textos que pertençam ao próprio DriFly
        val cleanTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanTexts.isEmpty()) return null

        // Proteção 1: Se a árvore/OCR capturou o próprio overlay do DriFly, descarta imediatamente!
        if (containsSelfOverlayMarkers(cleanTexts)) {
            return null
        }

        // Proteção 2: Se a tela atual é de corrida em andamento / navegação GPS, NUNCA interpreta como nova oferta!
        if (isActiveRideOrNavigationScreen(cleanTexts)) {
            return null
        }

        var price: Double? = null
        var distance: Double? = null
        var duration: Int? = null
        var rating: Float? = null

        // 1. Extração de Preço (Individual, Janela Deslizante de 2 Tokens e Texto Completo)
        // Ignora tokens que sejam tarifas por km (ex: "R$ 1,59/km" ou "R$ 1,59" seguido de "/km" ou "(por km)")
        for (i in cleanTexts.indices) {
            val current = cleanTexts[i]
            val nextToken = if (i + 1 < cleanTexts.size) cleanTexts[i + 1].lowercase() else ""
            val prevToken = if (i > 0) cleanTexts[i - 1].lowercase() else ""

            // Se o token atual ou vizinho indica que esse valor é uma tarifa por km ou por hora, pula!
            val isRateContext = current.lowercase().contains("/km") ||
                    current.lowercase().contains("por km") ||
                    nextToken.startsWith("/km") ||
                    nextToken.startsWith("por km") ||
                    nextToken.startsWith("(por km") ||
                    nextToken.startsWith("/h") ||
                    prevToken.endsWith("/km")

            if (isRateContext) continue

            // Token atual tem "R$ ..." explícito
            val mPreco = REGEX_PRECO_EXPLICITO.matcher(current)
            if (mPreco.find()) {
                val groupVal = mPreco.group(1)
                if (groupVal != null) {
                    val p = parseMoneyValue(groupVal)
                    if (p != null && p >= MIN_VALID_RIDE_PRICE) {
                        price = p
                        break
                    }
                }
            }

            // Caso "R$" esteja em um token e o valor numérico no token imediatamente seguinte
            if (current.equals("R$", ignoreCase = true) && i + 1 < cleanTexts.size) {
                val next = cleanTexts[i + 1]
                val afterNext = if (i + 2 < cleanTexts.size) cleanTexts[i + 2].lowercase() else ""
                if (afterNext.startsWith("/km") || afterNext.startsWith("por km") || afterNext.startsWith("(por km")) {
                    continue
                }
                val mNum = REGEX_NUMERO_ISOLADO.matcher(next)
                if (mNum.find()) {
                    val groupVal = mNum.group(1)
                    if (groupVal != null) {
                        val p = parseMoneyValue(groupVal)
                        if (p != null && p >= MIN_VALID_RIDE_PRICE) {
                            price = p
                            break
                        }
                    }
                }
            }
        }

        // Passo 1b: Se ainda não achou preço, busca no texto completo concatenado
        if (price == null) {
            val fullText = cleanTexts.joinToString(" ")
            val mPrecoFull = REGEX_PRECO_EXPLICITO.matcher(fullText)
            while (mPrecoFull.find()) {
                val groupVal = mPrecoFull.group(1)
                if (groupVal != null) {
                    val p = parseMoneyValue(groupVal)
                    if (p != null && p >= MIN_VALID_RIDE_PRICE) {
                        price = p
                        break
                    }
                }
            }
        }

        // 2. Extração de Distância (Suporta KM, Metros, tokens divididos e múltiplos trechos embarque/viagem)
        var pickupDistKm: Double? = null
        var tripDistKm: Double? = null
        var totalDistKm: Double? = null
        val allDistancesFound = mutableListOf<Double>()

        for (i in cleanTexts.indices) {
            val current = cleanTexts[i]
            val lower = current.lowercase()

            // Concatena com próximo token para casos de divisão: ["5,2", "km"] ou ["800", "m"]
            val combinedWithNext = if (i + 1 < cleanTexts.size) "$current ${cleanTexts[i + 1]}" else current
            val combinedLower = combinedWithNext.lowercase()

            var detectedInToken: Double? = null

            // KM no token ou token+1
            val mKm = REGEX_KM.matcher(combinedWithNext)
            if (mKm.find()) {
                val raw = mKm.group(1)?.replace(",", ".")
                val d = raw?.toDoubleOrNull()
                if (d != null && d > 0.0) {
                    detectedInToken = d
                }
            }

            // Metros no token ou token+1
            if (detectedInToken == null) {
                val mM = REGEX_METROS.matcher(combinedWithNext)
                if (mM.find()) {
                    val raw = mM.group(1)
                    val m = raw?.toDoubleOrNull()
                    if (m != null && m > 0.0) {
                        detectedInToken = Math.round((m / 1000.0) * 100.0) / 100.0
                    }
                }
            }

            if (detectedInToken != null) {
                allDistancesFound.add(detectedInToken)

                // Identifica o papel deste trecho no contexto do texto
                if (combinedLower.contains("total") || combinedLower.contains("distância total")) {
                    totalDistKm = detectedInToken
                } else if (combinedLower.contains("passageiro") || combinedLower.contains("embarque") || combinedLower.contains("buscar")) {
                    pickupDistKm = detectedInToken
                } else if (combinedLower.contains("viagem") || combinedLower.contains("destino") || combinedLower.contains("levar")) {
                    tripDistKm = detectedInToken
                }
            }
        }

        // Determinação da distância final:
        // Se houver total explícito, usa ele
        if (totalDistKm != null && totalDistKm > 0.0) {
            distance = totalDistKm
        } else if (pickupDistKm != null && tripDistKm != null) {
            // Se identificou trecho até o passageiro E trecho de viagem, a distância total é a soma
            distance = Math.round((pickupDistKm + tripDistKm) * 100.0) / 100.0
        } else if (tripDistKm != null) {
            distance = tripDistKm
        } else if (allDistancesFound.isNotEmpty()) {
            // Se achou distâncias na tela sem labels específicos, prioriza a maior se for viagem + embarque
            distance = allDistancesFound.first()
        }

        // 3. Extração de Duração (minutos)
        for (i in cleanTexts.indices) {
            val current = cleanTexts[i]
            val combined = if (i + 1 < cleanTexts.size) "$current ${cleanTexts[i + 1]}" else current

            val mMin = REGEX_MIN.matcher(combined)
            if (mMin.find()) {
                val raw = mMin.group(1)
                val m = raw?.toIntOrNull()
                if (m != null && m > 0) {
                    duration = m
                    break
                }
            }
        }

        // 4. Extração de Nota do Passageiro (★ 4.85 ou 4.85)
        for (text in cleanTexts) {
            if (text.contains("R$") || text.contains("km", ignoreCase = true) || text.contains("min", ignoreCase = true)) {
                continue
            }
            val mNota = REGEX_NOTA.matcher(text)
            if (mNota.find()) {
                val raw = mNota.group(1)?.replace(",", ".")
                val parsed = raw?.toFloatOrNull()
                if (parsed != null && parsed in 3.0f..5.0f) {
                    rating = parsed
                    break
                }
            }
        }

        // 5. Validação da Oferta
        if (price != null && distance != null && distance > 0.0) {
            val rate = price / distance
            val roundedRate = Math.round(rate * 100.0) / 100.0

            val ratePerHour = if (duration != null && duration > 0) {
                Math.round((price / (duration / 60.0)) * 100.0) / 100.0
            } else {
                0.0
            }

            val estimatedCost = distance * costPerKm
            val net = price - estimatedCost
            val roundedNet = Math.round(net * 100.0) / 100.0

            val isLow = rating != null && rating < minRatingThreshold
            val destinationAddress = extractDestinationAddress(cleanTexts)

            try {
                Log.d(TAG, "parseNodes SUCESSO: [$platform] R$$price, ${distance}km, $duration min, R$/km=$roundedRate, R$/h=$ratePerHour, nota=$rating, lucro=R$$roundedNet")
            } catch (ignored: Throwable) {}

            return RideOffer(
                platform = platform,
                price = price,
                distanceKm = distance,
                durationMinutes = duration ?: 0,
                ratePerKm = roundedRate,
                ratePerHour = ratePerHour,
                estimatedFuelCost = Math.round(estimatedCost * 100.0) / 100.0,
                netProfit = roundedNet,
                passengerRating = rating,
                isLowRating = isLow,
                destinationAddress = destinationAddress
            )
        }

        try {
            Log.d(TAG, "parseNodes FALHA: [$platform] price=$price, dist=$distance | Textos (${cleanTexts.size}): ${cleanTexts.joinToString(" | ")}")
        } catch (ignored: Throwable) {}

        return null
    }

    fun extractDestinationAddress(texts: List<String>): String? {
        for (text in texts) {
            val m = REGEX_ENDERECO.matcher(text)
            if (m.find()) {
                val addr = m.group()?.trim()?.trimEnd(',', '-', '.', ' ')
                if (!addr.isNullOrBlank() && !addr.contains("R$") && !addr.contains("km", ignoreCase = true)) {
                    return addr
                }
            }
        }
        return null
    }

    fun parseReceipt(packageName: String, texts: List<String>): ReceiptResult? {
        val platform = resolvePlatform(packageName, texts)
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
                val mPreco = REGEX_PRECO_EXPLICITO.matcher(text)
                if (mPreco.find()) {
                    val raw = mPreco.group(1)
                    val parsed = raw?.let { parseMoneyValue(it) }
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
                    val mPreco = REGEX_PRECO_EXPLICITO.matcher(texts[i])
                    if (mPreco.find()) {
                        val p = mPreco.group(1)?.let { parseMoneyValue(it) }
                        if (p != null && p > 0.0) {
                            finalPrice = p
                            break
                        }
                    } else if (i + 1 < texts.size) {
                        val mPrecoNext = REGEX_PRECO_EXPLICITO.matcher(texts[i + 1])
                        if (mPrecoNext.find()) {
                            val p = mPrecoNext.group(1)?.let { parseMoneyValue(it) }
                            if (p != null && p > 0.0) {
                                finalPrice = p
                                break
                            }
                        }
                    }
                }
            }

            // Prioridade 2: Se não encontrou pelo fluxo acima, extrai o maior valor monetário encontrado na tela
            if (finalPrice == null) {
                val allPrices = mutableListOf<Double>()
                for (text in texts) {
                    val mPreco = REGEX_PRECO_EXPLICITO.matcher(text)
                    if (mPreco.find()) {
                        val p = mPreco.group(1)?.let { parseMoneyValue(it) }
                        if (p != null && p > 0.0) {
                            allPrices.add(p)
                        }
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
