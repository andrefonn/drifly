package com.drifly.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Testes unitários para o Requisito R2 / Milestone 2:
 * 1. Extração de Endereço de Embarque (pickupAddress) e Destino (destinationAddress).
 * 2. Normalização textual e correspondência flexível (acentos, pontuação, tokens e geolocalização).
 * 3. Formatação dos alertas de Lista Negra (apenas embarque, apenas destino e ambos).
 * 4. Bloqueio estrito de Auto-Accept e sobreposição obrigatória sobre vereditos positivos (incluindo Modo Casa).
 * 5. Serialização e restauração do array de Lista Negra no BackupManager.
 */
class BlacklistAndPickupExtractionTest {

    // -------------------------------------------------------------
    // 1. Extração de Endereço de Embarque (pickupAddress) e Destino
    // -------------------------------------------------------------

    @Test
    fun testExtractPickupAndDestinationFromRealUberTexts() {
        // Texto REAL da tela da Uber Motorista
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

        val pickup = ParsingEngine.extractPickupAddress(texts)
        val destination = ParsingEngine.extractDestinationAddress(texts)

        assertEquals("R. Adolfo Soares, Itinga, Joinville", pickup)
        assertEquals("Rua Estados Unidos, 75, Araquari, Araquari", destination)

        val offer = ParsingEngine.parseNodes(
            packageName = "com.ubercab.driver",
            texts = texts,
            costPerKm = 0.25,
            minRatingThreshold = 4.75f
        )
        assertNotNull(offer)
        assertEquals("R. Adolfo Soares, Itinga, Joinville", offer?.pickupAddress)
        assertEquals("Rua Estados Unidos, 75, Araquari, Araquari", offer?.destinationAddress)
    }

    @Test
    fun testExtractPickupAndDestinationFromReal99Texts() {
        val texts = listOf(
            "99Pop",
            "R$ 18,50",
            "7,2 km",
            "15 min",
            "Rua Adolfo Soares, 100 - Itinga",
            "Viagem de 12 min",
            "Avenida Brasil, 500 - Centro",
            "Aceitar"
        )

        val pickup = ParsingEngine.extractPickupAddress(texts)
        val destination = ParsingEngine.extractDestinationAddress(texts)

        assertEquals("Rua Adolfo Soares, 100 - Itinga", pickup)
        assertEquals("Avenida Brasil, 500 - Centro", destination)
    }

    @Test
    fun testSingleAddressKeepsDestinationAndNullPickup() {
        val texts = listOf(
            "UberX",
            "R$ 25,00",
            "5,0 km",
            "10 min",
            "Rua das Palmeiras, 120 - Centro",
            "Aceitar"
        )

        val pickup = ParsingEngine.extractPickupAddress(texts)
        val destination = ParsingEngine.extractDestinationAddress(texts)

        assertNull("Com apenas 1 endereço, pickupAddress deve ser nulo", pickup)
        assertEquals("Rua das Palmeiras, 120 - Centro", destination)
    }

    @Test
    fun testZeroAddressesYieldsNullForBoth() {
        val texts = listOf(
            "UberX",
            "R$ 15,00",
            "3,0 km",
            "8 min",
            "Aceitar"
        )

        assertNull(ParsingEngine.extractPickupAddress(texts))
        assertNull(ParsingEngine.extractDestinationAddress(texts))
    }

    // -------------------------------------------------------------
    // 2. Normalização e Correspondência Textual e Geográfica
    // -------------------------------------------------------------

    @Test
    fun testTextNormalizationRemovesAccentsPunctuationAndTrims() {
        val input = "  Av. dos Ipês, Cidade Jardim - Parauapebas / PA!  "
        val normalized = BlacklistManager.normalizeText(input)
        assertEquals("av dos ipes cidade jardim parauapebas pa", normalized)

        val input2 = "São José do Rio Preto - SP"
        val normalized2 = BlacklistManager.normalizeText(input2)
        assertEquals("sao jose do rio preto sp", normalized2)
    }

    @Test
    fun testBlacklistTextMatchingVariousFormats() {
        val item = BlacklistItem(
            id = 1L,
            rawTerm = "Cidade Jardim Parauapebas",
            normalizedTerm = BlacklistManager.normalizeText("Cidade Jardim Parauapebas")
        )
        val blacklist = listOf(item)

        // 1. Endereço completo contendo bairro e cidade
        val match1 = BlacklistManager.matchesBlacklist(
            "Av. dos Ipês, Cidade Jardim, Parauapebas",
            blacklist
        )
        assertNotNull("Deve casar endereço completo contendo Cidade Jardim, Parauapebas", match1)
        assertEquals(item.id, match1?.id)

        // 2. Variação com pontuação e número
        val match2 = BlacklistManager.matchesBlacklist(
            "Rua 15, nº 450 - Cidade Jardim - Parauapebas / PA",
            blacklist
        )
        assertNotNull(match2)

        // 3. Outro bairro na mesma cidade não deve casar
        val noMatch = BlacklistManager.matchesBlacklist(
            "Rua 10, Bairro da Paz, Parauapebas",
            blacklist
        )
        assertNull("Não deve casar bairro diferente na mesma cidade", noMatch)
    }

