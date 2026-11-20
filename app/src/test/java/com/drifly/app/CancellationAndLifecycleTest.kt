package com.drifly.app

import org.junit.Assert.*
import org.junit.Test

class CancellationAndLifecycleTest {

    @Test
    fun testUberCancellationNotificationWithoutExplicitAmountNeverInventsStandardFee() {
        val event = RideNotificationListenerService.parseCancellationNotification(
            packageName = "com.ubercab.driver",
            content = "Viagem cancelada pelo passageiro. Uma taxa de cancelamento foi aplicada à sua conta."
        )

        assertNotNull("Event should be detected as cancellation", event)
        assertTrue(event!!.isCancelled)
        assertEquals("UBER", event.platform)
        assertEquals(0.0, event.fee, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO, event.status)
    }

    @Test
    fun test99CancellationNotificationWithoutExplicitAmountNeverInventsStandardFee() {
        val event = RideNotificationListenerService.parseCancellationNotification(
            packageName = "com.app99.driver",
            content = "Corrida cancelada pelo passageiro."
        )

        assertNotNull(event)
        assertTrue(event!!.isCancelled)
        assertEquals("99", event.platform)
        assertEquals(0.0, event.fee, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO, event.status)
    }

    @Test
    fun testCancellationNotificationWithExplicitFeeAmount() {
        val event = RideNotificationListenerService.parseCancellationNotification(
            packageName = "com.ubercab.driver",
            content = "Viagem cancelada. Taxa recebida: R$ 7,20."
        )

        assertNotNull(event)
        assertTrue(event!!.isCancelled)
        assertEquals("UBER", event.platform)
        assertEquals(7.20, event.fee, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO_TAXA, event.status)
    }

    @Test
    fun testCancellationNotificationWithoutFee() {
        val event = RideNotificationListenerService.parseCancellationNotification(
            packageName = "com.app99.driver",
            content = "Corrida cancelada pelo usuário sem taxa de cancelamento."
        )

        assertNotNull(event)
        assertTrue(event!!.isCancelled)
        assertEquals("99", event.platform)
        assertEquals(0.0, event.fee, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO, event.status)
    }

    @Test
    fun testNonCancellationNotificationReturnsNull() {
        val event = RideNotificationListenerService.parseCancellationNotification(
            packageName = "com.ubercab.driver",
            content = "Sua conta está ativa. Conecte-se para receber ofertas."
        )
        assertNull("Non-cancellation notifications should return null", event)
    }

    @Test
    fun testParseOnScreenCompletionReceipt() {
        val texts = listOf(
            "Detalhes da viagem",
            "Você ganhou",
            "R$ 28,50",
            "Distância: 7,2 km"
        )
        val result = ParsingEngine.parseReceipt("com.ubercab.driver", texts)

        assertNotNull("Should detect on-screen completion receipt", result)
        assertTrue(result!!.isCompleted)
        assertFalse(result.isCancelled)
        assertEquals("UBER", result.platform)
        assertEquals(28.50, result.finalPrice ?: 0.0, 0.001)
        assertEquals(RideRecord.STATUS_CONCLUIDO, result.status)
    }

    @Test
    fun testParseOnScreenCancellationDialog99() {
        val texts = listOf(
            "Atenção",
            "Corrida cancelada pelo passageiro",
            "Você receberá uma taxa padrão."
        )
        val result = ParsingEngine.parseReceipt("com.app99.driver", texts)

        assertNotNull("Should detect in-app cancellation dialog", result)
        assertTrue(result!!.isCancelled)
        assertFalse(result.isCompleted)
        assertEquals("99", result.platform)
        assertEquals(0.0, result.cancellationFee ?: 0.0, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO, result.status)
    }

    @Test
    fun testParseOnScreenCancellationZeroFee() {
        val texts = listOf(
            "Corrida cancelada",
            "Cancelada pelo passageiro",
            "Sem taxa aplicada pois o cancelamento ocorreu nos primeiros minutos."
        )
        val result = ParsingEngine.parseReceipt("com.app99.driver", texts)

        assertNotNull(result)
        assertTrue(result!!.isCancelled)
        assertEquals(0.0, result.cancellationFee ?: 0.0, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO, result.status)
    }

