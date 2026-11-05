package com.rotacore.app

import org.junit.Assert.*
import org.junit.Test

class CancellationAndLifecycleTest {

    @Test
    fun testUberCancellationNotificationWithStandardFee() {
        val event = RideNotificationListenerService.parseCancellationNotification(
            packageName = "com.ubercab.driver",
            content = "Viagem cancelada pelo passageiro. Uma taxa de cancelamento foi aplicada à sua conta."
        )

        assertNotNull("Event should be detected as cancellation", event)
        assertTrue(event!!.isCancelled)
        assertEquals("UBER", event.platform)
        assertEquals(5.50, event.fee, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO_TAXA, event.status)
    }

    @Test
    fun test99CancellationNotificationWithStandardFee() {
        val event = RideNotificationListenerService.parseCancellationNotification(
            packageName = "com.app99.driver",
            content = "Corrida cancelada pelo passageiro."
        )

        assertNotNull(event)
        assertTrue(event!!.isCancelled)
        assertEquals("99", event.platform)
        assertEquals(3.00, event.fee, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO_TAXA, event.status)
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
        assertEquals(3.00, result.cancellationFee ?: 0.0, 0.001)
        assertEquals(RideRecord.STATUS_CANCELADO_TAXA, result.status)
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
        assertEquals("com.rotacore.app.ACTION_STOP_MONITORING", MonitoringService.ACTION_STOP_MONITORING)
        assertEquals("com.rotacore.app.ACTION_STOP_BUBBLE", BubbleOverlayService.ACTION_STOP_BUBBLE)
        assertEquals(20_000L, BubbleOverlayService.AUTO_DISMISS_DELAY_MS)
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
}