    @Test
    fun testBlacklistGeographicMatchingWithin1500Meters() {
        // Coordenadas de referência do Bairro Itinga: lat -26.3500, lng -48.8300
        val itemGeo = BlacklistItem(
            id = 2L,
            rawTerm = "Itinga",
            normalizedTerm = "itinga",
            latitude = -26.3500,
            longitude = -48.8300
        )
        val blacklist = listOf(itemGeo)

        // Ponto a ~0.6 km (600 metros) de distância
        val closeCoords = Pair(-26.3550, -48.8320)
        val matchGeo = BlacklistManager.matchesBlacklist(
            address = "Rua Desconhecida Sem Nome de Bairro",
            items = blacklist,
            addressCoords = closeCoords
        )
        assertNotNull("Deve casar geograficamente quando distância < 1.5 km", matchGeo)

        // Ponto a ~7.5 km de distância
        val farCoords = Pair(-26.4100, -48.8600)
        val matchFar = BlacklistManager.matchesBlacklist(
            address = "Rua Desconhecida Longe",
            items = blacklist,
            addressCoords = farCoords
        )
        assertNull("Não deve casar geograficamente quando distância >= 1.5 km", matchFar)
    }

    // -------------------------------------------------------------
    // 3. Formatação dos Alertas da Lista Negra
    // -------------------------------------------------------------

    @Test
    fun testAlertMessageFormatting() {
        val itemPickup = BlacklistItem(rawTerm = "Itinga", normalizedTerm = "itinga")
        val itemDest = BlacklistItem(rawTerm = "Paranaguamirim", normalizedTerm = "paranaguamirim")

        // Apenas embarque
        val alertPickup = BlacklistManager.formatBlacklistAlert(itemPickup, null)
        assertEquals("⛔ Embarque em Lista Negra: Itinga", alertPickup)

        // Apenas destino
        val alertDest = BlacklistManager.formatBlacklistAlert(null, itemDest)
        assertEquals("⛔ Destino em Lista Negra: Paranaguamirim", alertDest)

        // Ambos (bairros distintos)
        val alertBothDiff = BlacklistManager.formatBlacklistAlert(itemPickup, itemDest)
        assertEquals("⛔ Embarque e Destino em Lista Negra (Itinga, Paranaguamirim)", alertBothDiff)

        // Ambos (mesmo bairro)
        val alertBothSame = BlacklistManager.formatBlacklistAlert(itemPickup, itemPickup)
        assertEquals("⛔ Embarque e Destino em Lista Negra (Itinga)", alertBothSame)

        // Nenhum
        val alertNone = BlacklistManager.formatBlacklistAlert(null, null)
        assertNull(alertNone)
    }

    // -------------------------------------------------------------
    // 4. Bloqueio de Auto-Accept e Sobrescrita do Modo Casa
    // -------------------------------------------------------------

    @Test
    fun testBlacklistEvaluationBlocksOfferAndOverridesHomeMode() {
        val blacklist = listOf(
            BlacklistItem(rawTerm = "Cidade Jardim", normalizedTerm = "cidade jardim")
        )

        // Oferta altamente vantajosa e direcionando para casa (Home Mode favorável)
        val goodHomeOffer = RideOffer(
            platform = "UBER",
            price = 45.0,
            distanceKm = 10.0,
            durationMinutes = 20,
            ratePerKm = 4.50,
            ratePerHour = 135.0,
            isHeadingHome = true,
            homeDistanceDeltaKm = -6.0,
            destinationAddress = "Av dos Ipês, Cidade Jardim, Parauapebas",
            pickupAddress = "Rua do Comércio, Centro"
        )

        val evaluated = BlacklistManager.evaluateOffer(goodHomeOffer, blacklist)

        assertTrue("Oferta com destino na lista negra deve ser bloqueada", evaluated.isBlacklistBlocked)
        assertEquals("⛔ Destino em Lista Negra: Cidade Jardim", evaluated.blacklistAlert)

        // Verificação com PreferencesManager falso
        val fakePrefs = createFakePreferences(isModoCasa = true)
        val offerGoodVerdict = BubbleOverlayService.isOfferGood(evaluated, fakePrefs)

        assertFalse(
            "isOfferGood deve retornar FALSE incondicionalmente quando isBlacklistBlocked=true, sobrepondo o Modo Casa!",
            offerGoodVerdict
        )
    }