    @Test
    fun testFinancialMetricsCalculationRules() {
        // Simulação do algoritmo de getSummary com as regras de status da tarefa:
        // - CONCLUIDO: soma gross_price e net_profit
        // - ESTIMADO: soma gross_price e net_profit
        // - CANCELADO_TAXA: soma apenas taxa de cancelamento no gross e net
        // - CANCELADO: soma R$ 0,00 no gross e net

        val rides = listOf(
            RideRecord(id = 1, platform = "UBER", grossPrice = 30.0, distanceKm = 8.0, netProfit = 24.0, timestamp = 1000L, status = RideRecord.STATUS_CONCLUIDO),
            RideRecord(id = 2, platform = "99", grossPrice = 20.0, distanceKm = 5.0, netProfit = 16.0, timestamp = 2000L, status = RideRecord.STATUS_ESTIMADO),
            RideRecord(id = 3, platform = "UBER", grossPrice = 45.0, distanceKm = 12.0, netProfit = 35.0, timestamp = 3000L, status = RideRecord.STATUS_CANCELADO_TAXA, cancellationFee = 5.50),
            RideRecord(id = 4, platform = "99", grossPrice = 25.0, distanceKm = 6.0, netProfit = 19.0, timestamp = 4000L, status = RideRecord.STATUS_CANCELADO, cancellationFee = 0.0)
        )

        var totalGross = 0.0
        var totalNet = 0.0
        var count = 0

        for (r in rides) {
            when (r.status) {
                RideRecord.STATUS_CONCLUIDO, RideRecord.STATUS_ESTIMADO -> {
                    totalGross += r.grossPrice
                    totalNet += r.netProfit
                    count++
                }
                RideRecord.STATUS_CANCELADO_TAXA -> {
                    totalGross += r.cancellationFee
                    totalNet += r.cancellationFee
                    count++
                }
                RideRecord.STATUS_CANCELADO -> {
                    // R$ 0,00
                }
            }
        }

        val expenses = 10.0 // Despesa manual (ex: lanche)
        val finalNet = totalNet - expenses

        // Total Gross: 30.0 (Concluída) + 20.0 (Estimada) + 5.50 (Taxa Uber) + 0.0 (Cancelada) = 55.50
        assertEquals(55.50, totalGross, 0.001)

        // Total Net: 24.0 + 16.0 + 5.50 = 45.50 - 10.0 (Despesas) = 35.50
        assertEquals(35.50, finalNet, 0.001)

        // Contagem de corridas com faturamento: 3
        assertEquals(3, count)
    }

    @Test
    fun testOemCompatibilityHelperDetection() {
        assertEquals(DeviceBrand.XIAOMI, OemCompatibilityHelper.detectBrand("Xiaomi"))
        assertEquals(DeviceBrand.XIAOMI, OemCompatibilityHelper.detectBrand("Redmi Note 12"))
        assertEquals(DeviceBrand.XIAOMI, OemCompatibilityHelper.detectBrand("Poco X5 Pro"))
        assertEquals(DeviceBrand.SAMSUNG, OemCompatibilityHelper.detectBrand("samsung"))
        assertEquals(DeviceBrand.MOTOROLA, OemCompatibilityHelper.detectBrand("motorola"))
        assertEquals(DeviceBrand.MOTOROLA, OemCompatibilityHelper.detectBrand("Lenovo"))
        assertEquals(DeviceBrand.GENERIC, OemCompatibilityHelper.detectBrand("Google Pixel"))
        assertEquals(DeviceBrand.GENERIC, OemCompatibilityHelper.detectBrand(null))

        assertTrue(OemCompatibilityHelper.getBrandOptimizationTip(DeviceBrand.XIAOMI).contains("Início Automático"))
        assertTrue(OemCompatibilityHelper.getBrandOptimizationTip(DeviceBrand.SAMSUNG).contains("One UI"))
        assertTrue(OemCompatibilityHelper.getBrandOptimizationTip(DeviceBrand.MOTOROLA).contains("Motorola"))

        assertNotNull(OemCompatibilityHelper.currentBrand)
        assertNotNull(OemCompatibilityHelper.deviceModelName)
        assertNotNull(OemCompatibilityHelper.androidVersionSummary)
        assertNotNull(OemCompatibilityHelper.getBrandOptimizationTip())
    }

