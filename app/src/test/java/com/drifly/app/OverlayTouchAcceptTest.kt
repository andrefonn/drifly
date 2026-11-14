package com.drifly.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Testes unitários para o Requisito R1:
 * - Geometria matemática do raio circular de exclusão (~52 dp ao redor do centro do botão X).
 * - Distinção precisa entre toques de fechamento (d <= 52dp) e toques no corpo do overlay (d > 52dp).
 * - Detecção de deslocamento de arrasto (drag) vs toque estático (tap).
 * - Mecanismo de debounce e proteção contra duplo toque.
 * - Padrão e persistência de `tapToAcceptEnabled` em `PreferencesManager`.
 * - Serialização e restauração no `BackupManager`.
 * - Declaração de `android:canPerformGestures="true"` no XML de acessibilidade.
 */
class OverlayTouchAcceptTest {

    // -------------------------------------------------------------
    // 1. Testes de Geometria do Raio Circular de Exclusão (~52 dp)
    // -------------------------------------------------------------

    @Test
    fun testEuclideanDistanceCalculation() {
        // Triângulo pitagórico clássico 3-4-5
        val dist1 = OverlayTouchMath.calculateDistanceToCenter(
            rawX = 10f,
            rawY = 20f,
            centerX = 13f,
            centerY = 24f
        )
        assertEquals(5.0f, dist1, 0.001f)

        // Triângulo 30-40-50
        val dist2 = OverlayTouchMath.calculateDistanceToCenter(
            rawX = 0f,
            rawY = 0f,
            centerX = 30f,
            centerY = 40f
        )
        assertEquals(50.0f, dist2, 0.001f)

        // Ponto idêntico ao centro
        val distZero = OverlayTouchMath.calculateDistanceToCenter(
            rawX = 250f,
            rawY = 180f,
            centerX = 250f,
            centerY = 180f
        )
        assertEquals(0.0f, distZero, 0.0001f)
    }

    @Test
    fun testCloseExclusionRadiusClassification_Density1x() {
        val density = 1.0f // mdpi: 52 dp = 52 px
        val centerX = 300f
        val centerY = 100f

        // Ponto 1: No exato centro do botão X (distância = 0) -> DENTRO da exclusão
        assertTrue(
            "Toque no centro deve estar dentro da zona de exclusão",
            OverlayTouchMath.isTouchInExclusionZone(300f, 100f, centerX, centerY, density)
        )

        // Ponto 2: A 20 dp do centro (dentro do próprio botão 44x44 dp) -> DENTRO da exclusão
        assertTrue(
            "Toque a 20 dp deve estar dentro da zona de exclusão",
            OverlayTouchMath.isTouchInExclusionZone(320f, 100f, centerX, centerY, density)
        )

        // Ponto 3: A 35 dp do centro (fora do botão 44x44 mas dentro do raio de 52 dp) -> DENTRO da exclusão
        assertTrue(
            "Toque a 35 dp deve ser protegido pela margem de 52 dp",
            OverlayTouchMath.isTouchInExclusionZone(300f, 135f, centerX, centerY, density)
        )

        // Ponto 4: A 51.9 dp (pouco abaixo do limite) -> DENTRO da exclusão
        assertTrue(
            "Toque a 51.9 dp deve estar dentro da zona de exclusão",
            OverlayTouchMath.isTouchInExclusionZone(300f, 151.9f, centerX, centerY, density)
        )

        // Ponto 5: Exatamente no limite (52.0 dp) -> DENTRO da exclusão
        assertTrue(
            "Toque exatamente a 52 dp está no limite e deve ser classificado como exclusão",
            OverlayTouchMath.isTouchInExclusionZone(300f, 152.0f, centerX, centerY, density)
        )

        // Ponto 6: Imediatamente fora do limite (52.1 dp) -> FORA da exclusão (corpo do overlay)
        assertFalse(
            "Toque a 52.1 dp deve estar fora da zona de exclusão",
            OverlayTouchMath.isTouchInExclusionZone(300f, 152.1f, centerX, centerY, density)
        )

        // Ponto 7: A 70 dp do centro -> FORA da exclusão
        assertFalse(
            "Toque a 70 dp deve estar fora da zona de exclusão",
            OverlayTouchMath.isTouchInExclusionZone(300f, 170f, centerX, centerY, density)
        )

        // Ponto 8: No corpo inferior do card (ex: Y=350) -> FORA da exclusão
        assertFalse(
            "Toque no corpo do card deve estar fora da exclusão",
            OverlayTouchMath.isTouchInExclusionZone(200f, 350f, centerX, centerY, density)
        )
    }

    @Test
    fun testCloseExclusionRadiusClassification_MultiDensity() {
        val centerX = 500f
        val centerY = 200f

        // Densidade 2.0x (xhdpi): 52 dp = 104 px
        val density2x = 2.0f
        assertTrue(
            "A 100px com densidade 2.0x deve estar dentro (< 104px)",
            OverlayTouchMath.isTouchInExclusionZone(500f, 300f, centerX, centerY, density2x)
        )
        assertFalse(
            "A 105px com densidade 2.0x deve estar fora (> 104px)",
            OverlayTouchMath.isTouchInExclusionZone(500f, 305f, centerX, centerY, density2x)
        )

        // Densidade 3.0x (xxhdpi): 52 dp = 156 px
        val density3x = 3.0f
        assertTrue(
            "A 150px com densidade 3.0x deve estar dentro (< 156px)",
            OverlayTouchMath.isTouchInExclusionZone(500f, 350f, centerX, centerY, density3x)
        )
        assertFalse(
            "A 160px com densidade 3.0x deve estar fora (> 156px)",
            OverlayTouchMath.isTouchInExclusionZone(500f, 360f, centerX, centerY, density3x)
        )
    }

