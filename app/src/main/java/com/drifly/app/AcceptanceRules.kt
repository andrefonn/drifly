package com.drifly.app

/**
 * Regras puras (sem Android) que decidem se uma corrida pode entrar no Faturamento como ACEITA.
 *
 * Princípio: só vira corrida aceita quando existe EVIDÊNCIA de aceite. Oferta que apenas apareceu,
 * expirou, foi recusada ou foi ignorada NUNCA pode ser promovida.
 *
 * Evidências válidas:
 *  1. Clique real do motorista (ou do DriFly) num botão "Aceitar" enquanto a oferta está viva.
 *  2. Toque no overlay cujo clique/gesto foi de fato executado no app da plataforma.
 *  3. A tela mudou para corrida ativa/navegação logo após a oferta (aceite feito dentro do app),
 *     exceto quando a oferta chegou DURANTE outra corrida (a tela de navegação anterior continua lá
 *     mesmo que o motorista ignore a oferta, então ela não prova nada).
 */
object AcceptanceRules {

    /** Tempo máximo que uma oferta pendente continua elegível a virar aceita. */
    const val PENDING_OFFER_VALIDITY_MS: Long = 45_000L

    fun isPendingStillValid(pendingTimestamp: Long, now: Long): Boolean =
        (now - pendingTimestamp) in 0..PENDING_OFFER_VALIDITY_MS

    /** Clique no botão Aceitar (real ou disparado pelo DriFly e confirmado pelo sistema). */
    fun canCommitOnAcceptClick(hasPending: Boolean, pendingTimestamp: Long, now: Long): Boolean =
        hasPending && isPendingStillValid(pendingTimestamp, now)

    /**
     * Transição para a tela de corrida ativa.
     * [offerArrivedDuringRide] = a oferta apareceu enquanto o app já mostrava uma corrida em andamento.
     */
    fun canCommitOnActiveRideScreen(
        hasPending: Boolean,
        pendingTimestamp: Long,
        now: Long,
        offerArrivedDuringRide: Boolean
    ): Boolean =
        hasPending && !offerArrivedDuringRide && isPendingStillValid(pendingTimestamp, now)

    /**
     * Corrida consecutiva aceita dentro do app durante a viagem anterior: a única prova aceitável é
     * a tela entrar na FASE DE EMBARQUE da nova corrida. Endereço genérico no HUD da corrida anterior
     * NÃO prova aceite (foi o furo da 1.1.9).
     */
    fun canCommitOnConsecutivePickupTransition(
        hasPending: Boolean,
        pendingTimestamp: Long,
        now: Long,
        offerArrivedDuringRide: Boolean,
        activeRideSeenDropoffPhase: Boolean,
        lastAcceptedTimestamp: Long,
        samePlatform: Boolean,
        isPickupPhase: Boolean
    ): Boolean {
        if (!hasPending || !offerArrivedDuringRide || !activeRideSeenDropoffPhase) return false
        if (!samePlatform || !isPickupPhase) return false
        if ((now - pendingTimestamp) !in 0..120_000L) return false
        if ((now - lastAcceptedTimestamp) < 60_000L) return false
        return true
    }

    enum class PostTapOutcome {
        CONFIRMED,
        FAILED,
        RETRY
    }

    private val POST_TAP_FAILURE_MARKERS = listOf(
        "indisponível",
        "indisponivel",
        "outro motorista",
        "expirou",
        "expirada",
        "já foi aceita",
        "ja foi aceita",
        "não está mais disponível",
        "nao esta mais disponivel",
        "algo deu errado",
        "solicitação cancelada",
        "solicitacao cancelada",
        "oferta encerrada",
        "corrida cancelada"
    )

    private val POST_TAP_LOADING_MARKERS = listOf(
        "carregando",
        "aguarde",
        "processando"
    )

    private val ADDRESS_STOPWORDS = setOf(
        "rua", "avenida", "alameda", "travessa", "estrada", "rodovia", "praca",
        "loteamento", "condominio", "residencial", "jardim", "vila", "parque",
        "bairro", "centro", "zona", "norte", "leste", "oeste", "brasil",
        "joinville", "proximo", "perto", "cidade", "estado", "sao", "santa", "santo"
    )