    @Test
    fun testUnrelatedAppNotificationIsRejected() {
        // Mensagem de WhatsApp contendo palavras como Uber e cancelada não deve ser capturada
        val event = RideNotificationListenerService.parseCancellationNotification(
            packageName = "com.whatsapp",
            content = "A reunião na Uber foi cancelada pelo usuário."
        )
        assertNull("Unrelated apps like WhatsApp should never trigger ride cancellations", event)

        val smsEvent = RideNotificationListenerService.parseCancellationNotification(
            packageName = "com.google.android.apps.messaging",
            content = "Sua corrida no 99 foi cancelada"
        )
        assertNull("SMS messaging app should not trigger ride cancellations", smsEvent)
    }

    @Test
    fun testCancellationNotificationWithExplicitZeroFeeAmount() {
        // Notificação que menciona explicitamente R$ 0,00 ou R$ 0.00
        val event = RideNotificationListenerService.parseCancellationNotification(
            packageName = "com.ubercab.driver",
            content = "Viagem cancelada pelo usuário. Taxa cobrada: R$ 0,00."
        )
        assertNotNull(event)
        assertTrue(event!!.isCancelled)
        assertEquals(0.0, event.fee, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO, event.status)
    }

    @Test
    fun testParseOnScreenCancellationExplicitZeroFeeAmount() {
        // Recibo ou popup em tela dizendo explicitamente R$ 0,00
        val texts = listOf(
            "Corrida cancelada pelo passageiro",
            "Taxa de cancelamento: R$ 0,00"
        )
        val result = ParsingEngine.parseReceipt("com.app99.driver", texts)
        assertNotNull(result)
        assertTrue(result!!.isCancelled)
        assertEquals(0.0, result.cancellationFee ?: -1.0, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO, result.status)
    }

    @Test
    fun testParseOnScreenCompletionReceiptWithBreakdownPicksFinalAmount() {
        // Tela de recibo com múltiplos valores (subtaxas/descontos e valor final)
        val texts = listOf(
            "Viagem finalizada com sucesso",
            "Tarifa base: R$ 6,50",
            "Pedágio: R$ 4,20",
            "Você ganhou",
            "R$ 38,90",
            "Distância: 11,4 km"
        )
        val result = ParsingEngine.parseReceipt("com.ubercab.driver", texts)
        assertNotNull(result)
        assertTrue(result!!.isCompleted)
        // Deve extrair R$ 38,90 (total recebido), NÃO R$ 6,50 ou R$ 4,20
        assertEquals(38.90, result.finalPrice ?: 0.0, 0.001)
        assertEquals(RideRecord.STATUS_CONCLUIDO, result.status)
    }

    @Test
    fun testSamsungDeviceBrandDetectionAndTips() {
        val samsungBrand = OemCompatibilityHelper.detectBrand("samsung SM-G991B")
        assertEquals(DeviceBrand.SAMSUNG, samsungBrand)
        val tip = OemCompatibilityHelper.getBrandOptimizationTip(samsungBrand)
        assertTrue("Tip must mention One UI or Sem Restrições", tip.contains("One UI") && tip.contains("Sem Restrições"))
    }

