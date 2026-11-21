package com.drifly.app

import android.util.Log
import java.util.regex.Pattern

object ParsingEngine {

    private const val TAG = "ParsingEngine"

    // Valor mínimo real de uma oferta de corrida (filtra tarifas R$/km residuais como R$ 1,59 ou saldos R$ 0,00)
    const val MIN_VALID_RIDE_PRICE = 3.80

    // Guarda-corpos contra lixo de OCR (banner promocional, barra de status, texto solto da tela)
    const val MIN_VALID_DISTANCE_KM = 0.1
    const val MAX_PLAUSIBLE_RATE_PER_KM = 50.0

    // Regex para Preço: Suporta R$ 18,90 / R$ 18.90 / R$ 1.520,50 / R$ 25 (e ignora tarifas por km como R$ 2,50/km ou R$ 1,59 (por km))
    private val REGEX_PRECO_EXPLICITO = Pattern.compile(
        """(?i)R\$\s*([0-9]{1,4}(?:[.,][0-9]{3})*[.,][0-9]{2}|[0-9]{1,4}[.,][0-9]{2}|[0-9]{1,4})(?!\s*\(?\s*(?:/|por)\s*km)"""
    )
    private val REGEX_NUMERO_ISOLADO = Pattern.compile(
        """^([0-9]{1,4}(?:[.,][0-9]{3})*[.,][0-9]{2}|[0-9]{1,4}[.,][0-9]{2}|[0-9]{1,4})$"""
    )

    // Palavras exclusivas do próprio overlay e da tela principal do DriFly (para blindar contra auto-leitura via OCR ou janelas sobrepostas)
    private val SELF_OVERLAY_MARKERS = listOf(
        "lucro real",
        "lucro:",
        "gasto combustível",
        "combust.:",
        "r$ / km",
        "r$ / hora",
        "rstkvm",
        "trsthora",
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
        "ocultar fachada",
        "aceite bloqueado",
        // Tela principal (MainActivity) do próprio DriFly
        "drifly",
        "painel de resultados",
        "progresso da sua meta",
        "progresso dasua meta",
        "ajustar meta de ganhos",
        "lançar despesa",
        "histórico de corridas",
        "historico de corridas",
        "monitoramento ativo",
        "atualizar meta",
        "km rodados"
    )

