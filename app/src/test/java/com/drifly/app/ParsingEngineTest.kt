package com.drifly.app

import org.junit.Assert.*
import org.junit.Test

class ParsingEngineTest {

    @Test
    fun testParseValidUberOffer() {
        val texts = listOf(
            "UberX",
            "R$ 32,50",
            "8,4 km",
            "19 min",
            "★ 4.88"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = texts,
            costPerKm = 0.78,
            minRatingThreshold = 4.75f
        )

        assertNotNull("Offer should not be null", offer)
        assertEquals("UBER", offer!!.platform)
        assertEquals(32.50, offer.price, 0.001)
        assertEquals(8.4, offer.distanceKm, 0.001)
        assertEquals(19, offer.durationMinutes)
        assertEquals(3.87, offer.ratePerKm, 0.01)
        assertEquals(6.55, offer.estimatedFuelCost, 0.01)
        assertEquals(25.95, offer.netProfit, 0.01)
        assertEquals(4.88f, offer.passengerRating ?: 0f, 0.01f)
        assertFalse(offer.isLowRating)
        assertEquals(102.63, offer.ratePerHour, 0.01)
    }

    @Test
    fun testParseLowRatingTriggersAlert() {
        val texts = listOf(
            "99Pop",
            "R$ 20,00",
            "5.0 km",
            "12 min",
            "4.60"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.80,
            minRatingThreshold = 4.75f
        )

        assertNotNull(offer)
        assertEquals("99", offer!!.platform)
        assertEquals(4.60f, offer.passengerRating ?: 0f, 0.01f)
        assertTrue("Rating 4.60 should be marked as low rating when threshold is 4.75", offer.isLowRating)
    }