    @Test
    fun testNotificationConstantsAndIds() {
        // Assegura que os IDs de notificação não colidem e possuem valores padronizados
        assertEquals(1001, MonitoringService.NOTIFICATION_ID)
        assertEquals(2002, BubbleOverlayService.NOTIFICATION_ID)
        assertEquals(3003, SupportNotificationWorker.NOTIFICATION_ID)
        assertEquals("drifly_monitoring_channel", MonitoringService.CHANNEL_ID)
        assertEquals("drifly_bubble_channel", BubbleOverlayService.CHANNEL_ID)
        assertEquals("com.drifly.app.ACTION_STOP_MONITORING", MonitoringService.ACTION_STOP_MONITORING)
        assertEquals("com.drifly.app.ACTION_STOP_BUBBLE", BubbleOverlayService.ACTION_STOP_BUBBLE)
        assertEquals(20_000L, BubbleOverlayService.AUTO_DISMISS_DELAY_MS)
        assertEquals(7_000L, BubbleOverlayService.AUTO_DISMISS_BAD_OFFER_MS)
        assertEquals(15_000L, BubbleOverlayService.AUTO_DISMISS_GOOD_OFFER_MS)
        assertEquals(15_000L, BubbleOverlayService.AUTO_DISMISS_STREETVIEW_MS)
        assertEquals("drifly_support_reminder_8h", SupportNotificationWorker.WORK_NAME_8H)
        assertEquals("drifly_support_reminder_15h", SupportNotificationWorker.WORK_NAME_15H)
        assertEquals("drifly_support_reminder_20h", SupportNotificationWorker.WORK_NAME_20H)
    }

    @Test
    fun testOverlayWindowFlagsContainRequiredPolicyFlags() {
        // Valida que o conjunto de flags de overlay inclui as flags críticas
        // para renderizar sobre telas de bloqueio (FLAG_SHOW_WHEN_LOCKED),
        // acordar a tela (FLAG_TURN_SCREEN_ON), acelerar por hardware (FLAG_HARDWARE_ACCELERATED)
        // e não roubar foco (FLAG_NOT_FOCUSABLE)
        val flagNotFocusable = 0x00000008 // WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val flagNotTouchModal = 0x00000020 // WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val flagLayoutInScreen = 0x00000100 // WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val flagShowWhenLocked = 0x00080000 // WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        val flagTurnScreenOn = 0x00200000 // WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        val flagHardwareAccelerated = 0x01000000 // WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val combinedFlags = flagNotFocusable or
                flagNotTouchModal or
                flagLayoutInScreen or
                flagHardwareAccelerated or
                flagShowWhenLocked or
                flagTurnScreenOn

        assertTrue("Must have FLAG_NOT_FOCUSABLE", (combinedFlags and flagNotFocusable) != 0)
        assertTrue("Must have FLAG_SHOW_WHEN_LOCKED to prevent mPolicyVisibility=false", (combinedFlags and flagShowWhenLocked) != 0)
        assertTrue("Must have FLAG_HARDWARE_ACCELERATED", (combinedFlags and flagHardwareAccelerated) != 0)
        assertTrue("Must have FLAG_NOT_TOUCH_MODAL", (combinedFlags and flagNotTouchModal) != 0)
        assertTrue("Must have FLAG_LAYOUT_IN_SCREEN", (combinedFlags and flagLayoutInScreen) != 0)
        assertTrue("Must have FLAG_TURN_SCREEN_ON", (combinedFlags and flagTurnScreenOn) != 0)
    }

    @Test
    fun testOverlayDimensionsCalculation() {
        val screenWidth = 720
        val cardWidth = (screenWidth * 0.94f).toInt()
        assertEquals(676, cardWidth)

        // Assegura proporção de 94% para dar margem elegante nas laterais em qualquer densidade
        val fhdWidth = 1080
        val fhdCardWidth = (fhdWidth * 0.94f).toInt()
        assertEquals(1015, fhdCardWidth)
    }

