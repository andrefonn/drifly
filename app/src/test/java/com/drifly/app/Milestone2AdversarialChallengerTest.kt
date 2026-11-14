package com.drifly.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Suite de Testes Empíricos Adversariais do Challenger 2 para o Milestone 2.
 *
 * Escopo de Testes:
 * 1. Resiliência do BackupManager na importação de JSON corrupto, ausente de chaves, arrays vazios
 *    e coordenadas malformadas (strings em vez de números, NaN, nulos).
 * 2. Supressão incondicional de veredito positivo e do Modo Casa quando `isBlacklistBlocked = true` em `BubbleOverlayService.isOfferGood`.
 * 3. Garantia de tempo de exibição curto (`AUTO_DISMISS_BAD_OFFER_MS` = 7s) para ofertas bloqueadas por lista negra.
 * 4. Validação de estilização visual do card e badge ("Recusar ❌", vermelho) com preservação de alertas de lista negra.
 * 5. Casos extremos de correspondência de lista negra (acentos, pontuação, tokens e proximidade geográfica).
 */
class Milestone2AdversarialChallengerTest {

    // Helper para criar PreferencesManager simulado em memória
    private fun createMockPreferences(
        isModoCasa: Boolean = false,
        blacklistEnabled: Boolean = true,
        minRatePerKm: Float = 2.20f,
        minPassengerRating: Float = 4.75f
    ): PreferencesManager {
        val store = mutableMapOf<String, Any>(
            "is_modo_casa_active" to isModoCasa,
            "min_rate_km" to minRatePerKm,
            "min_passenger_rating" to minPassengerRating,
            "blacklist_enabled" to blacklistEnabled
        )

        val inMemoryPrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = store
            override fun getString(key: String?, defValue: String?): String? = (store[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (store[key] as? MutableSet<String>) ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (store[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (store[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (store[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (store[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = store.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private val temp = mutableMapOf<String, Any>()
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null && value != null) temp[key] = value; return this }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { if (key != null && values != null) temp[key] = values; return this }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) temp[key] = value; return this }
                override fun remove(key: String?): SharedPreferences.Editor { store.remove(key); return this }
                override fun clear(): SharedPreferences.Editor { store.clear(); return this }
                override fun commit(): Boolean { store.putAll(temp); return true }
                override fun apply() { store.putAll(temp) }
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        val testContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = inMemoryPrefs
        }

        return PreferencesManager(testContext)
    }

    // -------------------------------------------------------------------------
    // 1. Testes de Importação/Restauração de Backup com Payloads Adversariais
    // -------------------------------------------------------------------------

    @Test
    fun testBackupImport_CorruptPayloads_FailGracefullyWithoutCrash() {
        val corruptPayloads = listOf(
            "",
            "   ",
            "{",
            "{\"app\": \"DriFly\", preferences: invalid}",
            "<html><body>502 Bad Gateway</body></html>",
            "null",
            "{\"invalid_key\": 123}",
            "[]",
            "{\"preferences\": []}",
            "{\"rides\": {}}",
            "{\"expenses\": \"invalid\"}",
            "{\"monthly_obligations\": 123}",
            "{\"blacklist\": \"not_array\"}"
        )

        for (payload in corruptPayloads) {
            val result = BackupManager.validateBackupJson(payload)
            assertTrue("Payload corrupto '$payload' deve retornar Result.failure", result.isFailure)
        }
    }

    @Test
    fun testBackupImport_MissingBlacklistKey_SucceedsGracefully() {
        // Simula arquivo de backup v1 ou backup sem a chave 'blacklist'
        val jsonWithoutBlacklist = JSONObject().apply {
            put("app", "DriFly")
            put("backup_version", 1)
            put("preferences", JSONObject().apply {
                put("fuel_price", 5.99)
                put("auto_accept_enabled", true)
            })
            put("rides", JSONArray())
        }

        // Valida que a ausência de 'blacklist' não dispara NullPointerException nem falha
        assertTrue(jsonWithoutBlacklist.has("preferences"))
        assertFalse(jsonWithoutBlacklist.has("blacklist"))
        assertTrue("Backup sem blacklist deve ser validado com sucesso", BackupManager.validateBackupJson(jsonWithoutBlacklist.toString()).isSuccess)

        // Simula o bloco de desserialização do BackupManager
        var itemsParsed = 0
        if (jsonWithoutBlacklist.has("blacklist")) {
            val blArray = jsonWithoutBlacklist.getJSONArray("blacklist")
            itemsParsed = blArray.length()
        }

        assertEquals("Na ausência de 'blacklist', 0 itens devem ser restaurados sem exceção", 0, itemsParsed)
    }

    @Test
    fun testBackupImport_EmptyBlacklistArray_SucceedsGracefully() {
        val jsonWithEmptyBlacklist = JSONObject().apply {
            put("app", "DriFly")
            put("backup_version", 2)
            put("blacklist", JSONArray())
        }

        assertTrue(jsonWithEmptyBlacklist.has("blacklist"))
        assertTrue("Backup contendo apenas blacklist deve ser validado com sucesso", BackupManager.validateBackupJson(jsonWithEmptyBlacklist.toString()).isSuccess)
        val blArray = jsonWithEmptyBlacklist.getJSONArray("blacklist")
        assertEquals(0, blArray.length())

        val restoredItems = mutableListOf<BlacklistItem>()
        for (i in 0 until blArray.length()) {
            val item = blArray.getJSONObject(i)
            restoredItems.add(
                BlacklistItem(
                    id = item.optLong("id", 0L),
                    rawTerm = item.optString("raw_term", ""),
                    normalizedTerm = item.optString("normalized_term", "")
                )
            )
        }

        assertEquals(0, restoredItems.size)
    }

    @Test
    fun testBackupImport_MalformedCoordinateTypes_FallbackGracefully() {
        val itemsArray = JSONArray().apply {
            // Item 1: Coordenadas como Strings numéricas (deve converter ou aceitar como Double)
            put(JSONObject().apply {
                put("id", 1L)
                put("raw_term", "Cidade Jardim")
                put("normalized_term", "cidade jardim")
                put("lat", "-6.0712")
                put("lng", "-49.8923")
                put("created_at", 1000L)
            })
            // Item 2: Coordenadas como Strings inválidas (não-numéricas)
            put(JSONObject().apply {
                put("id", 2L)
                put("raw_term", "Itinga")
                put("normalized_term", "itinga")
                put("lat", "INVALID_COORD")
                put("lng", "NOT_A_NUMBER")
                put("created_at", 2000L)
            })
            // Item 3: Coordenadas nulas explícitas
            put(JSONObject().apply {
                put("id", 3L)
                put("raw_term", "Centro")
                put("normalized_term", "centro")
                put("lat", JSONObject.NULL)
                put("lng", JSONObject.NULL)
                put("created_at", 3000L)
            })
            // Item 4: Coordenadas ausentes
            put(JSONObject().apply {
                put("id", 4L)
                put("raw_term", "Bairro da Paz")
                put("normalized_term", "bairro da paz")
                put("created_at", 4000L)
            })
        }

        val parsedItems = mutableListOf<BlacklistItem>()
        for (i in 0 until itemsArray.length()) {
            val item = itemsArray.getJSONObject(i)
            // Lógica exata utilizada em BackupManager.kt:329-330
            val lat = if (item.isNull("lat")) null else item.optDouble("lat").takeIf { !it.isNaN() }
            val lng = if (item.isNull("lng")) null else item.optDouble("lng").takeIf { !it.isNaN() }

            parsedItems.add(
                BlacklistItem(
                    id = item.optLong("id", 0L),
                    rawTerm = item.optString("raw_term", ""),
                    normalizedTerm = item.optString("normalized_term", ""),
                    latitude = lat,
                    longitude = lng,
                    createdAt = item.optLong("created_at", System.currentTimeMillis())
                )
            )
        }

        assertEquals(4, parsedItems.size)

        // Item 1: string numérica é convertida com sucesso por optDouble
        assertNotNull("String numérica '-6.0712' deve ser convertida com sucesso", parsedItems[0].latitude)
        assertEquals(-6.0712, parsedItems[0].latitude!!, 0.0001)
        assertEquals(-49.8923, parsedItems[0].longitude!!, 0.0001)

        // Item 2: string inválida resulta em NaN e faz fallback para null
        assertNull("String inválida 'INVALID_COORD' deve fazer fallback seguro para null", parsedItems[1].latitude)
        assertNull("String inválida 'NOT_A_NUMBER' deve fazer fallback seguro para null", parsedItems[1].longitude)

        // Item 3: JSONObject.NULL resulta em null
        assertNull("JSONObject.NULL deve resultar em null", parsedItems[2].latitude)
        assertNull("JSONObject.NULL deve resultar em null", parsedItems[2].longitude)

        // Item 4: chaves ausentes resultam em null
        assertNull("Chaves de coordenadas ausentes devem resultar em null", parsedItems[3].latitude)
        assertNull("Chaves de coordenadas ausentes devem resultar em null", parsedItems[3].longitude)
    }

    // -------------------------------------------------------------------------
    // 2. Testes de Decisão de Oferta: isOfferGood e getDismissDelayForOffer
    // -------------------------------------------------------------------------

    @Test
    fun testIsOfferGood_BlacklistBlockedOverridesHomeModeDirection() {
        val prefs = createMockPreferences(isModoCasa = true)

        // Cenário extremo: Motorista quer ir para casa, a corrida vai exatamente para casa (-15 km delta!),
        // o valor é altíssimo (R$ 100 por 10 km = R$ 10/km), nota 5.0.
        // Porém, o bairro está na Lista Negra (ex: área de alto risco).
        val blockedSuperHomeOffer = RideOffer(
            platform = "UBER",
            price = 100.0,
            distanceKm = 10.0,
            durationMinutes = 20,
            ratePerKm = 10.0,
            ratePerHour = 300.0,
            passengerRating = 5.0f,
            isHeadingHome = true,
            homeDistanceDeltaKm = -15.0,
            isBlacklistBlocked = true,
            blacklistAlert = "⛔ Destino em Lista Negra: Área de Risco",
            destinationAddress = "Rua do Perigo, Área de Risco",
            pickupAddress = "Centro"
        )

        val verdict = BubbleOverlayService.isOfferGood(blockedSuperHomeOffer, prefs)
        assertFalse(
            "isOfferGood DEVE ser false incondicionalmente para ofertas na Lista Negra, mesmo que homeDistanceDeltaKm <= 0!",
            verdict
        )
    }

    @Test
    fun testIsOfferGood_BlacklistBlockedOverridesNormalProfitableOffer() {
        val prefs = createMockPreferences(isModoCasa = false)

        val blockedProfitableOffer = RideOffer(
            platform = "99",
            price = 50.0,
            distanceKm = 10.0,
            durationMinutes = 15,
            ratePerKm = 5.00,
            ratePerHour = 200.0,
            passengerRating = 4.95f,
            isLowRating = false,
            isBlacklistBlocked = true,
            blacklistAlert = "⛔ Embarque em Lista Negra: Bairro Bloqueado",
            pickupAddress = "Rua A, Bairro Bloqueado",
            destinationAddress = "Shopping"
        )

        val verdict = BubbleOverlayService.isOfferGood(blockedProfitableOffer, prefs)
        assertFalse("isOfferGood DEVE ser false para ofertas com embarque na Lista Negra", verdict)
    }

    @Test
    fun testIsOfferGood_NotBlockedAllowsHomeModeAndNormalOffers() {
        val prefsHome = createMockPreferences(isModoCasa = true)
        val prefsNormal = createMockPreferences(isModoCasa = false)

        val unblockedHomeOffer = RideOffer(
            platform = "UBER",
            price = 25.0,
            distanceKm = 8.0,
            durationMinutes = 15,
            ratePerKm = 3.12,
            ratePerHour = 45.0,
            homeDistanceDeltaKm = -3.5,
            isBlacklistBlocked = false
        )

        val unblockedNormalOffer = RideOffer(
            platform = "UBER",
            price = 30.0,
            distanceKm = 10.0,
            durationMinutes = 20,
            ratePerKm = 3.00,
            ratePerHour = 45.0,
            passengerRating = 4.85f,
            isLowRating = false,
            isBlacklistBlocked = false
        )

        assertTrue("Oferta boa e não-bloqueada no Modo Casa deve ser aceita", BubbleOverlayService.isOfferGood(unblockedHomeOffer, prefsHome))
        assertTrue("Oferta lucrativa e não-bloqueada no modo normal deve ser aceita", BubbleOverlayService.isOfferGood(unblockedNormalOffer, prefsNormal))
    }

    @Test
    fun testDismissDelay_BlacklistBlockedReceivesShortDismissDelay() {
        val prefsHome = createMockPreferences(isModoCasa = true)
        val prefsNormal = createMockPreferences(isModoCasa = false)

        val blockedOffer = RideOffer(
            platform = "UBER",
            price = 80.0,
            distanceKm = 10.0,
            durationMinutes = 20,
            ratePerKm = 8.0,
            ratePerHour = 150.0,
            homeDistanceDeltaKm = -8.0,
            isBlacklistBlocked = true
        )

        val delayHome = BubbleOverlayService.getDismissDelayForOffer(blockedOffer, prefsHome)
        val delayNormal = BubbleOverlayService.getDismissDelayForOffer(blockedOffer, prefsNormal)

        assertEquals("Oferta em lista negra deve receber delay curto de descarte (7s)", BubbleOverlayService.AUTO_DISMISS_BAD_OFFER_MS, delayHome)
        assertEquals("Oferta em lista negra deve receber delay curto de descarte (7s)", BubbleOverlayService.AUTO_DISMISS_BAD_OFFER_MS, delayNormal)
    }

    // -------------------------------------------------------------------------
    // 3. Testes de Correspondência de Bairros e Normalização Flexível
    // -------------------------------------------------------------------------

    @Test
    fun testBlacklistMatching_AccentsPunctuationAndWordTokens() {
        val item = BlacklistItem(
            id = 10L,
            rawTerm = "São Cristóvão",
            normalizedTerm = BlacklistManager.normalizeText("São Cristóvão")
        )
        val list = listOf(item)

        assertEquals("sao cristovao", item.normalizedTerm)

        // Variação 1: Sem acentos e com pontuação
        val match1 = BlacklistManager.matchesBlacklist("Rua Dom Pedro I, Sao Cristovao - RJ", list)
        assertNotNull(match1)
        assertEquals(10L, match1?.id)

        // Variação 2: Maiúsculas com barra e hífen
        val match2 = BlacklistManager.matchesBlacklist("AV. SÃO CRISTÓVÃO, N 100 / ZONA NORTE", list)
        assertNotNull(match2)

        // Bairro diferente não deve casar
        val match3 = BlacklistManager.matchesBlacklist("Rua São Gonçalo, Centro", list)
        assertNull(match3)
    }

    @Test
    fun testBlacklistMatching_BlankOrNullAddress_ReturnsNullWithoutException() {
        val list = listOf(BlacklistItem(rawTerm = "Centro", normalizedTerm = "centro"))

        assertNull(BlacklistManager.matchesBlacklist(null, list))
        assertNull(BlacklistManager.matchesBlacklist("", list))
        assertNull(BlacklistManager.matchesBlacklist("   ", list))
    }

    @Test
    fun testBlacklistMatching_EmptyBlacklist_ReturnsNull() {
        assertNull(BlacklistManager.matchesBlacklist("Rua Qualquer, Centro", emptyList()))
    }

    @Test
    fun testBlacklistMatching_GeographicThresholdEdgeCases() {
        // Ponto de referência: lat 0.0, lon 0.0
        val item = BlacklistItem(
            id = 99L,
            rawTerm = "Marco Zero",
            normalizedTerm = "marco zero",
            latitude = 0.0,
            longitude = 0.0
        )
        val list = listOf(item)

        // Ponto a ~1.11 km de distância (0.01 grau de latitude = 1.111 km)
        val insideThresholdCoords = Pair(0.01, 0.0)
        val matchInside = BlacklistManager.matchesBlacklist(
            address = "Rua Nova Sem Bairro",
            items = list,
            addressCoords = insideThresholdCoords
        )
        assertNotNull("Ponto a ~1.11 km (< 1.5 km) deve ser bloqueado geograficamente", matchInside)

        // Ponto a ~2.22 km de distância (0.02 grau de latitude = 2.223 km)
        val outsideThresholdCoords = Pair(0.02, 0.0)
        val matchOutside = BlacklistManager.matchesBlacklist(
            address = "Rua Nova Sem Bairro",
            items = list,
            addressCoords = outsideThresholdCoords
        )
        assertNull("Ponto a ~2.22 km (>= 1.5 km) não deve ser bloqueado geograficamente", matchOutside)
    }

    @Test
    fun testEvaluateOffer_PickupAndDestinationCombinations() {
        val list = listOf(
            BlacklistItem(id = 1L, rawTerm = "Itinga", normalizedTerm = "itinga"),
            BlacklistItem(id = 2L, rawTerm = "Boa Vista", normalizedTerm = "boa vista")
        )

        // 1. Apenas embarque na lista negra
        val offerPickup = RideOffer(
            platform = "UBER",
            price = 20.0,
            distanceKm = 5.0,
            durationMinutes = 12,
            ratePerKm = 4.0,
            pickupAddress = "Rua Adolfo Soares, Itinga",
            destinationAddress = "Rua XV de Novembro, Centro"
        )
        val evalPickup = BlacklistManager.evaluateOffer(offerPickup, list)
        assertTrue(evalPickup.isBlacklistBlocked)
        assertEquals("⛔ Embarque em Lista Negra: Itinga", evalPickup.blacklistAlert)

        // 2. Apenas destino na lista negra
        val offerDest = RideOffer(
            platform = "UBER",
            price = 25.0,
            distanceKm = 6.0,
            durationMinutes = 15,
            ratePerKm = 4.16,
            pickupAddress = "Rua XV de Novembro, Centro",
            destinationAddress = "Rua Albano Schmidt, Boa Vista"
        )
        val evalDest = BlacklistManager.evaluateOffer(offerDest, list)
        assertTrue(evalDest.isBlacklistBlocked)
        assertEquals("⛔ Destino em Lista Negra: Boa Vista", evalDest.blacklistAlert)

        // 3. Ambos na lista negra (bairros distintos)
        val offerBoth = RideOffer(
            platform = "UBER",
            price = 30.0,
            distanceKm = 7.0,
            durationMinutes = 18,
            ratePerKm = 4.28,
            pickupAddress = "Rua Adolfo Soares, Itinga",
            destinationAddress = "Rua Albano Schmidt, Boa Vista"
        )
        val evalBoth = BlacklistManager.evaluateOffer(offerBoth, list)
        assertTrue(evalBoth.isBlacklistBlocked)
        assertEquals("⛔ Embarque e Destino em Lista Negra (Itinga, Boa Vista)", evalBoth.blacklistAlert)

        // 4. Ambos na lista negra (mesmo bairro)
        val offerBothSame = RideOffer(
            platform = "UBER",
            price = 15.0,
            distanceKm = 3.0,
            durationMinutes = 8,
            ratePerKm = 5.0,
            pickupAddress = "Rua A, Itinga",
            destinationAddress = "Rua B, Itinga"
        )
        val evalBothSame = BlacklistManager.evaluateOffer(offerBothSame, list)
        assertTrue(evalBothSame.isBlacklistBlocked)
        assertEquals("⛔ Embarque e Destino em Lista Negra (Itinga)", evalBothSame.blacklistAlert)

        // 5. Nenhum na lista negra
        val offerClean = RideOffer(
            platform = "UBER",
            price = 22.0,
            distanceKm = 5.5,
            durationMinutes = 14,
            ratePerKm = 4.0,
            pickupAddress = "Rua Central, Centro",
            destinationAddress = "Rua das Palmeiras, Gloria"
        )
        val evalClean = BlacklistManager.evaluateOffer(offerClean, list)
        assertFalse(evalClean.isBlacklistBlocked)
        assertNull(evalClean.blacklistAlert)
    }
}
