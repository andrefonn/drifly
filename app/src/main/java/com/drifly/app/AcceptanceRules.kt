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
}