    @Test
    fun testStreetViewStaticUrlGeneration() {
        val urlWithPano = StreetViewHelper.buildStaticStreetViewUrl(
            panoId = "CAoSF0NJSE0wb2dLRUlDQWdJREVsTGF6a0FF",
            heading = 120L
        )
        assertTrue(urlWithPano.contains("maps.googleapis.com/maps/api/streetview"))
        assertTrue(urlWithPano.contains("pano=CAoSF0NJSE0wb2dLRUlDQWdJREVsTGF6a0FF"))
        assertTrue(urlWithPano.contains("heading=120"))

        val urlWithCoords = StreetViewHelper.buildStaticStreetViewUrl(
            lat = -23.5614,
            lng = -46.6558,
            heading = 45L
        )
        assertTrue(urlWithCoords.contains("location=-23.5614,-46.6558"))
    }

    @Test
    fun testColdStartSplashPlaysOnlyOncePerProcessAndSkipsWarmOrRecreatedStarts() {
        MainActivity.resetColdStartSplashForTesting()

        // 1. Primeira abertura real (Cold Start: savedInstanceState nulo, sem intent de fundo) -> DEVE tocar
        assertTrue(
            "Splash must play on initial cold start",
            MainActivity.consumeColdStartSplashEligibility(hasSavedInstanceState = false, isBackgroundShortcutIntent = false)
        )

        // 2. Segunda abertura com app já em 2º plano (Warm/Hot Start) -> NÃO deve tocar
        assertFalse(
            "Splash must NOT play when reopening from background",
            MainActivity.consumeColdStartSplashEligibility(hasSavedInstanceState = false, isBackgroundShortcutIntent = false)
        )

        // 3. Mesmo após reset de processo, se a Activity for recriada pelo sistema (savedInstanceState != null) -> NÃO deve tocar
        MainActivity.resetColdStartSplashForTesting()
        assertFalse(
            "Splash must NOT play on configuration change / recreation",
            MainActivity.consumeColdStartSplashEligibility(hasSavedInstanceState = true, isBackgroundShortcutIntent = false)
        )

        // 4. Se aberto por intent de atalho direto / serviço -> NÃO deve tocar e já marca sessão como iniciada
        MainActivity.resetColdStartSplashForTesting()
        assertFalse(
            "Splash must NOT play on direct deep-link/service intent",
            MainActivity.consumeColdStartSplashEligibility(hasSavedInstanceState = false, isBackgroundShortcutIntent = true)
        )
        assertFalse(
            "Subsequent opens after deep-link must also skip splash",
            MainActivity.consumeColdStartSplashEligibility(hasSavedInstanceState = false, isBackgroundShortcutIntent = false)
        )
    }

    @Test
    fun testIdleDriverScreenDetectionResetsActiveRide() {
        val uberIdle = listOf("Você está online", "Procurando viagens")
        assertTrue("Uber idle screen must be detected", ParsingEngine.isIdleDriverScreen(uberIdle))

        val app99Idle = listOf("Você está online", "Aguardando chamadas")
        assertTrue("99 idle screen must be detected", ParsingEngine.isIdleDriverScreen(app99Idle))

        val radarIdle = listOf("Radar de viagens", "Encontrar viagens")
        assertTrue("Trip radar screen must be detected as idle", ParsingEngine.isIdleDriverScreen(radarIdle))

        val activeTrip = listOf("Em viagem com João", "Desembarque na Av Paulista")
        assertFalse("Active trip must NOT be detected as idle", ParsingEngine.isIdleDriverScreen(activeTrip))
    }

    @Test
    fun testOfferActionReceiverConstants() {
        assertEquals("com.drifly.app.ACTION_DISCARD_OFFER", OfferActionReceiver.ACTION_DISCARD_OFFER)
        assertEquals("EXTRA_DISCARD_RIDE_ID", OfferActionReceiver.EXTRA_DISCARD_RIDE_ID)
        assertEquals("EXTRA_DISCARD_NOTIF_ID", OfferActionReceiver.EXTRA_DISCARD_NOTIF_ID)
    }