    // Captura variantes de OCR para o cabeçalho do overlay ("R$ / KM", "RS / HORA", etc.) sem casar com "Est. de R$1,35/km"
    private val REGEX_SELF_RATE_HEADER = Pattern.compile("""(?i)\br[\x24s]\s*/\s*(?:km|h(?:ora)?)\b""")

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
        "encontro com ",
        "destino de ",
        "embarque de ",
        "embarque em ",
        "desembarque de ",
        "local de partida",
        "ponto de encontro",
        "passageiro a bordo",
        "a caminho do passageiro",
        "a caminho do embarque",
        "a caminho do destino",
        "buscar passageiro",
        "deixar passageiro",
        "cancelar corrida",
        "cancelar a corrida",
        "cancelar viagem",
        "viagem em andamento",
        "corrida em andamento",
        "interromper novas solicitações",
        "interromper novas solicitacoes",
        "aconteceu alguma coisa?",
        "quer cancelar a viagem",
        "o passageiro pode gravar",
        "passageiro pode gravar",
        "pagamento no aplicativo",
        "pagamento em dinheiro",
        "corrida em dinheiro",
        "cobrar pagamento",
        "ponto de embarque",
        "local de embarque",
        "destino final",
        "corrida aceita",
        "viagem aceita",
        "passageiro aguardando",
        "a caminho do local"
    )

    // Indicadores exclusivos da FASE DE EMBARQUE (a caminho do passageiro / aguardando embarque).
    // Usado para distinguir o início de uma corrida consecutiva (fase de buscar o próximo passageiro)
    // do final da corrida anterior (fase de desembarque / cobrança).
    private val PICKUP_PHASE_MARKERS = listOf(
        "cheguei ao local",
        "chegou ao local",
        "aguardando passageiro",
        "esperando passageiro",
        "encontro com ",
        "embarque de ",
        "embarque em ",
        "local de partida",
        "ponto de encontro",
        "a caminho do passageiro",
        "a caminho do embarque",
        "buscar passageiro",
        "ponto de embarque",
        "local de embarque",
        "corrida aceita",
        "viagem aceita",
        "passageiro aguardando"
    )

    // Indicadores exclusivos da FASE DE VIAGEM COM PASSAGEIRO / DESEMBARQUE (após o embarque).
    // Usado para garantir que uma oferta consecutiva só seja promovida por transição de embarque
    // quando a corrida anterior já havia entrado na fase de viagem/desembarque.
    private val IN_TRIP_OR_DROPOFF_MARKERS = listOf(
        "iniciar corrida",
        "iniciar viagem",
        "finalizar corrida",
        "finalizar viagem",
        "encerrar corrida",
        "encerrar viagem",
        "desembarque de ",
        "desembarque em ",
        "chegou ao destino",
        "cheguei ao destino",
        "local de desembarque",
        "ponto de desembarque",
        "indo para ",
        "em direção a ",
        "em direcao a ",
        "deixar ",
        "deixar passageiro",
        "viagem em andamento",
        "corrida em andamento",
        "o passageiro pode gravar",
        "passageiro pode gravar",
        "pagamento no aplicativo",
        "pagamento em dinheiro",
        "corrida em dinheiro",
        "cobrar pagamento",
        "destino final"
    )

    // Sinais genéricos de navegação GPS passo-a-passo (podem aparecer também ao navegar
    // até uma área de demanda sem corrida aceita). Por si só NÃO provam corrida ativa,
    // mas bloqueiam falsa detecção de oferta caso não haja card explícito.
    private val TURN_BY_TURN_GPS_MARKERS = listOf(
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

    // Rótulos do cabeçalho do mapa da Uber e da 99 onde fica a pílula de saldo acumulado do dia
    private val MAP_HEADER_NEIGHBOR_LABELS = setOf(
        "página inicial",
        "pagina inicial",
        "hoje",
        "pesquisar locais",
        "saldo",
        "ganhos",
        "recompensas",
        "ver"
    )

    private val TOP_PILL_AFTER_LABELS = setOf(
        "moto",
        "pop",
        "99pop",
        "99moto",
        "99",
        "9 99",
        "m 99",
        "o 99",
        "ver",
        "recompensas",
        "solicitações",
        "solicitacoes",
        "solicitação",
        "solicitacao",
        "ganhos",
        "hoje"
    )

    private val NON_FARE_PRICE_MARKERS = listOf(
        "pacote 0%",
        "com o pacote",
        "como pacote",
        "ganhe até",
        "ganhe ate",
        "ganhe r$",
        "ganhers",
        "último",
        "ultimo",
        "tarifa base",
        "dinâmica incl",
        "dinamica incl",
        "ganhos desta semana",
        "ganhos de hoje",
        "ganhos do dia",
        "saldo",
        "faturamento",
        "atingido",
        "meta:",
        "antecip",
        "antecipação",
        "antecipacao",
        "adiant",
        "bônus",
        "bonus",
        "recebíveis",
        "recebiveis",
        "cashback",
        "carteira",
        "valor devolvido",
        "devolvido após",
        "devolvido apos",
        "próxima seg",
        "proxima seg",
        "entrega moto, entrega moto"
    )

    // Captura banner promocional da 99 mesmo quando truncado pelo OCR ("...ou R$68,4 com" de "Você ganhou R$68,4 com o pacote 0% de")
    private val REGEX_TRUNCATED_PROMO_BANNER = Pattern.compile(
        """(?i)\b(?:voc[eê]\s+ganhou|ganhou|ou)\s+r\$\s*[0-9.,]+\s+com\b"""
    )

    private val OFFER_VEHICLE_LABELS = listOf(
        "moto", "99pop", "99moto", "99negocia", "99entrega",
        "uberx", "uber comfort", "uber black", "uber moto",
        "exclusivo", "prioridade", "vip"
    )

    private val OFFER_AFTER_PRICE_LABELS = listOf(
        "0% de taxa99", "taxa99", "est. de r$", "/km", "tarifa base"
    )

    private val REGEX_CLOCK_TOKEN = Pattern.compile("""^\d{1,2}:\d{2}\b.*""")

    // Regex de Distância em KM e Metros
    private val REGEX_KM = Pattern.compile("""(?i)(\d{1,3}(?:[.,]\d{1,2})?)\s*km""")

    // Metros: exige que o número NÃO venha logo depois de outro dígito ou de ':' para não ler
    // o relógio da barra de status ("19:33 M 99" virava "33 metros" e criava oferta falsa).
    private val REGEX_METROS = Pattern.compile("""(?i)(?<![\d:])(\d{2,4})\s*(?:metros|m)\b(?!\d)(?!\s*in)""")

    // Regex de Duração
    private val REGEX_MIN = Pattern.compile("""(?i)(\d{1,3})\s*min""")

    // Trecho no formato "7 min (1,8 km)" ou "2 min (113 m)" — usado pelos cards da 99 e da Uber para
    // distância/tempo até o passageiro e de viagem. A Uber escreve tanto "6 min" quanto "6 minutos".
    private val REGEX_TRECHO_MIN_KM = Pattern.compile(
        """(?i)(\d{1,3})\s*(?:minutos|minuto|min)\s*\(\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*(km|m)\s*\)"""
    )

    private fun extractLegKm(matcher: java.util.regex.Matcher): Double? {
        val raw = matcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: return null
        val unit = matcher.group(3)?.lowercase() ?: "km"
        val km = if (unit == "m") raw / 1000.0 else raw
        return if (km > 0.0) km else null
    }

    // Regex de Nota do passageiro (3.00 a 5.00)
    private val REGEX_NOTA = Pattern.compile("""(?:★|\*|\b)?\s*([345][.,]\d{1,2})\b""")

    // Regex de Endereço de Destino (para visualização no Street View). Exige \b antes do prefixo
    // para impedir que verbos terminados em 'r' (ex: "Ajustar Meta", "Atualizar Meta") casem como "R Meta".
    private val REGEX_ENDERECO = Pattern.compile(
        """(?i)\b(?:Av\.?|Avenida|Rua|R\.?|Travessa|Alameda|Estrada|Rodovia|Praça)\s+[^\n]{4,80}"""
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
        val stripped = lower.trim { !it.isLetterOrDigit() }
        return lower == "aceitar" ||
                stripped == "aceitar" ||
                lower.contains("toque para aceitar") ||
                lower.contains("corresponder") ||
                lower.contains("aceitar corrida") ||
                lower.contains("aceitar viagem") ||
                lower == "pegar" ||
                stripped == "pegar" ||
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
     * Texto/ID de um botão de recusa de verdade (Uber/nativo). Diferente de [isDeclineActionText],
     * não aceita um "X" ou "Fechar" solto, que pode ser o fechar da navegação por baixo do card.
     */
    fun isDeclineButtonText(textOrId: String): Boolean {
        val lower = textOrId.trim().lowercase()
        if (lower.isEmpty()) return false
        return lower == "recusar" ||
            lower == "rejeitar" ||
            lower == "ignorar" ||
            lower.contains("recusar corrida") ||
            lower.contains("recusar viagem") ||
            lower.contains("btn_decline") ||
            lower.contains("btn_reject") ||
            lower.contains("decline") ||
            lower.contains("reject")
    }

    /** X/×/✕/✖ isolado, como o botão de fechar/recusar do card da 99. */
    fun isExactDeclineGlyph(text: String): Boolean {
        val normalized = text.trim().lowercase().trim(' ', '.', ',', ':', ';', '!', '?', '(', ')', '[', ']')
        return normalized == "x" || normalized == "×" || normalized == "✕" || normalized == "✖"
    }

    /** OCR que juntou o X do card com o selo "Não afeta a TA" na mesma linha. */
    fun isDeclineGlyphMergedWithBadge(text: String): Boolean {
        val lower = text.trim().lowercase()
        val startsWithX = lower.startsWith("x ") || lower.startsWith("× ") ||
            lower.startsWith("✕ ") || lower.startsWith("✖ ")
        return startsWithX && (lower.contains("não afeta") || lower.contains("nao afeta"))
    }

    /**
     * Verifica se a lista de textos da tela indica que o motorista já está em uma corrida em andamento
     * ou em navegação passo-a-passo (evitando falsos overlays durante a corrida).
     */
    fun isActiveRideOrNavigationScreen(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val joined = texts.joinToString(" ").lowercase()

        // Se há um card explícito de nova oferta na tela (ex: corrida consecutiva tocando no fim da viagem
        // ou card sobreposto ao mapa), nunca bloqueia como corrida ativa.
        val hasExplicitOfferCard = texts.any {
            val trimmed = it.trim()
            isAcceptActionText(trimmed) || trimmed.equals("tenho interesse", ignoreCase = true)
        } || joined.contains("est. de r$")
        if (hasExplicitOfferCard) return false

        // Se o motorista está na tela ociosa ("Procurando viagens" / "Você está online") ou no histórico/configurações,
        // nunca marca como corrida ativa. (Não usar EARNINGS_PANEL_MARKERS amplo aqui porque pílulas como
        // "Recompensas" ou "Interromper novas solicitações" aparecem durante corridas ativas.)
        val isHistoryOrConfig = HISTORY_OR_SETTINGS_MARKERS.any { joined.contains(it) }
        if (isIdleDriverScreen(texts) || isHistoryOrConfig) return false

        val hasExactActiveButton = texts.any {
            val t = it.trim().lowercase()
            t == "cheguei" || t == "ir buscar"
        }
        if (hasExactActiveButton) return true

        return ACTIVE_RIDE_OR_NAVIGATION_MARKERS.any { joined.contains(it) }
    }

    /**
     * Verifica se a tela atual pertence especificamente à FASE DE EMBARQUE (a caminho do passageiro
     * ou aguardando o passageiro no ponto de encontro). Usado para confirmar o início de uma corrida
     * consecutiva após o término da corrida anterior.
     */
    fun isPickupPhaseScreen(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val joined = texts.joinToString(" ").lowercase()
        if (isIdleDriverScreen(texts) || HISTORY_OR_SETTINGS_MARKERS.any { joined.contains(it) }) return false
        if (IN_TRIP_OR_DROPOFF_MARKERS.any { joined.contains(it) }) return false
        val hasExactPickupButton = texts.any {
            val t = it.trim().lowercase()
            t == "cheguei" || t == "ir buscar"
        }
        if (hasExactPickupButton) return true
        return PICKUP_PHASE_MARKERS.any { joined.contains(it) }
    }

    /**
     * Verifica se a tela atual pertence à FASE DE VIAGEM COM PASSAGEIRO ou DESEMBARQUE.
     */
    fun isInTripOrDropoffPhaseScreen(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val joined = texts.joinToString(" ").lowercase()
        if (isIdleDriverScreen(texts) || HISTORY_OR_SETTINGS_MARKERS.any { joined.contains(it) }) return false
        return IN_TRIP_OR_DROPOFF_MARKERS.any { joined.contains(it) }
    }

    private fun isPromoBannerText(joinedLower: String): Boolean {
        return joinedLower.contains("pacote 0%") ||
                joinedLower.contains("com o pacote") ||
                joinedLower.contains("como pacote") ||
                joinedLower.contains("ganhers") ||
                joinedLower.contains("ganhe r$") ||
                REGEX_TRUNCATED_PROMO_BANNER.matcher(joinedLower).find()
    }

    /**
     * Identifica se o(s) token(s) em [index]..[index + span - 1] pertencem à pílula de ganhos acumulados
     * no topo da Uber ("Página inicial | R$ 22,13 | HOJE"), da 99 ("18:13 | R$12 | Moto | 99" ou
     * "13:15 0 99 | ) | On: | ha Frd | Taxa | R$88,95 | Ver"), a banners promocionais
     * ("Você ganhou R$78,95 com o pacote 0%" / "...ou R$68,4 com") ou a sub-itens de tarifa.
     */
    private fun isMapHeaderPriceToken(cleanTexts: List<String>, index: Int, span: Int = 1): Boolean {
        if (index !in cleanTexts.indices) return false
        val endIdx = (index + span - 1).coerceAtMost(cleanTexts.lastIndex)
        val combinedToken = (index..endIdx).joinToString(" ") { cleanTexts[it].trim().lowercase() }
        if (NON_FARE_PRICE_MARKERS.any { combinedToken.contains(it) }) return true
        if (REGEX_TRUNCATED_PROMO_BANNER.matcher(combinedToken).find()) return true

        val prev1 = if (index - 1 >= 0) cleanTexts[index - 1].trim().lowercase() else ""
        val prev2 = if (index - 2 >= 0) cleanTexts[index - 2].trim().lowercase() else ""
        val next1 = if (endIdx + 1 < cleanTexts.size) cleanTexts[endIdx + 1].trim().lowercase() else ""
        val next2 = if (endIdx + 2 < cleanTexts.size) cleanTexts[endIdx + 2].trim().lowercase() else ""

        if (prev1 == "página inicial" || prev1 == "pagina inicial") return true
        if (prev1 == "r$" && (prev2 == "página inicial" || prev2 == "pagina inicial")) return true
        if (next1 in MAP_HEADER_NEIGHBOR_LABELS) return true
        val prev1HasOwnPrice = REGEX_PRECO_EXPLICITO.matcher(prev1).find()
        val next1HasOwnPrice = REGEX_PRECO_EXPLICITO.matcher(next1).find()
        if ((!prev1HasOwnPrice && NON_FARE_PRICE_MARKERS.any { prev1.contains(it) }) ||
            (!next1HasOwnPrice && NON_FARE_PRICE_MARKERS.any { next1.contains(it) })
        ) {
            return true
        }

        // Pílula de pacote/saldo no topo da 99 onde "Taxa" precede o saldo acumulado ("Taxa | R$88,95 | Ver")
        if (prev1 == "taxa" && (index <= 7 || next1 in TOP_PILL_AFTER_LABELS || next2 in TOP_PILL_AFTER_LABELS)) {
            return true
        }

        // Pílula de ganho acumulado no topo da 99 ao lado do relógio ("18:13 O | R$12 | Moto | 9 99")
        val hasClockShortlyBefore = (index <= 6) && (0 until index).any {
            REGEX_CLOCK_TOKEN.matcher(cleanTexts[it].trim()).matches()
        }
        if (hasClockShortlyBefore && (next1 in TOP_PILL_AFTER_LABELS || next1.startsWith("recompensas") || next1.startsWith("solicita"))) {
            return true
        }
        return false
    }

    private data class PriceCandidate(val index: Int, val value: Double, val score: Int)

    private fun scoreOfferPriceCandidate(cleanTexts: List<String>, index: Int, span: Int): Int {
        var score = 0
        val prevRangeStart = (index - 3).coerceAtLeast(0)
        for (k in prevRangeStart until index) {
            val prevLower = cleanTexts[k].trim().lowercase()
            if (OFFER_VEHICLE_LABELS.any { prevLower == it || prevLower.startsWith("$it ") }) {
                score += 10
                break
            }
        }
        val nextRangeStart = index + span
        val nextRangeEnd = (nextRangeStart + 1).coerceAtMost(cleanTexts.lastIndex)
        for (k in nextRangeStart..nextRangeEnd) {
            val nextLower = cleanTexts[k].trim().lowercase()
            if (OFFER_AFTER_PRICE_LABELS.any { nextLower.contains(it) } ||
                REGEX_TRECHO_MIN_KM.matcher(cleanTexts[k]).find() ||
                REGEX_KM.matcher(cleanTexts[k]).find() ||
                REGEX_MIN.matcher(cleanTexts[k]).find()
            ) {
                score += 10
                break
            }
        }
        val next1Lower = if (nextRangeStart < cleanTexts.size) cleanTexts[nextRangeStart].trim().lowercase() else ""
        if (next1Lower in TOP_PILL_AFTER_LABELS || OFFER_VEHICLE_LABELS.any { next1Lower == it }) {
            score -= 15
        }
        val hasClockBefore = index <= 6 && (0 until index).any { REGEX_CLOCK_TOKEN.matcher(cleanTexts[it].trim()).matches() }
        val hasStrongOfferAfter = (nextRangeStart..nextRangeEnd).any { k ->
            val nl = cleanTexts[k].trim().lowercase()
            nl.contains("0% de taxa99") || nl.contains("est. de r$") || REGEX_TRECHO_MIN_KM.matcher(cleanTexts[k]).find()
        }
        if (hasClockBefore && !hasStrongOfferAfter) {
            score -= 20
        }
        return score
    }

    private val IDLE_DRIVER_MARKERS = listOf(
        "você está online",
        "voce esta online",
        "você está offline",
        "voce esta offline",
        "procurando viagens",
        "procurando corridas",
        "encontrar viagens",
        "aguardando chamadas",
        "aguardando pedidos",
        "ouvindo chamadas",
        "em busca de passageiros",
        "radar de viagens"
    )

    /**
     * Verifica se o motorista está na tela ociosa ou offline dos apps parceiros (mapa inicial).
     * Usado para resetar qualquer estado residual de "corrida em andamento" e filtrar saldos do mapa.
     */
    fun isIdleDriverScreen(texts: List<String>): Boolean {
        val joined = texts.joinToString(" ").lowercase()
        if (IDLE_DRIVER_MARKERS.any { joined.contains(it) }) return true
        val hasIdleButton = texts.any {
            val t = it.trim().lowercase()
            t == "conectar" || t == "começar" || t == "comecar" ||
                t == "buscando" || t == "buscando..." || t == "buscando corridas" || t == "buscando viagens"
        }
        if (hasIdleButton) return true
        val hasActiveTripCue = joined.contains("em viagem") ||
            joined.contains("encontro com") ||
            joined.contains("passageiro a bordo")
        return !hasActiveTripCue && isPromoBannerText(joined)
    }

    /**
     * Verifica se uma tela (que contém marcadores de mapa ocioso/offline ao fundo) possui
     * sinais inequívocos de um card de oferta de corrida sobreposto.
     */
    private fun hasOfferCardSignal(cleanTexts: List<String>): Boolean {
        val joined = cleanTexts.joinToString(" ").lowercase()
        if (cleanTexts.any {
                val t = it.trim()
                isAcceptActionText(t) || t.equals("tenho interesse", ignoreCase = true) || t.equals("negociar", ignoreCase = true)
            }
        ) {
            return true
        }
        if (joined.contains("est. de r$") ||
            joined.contains("/km") ||
            joined.contains("por km") ||
            joined.contains("nova corrida") ||
            joined.contains("novo pedido") ||
            joined.contains("tarifa base") ||
            joined.contains("até o passageiro") ||
            joined.contains("de viagem")
        ) {
            return true
        }
        var legs = 0
        for (text in cleanTexts) {
            val m = REGEX_TRECHO_MIN_KM.matcher(text)
            while (m.find()) {
                legs++
                if (legs >= 2) return true
            }
        }
        return false
    }

    private val HISTORY_OR_SETTINGS_MARKERS = listOf(
        "histórico de corridas", "historico de corridas",
        "histórico de solicitações", "historico de solicitacoes",
        "central de ganhos", "ganhos desta semana", "painel de ganhos",
        "detalhamento de ganhos", "demonstrativo de ganhos",
        "configurar solicitações", "configurar solicitacoes", "teste de status"
    )

    private val EARNINGS_PANEL_MARKERS = listOf(
        "solicitações", "solicitacoes", "solicitação", "solicitacao", "central de ganhos", "ganhos desta semana",
        "ganhos de hoje", "ganhos do dia", "recompensas", "painel de ganhos", "meta do dia",
        "ganhe até", "ganhe ate", "último r$", "ultimo r$", "saldo de hoje", "ganhos hoje",
        "total de hoje", "meu saldo", "entrega moto, entrega moto",
        "histórico de corridas", "historico de corridas", "histórico de solicitações", "historico de solicitacoes",
        "seus ganhos", "detalhamento de ganhos", "demonstrativo de ganhos",
        "configurar solicitações", "configurar solicitacoes", "teste de status"
    )

    /** Tela com o painel de ganhos acumulados (pílula do topo / painel da 99). */
    fun isEarningsPanelScreen(texts: List<String>): Boolean {
        val sanitized = texts.joinToString(" ").lowercase()
            .replace("interromper novas solicitações", "")
            .replace("interromper novas solicitacoes", "")
        return EARNINGS_PANEL_MARKERS.any { sanitized.contains(it) }
    }

    /** Card de zona de demanda do mapa da 99 ("Demanda alta | Navegar | Como funciona o mapa"). */
    fun isDemandHotspotScreen(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val joined = texts.joinToString(" ").lowercase()
        return joined.contains("demanda alta") ||
            joined.contains("demanda média") ||
            joined.contains("demanda media") ||
            joined.contains("como funciona o mapa")
    }

    /**
     * A 99 combina barras nativas de acessibilidade (GPS top bar / km/h / velocidade) com uma camada
     * Flutter canvas embaixo onde ficam oferta ("Aceitar"), botões de viagem ("Cheguei", "Iniciar viagem")
     * ou "Buscando". Quando a árvore nativa traz só GPS/HUD e retorna Unknown, precisamos de OCR para ler
     * a camada Flutter em vez de cegar o OCR achando que a árvore "já resolve".
     */
    fun shouldVerifyFlutterLayerViaOcr(
        packageName: String,
        texts: List<String>,
        hasPendingOffer: Boolean
    ): Boolean {
        if (texts.isEmpty()) return false
        val pkg = packageName.lowercase()
        val is99 = pkg.contains("app99") || pkg.contains("taxis99") || pkg.contains("didi") || pkg.contains("99")
        if (!is99) return false
        if (containsSelfOverlayMarkers(texts)) return false
        if (isIdleDriverScreen(texts) || isEarningsPanelScreen(texts) || isDemandHotspotScreen(texts)) return false
        return hasPendingOffer || texts.size <= 12
    }

    /**
     * Verifica se os textos capturados contêm elementos do próprio overlay ou da UI do DriFly.
     */
    fun containsSelfOverlayMarkers(texts: List<String>): Boolean {
        val joined = texts.joinToString(" ").lowercase()
        if (SELF_OVERLAY_MARKERS.any { joined.contains(it) }) return true
        return REGEX_SELF_RATE_HEADER.matcher(joined).find()
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

        // Proteção 2: Se a tela atual é de corrida em andamento, NUNCA interpreta como nova oferta!
        if (isActiveRideOrNavigationScreen(cleanTexts)) {
            return null
        }

        // Proteção 2b: Navegação GPS passo-a-passo livre ou HUD de rota ("km/h" / "Chegada prevista")
        // sem card de oferta explícito não gera oferta falsa nem marca corrida ativa.
        val joinedCleanLower = cleanTexts.joinToString(" ").lowercase()
        val hasGenericGpsHud = TURN_BY_TURN_GPS_MARKERS.any { joinedCleanLower.contains(it) } ||
            joinedCleanLower.contains("chegada prevista") ||
            joinedCleanLower.contains("km/h")
        if (hasGenericGpsHud && !hasOfferCardSignal(cleanTexts)) {
            return null
        }

        // Proteção 3: Se a tela é o mapa inicial ocioso/offline (ex: "Você está offline", "Conectar", "Procurando viagens")
        // e NÃO há nenhum card de oferta sobreposto, qualquer R$ + km na tela é saldo do topo + distância de mapa/demanda.
        if (isIdleDriverScreen(cleanTexts) && !hasOfferCardSignal(cleanTexts)) {
            return null
        }

        // Proteção 4: painel/central de ganhos da 99 e Uber ("R$45,93", "6 Solicitações", "Recompensas").
        // Esses valores são o ACUMULADO do dia, nunca o preço de uma corrida. Sem sinais de card de oferta, ignora.
        if (isEarningsPanelScreen(cleanTexts) && !hasOfferCardSignal(cleanTexts)) {
            return null
        }

        var price: Double? = null
        var distance: Double? = null
        var duration: Int? = null
        var rating: Float? = null

        // 1. Extração de Preço (coleta candidatos fora de cabeçalhos/banners/tarifas por km e escolhe o mais aderente ao card)
        val priceCandidates = mutableListOf<PriceCandidate>()
        for (i in cleanTexts.indices) {
            if (isMapHeaderPriceToken(cleanTexts, i, span = 1)) continue

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
                        priceCandidates.add(
                            PriceCandidate(i, p, scoreOfferPriceCandidate(cleanTexts, i, span = 1))
                        )
                        continue
                    }
                }
            }

            // Caso "R$" esteja em um token e o valor numérico no token imediatamente seguinte
            if (current.equals("R$", ignoreCase = true) && i + 1 < cleanTexts.size) {
                if (isMapHeaderPriceToken(cleanTexts, i, span = 2)) continue
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
                            priceCandidates.add(
                                PriceCandidate(i, p, scoreOfferPriceCandidate(cleanTexts, i, span = 2))
                            )
                        }
                    }
                }
            }
        }

        price = priceCandidates.maxByOrNull { it.score }?.value

        // Passo 1b: Se ainda não achou preço, busca no texto completo concatenado (excluindo tokens do cabeçalho/banners)
        if (price == null) {
            val nonHeaderTexts = cleanTexts.filterIndexed { idx, _ -> !isMapHeaderPriceToken(cleanTexts, idx, span = 1) }
            val fullText = nonHeaderTexts.joinToString(" ")
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

        // 2b. Trechos no formato "7 min (1,8 km)": embarque e viagem.
        // Quando o card não traz rótulo textual ("até o passageiro" / "de viagem"), esse formato é o
        // único indício de que existem DOIS trechos. Somar ambos é o que reflete o km realmente rodado
        // (usar só o primeiro trecho infla o R$/km e faz o app recomendar corrida ruim).
        var legsDistanceSum: Double? = null
        var legsDurationSum: Int? = null
        var legCount = 0
        var legDistanceAccum = 0.0
        var legMinutesAccum = 0
        var firstLegKm: Double? = null
        var firstLegMinutes: Int? = null
        var remainingLegKmAccum = 0.0
        var remainingLegMinutesAccum = 0
        for (text in cleanTexts) {
            val matcher = REGEX_TRECHO_MIN_KM.matcher(text)
            while (matcher.find()) {
                val mins = matcher.group(1)?.toIntOrNull()
                val km = extractLegKm(matcher)
                if (mins != null && km != null) {
                    if (legCount == 0) {
                        firstLegKm = km
                        firstLegMinutes = mins
                    } else {
                        remainingLegKmAccum += km
                        remainingLegMinutesAccum += mins
                    }
                    legDistanceAccum += km
                    legMinutesAccum += mins
                    legCount++
                }
            }
        }
        if (legCount >= 2) {
            legsDistanceSum = Math.round(legDistanceAccum * 100.0) / 100.0
            legsDurationSum = legMinutesAccum
        }

        // Trechos detalhados preservados para a notificação (análise completa).
        // Quando o card não rotula os trechos, o primeiro "X min (Y km)" é o embarque e o restante é a viagem.
        val pickupLegKm = pickupDistKm
            ?: if (legCount >= 2) firstLegKm else null
        val tripLegKm = when {
            tripDistKm != null -> tripDistKm
            legCount >= 2 -> Math.round(remainingLegKmAccum * 100.0) / 100.0
            else -> null
        }
        val pickupLegMinutes = if (legCount >= 2) firstLegMinutes else null
        val tripLegMinutes = if (legCount >= 2 && remainingLegMinutesAccum > 0) remainingLegMinutesAccum else null

        // Determinação da distância final:
        // Se houver total explícito, usa ele
        val legsSum = legsDistanceSum
        if (totalDistKm != null && totalDistKm > 0.0) {
            distance = totalDistKm
        } else if (legsSum != null && legsSum > 0.0) {
            // Embarque + viagem identificados pelo formato "X min (Y km)" / "X min (Y m)"
            distance = legsSum
        } else if (pickupDistKm != null && tripDistKm != null) {
            // Se identificou trecho até o passageiro E trecho de viagem, a distância total é a soma
            distance = Math.round((pickupDistKm + tripDistKm) * 100.0) / 100.0
        } else if (tripDistKm != null) {
            distance = tripDistKm
        } else if (allDistancesFound.isNotEmpty()) {
            // Se achou distâncias na tela sem labels específicos, prioriza a maior (evita pegar embarque curto em metros)
            distance = allDistancesFound.max()
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

        // Se identificamos múltiplos trechos ("X min (Y km)"), o tempo real é a soma deles
        val legsMinutes = legsDurationSum
        if (legsMinutes != null && legsMinutes > 0) {
            duration = legsMinutes
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

            // Guarda-corpo: distância irrisória ou rentabilidade absurda não vêm de um card de corrida,
            // e sim de texto residual da tela (banner de recompensa, barra de status, botão solto).
            if (distance < MIN_VALID_DISTANCE_KM || rate > MAX_PLAUSIBLE_RATE_PER_KM) {
                try {
                    Log.d(TAG, "parseNodes DESCARTADO (fora de faixa plausível): R$$price, ${distance}km, R$/km=$roundedRate")
                } catch (ignored: Throwable) {}
                return null
            }

            val ratePerHour = if (duration != null && duration > 0) {
                Math.round((price / (duration / 60.0)) * 100.0) / 100.0
            } else {
                0.0
            }

            val estimatedCost = distance * costPerKm
            val net = price - estimatedCost
            val roundedNet = Math.round(net * 100.0) / 100.0

            val isLow = rating != null && rating < minRatingThreshold
            val pickupAddress = extractPickupAddress(cleanTexts)
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
                destinationAddress = destinationAddress,
                pickupAddress = pickupAddress,
                pickupDistanceKm = pickupLegKm,
                tripDistanceKm = tripLegKm,
                pickupDurationMinutes = pickupLegMinutes,
                tripDurationMinutes = tripLegMinutes
            )
        }

        try {
            Log.d(TAG, "parseNodes FALHA: [$platform] price=$price, dist=$distance | Textos (${cleanTexts.size}): ${cleanTexts.joinToString(" | ")}")
        } catch (ignored: Throwable) {}

        return null
    }

    /**
     * Extrai todos os endereços identificados nos textos da oferta.
     */
    fun extractAllAddresses(texts: List<String>): List<String> {
        val addresses = mutableListOf<String>()
        for (text in texts) {
            val m = REGEX_ENDERECO.matcher(text)
            while (m.find()) {
                val addr = m.group()?.trim()?.trimEnd(',', '-', '.', ' ')
                if (!addr.isNullOrBlank() && !addr.contains("R$") && !addr.contains("km", ignoreCase = true)) {
                    addresses.add(addr)
                }
            }
        }
        return addresses
    }

    /**
     * Extrai o endereço de EMBARQUE da oferta.
     *
     * Os cards da Uber e da 99 listam o ponto de EMBARQUE primeiro e o destino depois.
     * Quando 2 ou mais endereços casam com REGEX_ENDERECO, o primeiro é pickupAddress.
     * Se apenas 1 endereço casar, pickupAddress permanece null.
     */
    fun extractPickupAddress(texts: List<String>): String? {
        val addresses = extractAllAddresses(texts)
        return if (addresses.size >= 2) {
            addresses.first()
        } else {
            null
        }
    }

    /**
     * Extrai o endereço de DESTINO da oferta.
     *
     * Os cards da Uber e da 99 listam o ponto de EMBARQUE primeiro e o destino depois.
     * Por isso ficamos com o ÚLTIMO endereço encontrado (antes pegávamos o primeiro e a
     * fachada abria no lugar errado).
     */
    fun extractDestinationAddress(texts: List<String>): String? {
        val addresses = extractAllAddresses(texts)
        return addresses.lastOrNull()
    }

    /**
     * Diálogo em que o MOTORISTA confirma o cancelamento da corrida. Não é recibo: a tela ainda está
     * ativa e o motorista pode tocar em "Não cancelar". O serviço usa isso para, se a tela voltar ao
     * ocioso logo depois, marcar a corrida como CANCELADA sem taxa em vez de auto-concluir.
     */
    fun isDriverCancellationPromptScreen(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val joined = texts.joinToString(" ").lowercase()
        val hasPrompt = joined.contains("deseja mesmo cancelar") ||
            joined.contains("tem certeza que deseja cancelar") ||
            joined.contains("por favor, nos conte o motivo") ||
            joined.contains("por favor, nos conte o motiv") ||
            (joined.contains("não cancelar") && joined.contains("cancelar")) ||
            (joined.contains("nao cancelar") && joined.contains("cancelar"))
        return hasPrompt && !joined.contains("corrida cancelada") && !joined.contains("viagem cancelada")
    }

    /**
     * Extrai taxa APENAS quando existe uma frase explícita de cobrança de cancelamento perto do valor.
     * Nunca usa o primeiro R$ da tela e nunca assume a taxa padrão da plataforma.
     */
    fun extractExplicitCancellationFee(texts: List<String>): Double? {
        if (texts.isEmpty()) return null
        val joined = texts.joinToString(" \n ").lowercase()
        val phrases = listOf(
            "taxa de cancelamento",
            "taxa recebida",
            "taxa cobrada",
            "cobrança de cancelamento",
            "cobranca de cancelamento"
        )
        for (phrase in phrases) {
            var from = joined.indexOf(phrase)
            while (from >= 0) {
                val start = (from - 80).coerceAtLeast(0)
                val end = (from + phrase.length + 80).coerceAtMost(joined.length)
                val window = joined.substring(start, end)
                if (NON_FARE_PRICE_MARKERS.none { window.contains(it) }) {
                    val matcher = REGEX_PRECO_EXPLICITO.matcher(window)
                    if (matcher.find()) {
                        val parsed = matcher.group(1)?.let { parseMoneyValue(it) }
                        if (parsed != null && parsed in 0.0..100.0) return parsed
                    }
                }
                from = joined.indexOf(phrase, from + phrase.length)
            }
        }
        return null
    }

    /** Evita que banner/antecipação/bônus entre como tarifa final. */
    fun isPlausibleFinalFare(finalPrice: Double, originalPrice: Double): Boolean {
        if (finalPrice <= 0.0) return false
        if (originalPrice <= 0.0) return true
        val lower = originalPrice * 0.5
        val upper = originalPrice * 2.0 + 10.0
        return finalPrice in lower..upper
    }

    /** Textos que NUNCA são uma oferta de corrida (antecipação, bônus, saldo, pacote, etc.). */
    fun isNonFareNotificationText(content: String): Boolean {
        val lower = content.lowercase()
        val markers = listOf(
            "antecip", "adiant", "bônus", "bonus", "saldo", "pacote 0%", "com o pacote",
            "recebíveis", "recebiveis", "cashback", "carteira", "ganhos do dia", "ganhos de hoje",
            "valor devolvido", "devolvido após", "proxima seg", "próxima seg", "promoção", "promocao"
        )
        return markers.any { lower.contains(it) }
    }

    /** Só permite que uma notificação vire oferta se ela tiver sinais reais de card de corrida. */
    fun isLikelyRideOfferNotification(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val joined = texts.joinToString(" ").lowercase()
        if (isNonFareNotificationText(joined)) return false
        val hasOfferSignal = joined.contains("nova corrida") ||
            joined.contains("nova viagem") ||
            joined.contains("corrida disponível") ||
            joined.contains("corrida disponivel") ||
            joined.contains("aceitar") ||
            joined.contains("passageiro") ||
            joined.contains("embarque") ||
            joined.contains("destino") ||
            joined.contains("solicitação") ||
            joined.contains("solicitacao")
        return hasOfferSignal
    }

    /**
     * Notificação de conclusão/pagamento. "Antecipação" só conta quando o texto também fala de
     * corrida/viagem/pagamento; um aviso genérico de recebíveis não pode finalizar corrida.
     */
    fun isCompletionOrAnticipationNotification(content: String): Boolean {
        val lower = content.lowercase()
        val hasCompletion = lower.contains("pagamento recebido") ||
            lower.contains("corrida finalizada") ||
            lower.contains("viagem finalizada") ||
            lower.contains("corrida concluída") ||
            lower.contains("corrida concluida") ||
            lower.contains("viagem concluída") ||
            lower.contains("viagem concluida") ||
            lower.contains("pagamento confirmado")
        if (hasCompletion) return true
        val hasAnticipation = lower.contains("antecip") || lower.contains("adiant")
        val hasBonus = lower.contains("bônus") || lower.contains("bonus")
        val hasRideContext = lower.contains("corrida") || lower.contains("viagem") || lower.contains("pagamento")
        val hasReceived = lower.contains("receb") || lower.contains("credit") || lower.contains("pago") || lower.contains("pagamento")
        return (hasAnticipation || hasBonus) && hasRideContext && hasReceived
    }

    fun parseReceipt(packageName: String, texts: List<String>): ReceiptResult? {
        if (texts.isEmpty()) return null
        if (packageName.contains("com.drifly.app", ignoreCase = true)) return null
        if (containsSelfOverlayMarkers(texts)) return null

        val cleanTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanTexts.isEmpty()) return null

        val platform = resolvePlatform(packageName, cleanTexts)
        val joined = cleanTexts.joinToString(" ").lowercase()

        // Nunca interpreta telas de histórico de corridas, central de ganhos ou configurações como recibo/cancelamento ao vivo
        if (HISTORY_OR_SETTINGS_MARKERS.any { joined.contains(it) }) {
            return null
        }

        // Diálogo "Deseja mesmo cancelar esta corrida?" não é recibo: o motorista ainda pode desistir.
        if (isDriverCancellationPromptScreen(cleanTexts)) return null

        // 1. Verifica Cancelamento em Tela (ignorando avisos de oferta/solicitação expirada antes do aceite)
        val isOfferOnlyCancellation = joined.contains("solicitação cancelada") ||
                joined.contains("solicitacao cancelada") ||
                joined.contains("cancelou a solicitação") ||
                joined.contains("cancelou a solicitacao") ||
                joined.contains("oferta cancelada") ||
                joined.contains("oferta encerrada") ||
                joined.contains("pedido de corrida cancelado") ||
                joined.contains("pedido cancelado") ||
                joined.contains("cancelou o pedido")

        val isCancelled = !isOfferOnlyCancellation && (
                joined.contains("cancelada pelo passageiro") ||
                joined.contains("corrida cancelada") ||
                joined.contains("viagem cancelada") ||
                joined.contains("cancelado pelo usuário") ||
                joined.contains("cancelado pelo passageiro") ||
                joined.contains("passageiro cancelou") ||
                joined.contains("cancelou a corrida") ||
                joined.contains("cancelou a viagem") ||
                joined.contains("trip cancelled") ||
                joined.contains("ride cancelled")
        )

        // "OK, corrida cancelada" também aparece quando o motorista recusa uma OFERTA consecutiva.
        // Se a tela ainda tem a corrida ativa (Finalizar corrida / Chegada prevista / GPS), isso NÃO é
        // recibo de cancelamento da corrida. Era exatamente a causa da taxa fantasma #193/#215.
        if (isCancelled && (isInTripOrDropoffPhaseScreen(cleanTexts) ||
                joined.contains("chegada prevista") ||
                joined.contains("km/h"))
        ) {
            return null
        }

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

            // Só existe taxa se a tela disser explicitamente qual é. Nunca usar taxa padrão
            // nem o primeiro R$ da tela (que podia ser saldo/banner).
            val fee = extractExplicitCancellationFee(cleanTexts) ?: 0.0
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
        // Nunca confunde banners promocionais ("Você ganhou R$78,95 com o pacote 0% de Taxa"),
        // tela ociosa ("Buscando") ou painéis de ganhos com recibo de conclusão de corrida.
        val hasExplicitCompletionKeyword = joined.contains("valor da corrida") ||
                joined.contains("valor final") ||
                joined.contains("corrida finalizada") ||
                joined.contains("viagem finalizada") ||
                joined.contains("viagem concluída") ||
                joined.contains("viagem concluida") ||
                joined.contains("corrida concluída") ||
                joined.contains("corrida concluida") ||
                joined.contains("detalhes da viagem") ||
                joined.contains("ganhos da viagem") ||
                joined.contains("total da viagem") ||
                joined.contains("ganhos líquidos") ||
                joined.contains("ganhos liquidos")

        // Tela de cobrança em dinheiro ao encerrar corrida na 99 ("Seus ganhos | Cobrar do passageiro | Detalhes da tarifa | O passageiro pagou")
        val hasCashCollectionCompletionKeyword = joined.contains("cobrar do passageiro") && (
                joined.contains("detalhes da tarifa") ||
                joined.contains("o passageiro pagou") ||
                joined.contains("receba em dinheiro") ||
                joined.contains("valor devolvido")
        )

        val hasRatingCompletionKeyword = joined.contains("como foi sua corrida") ||
                joined.contains("seu feedback sincero pode ajudar") ||
                joined.contains("avaliar como anônimo") ||
                joined.contains("avaliar como anonimo") ||
                joined.contains("você se sentiu seguro(a) nesta corrida") ||
                joined.contains("voce se sentiu seguro(a) nesta corrida") ||
                joined.contains("você se sentiu seguro nesta corrida") ||
                joined.contains("voce se sentiu seguro nesta corrida") ||
                joined.contains("você gostou do local de desembarque") ||
                joined.contains("voce gostou do local de desembarque") ||
                joined.contains("pagamento recebido") ||
                joined.contains("solução da 99 para a corrida não paga") ||
                joined.contains("solucao da 99 para a corrida nao paga") ||
                joined.contains("contestar o valor de uma corrida 99") ||
                joined.contains("avalie o passageiro") ||
                joined.contains("avaliar passageiro")

        if (!hasExplicitCompletionKeyword &&
            (isPromoBannerText(joined) || isIdleDriverScreen(cleanTexts))
        ) {
            return null
        }

        // Nas telas de encerramento da 99 em dinheiro ("Seus ganhos | Cobrar do passageiro" ou modal "Como foi sua corrida"
        // sobreposto a "Seus ganhos"), "seus ganhos" aparece no topo da tela mas a corrida acabou de ser concluída.
        if (!hasExplicitCompletionKeyword &&
            !hasRatingCompletionKeyword &&
            !hasCashCollectionCompletionKeyword &&
            isEarningsPanelScreen(cleanTexts)
        ) {
            return null
        }

        val hasFareCompletionPhrase = (!isPromoBannerText(joined) &&
                !isNonFareNotificationText(joined) &&
                (joined.contains("você ganhou") || joined.contains("voce ganhou"))) ||
                joined.contains("total recebido") ||
                hasExplicitCompletionKeyword

        val isCompleted = hasFareCompletionPhrase || hasCashCollectionCompletionKeyword || hasRatingCompletionKeyword

        if (isCompleted) {
            val keyWords = listOf(
                "valor da corrida",
                "valor final",
                "total recebido",
                "ganhos da viagem",
                "total da viagem",
                "ganhos líquidos",
                "ganhos liquidos",
                "você ganhou",
                "voce ganhou"
            )
            var finalPrice: Double? = null

            // Na tela de avaliação/segurança/"Pagamento recebido!" pós-corrida da 99 (sem frase explícita de tarifa final),
            // mantém finalPrice = null para preservar o preço original da corrida (evitando capturar R$ 16,64 de troco/voucher
            // ou R$ 0,00 de "pagar na próxima").
            if (hasFareCompletionPhrase) {
                // Prioridade 1: Preço na mesma linha ou linha imediatamente seguinte a uma palavra-chave de total
                for (i in cleanTexts.indices) {
                    if (isMapHeaderPriceToken(cleanTexts, i, span = 1)) continue
                    val lineLower = cleanTexts[i].lowercase()
                    if (NON_FARE_PRICE_MARKERS.any { lineLower.contains(it) } || isPromoBannerText(lineLower)) continue
                    if (keyWords.any { lineLower.contains(it) }) {
                        val mPreco = REGEX_PRECO_EXPLICITO.matcher(cleanTexts[i])
                        if (mPreco.find()) {
                            val p = mPreco.group(1)?.let { parseMoneyValue(it) }
                            if (p != null && p >= MIN_VALID_RIDE_PRICE) {
                                finalPrice = p
                                break
                            }
                        } else if (i + 1 < cleanTexts.size && !isMapHeaderPriceToken(cleanTexts, i + 1, span = 1)) {
                            val nextLower = cleanTexts[i + 1].lowercase()
                            if (NON_FARE_PRICE_MARKERS.none { nextLower.contains(it) } && !isPromoBannerText(nextLower)) {
                                val mPrecoNext = REGEX_PRECO_EXPLICITO.matcher(cleanTexts[i + 1])
                                if (mPrecoNext.find()) {
                                    val p = mPrecoNext.group(1)?.let { parseMoneyValue(it) }
                                    if (p != null && p >= MIN_VALID_RIDE_PRICE) {
                                        finalPrice = p
                                        break
                                    }
                                }
                            }
                        }
                    }
                }

                // Prioridade 2: Se não encontrou pelo fluxo acima, extrai o maior valor monetário válido encontrado na tela
                if (finalPrice == null) {
                    val allPrices = mutableListOf<Double>()
                    for (i in cleanTexts.indices) {
                        if (isMapHeaderPriceToken(cleanTexts, i, span = 1)) continue
                        val text = cleanTexts[i]
                        val lineLower = text.lowercase()
                        if (NON_FARE_PRICE_MARKERS.any { lineLower.contains(it) } || isPromoBannerText(lineLower)) continue
                        val mPreco = REGEX_PRECO_EXPLICITO.matcher(text)
                        if (mPreco.find()) {
                            val p = mPreco.group(1)?.let { parseMoneyValue(it) }
                            if (p != null && p >= MIN_VALID_RIDE_PRICE) {
                                allPrices.add(p)
                            }
                        }
                    }
                    finalPrice = allPrices.maxOrNull()
                }
            } else if (hasCashCollectionCompletionKeyword && !hasRatingCompletionKeyword) {
                // Na tela "Seus ganhos | Cobrar do passageiro" (onde o topo pode exibir R$ 16,64 a cobrar em dinheiro após
                // voucher e o rodapé exibe R$ 17,80 de ganho total da corrida), extrai o maior valor válido e sinaliza
                // isCashCollectionScreen = true para nunca reduzir abaixo do originalPrice (ex: "pagar na próxima").
                val allPrices = mutableListOf<Double>()
                for (i in cleanTexts.indices) {
                    if (isMapHeaderPriceToken(cleanTexts, i, span = 1)) continue
                    val text = cleanTexts[i]
                    val lineLower = text.lowercase()
                    if (NON_FARE_PRICE_MARKERS.any { lineLower.contains(it) } || isPromoBannerText(lineLower)) continue
                    val mPreco = REGEX_PRECO_EXPLICITO.matcher(text)
                    if (mPreco.find()) {
                        val p = mPreco.group(1)?.let { parseMoneyValue(it) }
                        if (p != null && p >= MIN_VALID_RIDE_PRICE) {
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
                status = RideRecord.STATUS_CONCLUIDO,
                isCashCollectionScreen = hasCashCollectionCompletionKeyword && !hasFareCompletionPhrase
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
    val status: String,
    val isCashCollectionScreen: Boolean = false
)