    @Test
    fun testParseZeroOrNegativeDistanceReturnsNull() {
        val texts = listOf("R$ 15,00", "0 km")
        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = texts,
            costPerKm = 0.80,
            minRatingThreshold = 4.70f
        )
        assertNull("Zero distance should return null offer", offer)
    }

    @Test
    fun testParseEmptyTextsReturnsNull() {
        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = emptyList(),
            costPerKm = 0.80,
            minRatingThreshold = 4.70f
        )
        assertNull("Empty list should return null offer", offer)
    }

    @Test
    fun testParseRealUberMotoCardComTotalETrechos() {
        // Texto REAL da tela da Uber Motorista (21:15 de 06/11/2026)
        val texts = listOf(
            "Moto",
            "Exclusivo",
            "R$5,73",
            "12min • 4.1km • Est. de R$1,40/km",
            "5,00 (17)",
            "6 min (1.8km)",
            "R. Adolfo Soares, Itinga, Joinville",
            "6 minutos (2.3km)",
            "Rua Estados Unidos, 75, Araquari, Araquari",
            "Aceitar"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = texts,
            costPerKm = 0.2148,
            minRatingThreshold = 4.75f
        )

        assertNotNull("Card real da Uber deve gerar oferta", offer)
        assertEquals("UBER", offer!!.platform)
        assertEquals(5.73, offer.price, 0.001)
        assertEquals(4.1, offer.distanceKm, 0.01)
        assertEquals(12, offer.durationMinutes)
        assertEquals(1.40, offer.ratePerKm, 0.01)
        // A fachada deve apontar para o DESTINO (último endereço do card), não para o embarque
        assertEquals("Rua Estados Unidos, 75, Araquari, Araquari", offer.destinationAddress)
    }

    @Test
    fun testParseTrechosComMinutosSemTotalExplicito() {
        // "minutos" por extenso também precisa contar como trecho (a Uber usa os dois formatos)
        val texts = listOf(
            "UberX",
            "R$ 20,00",
            "6 min (1.8km)",
            "6 minutos (2.3km)",
            "Aceitar"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = texts,
            costPerKm = 0.2148,
            minRatingThreshold = 4.75f
        )
        assertNotNull(offer)
        assertEquals(4.1, offer!!.distanceKm, 0.01)
        assertEquals(12, offer.durationMinutes)
    }

    @Test
    fun testBannerPromocionalNaoViraCorrida() {
        // Texto REAL lido por OCR do banner "Você ganhou R$38,2 com o pacote 0% de."
        // A barra de status ("19:33 M") era interpretada como "33 metros" e gerava uma oferta falsa.
        val texts = listOf(
            "19:33 M 99",
            "Taxa",
            "% 0% de Taxa 13:23:30",
            "Ver",
            "ITÌNGA",
            "Você ganhou R$38,2 com o pacote 0% de",
            "9A060%",
            "Conectar",
            "%",
            "A"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.2148,
            minRatingThreshold = 4.75f
        )
        assertNull("Banner promocional não pode virar oferta de corrida", offer)
    }

    @Test
    fun testRelogioDaBarraDeStatusNaoViraDistancia() {
        val texts = listOf("19:42 M 99", "R$ 12,50", "Taxa", "Conectar")
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.2148,
            minRatingThreshold = 4.75f
        )
        assertNull("Horário da barra de status não pode ser lido como metros", offer)
    }

    @Test
    fun testDistanciaIrrisoriaNaoViraOferta() {
        val texts = listOf("R$ 9,00", "0,03 km", "Aceitar")
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.2148,
            minRatingThreshold = 4.75f
        )
        assertNull("Distância residual de 30 metros não pode virar oferta", offer)
    }

    @Test
    fun testParse99MotoRealCardWithTwoLegsInMinutesFormat() {
        // Texto REAL capturado por OCR da tela da 99 (card 99Moto, 06/11/2026 19:02)
        // Formato dos trechos: "7 min (1,8 km)" = embarque e "2 min (1 km)" = viagem, sem rótulo textual.
        val texts = listOf(
            "Moto",
            "R$5,10",
            "0% de Taxa99 semanal",
            "R$1,38 Tarifa base dinâmica incl.",
            "4,91- 581 corridas",
            "7 min (1,8 km)",
            "ponto final do ônibus Itinga, Itinga",
            "2 min (1 km)",
            "CPF e Cartão verif.",
            "Itinga",
            "Carine cílios e sobrancelhas, Rua São João, 62 -",
            "Aceitar"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.2148,
            minRatingThreshold = 4.75f
        )

        assertNotNull("Card real da 99Moto deve gerar oferta", offer)
        assertEquals("99", offer!!.platform)
        assertEquals(5.10, offer.price, 0.001)
        // Embarque (1,8 km) + viagem (1,0 km) = 2,8 km percorridos no total
        assertEquals(2.8, offer.distanceKm, 0.01)
        // 7 min de embarque + 2 min de viagem = 9 min
        assertEquals(9, offer.durationMinutes)
        // R$/km real = 5,10 / 2,8 = 1,82 (e não 2,83 usando só o embarque)
        assertEquals(1.82, offer.ratePerKm, 0.01)
    }

    @Test
    fun testParse99OfferWithSplitCurrencyAndNumber() {
        // Simula a árvore de nós real da 99 onde R$, valor, número e km são TextViews separados
        val texts = listOf(
            "99Pop",
            "R$",
            "18,90",
            "5,2",
            "km",
            "14",
            "min",
            "4.82"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.80,
            minRatingThreshold = 4.75f
        )
        assertNotNull("Offer with split nodes must be parsed successfully", offer)
        assertEquals("99", offer!!.platform)
        assertEquals(18.90, offer.price, 0.001)
        assertEquals(5.2, offer.distanceKm, 0.001)
        assertEquals(14, offer.durationMinutes)
        assertEquals(4.82f, offer.passengerRating ?: 0f, 0.01f)
    }

    @Test
    fun testParse99OfferWithComTaxis99Package() {
        val texts = listOf("99Moto", "R$ 12,50", "3,5 km", "8 min")
        val offer = ParsingEngine.parseNodes(
            packageName = "com.taxis99",
            texts = texts,
            costPerKm = 0.50,
            minRatingThreshold = 4.75f
        )
        assertNotNull(offer)
        assertEquals("99", offer!!.platform)
        assertEquals(12.50, offer.price, 0.001)
        assertEquals(3.5, offer.distanceKm, 0.001)
    }

    @Test
    fun testParsePriceWithDotDecimal() {
        val texts = listOf("99Pop", "R$ 15.50", "4.0 km", "10 min")
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.80,
            minRatingThreshold = 4.75f
        )
        assertNotNull(offer)
        assertEquals(15.50, offer!!.price, 0.001) // Não deve virar 1550.0!
    }

    @Test
    fun testParseDistanceInMeters() {
        val texts = listOf("99Entrega", "R$ 8,00", "800 m", "5 min")
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.80,
            minRatingThreshold = 4.75f
        )
        assertNotNull(offer)
        assertEquals(8.00, offer!!.price, 0.001)
        assertEquals(0.8, offer.distanceKm, 0.001) // 800 m = 0.8 km
    }

    @Test
    fun testParse99OfferWithPickupAndTripDistances() {
        // Na 99 aparecem a distância de embarque e a de viagem
        val texts = listOf(
            "99Pop",
            "R$ 22,00",
            "1,2 km até o passageiro",
            "5,8 km de viagem",
            "20 min"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.80,
            minRatingThreshold = 4.75f
        )
        assertNotNull(offer)
        assertEquals(22.00, offer!!.price, 0.001)
        // A distância total considerada para o custo real deve ser a soma (1.2 + 5.8 = 7.0) ou pelo menos a distância da viagem se discriminada
        assertEquals(7.0, offer.distanceKm, 0.01)
    }

    @Test
    fun testParse99NotificationFormat() {
        val texts = listOf(
            "99 Motorista",
            "Nova corrida: 99Pop - R$ 19,50 • 6,2 km • 15 min",
            "Toque para ver os detalhes da corrida"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = texts,
            costPerKm = 0.38,
            minRatingThreshold = 4.75f
        )
        assertNotNull("Offer from notification string must be parsed", offer)
        assertEquals("99", offer!!.platform)
        assertEquals(19.50, offer.price, 0.001)
        assertEquals(6.2, offer.distanceKm, 0.001)
        assertEquals(15, offer.durationMinutes)
        assertEquals(3.15, offer.ratePerKm, 0.01)
    }

    @Test
    fun testParseUberNotificationFormat() {
        val texts = listOf(
            "Uber Driver",
            "UberX: R$ 28,90 - 7.5 km (18 min)"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = texts,
            costPerKm = 0.50,
            minRatingThreshold = 4.75f
        )
        assertNotNull(offer)
        assertEquals("UBER", offer!!.platform)
        assertEquals(28.90, offer.price, 0.001)
        assertEquals(7.5, offer.distanceKm, 0.001)
        assertEquals(18, offer.durationMinutes)
    }

    @Test
    fun testParseOfferWithDestinationAddress() {
        val texts = listOf(
            "UberX",
            "R$ 25,00",
            "6,0 km",
            "15 min",
            "Desembarque em:",
            "Av. Paulista, 1578 - Bela Vista"
        )
        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = texts,
            costPerKm = 0.80,
            minRatingThreshold = 4.75f
        )
        assertNotNull(offer)
        assertEquals("Av. Paulista, 1578 - Bela Vista", offer!!.destinationAddress)
        assertEquals(100.0, offer.ratePerHour, 0.01)
    }

    @Test
    fun testRejectSelfOverlayOrRatePerKmAsGrossPrice() {
        // Reproduz o bug exato do vídeo do usuário:
        // Quando a corrida de R$ 5,10 (3,2 km -> R$ 1,59/km) some após recusar,
        // ou o texto R$ 1,59 / km aparece na tela ou no próprio overlay DriFly,
        // o parser NÃO PODE achar que R$ 1,59 é uma nova corrida bruta de R$ 1,59!
        val textsWithRateAndDistance = listOf(
            "Recusar ❌",
            "UBER",
            "3,2 km",
            "7 min",
            "R$ / KM",
            "R$ 1,59",
            "R$ / HORA",
            "R$ 44",
            "★ NOTA",
            "4,94",
            "LUCRO REAL",
            "R$ 4,40"
        )
        val offerFromSelfOverlay = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = textsWithRateAndDistance,
            costPerKm = 0.22,
            minRatingThreshold = 4.75f
        )
        assertNull("Self-overlay texts or sub-minimum prices (R$ 1,59) must NEVER be parsed as a ride offer", offerFromSelfOverlay)

        // Mesmo que venha só "R$ 1,59 (por km)" e "3,2 km" da tela da Uber ao fechar o card:
        val textsResidualUber = listOf(
            "R$ 1,59",
            "(por km)",
            "3,2 km",
            "7 min",
            "4.94",
            "Rua das Flores, 123"
        )
        val residualOffer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = textsResidualUber,
            costPerKm = 0.22,
            minRatingThreshold = 4.75f
        )
        assertNull("Price R$ 1,59 below minimum ride fare (R$ 4.00) must be rejected", residualOffer)
    }

    @Test
    fun testRejectActiveRideAndNavigationScreens() {
        // Reproduz o bug onde o motorista ACEITA a corrida e durante a viagem/navegação
        // ficam aparecendo inúmeros overlays zerados ou negativos (ex: R$ 0,00 no painel,
        // nota 5.00 do passageiro e distância de navegação 200 m / 3,2 km).
        val activeNavigationTexts = listOf(
            "Vire à direita na Av. Brasil",
            "300 m",
            "3,2 km",
            "8 min",
            "R$ 0,00",
            "5.00",
            "Iniciar viagem",
            "Deslize para finalizar"
        )
        val navOffer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = activeNavigationTexts,
            costPerKm = 0.30,
            minRatingThreshold = 4.75f
        )
        assertNull("Active navigation screen must NEVER trigger an offer overlay", navOffer)

        // Mesmo que na tela de navegação apareça algum saldo/promoção > R$ 4,00, se tiver indicadores de corrida em andamento, deve rejeitar:
        val ongoingRideWithBalanceTexts = listOf(
            "Em viagem com João",
            "5.00",
            "R$ 12,50",
            "2,4 km",
            "6 min",
            "Deslize para concluir"
        )
        val ongoingOffer = ParsingEngine.parseNodes(
            packageName = "com.app99.driver",
            texts = ongoingRideWithBalanceTexts,
            costPerKm = 0.30,
            minRatingThreshold = 4.75f
        )
        assertNull("Screen with active trip indicators ('Em viagem', 'Deslize para concluir') must return null", ongoingOffer)
    }

    @Test
    fun testDetectAcceptAndDeclineActions() {
        assertTrue(ParsingEngine.isAcceptActionText("Aceitar"))
        assertTrue(ParsingEngine.isAcceptActionText("Toque para aceitar"))
        assertTrue(ParsingEngine.isAcceptActionText("Corresponder"))
        assertFalse(ParsingEngine.isAcceptActionText("Recusar"))

        assertTrue(ParsingEngine.isDeclineActionText("Recusar"))
        assertTrue(ParsingEngine.isDeclineActionText("Rejeitar"))
        assertTrue(ParsingEngine.isDeclineActionText("X"))
        assertFalse(ParsingEngine.isDeclineActionText("Aceitar"))
    }

    @Test
    fun testParseBrDecimalInputWithCommaAndDot() {
        // Reproduz o bug onde o usuário digita "1,90" ou "1.90" nas configurações de Corridas
        assertEquals(1.90f, ParsingEngine.parseFlexibleFloat("1,90", 0f), 0.001f)
        assertEquals(1.90f, ParsingEngine.parseFlexibleFloat("1.90", 0f), 0.001f)
        assertEquals(1.80f, ParsingEngine.parseFlexibleFloat("1,80", 0f), 0.001f)
        assertEquals(150.0f, ParsingEngine.parseFlexibleFloat("150,00", 0f), 0.001f)
        assertEquals(1250.50f, ParsingEngine.parseFlexibleFloat("1.250,50", 0f), 0.001f)
    }
}