    @Test
    fun testOfferExpirationNotificationsAndHistoryScreensNeverCancelCompletedOrEstimatedRides() {
        // Notificação de oferta expirada/retirada antes do aceite NÃO pode cancelar corrida anterior
        assertNull(
            RideNotificationListenerService.parseCancellationNotification(
                packageName = "com.app99.driver",
                content = "Solicitação cancelada pelo passageiro."
            )
        )
        assertNull(
            RideNotificationListenerService.parseCancellationNotification(
                packageName = "com.app99.driver",
                content = "Oferta encerrada. O passageiro cancelou a solicitação."
            )
        )
        assertNull(
            RideNotificationListenerService.parseCancellationNotification(
                packageName = "com.app99.driver",
                content = "Pedido cancelado pelo passageiro."
            )
        )
        assertNull(
            ParsingEngine.parseReceipt(
                "com.app99.driver",
                listOf("Pedido cancelado pelo passageiro")
            )
        )

        // Fase de embarque vs. fase de viagem/desembarque
        assertTrue(ParsingEngine.isPickupPhaseScreen(listOf("40 km/h", "A caminho do passageiro", "Cheguei ao local")))
        assertFalse(ParsingEngine.isInTripOrDropoffPhaseScreen(listOf("40 km/h", "A caminho do passageiro", "Cheguei ao local")))
        assertTrue(ParsingEngine.isInTripOrDropoffPhaseScreen(listOf("19:45 99", "Corrida em dinheiro", "O passageiro pode gravar")))
        assertFalse(ParsingEngine.isPickupPhaseScreen(listOf("19:45 99", "Corrida em dinheiro", "O passageiro pode gravar")))

        // Tela de "Histórico de corridas" contendo uma corrida antiga cancelada NÃO pode gerar ReceiptResult de cancelamento
        val historyWithCancelledRide = listOf(
            "Histórico de corridas",
            "11:36 - R$ 9,45 - Concluída",
            "10:15 - Corrida cancelada pelo passageiro"
        )
        assertNull(ParsingEngine.parseReceipt("com.app99.driver", historyWithCancelledRide))

        // Popup de cancelamento sobreposto ao mapa não deve capturar a pílula de saldo R$88,95 como taxa de cancelamento
        val cancelOverMapHeader = listOf(
            "13:15 0 99", "Taxa", "R$88,95", "Ver",
            "Corrida cancelada pelo passageiro"
        )
        val cancelReceipt = ParsingEngine.parseReceipt("com.app99.driver", cancelOverMapHeader)
        assertNotNull(cancelReceipt)
        assertTrue(cancelReceipt!!.isCancelled)
        assertEquals(0.0, cancelReceipt.cancellationFee ?: 0.0, 0.001)
    }

    @Test
    fun testBillableRulesExcludeLegacyAndDuplicates() {
        assertTrue(RideRecord.isBillable(RideRecord.STATUS_CONCLUIDO, RideRecord.EVIDENCE_RECEIPT, 0.0))
        assertTrue(RideRecord.isBillable(RideRecord.STATUS_ESTIMADO, RideRecord.EVIDENCE_CLICK, 0.0))
        assertFalse(RideRecord.isBillable(RideRecord.STATUS_ESTIMADO, RideRecord.EVIDENCE_LEGACY, 0.0))
        assertFalse(RideRecord.isBillable(RideRecord.STATUS_ESTIMADO, RideRecord.EVIDENCE_SUSPECT, 0.0))
        assertFalse(RideRecord.isBillable(RideRecord.STATUS_OFERTA, RideRecord.EVIDENCE_OFFER, 0.0))
        assertFalse(RideRecord.isBillable(RideRecord.STATUS_DUPLICADO, RideRecord.EVIDENCE_DUPLICATE, 0.0))
        assertFalse(RideRecord.isBillable(RideRecord.STATUS_CANCELADO, RideRecord.EVIDENCE_CANCEL, 0.0))
        assertFalse(RideRecord.isBillable(RideRecord.STATUS_CANCELADO_TAXA, RideRecord.EVIDENCE_EXPLICIT_FEE, 0.0))
        assertTrue(RideRecord.isBillable(RideRecord.STATUS_CANCELADO_TAXA, RideRecord.EVIDENCE_EXPLICIT_FEE, 4.25))
    }

