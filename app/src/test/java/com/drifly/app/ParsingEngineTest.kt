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

    @Test
    fun testUberActivePickupAndDropoffScreensNeverTriggerFalseOverlays() {
        // Capturas reais do drifly_diag.log.old após aceitar corrida (Yondri e Denise)
        val pickupYondri = listOf(
            "Página inicial", "R$ 16,12", "Pesquisar locais", "60", "LIMITE",
            "Recursos de segurança", "Rua Expedicionário Holz", "0.7 km", "Rua Conselheiro Arp",
            "Está tudo pronto para você ficar online", "Preferências", "3 min", "1,4 km",
            "Encontro com Yondri", "Recomendado para você", "Conteúdo", "Registro de viagens"
        )
        val dropoffYondri = listOf(
            "Página inicial", "R$ 16,12", "Pesquisar locais", "30", "LIMITE",
            "Recursos de segurança", "Rua Vinte e Cinco de Julho", "150 m", "Rua Orestes Guimarães",
            "Está tudo pronto para você ficar online", "Preferências", "2 min", "0,4 km",
            "Destino de Yondri", "Recomendado para você", "Conteúdo", "Registro de viagens"
        )
        val pickupDeniseDrawer = listOf(
            "Está tudo pronto para você ficar online", "3 min", "1,6 km", "Agenda de viagens",
            "Conteúdo", "Registro de viagens", "Preferências", "Interromper novas solicitações",
            "Pesquisar", "Página inicial", "R$ 22,13", "Pesquisar locais", "30", "LIMITE",
            "Recursos de segurança", "Rua dos Ginásticos", "250 m", "Rua Doutor João Colin"
        )
        val pickupDeniseBanner = listOf(
            "Página inicial", "R$ 22,13", "HOJE", "Pesquisar locais", "30", "LIMITE",
            "Recursos de segurança", "Rua dos Ginásticos", "Embarque em Rua Germano Stein",
            "Perto de Drogaria Catarinense, R. Germano Stein, 249 - America, Joinville - SC, 89204-090",
            "Está tudo pronto para você ficar online", "Preferências", "3 min", "1,5 km",
            "Encontro com Denise", "Agenda de viagens", "Conteúdo", "Registro de viagens"
        )

        for (screen in listOf(pickupYondri, dropoffYondri, pickupDeniseDrawer, pickupDeniseBanner)) {
            assertTrue(
                "Tela de corrida ativa da Uber deve ser reconhecida como ativa: ${screen.joinToString(" | ")}",
                ParsingEngine.isActiveRideOrNavigationScreen(screen)
            )
            assertNull(
                "Tela de corrida ativa da Uber NUNCA deve gerar oferta falsa com o saldo do topo",
                ParsingEngine.parseNodes("com.ubercab.driver", screen, 0.2148, 4.75f)
            )
        }
    }

    @Test
    fun testUberOfferCardOverMapHeaderIgnoresDailyEarningsPill() {
        // Captura real (10:55:44.350) onde o card "Moto | R$ 9,02" aparece com o topo do mapa "Página inicial | R$ 22,13 | HOJE" ainda na árvore
        val texts = listOf(
            "Página inicial", "R$ 22,13", "HOJE", "Pesquisar locais", "Recursos de segurança",
            "Está tudo pronto para você ficar online", "Preferências", "Você está online",
            "Recomendado para você", "Conteúdo", "O passe de 24 horas está ativo",
            "Expira em 11 de nov., 09:26", "Hoje, mais tarde", "Veja as próximas promoções",
            "Ver tempo ao volante", "Registro de viagens", "1-8 min",
            "Moto", "R$ 9,02", "16 min • 6.7 km • Est. de R$1,35/km", "4,97 (500+)",
            "Verificado", "4 min (1.6 km)", "Rua Orestes Guimarães, 728, América, Joinville",
            "12 minutos (5.1 km)", "Rua Xanxerê, 23, Saguaçu, Joinville", "Aceitar"
        )

        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = texts,
            costPerKm = 0.2148,
            minRatingThreshold = 4.75f
        )

        assertNotNull("Deve extrair a oferta real mesmo com o cabeçalho do mapa visível", offer)
        assertEquals(9.02, offer!!.price, 0.001)
        assertEquals(6.7, offer.distanceKm, 0.01)
        assertEquals(16, offer.durationMinutes)
    }

    @Test
    fun testUberIdleScreenWithBusyAreaGuidanceIsNeitherActiveRideNorOffer() {
        // Captura real (11:05:57.768): motorista online procurando viagens com "A caminho de uma região movimentada"
        val idleBusyAreaTexts = listOf(
            "Página inicial", "R$ 22,13", "Pesquisar locais", "80", "LIMITE",
            "Recursos de segurança", "Radar de Viagens", "350 m", "Rua Dona Francisca",
            "Está tudo pronto para você ficar online", "Preferências", "13 min", "6,6 km",
            "Procurando viagens", "Recomendado para você", "Conteúdo",
            "O passe de 24 horas está ativo", "Expira em 11 de nov., 09:26",
            "A caminho de uma região movimentada", "Disponível para todas as viagens"
        )

        assertFalse(
            "Tela ociosa 'Procurando viagens' com 'A caminho de uma região movimentada' NÃO é corrida ativa",
            ParsingEngine.isActiveRideOrNavigationScreen(idleBusyAreaTexts)
        )
        assertNull(
            "Tela ociosa do mapa com saldo R$ 22,13 NÃO pode virar oferta de corrida",
            ParsingEngine.parseNodes("com.ubercab.driver", idleBusyAreaTexts, 0.2148, 4.75f)
        )
    }

    @Test
    fun test99ActivePickupAndInTripOcrScreensNeverTriggerFalseOverlays() {
        // Simula o OCR periódico (a cada 5s) na 99 após aceitar uma corrida:
        // O motorista está indo buscar o passageiro ou em viagem, sem "Vire à direita" no momento,
        // mas com botões/textos da corrida ativa da 99 e valor/distância visíveis no mapa.
        val pickup99Ocr = listOf(
            "09:42", "R$ 14,80", "350 m", "Rua XV de Novembro",
            "4 min • 1,3 km", "Ponto de encontro", "Cheguei", "Ligar", "Cancelar corrida"
        )
        val inTrip99Ocr = listOf(
            "09:48", "R$ 14,80", "1,8 km", "Av. Getúlio Vargas",
            "Passageiro a bordo", "A caminho do destino", "Segurança"
        )

        for (screen in listOf(pickup99Ocr, inTrip99Ocr)) {
            assertTrue(
                "Tela de corrida ativa da 99 lida via OCR deve ser reconhecida como ativa: ${screen.joinToString(" | ")}",
                ParsingEngine.isActiveRideOrNavigationScreen(screen)
            )
            assertNull(
                "Tela de corrida ativa da 99 NUNCA deve disparar novo overlay durante o trajeto",
                ParsingEngine.parseNodes("com.taxis99", screen, 0.2148, 4.75f)
            )
        }
    }

    @Test
    fun testOcrMapScreenOfflineOrIdleWithBalanceAndDistanceNeverTriggersOffer() {
        // No OCR da tela de mapa (99 ou Uber), o ícone "Página inicial" não aparece como texto,
        // mas o saldo "R$ 22,13" e alguma distância do mapa ("1,2 km") podem aparecer junto com "Conectar" / "Você está offline" / "Você está online".
        val uberOfflineOcrWithDistance = listOf(
            "11:16", "Joinville", "R$ 22,13", "Estádio Arena", "1,2 km",
            "COMEÇAR", "Agenda de viagens", "Você está offline"
        )
        val app99IdleOcrWithDistance = listOf(
            "11:20", "Taxa", "0% de Taxa", "R$ 34,50", "Zona de alta demanda a 1,4 km",
            "Conectar", "Preferências"
        )

        assertFalse(
            "Tela offline com 'Agenda de viagens' NÃO pode ser marcada como corrida ativa",
            ParsingEngine.isActiveRideOrNavigationScreen(uberOfflineOcrWithDistance)
        )
        assertNull(
            "OCR do mapa offline da Uber com R$ 22,13 e distância não pode virar oferta",
            ParsingEngine.parseNodes("com.ubercab.driver", uberOfflineOcrWithDistance, 0.2148, 4.75f)
        )
        assertNull(
            "OCR do mapa ocioso da 99 com saldo R$ 34,50 e distância não pode virar oferta",
            ParsingEngine.parseNodes("com.taxis99", app99IdleOcrWithDistance, 0.2148, 4.75f)
        )
    }

    @Test
    fun testPainelDeGanhos99NaoViraOferta() {
        val texts = listOf("R$45,93", "6 Solicitações", "Último R$5,10", "Recompensas", "Ganhos desta semana R$155,79", "3,1 km")
        val offer = ParsingEngine.parseNodes("com.app99.driver", texts, 0.78, 4.75f)
        assertNull("Painel/pílula de ganhos não é oferta", offer)
    }

    @Test
    fun testDriflyMainActivityOcrNeverTriggersSelfCaptureOffer() {
        // Captura REAL do diag.log (18:53:49.792) quando o usuário abriu o DriFly sobre a 99:
        // antes, gerava oferta falsa de R$ 150,00 / 236,5 km com destino "r Meta".
        val driflyMainTexts = listOf(
            "18:53 M 99",
            "DriFly",
            "Painel de Resultados",
            "PROGRESSO DASUA META",
            "Meta: R$ 150,00 (Atingido: R$ 368,1| - 100%)",
            "G FATURAMENTO",
            "R$ 377,40",
            "+ Lançar Despesa",
            "G Histórico de Corridas",
            "Diária",
            "w Ajustar Meta de Ganhos",
            "Valor Alvo (R$):",
            "150,00",
            "R$ O,00",
            "DESPESAS",
            "Semanal",
            "Resultados",
            "Mensal",
            "C Atualizar Meta",
            "MONITORAMENTO ATIVO",
            "Turno: 236,5 km rodados",
            "9A68%",
            "Corridas",
            "LUCRo LÍQ"
        )

        assertTrue(
            "Tela principal do DriFly precisa ser reconhecida como auto-leitura",
            ParsingEngine.containsSelfOverlayMarkers(driflyMainTexts)
        )
        assertNull(
            "Palavra 'Ajustar Meta' / 'Atualizar Meta' NÃO pode casar REGEX_ENDERECO como destino 'r Meta'",
            ParsingEngine.extractDestinationAddress(driflyMainTexts)
        )
        assertNull(
            "Tela do próprio DriFly NUNCA pode gerar oferta falsa",
            ParsingEngine.parseNodes("com.app99.driver", driflyMainTexts, 0.2148, 4.75f)
        )
        assertEquals(
            "ScreenRouter deve retornar Unknown para a própria tela do DriFly",
            ScreenReading.Unknown,
            ScreenRouter.read("com.app99.driver", driflyMainTexts, 0.2148, 4.75f, isRideCurrentlyActive = true)
        )
    }

    @Test
    fun testFadingSelfOverlayMixedWith99OfferCardIsRejected() {
        // Captura REAL do diag.log (18:11:08.359): OCR leu restos do overlay DriFly ("RSTKVM", "TRSTHORA", "R$ 33")
        // junto com a oferta da 99 (R$ 21,40) e sobrescreveu a corrida #95 para R$ 33,00.
        val fadingOverlayTexts = listOf(
            "18:11",
            "O 99",
            "202 m",
            "Moto",
            "R. Antônio Ramos Alvim",
            "RSTKVM",
            "oo RS 16 63",
            "Vire à esquerda R$ 33",
            "Rua Florianópoll, 2710 Guanab",
            "TRSTHORA",
            "R$21,40",
            "0% de Taxa99 semanal",
            "BR-280",
            "9 R$1,25 Tarifa base dinâmica incl.",
            "7 min (2,3 km)",
            "t 5,00 56 corridas CPF verif.",
            "Rua Joāo Luiz Filho, 1500, Centro",
            "32 min (19,9 km)",
            "Aceitar",
            "Rua Florianópolis, 2710, Guanabara",
            "♡ e75atarina",
            "NOTA",
            "5,"
        )

        assertNull(
            "Captura contaminada pelo próprio overlay do DriFly deve ser descartada",
            ParsingEngine.parseNodes("com.app99.driver", fadingOverlayTexts, 0.2148, 4.75f)
        )
    }

    @Test
    fun test99IdleBuscandoAndPromoBannerNeverBecomesReceiptOrOffer() {
        // Capturas REAIS do diag.log (18:12:55.291 e 18:53:54.618):
        // após finalizar a corrida #95, a 99 voltou para "Buscando" com banner "Você ganhou R$78,95 com o pacote 0% de Taxa",
        // e o recibo sobrescreveu a corrida #95 com R$ 78,95!
        val buscandoPromo = listOf(
            "18:12", ")", "ámirim", "Rio Parati", "99", "Ver",
            "% 0% de Taxa 14:49:46",
            "Você ganhou R$78,95 com o pacote 0%",
            "de Taxa", "CENTRO", "GANHERS500", "Buscando", "%"
        )
        val buscandoPromoWithKm = listOf(
            "18:53", "SC-301", "M 99", "Ver",
            "% 0% de Taxa 14:08:50",
            "Você ganhou R$108,95 com o pacote 0%",
            "de Taxa", "GANHERS500", "2,4 km", "Buscando"
        )

        assertTrue(
            "Tela 'Buscando' da 99 precisa ser reconhecida como ociosa",
            ParsingEngine.isIdleDriverScreen(buscandoPromo)
        )
        assertNull(
            "Banner 'Você ganhou R$... com o pacote 0% de Taxa' NÃO pode virar recibo de corrida",
            ParsingEngine.parseReceipt("com.app99.driver", buscandoPromo)
        )
        assertEquals(
            ScreenReading.Unknown,
            ScreenRouter.read("com.app99.driver", buscandoPromo, 0.2148, 4.75f, isRideCurrentlyActive = true)
        )
        assertNull(
            "Tela ociosa 'Buscando' com banner promocional e km no mapa NÃO pode virar oferta",
            ParsingEngine.parseNodes("com.app99.driver", buscandoPromoWithKm, 0.2148, 4.75f)
        )
    }

    @Test
    fun test99TopEarningsPillAndPromoOverOfferCardNeverStealRidePrice() {
        // Quando a pílula de ganhos acumulados no topo da 99 (ou banner promocional) aparece junto com o card da corrida,
        // o parser NUNCA pode usar o valor acumulado do topo em vez do valor real da corrida abaixo.
        val offerWithPromoAndTopPill = listOf(
            "18:13 O",
            "R$108,95",
            "Moto",
            "9 99",
            "Você ganhou R$78,95 com o pacote 0%",
            "de Taxa",
            "Moto",
            "R$7,30",
            "0% de Taxa99 semanal",
            "R$1,25 Tarifa base dinâmica incl.",
            "t 5,00 12 corridas",
            "4 min (1,4 km)",
            "Mundo do Caiaque, BR-280",
            "8 min (4,3 km)",
            "Rua Rosa Amélia Pereira Braga, 492, Areias Pequenas",
            "Aceitar"
        )
        val offer1 = ParsingEngine.parseNodes("com.app99.driver", offerWithPromoAndTopPill, 0.2148, 4.75f)
        assertNotNull("Oferta real abaixo da pílula do topo deve ser extraída", offer1)
        assertEquals(7.30, offer1!!.price, 0.001)

        val offerWithTopClockPill = listOf(
            "18:13",
            "R$ 108,95",
            "Moto",
            "99",
            "R$ 12,30",
            "0% de Taxa99 semanal",
            "4 min (1,2 km)",
            "5 min (2,8 km)",
            "Aceitar"
        )
        val offer2 = ParsingEngine.parseNodes("com.app99.driver", offerWithTopClockPill, 0.2148, 4.75f)
        assertNotNull("Oferta real com pílula de saldo ao lado do relógio deve usar preço da corrida", offer2)
        assertEquals(12.30, offer2!!.price, 0.001)

        val offer3WithoutClock = listOf(
            "99",
            "R$ 108,95",
            "Moto",
            "R$ 7,30",
            "0% de Taxa99 semanal",
            "4 min (1,4 km)",
            "8 min (4,3 km)",
            "Aceitar"
        )
        val offer3 = ParsingEngine.parseNodes("com.app99.driver", offer3WithoutClock, 0.2148, 4.75f)
        assertNotNull("Oferta sem relógio visível ainda deve ignorar pílula acumulada acima do card", offer3)
        assertEquals(7.30, offer3!!.price, 0.001)

        val mapWithTopPillAndKmOnly = listOf(
            "18:13 O",
            "R$108,95",
            "Moto",
            "9 99",
            "Recompensas (3)",
            "1,4 km",
            "Ganhe até R$104 >"
        )
        assertNull(
            "Pílula do topo + Recompensas com km no mapa NÃO pode gerar oferta falsa",
            ParsingEngine.parseNodes("com.app99.driver", mapWithTopPillAndKmOnly, 0.2148, 4.75f)
        )
    }
}