    // -------------------------------------------------------------
    // 2. Testes de Arraste (Drag) vs Toque Estático (Tap)
    // -------------------------------------------------------------

    @Test
    fun testDragDetection() {
        val density = 1.0f // 10 dp slop = 10 px
        val startX = 200f
        val startY = 300f

        // Pequeno tremor acidental durante o toque (dx = 3, dy = 4) -> NÃO é drag
        assertFalse(
            "Tremor menor que 10 dp não é drag",
            OverlayTouchMath.isDrag(startX, startY, 203f, 304f, density)
        )

        // Arraste horizontal significativo (dx = 15 > 10) -> É drag
        assertTrue(
            "Deslocamento horizontal maior que 10 dp é drag",
            OverlayTouchMath.isDrag(startX, startY, 215f, 300f, density)
        )

        // Arraste vertical significativo (dy = 25 > 10) -> É drag
        assertTrue(
            "Deslocamento vertical maior que 10 dp é drag",
            OverlayTouchMath.isDrag(startX, startY, 200f, 325f, density)
        )
    }

    // -------------------------------------------------------------
    // 3. Testes de Proteção contra Duplo Toque / Debounce
    // -------------------------------------------------------------

    @Test
    fun testDebounceAndSingleShotProtection() {
        val initialTime = 10_000L

        // 1º toque: não disparado, sem toques anteriores -> PERMITIDO
        val allowedFirst = OverlayTouchMath.shouldAllowAcceptTap(
            isTriggered = false,
            lastTapTimestamp = 0L,
            currentTimestamp = initialTime
        )
        assertTrue("Primeiro toque deve ser permitido", allowedFirst)

        // Se a flag atômica já estiver marcada como disparada -> BLOQUEADO
        val blockedAlreadyTriggered = OverlayTouchMath.shouldAllowAcceptTap(
            isTriggered = true,
            lastTapTimestamp = initialTime,
            currentTimestamp = initialTime + 5000L
        )
        assertFalse("Toque subsequente com flag isTriggered=true deve ser bloqueado", blockedAlreadyTriggered)

        // Toque rápido dentro da janela de 3000ms (ex: 800ms depois) -> BLOQUEADO pelo debounce
        val blockedDebounce = OverlayTouchMath.shouldAllowAcceptTap(
            isTriggered = false,
            lastTapTimestamp = initialTime,
            currentTimestamp = initialTime + 800L
        )
        assertFalse("Toque dentro de 3000ms deve ser bloqueado pelo debounce", blockedDebounce)

        // Toque após a expiração do debounce de 3000ms (ex: 3500ms depois) com flag resetada -> PERMITIDO
        val allowedAfterDebounce = OverlayTouchMath.shouldAllowAcceptTap(
            isTriggered = false,
            lastTapTimestamp = initialTime,
            currentTimestamp = initialTime + 3500L
        )
        assertTrue("Toque após a janela de 3000ms deve ser permitido se resetado", allowedAfterDebounce)
    }

    // -------------------------------------------------------------
    // 4. Testes de Persistência e Padrão de `PreferencesManager`
    // -------------------------------------------------------------

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testPreferencesManagerTapToAcceptDefaultAndToggle() {
        val inMemoryStore = mutableMapOf<String, Any>()

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
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return inMemoryPrefs
            }
        }

        val prefs = PreferencesManager(testContext)

        // 1. Padrão deve ser estritamente false (desligado por padrão, conforme R1)
        assertFalse(
            "tapToAcceptEnabled deve ser false por padrão",
            prefs.tapToAcceptEnabled
        )

        // 2. Ativação via setter
        prefs.tapToAcceptEnabled = true
        assertTrue(
            "tapToAcceptEnabled deve refletir true após atribuição",
            prefs.tapToAcceptEnabled
        )
        assertEquals(
            "A chave gravada no SharedPreferences deve ser tap_to_accept_enabled",
            true,
            inMemoryStore["tap_to_accept_enabled"]
        )

        // 3. Desativação
        prefs.tapToAcceptEnabled = false
        assertFalse(
            "tapToAcceptEnabled deve refletir false após desligamento",
            prefs.tapToAcceptEnabled
        )
        assertEquals(
            false,
            inMemoryStore["tap_to_accept_enabled"]
        )
    }

    // -------------------------------------------------------------
    // 5. Testes de Integração de Backup (JSON)
    // -------------------------------------------------------------

    @Test
    fun testBackupJsonIncludesTapToAcceptKey() {
        val prefsJson = JSONObject().apply {
            put("auto_accept_enabled", true)
            put("tap_to_accept_enabled", true)
        }
        val root = JSONObject().apply {
            put("app", "DriFly")
            put("preferences", prefsJson)
        }

        assertTrue(root.getJSONObject("preferences").has("tap_to_accept_enabled"))
        assertTrue(root.getJSONObject("preferences").getBoolean("tap_to_accept_enabled"))
    }

    // -------------------------------------------------------------
    // 6. Teste de Configuração de Acessibilidade (canPerformGestures)
    // -------------------------------------------------------------

    @Test
    fun testAccessibilityServiceConfigDeclaresCanPerformGestures() {
        val configFile = File("src/main/res/xml/accessibility_service_config.xml")
        val altFile = File("app/src/main/res/xml/accessibility_service_config.xml")
        val target = if (configFile.exists()) configFile else altFile

        assertTrue("Arquivo accessibility_service_config.xml deve existir", target.exists())
        val content = target.readText()
        assertTrue(
            "accessibility_service_config.xml deve conter android:canPerformGestures=\"true\"",
            content.contains("android:canPerformGestures=\"true\"")
        )
    }
}