    /**
     * Classifica a tela capturada logo após tocar/deslizar em "Aceitar".
     * Nunca promove para CONFIRMED se o card continuar na tela, se o app voltou para tela
     * ociosa ("Buscando" / "Conectar") / ganhos, se exibiu aviso de corrida indisponível ou
     * se o OCR não trouxe nenhuma evidência até a última tentativa.
     */
    fun classifyPostTapVerification(
        texts: List<String>?,
        offerPrice: Double,
        isIdleScreen: Boolean,
        isEarningsPanel: Boolean,
        isActiveRide: Boolean,
        isDemandHotspot: Boolean = false,
        stillSameOffer: Boolean = false,
        attempt: Int,
        maxAttempts: Int = 2,
        emptyThreshold: Int = 3
    ): PostTapOutcome {
        if (texts == null || texts.size <= emptyThreshold) {
            return if (attempt < maxAttempts) PostTapOutcome.RETRY else PostTapOutcome.FAILED
        }

        val joined = texts.joinToString(" ").lowercase()

        if (POST_TAP_FAILURE_MARKERS.any { joined.contains(it) }) {
            return PostTapOutcome.FAILED
        }

        if (stillSameOffer) {
            return PostTapOutcome.FAILED
        }

        if (isIdleScreen || isEarningsPanel || isDemandHotspot) {
            return PostTapOutcome.FAILED
        }

        if (texts.any { ParsingEngine.isAcceptActionText(it) }) {
            return PostTapOutcome.FAILED
        }

        if (isActiveRide) {
            return PostTapOutcome.CONFIRMED
        }

        if (texts.size <= 6 && POST_TAP_LOADING_MARKERS.any { joined.contains(it) }) {
            return if (attempt < maxAttempts) PostTapOutcome.RETRY else PostTapOutcome.FAILED
        }

        val hasGpsTripTransition = joined.contains("km/h") ||
            joined.contains("chegada prevista") ||
            joined.contains("em viagem") ||
            joined.contains("ir buscar")

        if (hasGpsTripTransition) {
            return PostTapOutcome.CONFIRMED
        }

        return if (attempt < maxAttempts) PostTapOutcome.RETRY else PostTapOutcome.FAILED
    }

    private fun normalizeText(raw: String): String {
        val decomposed = java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Verifica se o HUD nativo de GPS da 99 (sem texto Flutter de "Aceitar"/"Cheguei") bate com os
     * endereços de embarque/destino da oferta pendente, confirmando transição para corrida ativa
     * desde que não seja navegação livre recente para zona de demanda.
     */
    fun doesGpsHudMatchPendingOffer(
        texts: List<String>,
        pendingOffer: RideOffer?,
        hasRecentDemandNavigation: Boolean
    ): Boolean {
        if (pendingOffer == null || hasRecentDemandNavigation || texts.isEmpty()) return false
        if (ParsingEngine.isIdleDriverScreen(texts) ||
            ParsingEngine.isEarningsPanelScreen(texts) ||
            ParsingEngine.isDemandHotspotScreen(texts)
        ) {
            return false
        }

        val joinedNorm = normalizeText(texts.joinToString(" "))
        if (!joinedNorm.contains("km h") && !joinedNorm.contains("chegada prevista")) {
            return false
        }

        val addresses = listOfNotNull(pendingOffer.pickupAddress, pendingOffer.destinationAddress)
            .filter { it.isNotBlank() }
        if (addresses.isEmpty()) return false

        val hudWords = joinedNorm.split(' ').filter { it.isNotEmpty() }.toSet()

        for (addr in addresses) {
            val tokens = normalizeText(addr)
                .split(' ')
                .filter { it.length >= 3 && it !in ADDRESS_STOPWORDS }
                .distinct()
            if (tokens.isEmpty()) continue

            val matched = tokens.filter { it in hudWords }
            val hasDistinctiveWord = matched.any { token ->
                token.length >= 5 && token.any { it.isLetter() }
            }
            if (hasDistinctiveWord || matched.size >= 2) {
                return true
            }
        }
        return false
    }

    /**
     * Quando uma oferta chega com o motorista ocioso ("Buscando", fora de corrida ativa e fora de
     * navegação livre para zona de demanda) e logo em seguida a tela da 99 muda para o HUD de rota
     * ("km/h" + "Chegada prevista"), confirma que o motorista aceitou a corrida mesmo que o banner
     * superior mostre uma rua intermediária da manobra em vez do endereço final.
     */
    fun isGpsHudTransitionFromFreshOffer(
        texts: List<String>,
        pendingOffer: RideOffer?,
        offerArrivedDuringRide: Boolean,
        offerArrivedDuringFreeGps: Boolean,
        hasRecentDemandNavigation: Boolean
    ): Boolean {
        if (pendingOffer == null || offerArrivedDuringRide || offerArrivedDuringFreeGps || hasRecentDemandNavigation) {
            return false
        }
        if (texts.isEmpty()) return false
        if (ParsingEngine.isIdleDriverScreen(texts) ||
            ParsingEngine.isEarningsPanelScreen(texts) ||
            ParsingEngine.isDemandHotspotScreen(texts) ||
            texts.any { ParsingEngine.isAcceptActionText(it) }
        ) {
            return false
        }
        val joinedNorm = normalizeText(texts.joinToString(" "))
        return joinedNorm.contains("chegada prevista") &&
            (joinedNorm.contains("km h") || joinedNorm.contains(" min "))
    }
}
