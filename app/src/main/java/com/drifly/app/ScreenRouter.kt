package com.drifly.app

/**
 * Resultado da leitura de uma tela monitorada (Uber/99), já classificado.
 *
 * A ordem de classificação é crítica: uma tela de conclusão de corrida
 * ("Você ganhou R$ 28,50" + "Distância: 7,2 km") TAMBÉM casa com o parser de
 * oferta. Por isso recibo/cancelamento precisa ser testado antes de oferta,
 * senão o recibo vira uma "nova corrida": o overlay reaparece na conclusão e a
 * corrida real nunca é marcada como CONCLUIDO.
 */
sealed class ScreenReading {
    data class Receipt(val receipt: ReceiptResult) : ScreenReading()
    data class Offer(val offer: RideOffer) : ScreenReading()
    object ActiveRide : ScreenReading()
    object Unknown : ScreenReading()
}

object ScreenRouter {

    /**
     * Classifica os textos da tela atual.
     *
     * @param isRideCurrentlyActive indica que o motorista está em uma corrida em andamento.
     *   Recibos de CONCLUSÃO só são aceitos nesse estado; caso contrário um banner
     *   promocional ("Você ganhou R$38,2 com o pacote...") finalizaria uma corrida aleatória.
     *   Cancelamentos são aceitos sempre (não têm esse risco).
     */
    fun read(
        packageName: String,
        texts: List<String>,
        costPerKm: Double,
        minRatingThreshold: Float,
        isRideCurrentlyActive: Boolean
    ): ScreenReading {
        if (texts.isEmpty()) return ScreenReading.Unknown

        // 1. Recibo/cancelamento tem PRIORIDADE sobre oferta.
        val receipt = ParsingEngine.parseReceipt(packageName, texts)
        if (receipt != null) {
            if (receipt.isCancelled || isRideCurrentlyActive) {
                return ScreenReading.Receipt(receipt)
            }
            // Conclusão sem corrida ativa: banner promocional ou estado residual.
            // Não pode virar oferta nem finalizar corrida.
            return ScreenReading.Unknown
        }

        // 2. Nova oferta (inclusive com estado residual de corrida ativa).
        val offer = ParsingEngine.parseNodes(packageName, texts, costPerKm, minRatingThreshold)
        if (offer != null) return ScreenReading.Offer(offer)

        // 3. Tela de corrida ativa / navegação.
        if (ParsingEngine.isActiveRideOrNavigationScreen(texts)) {
            return ScreenReading.ActiveRide
        }

        return ScreenReading.Unknown
    }
}
