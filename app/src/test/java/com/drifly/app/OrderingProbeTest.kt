package com.drifly.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Regressão da ORDEM de classificação de tela.
 *
 * Contexto: a correção do "overlay só aparecia na primeira oferta" passou a testar
 * oferta antes de recibo. Só que uma tela de conclusão ("Você ganhou R$ 28,50" +
 * "Distância: 7,2 km") também casa com o parser de oferta. Resultado: o recibo virava
 * uma "nova corrida", o overlay reaparecia na conclusão e a corrida real nunca era
 * marcada como CONCLUIDO.
 *
 * Estes testes travam a ordem correta e garantem que o estado residual de corrida
 * ativa não volte a bloquear ofertas seguintes.
 */
class OrderingProbeTest {

    private val costPerKm = 0.2148
    private val minRating = 4.75f

    private fun read(
        packageName: String,
        texts: List<String>,
        active: Boolean
    ) = ScreenRouter.read(packageName, texts, costPerKm, minRating, active)

    @Test
    fun completionReceiptWithActiveRideIsReceiptNeverOffer() {
        val texts = listOf(
            "Viagem finalizada com sucesso",
            "Você ganhou",
            "R$ 28,50",
            "Distância: 7,2 km"
        )

        // Confirma o conflito: o parser de oferta aceitaria essa tela.
        assertNotNull(ParsingEngine.parseNodes("com.ubercab.driver", texts, costPerKm, minRating))

        val reading = read("com.ubercab.driver", texts, active = true)
        assertTrue("Recibo deve ter prioridade sobre oferta", reading is ScreenReading.Receipt)
        assertEquals(28.50, (reading as ScreenReading.Receipt).receipt.finalPrice ?: 0.0, 0.001)
    }

    @Test
    fun completionBannerWithoutActiveRideIsIgnored() {
        // Banner promocional real da 99 que contém "Você ganhou R$..."
        val texts = listOf(
            "19:33 M 99",
            "Taxa",
            "% 0% de Taxa 13:23:30",
            "Você ganhou R$38,2 com o pacote 0% de",
            "Conectar"
        )

        val reading = read("com.app99.driver", texts, active = false)
        assertEquals(
            "Banner promocional não pode virar recibo nem oferta",
            ScreenReading.Unknown,
            reading
        )
    }

    @Test
    fun newOfferIsStillDetectedEvenWithResidualActiveRideState() {
        val texts = listOf(
            "R$ 18,90",
            "7 min (2,4 km)",
            "9 min (3,5 km)",
            "Aceitar"
        )

        val reading = read("com.ubercab.driver", texts, active = true)
        assertTrue(
            "Estado residual de corrida ativa não pode bloquear a oferta seguinte",
            reading is ScreenReading.Offer
        )
    }

    @Test
    fun cancellationReceiptIsHandledEvenWithoutActiveRide() {
        val texts = listOf(
            "Corrida cancelada pelo passageiro",
            "Taxa de cancelamento: R$ 3,00"
        )

        val reading = read("com.app99.driver", texts, active = false)
        assertTrue(reading is ScreenReading.Receipt)
        assertTrue((reading as ScreenReading.Receipt).receipt.isCancelled)
    }

    @Test
    fun navigationScreenIsActiveRide() {
        val texts = listOf("Em viagem com João", "Deslize para finalizar")
        val reading = read("com.ubercab.driver", texts, active = true)
        assertEquals(ScreenReading.ActiveRide, reading)
    }

    @Test
    fun sameOfferScreenIsIdentifiedRepeatedly() {
        val texts = listOf("R$ 22,40", "8 min (4,1 km)", "12 min (6,0 km)", "Aceitar")
        repeat(3) {
            val reading = read("com.ubercab.driver", texts, active = false)
            assertTrue("Leitura deve ser estável entre chamadas", reading is ScreenReading.Offer)
        }
    }

    @Test
    fun uberPickupAndDropoffMapScreensRouteToActiveRideNotOffer() {
        val pickupTexts = listOf(
            "Página inicial", "R$ 16,12", "Pesquisar locais", "60", "LIMITE",
            "Recursos de segurança", "Rua Expedicionário Holz", "0.7 km", "Rua Conselheiro Arp",
            "Está tudo pronto para você ficar online", "Preferências", "3 min", "1,4 km",
            "Encontro com Yondri", "Recomendado para você", "Conteúdo", "Registro de viagens"
        )
        assertEquals(ScreenReading.ActiveRide, read("com.ubercab.driver", pickupTexts, active = true))

        val idleWithBusyArea = listOf(
            "Página inicial", "R$ 22,13", "Pesquisar locais", "80", "LIMITE",
            "Recursos de segurança", "Radar de Viagens", "350 m", "Rua Dona Francisca",
            "Está tudo pronto para você ficar online", "Preferências", "13 min", "6,6 km",
            "Procurando viagens", "Recomendado para você", "Conteúdo",
            "A caminho de uma região movimentada", "Disponível para todas as viagens"
        )
        assertEquals(ScreenReading.Unknown, read("com.ubercab.driver", idleWithBusyArea, active = false))
    }
}