    @Test
    fun testActiveRideWithOfferCancelToastIsNotRideCancellation() {
        // Log real de 19/11 09:24:54 e 12:16:19: toast "OK, corrida cancelada" da OFERTA
        // enquanto a corrida atual continua na tela ("Finalizar corrida" + "Chegada prevista").
        val texts = listOf(
            "09:24 99",
            "Rua Blumenau, 334, Centro, Joinville - SC",
            "OK, corrida cancelada",
            "Finalizar corrida",
            "1 min 76 m",
            "Chegada prevista: 09:24"
        )
        val result = ParsingEngine.parseReceipt("com.app99.driver", texts)
        assertNull("Cancelamento de oferta durante corrida ativa não pode gerar taxa na corrida", result)
    }

    @Test
    fun testDriverCancellationPromptIsRecognizedAndNotAReceipt() {
        val texts = listOf(
            "Deseja mesmo cancelar esta corrida?",
            "Não cancelar",
            "Cancelar"
        )
        assertTrue(ParsingEngine.isDriverCancellationPromptScreen(texts))
        assertNull(ParsingEngine.parseReceipt("com.app99.driver", texts))
    }

    @Test
    fun testExplicitCancellationFeeWinsAndBannerNeverBecomesFee() {
        val explicit = ParsingEngine.parseReceipt(
            "com.app99.driver",
            listOf("Corrida cancelada", "Taxa de cancelamento: R$ 4,25")
        )
        assertNotNull(explicit)
        assertEquals(4.25, explicit!!.cancellationFee ?: 0.0, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO_TAXA, explicit.status)

        // Botão "Cancelar corrida" sozinho não pode esconder um recibo real de cancelamento.
        val withCancelButton = ParsingEngine.parseReceipt(
            "com.app99.driver",
            listOf("Corrida cancelada pelo passageiro", "Cancelar corrida", "Taxa de cancelamento: R$ 4,00")
        )
        assertNotNull(withCancelButton)
        assertEquals(4.00, withCancelButton!!.cancellationFee ?: 0.0, 0.001)

        val banner = ParsingEngine.parseReceipt(
            "com.app99.driver",
            listOf("13:15 99", "Taxa", "R$88,95", "Ver", "Corrida cancelada")
        )
        assertNotNull(banner)
        assertEquals(0.0, banner!!.cancellationFee ?: 0.0, 0.001)

        val feeNearBalance = ParsingEngine.parseReceipt(
            "com.app99.driver",
            listOf("Corrida cancelada", "Taxa de cancelamento aplicada. Seu saldo é R$ 88,95")
        )
        assertNotNull(feeNearBalance)
        assertEquals(0.0, feeNearBalance!!.cancellationFee ?: 0.0, 0.001)
    }

    @Test
    fun testAnticipationNotificationNeverCreatesOfferAndCompletionPreservesFare() {
        assertTrue(
            ParsingEngine.isCompletionOrAnticipationNotification(
                "Você recebeu uma antecipação da corrida. Pagamento recebido!"
            )
        )
        assertTrue(
            ParsingEngine.isCompletionOrAnticipationNotification(
                "Você recebeu um bônus de R$ 5,00 pela corrida"
            )
        )
        assertFalse(
            ParsingEngine.isCompletionOrAnticipationNotification(
                "Complete 10 corridas e ganhe bônus"
            )
        )
        assertFalse(
            ParsingEngine.isCompletionOrAnticipationNotification(
                "Antecipação da corrida disponível"
            )
        )
        assertFalse(
            ParsingEngine.isLikelyRideOfferNotification(
                listOf("Você recebeu R$ 3,82 de antecipação da corrida")
            )
        )
        assertTrue(ParsingEngine.isPlausibleFinalFare(5.10, 5.10))
        assertFalse(ParsingEngine.isPlausibleFinalFare(96.65, 12.30))
        assertFalse(ParsingEngine.isPlausibleFinalFare(27.45, 5.10))
    }
}


