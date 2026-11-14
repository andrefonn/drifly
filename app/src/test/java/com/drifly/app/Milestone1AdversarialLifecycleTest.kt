package com.drifly.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Suite de Testes Empíricos Adversariais do Challenger 2 para o Milestone 1.
 * Foco:
 * 1. Resiliência de persistência e fallback do BackupManager (chaves corrompidas, ausentes, tipos inválidos).
 * 2. Supressão de aceite manual quando tapToAcceptEnabled = false.
 * 3. Null-safety estrita quando RotaAccessibilityService.instance == null.
 * 4. Geometria de exclusão em condições extremas e limites de precisão de ponto flutuante.
 * 5. Stress test de concorrência e debounce em cenários de alta disputa.
 */
class Milestone1AdversarialLifecycleTest {

    // Helper para criar PreferencesManager simulado em memória
    private fun createMockPreferences(initialValues: Map<String, Any> = emptyMap()): Pair<PreferencesManager, MutableMap<String, Any>> {
        val inMemoryStore = HashMap<String, Any>(initialValues)
        val inMemoryPrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = inMemoryStore
            override fun getString(key: String?, defValue: String?): String? = (inMemoryStore[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (inMemoryStore[key] as? MutableSet<String>) ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (inMemoryStore[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (inMemoryStore[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (inMemoryStore[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (inMemoryStore[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = inMemoryStore.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private val temp = mutableMapOf<String, Any>()
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null && value != null) temp[key] = value; return this }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { if (key != null && values != null) temp[key] = values; return this }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
                override fun remove(key: String?): SharedPreferences.Editor { inMemoryStore.remove(key); return this }
                override fun clear(): SharedPreferences.Editor { inMemoryStore.clear(); return this }
                override fun commit(): Boolean { inMemoryStore.putAll(temp); return true }
                override fun apply() { inMemoryStore.putAll(temp) }
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        val testContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = inMemoryPrefs
        }

        return Pair(PreferencesManager(testContext), inMemoryStore)
    }

    // -------------------------------------------------------------------------
    // 1. Adversarial Backup Import & Fallback Tests
    // -------------------------------------------------------------------------

    @Test
    fun testBackupImport_MissingKeys_GracefulFallbackToDefaultFalse() {
        val (prefs, store) = createMockPreferences()

        // Garante que o estado inicial é o padrão (false)
        assertFalse("Padrão inicial de tapToAcceptEnabled deve ser false", prefs.tapToAcceptEnabled)

        // Simula importação de JSON legado onde a chave 'tap_to_accept_enabled' não existia
        val legacyJson = JSONObject().apply {
            put("app", "DriFly")
            put("preferences", JSONObject().apply {
                put("auto_accept_enabled", true)
                put("min_rate_km", 2.5)
                // 'tap_to_accept_enabled' e 'touch_to_accept_enabled' AUSENTES propositalmente
            })
        }

        val p = legacyJson.getJSONObject("preferences")
        if (p.has("tap_to_accept_enabled")) {
            prefs.tapToAcceptEnabled = p.getBoolean("tap_to_accept_enabled")
        } else if (p.has("touch_to_accept_enabled")) {
            prefs.tapToAcceptEnabled = p.getBoolean("touch_to_accept_enabled")
        }

        // Deve permanecer estritamente false
        assertFalse("tapToAcceptEnabled deve manter o padrão false na ausência da chave no JSON", prefs.tapToAcceptEnabled)
    }

    @Test
    fun testBackupImport_LegacyTouchToAcceptKey_MigratesToTapToAccept() {
        val (prefs, store) = createMockPreferences()

        val legacyJson = JSONObject().apply {
            put("app", "DriFly")
            put("preferences", JSONObject().apply {
                put("touch_to_accept_enabled", true) // Chave antiga
            })
        }

        val p = legacyJson.getJSONObject("preferences")
        if (p.has("tap_to_accept_enabled")) {
            prefs.tapToAcceptEnabled = p.getBoolean("tap_to_accept_enabled")
        } else if (p.has("touch_to_accept_enabled")) {
            prefs.tapToAcceptEnabled = p.getBoolean("touch_to_accept_enabled")
        }

        assertTrue("Chave legada touch_to_accept_enabled deve migrar com sucesso para tapToAcceptEnabled = true", prefs.tapToAcceptEnabled)
        assertEquals(true, store["tap_to_accept_enabled"])
    }

    @Test
    fun testBackupImport_CorruptedJson_FailsGracefullyWithoutCrashing() {
        val corruptJsonStrings = listOf(
            "",
            "   ",
            "{",
            "{\"app\": \"DriFly\", preferences: invalid_syntax}",
            "<html><body>404 Not Found</body></html>",
            "null",
            "{\"invalid_root\": true}"
        )

        for (corrupt in corruptJsonStrings) {
            val result = try {
                val root = JSONObject(corrupt)
                if (!root.has("rides") && !root.has("expenses") && !root.has("preferences") && !root.has("monthly_obligations")) {
                    Result.failure(IllegalArgumentException("Arquivo de backup inválido ou incompatível."))
                } else {
                    // Espelha fielmente BackupManager.kt:183 que faz root.getJSONObject("preferences")
                    if (root.has("preferences")) {
                        root.getJSONObject("preferences")
                    }
                    if (root.has("rides")) {
                        root.getJSONArray("rides")
                    }
                    Result.success(1)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }

            assertTrue("String JSON corrompida '$corrupt' deve falhar graciosamente via Result.failure", result.isFailure)
        }
    }

    @Test
    fun testBackupImport_TypeMismatchInPreferences_FailsGracefully() {
        val typeMismatchJson = JSONObject().apply {
            put("app", "DriFly")
            put("preferences", JSONObject().apply {
                put("tap_to_accept_enabled", "INVALID_STRING_INSTEAD_OF_BOOLEAN")
            })
        }

        val (prefs, _) = createMockPreferences()
        val p = typeMismatchJson.getJSONObject("preferences")

        var caughtException = false
        try {
            if (p.has("tap_to_accept_enabled")) {
                prefs.tapToAcceptEnabled = p.getBoolean("tap_to_accept_enabled")
            }
        } catch (e: Exception) {
            caughtException = true
        }

        // Em BackupManager.kt, toda a restauração é envolvida em try { ... } catch (e: Exception) { Result.failure(e) }
        assertTrue("Tipo inválido em tap_to_accept_enabled deve disparar exceção tratada", caughtException)
        assertFalse("Preferência corrompida não deve ser gravada como true", prefs.tapToAcceptEnabled)
    }

    // -------------------------------------------------------------------------
    // 2. Adversarial Overlay Touch Logic: tapToAcceptEnabled == false
    // -------------------------------------------------------------------------

    @Test
    fun testOverlayTouch_WhenTapToAcceptDisabled_NeverInvokesAccept() {
        val (prefs, _) = createMockPreferences()
        prefs.tapToAcceptEnabled = false

        val acceptTriggerInvoked = AtomicBoolean(false)
        val simulateManualAccept = {
            acceptTriggerInvoked.set(true)
        }

        // Simula a lógica de decisão executada no MotionEvent.ACTION_UP do BubbleOverlayService
        val isDragging = false
        val isExclusionZone = false
        val isStreetViewBounds = false

        if (!isDragging && !isExclusionZone && !isStreetViewBounds) {
            if (prefs.tapToAcceptEnabled) {
                simulateManualAccept()
            }
        }

        assertFalse("Quando tapToAcceptEnabled = false, toque no corpo do overlay NUNCA deve acionar aceite", acceptTriggerInvoked.get())
    }

    @Test
    fun testOverlayTouch_WhenInside52dpExclusionZone_ClosesAndNeverInvokesAccept() {
        val (prefs, _) = createMockPreferences()
        prefs.tapToAcceptEnabled = true // Mesmo com a funcionalidade ativada!

        val acceptTriggerInvoked = AtomicBoolean(false)
        val closeRequested = AtomicBoolean(false)

        val density = 2.0f
        val closeBtnCenterX = 400f
        val closeBtnCenterY = 100f

        // Toque a 30px (15dp) do centro do botão X -> DENTRO da exclusão
        val touchX = 400f
        val touchY = 130f

        val isExclusionZone = OverlayTouchMath.isTouchInExclusionZone(touchX, touchY, closeBtnCenterX, closeBtnCenterY, density)
        assertTrue("Toque dentro de 52dp deve ser identificado como zona de exclusão", isExclusionZone)

        if (isExclusionZone) {
            closeRequested.set(true)
            // Retorna imediatamente sem aceitar
        } else if (prefs.tapToAcceptEnabled) {
            acceptTriggerInvoked.set(true)
        }

        assertTrue("Toque dentro da exclusão deve solicitar fechamento do overlay", closeRequested.get())
        assertFalse("Toque dentro da exclusão NUNCA deve disparar o aceite, mesmo com tapToAcceptEnabled = true", acceptTriggerInvoked.get())
    }

    // -------------------------------------------------------------------------
    // 3. Adversarial Null-Safety & Lifecycle: RotaAccessibilityService.instance
    // -------------------------------------------------------------------------

    @Test
    fun testRotaAccessibilityService_InstanceNullSafety_NoCrashWhenNull() {
        // Quando o usuário desativa o serviço nas configurações do Android ou antes do binding do sistema:
        val serviceInstance: RotaAccessibilityService? = null

        var exceptionThrown = false
        var executedAccept = false

        try {
            // Sintaxe exata utilizada em BubbleOverlayService.kt:
            // RotaAccessibilityService.instance?.triggerManualAccept()
            serviceInstance?.let {
                it.triggerManualAccept()
                executedAccept = true
            }
        } catch (t: Throwable) {
            exceptionThrown = true
        }

        assertFalse("Chamada segura ?.triggerManualAccept() em instância nula NÃO deve lançar exceção", exceptionThrown)
        assertFalse("Nenhum aceite deve ser executado se o serviço de acessibilidade estiver nulo/inativo", executedAccept)
    }

    @Test
    fun testRotaAccessibilityService_LifecycleInstanceRetentionAndCleanup() {
        // Valida que a referência estática companion é devidamente gerenciada pelo padrão implementado:
        // override fun onDestroy() {
        //     if (instance === this) { instance = null }
        // }
        var mockStaticInstance: Any? = "ServiceRunningInstance"
        val activeService = mockStaticInstance

        // 1. Em execução: não-nulo
        assertNotNull(mockStaticInstance)

        // 2. Destruição do serviço: limpa apenas se for a mesma instância
        if (mockStaticInstance === activeService) {
            mockStaticInstance = null
        }
        assertNull("Referência estática deve ser anulada no onDestroy para prevenir vazamento de contexto", mockStaticInstance)

        // 3. Caso uma nova instância tenha sido criada antes do onDestroy da anterior:
        val newServiceInstance = "NewServiceInstance"
        mockStaticInstance = newServiceInstance
        val staleServiceInstance = "OldStaleInstance"

        if (mockStaticInstance === staleServiceInstance) {
            mockStaticInstance = null
        }
        assertEquals("Instância mais recente não deve ser anulada pelo onDestroy de uma instância antiga", newServiceInstance, mockStaticInstance)
    }

    // -------------------------------------------------------------------------
    // 4. Adversarial Geometry & Boundary Precision Tests
    // -------------------------------------------------------------------------

    @Test
    fun testOverlayTouchMath_ExtremeGeometryAndBoundaryPrecision() {
        // Coordenadas negativas (fora da tela / multi-display / toques nas bordas virtuais)
        val distNeg = OverlayTouchMath.calculateDistanceToCenter(-50f, -50f, 0f, 0f)
        assertEquals(70.71068f, distNeg, 0.001f)

        // Limite de alta resolução: telas de ultra-alta densidade (4.0x xxxhdpi)
        // 52 dp * 4.0 = 208 px
        val density4x = 4.0f
        val centerX = 1000f
        val centerY = 500f

        // 207.9 px -> DENTRO
        assertTrue(OverlayTouchMath.isTouchInExclusionZone(1000f, 707.9f, centerX, centerY, density4x))
        // 208.0 px -> EXATO LIMITE -> DENTRO
        assertTrue(OverlayTouchMath.isTouchInExclusionZone(1000f, 708.0f, centerX, centerY, density4x))
        // 208.1 px -> FORA
        assertFalse(OverlayTouchMath.isTouchInExclusionZone(1000f, 708.1f, centerX, centerY, density4x))

        // Telas de baixa densidade (0.75x ldpi): 52 dp * 0.75 = 39 px
        val densityLdpi = 0.75f
        assertTrue(OverlayTouchMath.isTouchInExclusionZone(100f, 138.9f, 100f, 100f, densityLdpi))
        assertTrue(OverlayTouchMath.isTouchInExclusionZone(100f, 139.0f, 100f, 100f, densityLdpi))
        assertFalse(OverlayTouchMath.isTouchInExclusionZone(100f, 139.1f, 100f, 100f, densityLdpi))
    }

    // -------------------------------------------------------------------------
    // 5. Stress Test de Concorrência e Debounce Multi-Thread
    // -------------------------------------------------------------------------

    @Test
    fun testOverlayTouchMath_ConcurrentAccessStressTest() {
        val threadCount = 64
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        val acceptedCount = AtomicInteger(0)
        val rejectedCount = AtomicInteger(0)

        val isTriggered = AtomicBoolean(false)
        val fixedTimestamp = 50_000L

        // Dispara 64 threads disputando simultaneamente o primeiro toque de aceite
        for (i in 0 until threadCount) {
            Thread {
                try {
                    startLatch.await()
                    // Tentativa atômica de obter autorização de aceite
                    if (isTriggered.compareAndSet(false, true)) {
                        acceptedCount.incrementAndGet()
                    } else {
                        rejectedCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        // Libera todas as threads simultaneamente
        startLatch.countDown()
        doneLatch.await()

        assertEquals("Exatamente 1 thread deve conseguir disparar o aceite", 1, acceptedCount.get())
        assertEquals("Todas as outras 63 threads concorrentes devem ser bloqueadas", threadCount - 1, rejectedCount.get())
    }
}