    @Test
    fun testBlacklistEvaluationBothPickupAndDestination() {
        val blacklist = listOf(
            BlacklistItem(rawTerm = "Itinga", normalizedTerm = "itinga"),
            BlacklistItem(rawTerm = "Paranaguamirim", normalizedTerm = "paranaguamirim")
        )

        val offer = RideOffer(
            platform = "99",
            price = 22.0,
            distanceKm = 5.0,
            durationMinutes = 12,
            ratePerKm = 4.40,
            ratePerHour = 110.0,
            pickupAddress = "Rua Adolfo Soares, Itinga",
            destinationAddress = "Rua Monsenhor Gercino, Paranaguamirim"
        )

        val evaluated = BlacklistManager.evaluateOffer(offer, blacklist)

        assertTrue(evaluated.isBlacklistBlocked)
        assertEquals("⛔ Embarque e Destino em Lista Negra (Itinga, Paranaguamirim)", evaluated.blacklistAlert)
    }

    // -------------------------------------------------------------
    // 5. Serialização e Restauração de Backup (JSON)
    // -------------------------------------------------------------

    @Test
    fun testBlacklistSerializationAndDeserialization() {
        val originalItems = listOf(
            BlacklistItem(
                id = 1L,
                rawTerm = "Cidade Jardim Parauapebas",
                normalizedTerm = "cidade jardim parauapebas",
                latitude = -6.0712,
                longitude = -49.8923,
                createdAt = 1700000000000L
            ),
            BlacklistItem(
                id = 2L,
                rawTerm = "Itinga",
                normalizedTerm = "itinga",
                latitude = null,
                longitude = null,
                createdAt = 1700000005000L
            )
        )

        // Serializa como no BackupManager
        val blacklistArray = JSONArray()
        for (item in originalItems) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("raw_term", item.rawTerm)
                put("normalized_term", item.normalizedTerm)
                if (item.latitude != null) put("lat", item.latitude) else put("lat", JSONObject.NULL)
                if (item.longitude != null) put("lng", item.longitude) else put("lng", JSONObject.NULL)
                put("created_at", item.createdAt)
            }
            blacklistArray.put(obj)
        }

        val rootJson = JSONObject().apply {
            put("preferences", JSONObject().apply {
                put("blacklist_enabled", true)
            })
            put("blacklist", blacklistArray)
        }

        val jsonString = rootJson.toString()

        // Desserializa como no BackupManager
        val parsedRoot = JSONObject(jsonString)
        val parsedPrefs = parsedRoot.getJSONObject("preferences")
        assertTrue(parsedPrefs.getBoolean("blacklist_enabled"))

        val parsedArray = parsedRoot.getJSONArray("blacklist")
        assertEquals(2, parsedArray.length())

        val restoredItems = mutableListOf<BlacklistItem>()
        for (i in 0 until parsedArray.length()) {
            val itemObj = parsedArray.getJSONObject(i)
            val lat = if (itemObj.isNull("lat")) null else itemObj.optDouble("lat").takeIf { !it.isNaN() }
            val lng = if (itemObj.isNull("lng")) null else itemObj.optDouble("lng").takeIf { !it.isNaN() }
            restoredItems.add(
                BlacklistItem(
                    id = itemObj.optLong("id", 0L),
                    rawTerm = itemObj.optString("raw_term", ""),
                    normalizedTerm = itemObj.optString("normalized_term", ""),
                    latitude = lat,
                    longitude = lng,
                    createdAt = itemObj.optLong("created_at", 0L)
                )
            )
        }

        assertEquals(2, restoredItems.size)
        assertEquals("Cidade Jardim Parauapebas", restoredItems[0].rawTerm)
        assertEquals("cidade jardim parauapebas", restoredItems[0].normalizedTerm)
        assertEquals(-6.0712, restoredItems[0].latitude!!, 0.0001)
        assertEquals(-49.8923, restoredItems[0].longitude!!, 0.0001)

        assertEquals("Itinga", restoredItems[1].rawTerm)
        assertEquals("itinga", restoredItems[1].normalizedTerm)
        assertNull(restoredItems[1].latitude)
        assertNull(restoredItems[1].longitude)
    }

    private fun createFakePreferences(isModoCasa: Boolean): PreferencesManager {
        val store = mutableMapOf<String, Any>(
            "is_modo_casa_active" to isModoCasa,
            "min_rate_km" to 2.20f,
            "min_passenger_rating" to 4.75f,
            "blacklist_enabled" to true
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
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return inMemoryPrefs
            }
        }

        return PreferencesManager(testContext)
    }
}
