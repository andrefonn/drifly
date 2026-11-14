package com.drifly.app

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.text.Normalizer

/**
 * Suíte de Testes Adversariais Empíricos do Challenger (Milestone 2).
 * Foca em estressar casos de borda, robustez contra inputs hostis ou malformados,
 * limites milimétricos de Haversine e bloqueio irrestrito de auto-aceite.
 */
class BlacklistAdversarialChallengeTest {

    private fun createFakePreferences(isModoCasa: Boolean = false): PreferencesManager {
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

    // -------------------------------------------------------------
    // 1. Stress-test normalizeText com strings adversariais
    // -------------------------------------------------------------

    @Test
    fun testNormalizeTextDiacriticsAndDecomposedForms() {
        // Formas canônicas pré-compostas (NFC) vs Decompostas (NFD)
        val nfc = "São José do Rio Preto - Chácara Santo Antônio"
        val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)

        val normNfc = BlacklistManager.normalizeText(nfc)
        val normNfd = BlacklistManager.normalizeText(nfd)

        assertEquals("Ambas formas (NFC e NFD) devem normalizar para o mesmo texto sem diacríticos", normNfc, normNfd)
        assertEquals("sao jose do rio preto chacara santo antonio", normNfc)

        // Todos os acentos e cedilhas comuns da língua portuguesa em maiúsculas e minúsculas
        val allAccents = "ÁÀÃÂÉÈÊÍÌÎÓÒÕÔÚÙÛÜÇ áàãâéèêíìîóòõôúùûüç"
        val normAccents = BlacklistManager.normalizeText(allAccents)
        val expectedAccents = "aaaaeeeiiioooouuuuc aaaaeeeiiioooouuuuc"
        assertEquals(expectedAccents, normAccents)
    }

    @Test
    fun testNormalizeTextAdversarialEmojisSpacesAndSymbols() {
        // Emojis, caracteres não-ASCII e múltiplos separadores
        val hostileInput = "  🚨\t\n  Av.   dos  Ipês  🏡 \uD83D\uDE97  ---  Cidade   Jardim  ### (Parauapebas / PA)!!! \r\n  "
        val normalized = BlacklistManager.normalizeText(hostileInput)
        assertEquals("av dos ipes cidade jardim parauapebas pa", normalized)

        // Apenas pontuação e emojis
        val emptyPunctuation = "!@#$%^&*()_+-=[]{}|;':\",./<>?~` 🚨🚗"
        val normEmpty = BlacklistManager.normalizeText(emptyPunctuation)
        assertEquals("", normEmpty)

        // Strings vazias ou só com espaços
        assertEquals("", BlacklistManager.normalizeText(""))
        assertEquals("", BlacklistManager.normalizeText("     \t\n\r  "))
    }

    // -------------------------------------------------------------
    // 2. Stress-test matchesBlacklist com variações adversariais
    // -------------------------------------------------------------

    @Test
    fun testMatchesBlacklistAdversarialVariations() {
        val item = BlacklistItem(
            id = 101L,
            rawTerm = "Cidade Jardim",
            normalizedTerm = BlacklistManager.normalizeText("Cidade Jardim")
        )
        val blacklist = listOf(item)

        // Caso 1: Variação caótica de Maiúsculas/Minúsculas
        val matchCase = BlacklistManager.matchesBlacklist(
            "Rua 14, cIdAdE jArDiM, Parauapebas",
            blacklist
        )
        assertNotNull("Deve casar variação de case", matchCase)
        assertEquals(101L, matchCase?.id)

        // Caso 2: Endereço com diacríticos estranhos e emojis misturados
        val matchDiacritics = BlacklistManager.matchesBlacklist(
            "Av. dos Ipês 📍, Cídadê Jârdîm 🏡 - Parauapebas",
            blacklist
        )
        assertNotNull("Deve casar endereço com acentos adversariais e emojis", matchDiacritics)

        // Caso 3: Endereço com pontuação extrema separando os termos
        val matchPunctuation = BlacklistManager.matchesBlacklist(
            "Rua Quinze... (Cidade-Jardim) / Parauapebas!!!",
            blacklist
        )
        assertNotNull("Deve casar mesmo com pontuação entre palavras", matchPunctuation)

        // Caso 4: Blacklist item com normalizedTerm vazio inicial (deve auto-recuperar do rawTerm)
        val unnormalizedItem = BlacklistItem(
            id = 102L,
            rawTerm = "Bairro União",
            normalizedTerm = "" // vazio de propósito
        )
        val matchUnnorm = BlacklistManager.matchesBlacklist(
            "Rua A, Bairro Uniao",
            listOf(unnormalizedItem)
        )
        assertNotNull("Deve auto-normalizar rawTerm quando normalizedTerm estiver em branco", matchUnnorm)
        assertEquals(102L, matchUnnorm?.id)

        // Caso 5: Endereço vazio ou nulo não deve casar
        assertNull(BlacklistManager.matchesBlacklist(null, blacklist))
        assertNull(BlacklistManager.matchesBlacklist("", blacklist))
        assertNull(BlacklistManager.matchesBlacklist("   \t  ", blacklist))
        assertNull(BlacklistManager.matchesBlacklist("🚗 🚨 ⛔", blacklist))

        // Caso 6: Item vazio na blacklist não deve casar com nada nem lançar exceção
        val emptyItem = BlacklistItem(id = 999L, rawTerm = "   ", normalizedTerm = "")
        assertNull(BlacklistManager.matchesBlacklist("Rua Qualquer, Centro", listOf(emptyItem)))
    }

    // -------------------------------------------------------------
    // 3. Teste Limítrofe de Haversine: 1.49 km vs 1.51 km
    // -------------------------------------------------------------

    @Test
    fun testHaversineDistanceBoundary149Vs151() {
        // Ponto de referência: Coordenada de teste (0.0, 0.0)
        // Na fórmula de Haversine:
        // Distância = EARTH_RADIUS_KM * dAngle_radians
        // Para EARTH_RADIUS_KM = 6371.0:
        // deltaLat_graus = (distanciaKm / 6371.0) * (180.0 / Math.PI)
        val baseLat = -26.3500
        val baseLon = -48.8300
        val itemGeo = BlacklistItem(
            id = 201L,
            rawTerm = "Ponto Geográfico Teste",
            normalizedTerm = "ponto geografico teste",
            latitude = baseLat,
            longitude = baseLon
        )
        val blacklist = listOf(itemGeo)

        // Cálculo exato de deltaLat para 1.49 km:
        val deltaDeg149 = (1.49 / 6371.0) * (180.0 / Math.PI)
        val lat149 = baseLat + deltaDeg149
        val dist149Calculated = ModoCasaHelper.calculateDistanceKm(baseLat, baseLon, lat149, baseLon)
        assertTrue("Distância calculada deve ser estritamente menor que 1.50 km", dist149Calculated < 1.50)
        assertEquals(1.49, dist149Calculated, 0.001)

        // Ponto a 1.49 km com endereço textual completamente irrelevante (garantindo que casa só por GPS)
        val match149 = BlacklistManager.matchesBlacklist(
            address = "Rua Sem Nome Totalmente Aleatoria",
            items = blacklist,
            addressCoords = Pair(lat149, baseLon)
        )
        assertNotNull("Ponto a 1.49 km DEVE CASAR com a lista negra (< 1.5 km)", match149)
        assertEquals(201L, match149?.id)

        // Cálculo exato de deltaLat para 1.51 km:
        val deltaDeg151 = (1.51 / 6371.0) * (180.0 / Math.PI)
        val lat151 = baseLat + deltaDeg151
        val dist151Calculated = ModoCasaHelper.calculateDistanceKm(baseLat, baseLon, lat151, baseLon)
        assertTrue("Distância calculada deve ser estritamente maior que 1.50 km", dist151Calculated > 1.50)
        assertEquals(1.51, dist151Calculated, 0.001)

        // Ponto a 1.51 km com endereço textual irrelevante (NÃO DEVE CASAR)
        val match151 = BlacklistManager.matchesBlacklist(
            address = "Rua Sem Nome Totalmente Aleatoria",
            items = blacklist,
            addressCoords = Pair(lat151, baseLon)
        )
        assertNull("Ponto a 1.51 km NÃO DEVE CASAR com a lista negra (>= 1.5 km)", match151)
    }

    // -------------------------------------------------------------
    // 4. Teste de Permutações de Embarque vs Destino
    // -------------------------------------------------------------

    @Test
    fun testPickupVsDestinationMatchPermutations() {
        val itemA = BlacklistItem(id = 1L, rawTerm = "Itinga", normalizedTerm = "itinga")
        val itemB = BlacklistItem(id = 2L, rawTerm = "Paranaguamirim", normalizedTerm = "paranaguamirim")
        val blacklist = listOf(itemA, itemB)

        val baseOffer = RideOffer(
            platform = "UBER",
            price = 30.0,
            distanceKm = 8.0,
            durationMinutes = 15,
            ratePerKm = 3.75,
            ratePerHour = 120.0
        )

        // Permutação 1: Apenas EMBARQUE na lista negra
        val offerPickupOnly = baseOffer.copy(
            pickupAddress = "Rua das Palmeiras, Itinga",
            destinationAddress = "Avenida Coronel Procopio, Centro"
        )
        val evalPickupOnly = BlacklistManager.evaluateOffer(offerPickupOnly, blacklist)
        assertTrue("Apenas embarque deve bloquear", evalPickupOnly.isBlacklistBlocked)
        assertEquals("⛔ Embarque em Lista Negra: Itinga", evalPickupOnly.blacklistAlert)

        // Permutação 2: Apenas DESTINO na lista negra
        val offerDestOnly = baseOffer.copy(
            pickupAddress = "Avenida Coronel Procopio, Centro",
            destinationAddress = "Rua das Palmeiras, Itinga"
        )
        val evalDestOnly = BlacklistManager.evaluateOffer(offerDestOnly, blacklist)
        assertTrue("Apenas destino deve bloquear", evalDestOnly.isBlacklistBlocked)
        assertEquals("⛔ Destino em Lista Negra: Itinga", evalDestOnly.blacklistAlert)

        // Permutação 3: Ambos na lista negra no MESMO bairro
        val offerBothSame = baseOffer.copy(
            pickupAddress = "Rua Adolfo Soares, Itinga",
            destinationAddress = "Rua Santa Catarina, Itinga"
        )
        val evalBothSame = BlacklistManager.evaluateOffer(offerBothSame, blacklist)
        assertTrue("Ambos mesmo bairro deve bloquear", evalBothSame.isBlacklistBlocked)
        assertEquals("⛔ Embarque e Destino em Lista Negra (Itinga)", evalBothSame.blacklistAlert)

        // Permutação 4: Ambos na lista negra em BAIRROS DIFERENTES
        val offerBothDiff = baseOffer.copy(
            pickupAddress = "Rua Adolfo Soares, Itinga",
            destinationAddress = "Rua Monsenhor Gercino, Paranaguamirim"
        )
        val evalBothDiff = BlacklistManager.evaluateOffer(offerBothDiff, blacklist)
        assertTrue("Ambos bairros diferentes deve bloquear", evalBothDiff.isBlacklistBlocked)
        assertEquals("⛔ Embarque e Destino em Lista Negra (Itinga, Paranaguamirim)", evalBothDiff.blacklistAlert)

        // Permutação 5: Nenhum na lista negra
        val offerNone = baseOffer.copy(
            pickupAddress = "Avenida Coronel Procopio, Centro",
            destinationAddress = "Rua Quinze de Novembro, America"
        )
        val evalNone = BlacklistManager.evaluateOffer(offerNone, blacklist)
        assertFalse("Nenhum bairro bloqueado não deve bloquear", evalNone.isBlacklistBlocked)
        assertNull(evalNone.blacklistAlert)
    }

    // -------------------------------------------------------------
    // 5. Verificação de Bloqueio 100% de Auto-Accept Sob Todas as Permutações
    // -------------------------------------------------------------

    @Test
    fun testAutoAcceptStrictlyBlockedUnderAllPermutations() {
        val itemA = BlacklistItem(id = 1L, rawTerm = "Itinga", normalizedTerm = "itinga")
        val itemB = BlacklistItem(id = 2L, rawTerm = "Paranaguamirim", normalizedTerm = "paranaguamirim")
        val blacklist = listOf(itemA, itemB)

        // Cria preferências com Modo Casa ATIVO, tarifa mínima baixa (muito fácil de aceitar)
        val prefsModoCasaActive = createFakePreferences(isModoCasa = true)
        val prefsModoCasaInactive = createFakePreferences(isModoCasa = false)

        val offersToTest = listOf(
            // Embarque na lista
            RideOffer(
                platform = "UBER", price = 50.0, distanceKm = 5.0, durationMinutes = 10,
                ratePerKm = 10.0, ratePerHour = 300.0, isHeadingHome = true, homeDistanceDeltaKm = -10.0,
                pickupAddress = "Rua Adolfo Soares, Itinga",
                destinationAddress = "Rua do Centro"
            ),
            // Destino na lista
            RideOffer(
                platform = "99", price = 80.0, distanceKm = 8.0, durationMinutes = 12,
                ratePerKm = 10.0, ratePerHour = 400.0, isHeadingHome = true, homeDistanceDeltaKm = -15.0,
                pickupAddress = "Rua do Centro",
                destinationAddress = "Rua Adolfo Soares, Itinga"
            ),
            // Ambos mesmo bairro
            RideOffer(
                platform = "UBER", price = 40.0, distanceKm = 4.0, durationMinutes = 8,
                ratePerKm = 10.0, ratePerHour = 300.0, isHeadingHome = true, homeDistanceDeltaKm = -8.0,
                pickupAddress = "Rua A, Itinga",
                destinationAddress = "Rua B, Itinga"
            ),
            // Ambos bairros diferentes
            RideOffer(
                platform = "99", price = 60.0, distanceKm = 6.0, durationMinutes = 10,
                ratePerKm = 10.0, ratePerHour = 360.0, isHeadingHome = true, homeDistanceDeltaKm = -12.0,
                pickupAddress = "Rua Adolfo Soares, Itinga",
                destinationAddress = "Rua Monsenhor Gercino, Paranaguamirim"
            )
        )

        for ((idx, offer) in offersToTest.withIndex()) {
            val evaluated = BlacklistManager.evaluateOffer(offer, blacklist)
            assertTrue("Oferta permutação $idx deve ter isBlacklistBlocked=true", evaluated.isBlacklistBlocked)
            assertNotNull("Oferta permutação $idx deve ter blacklistAlert preenchido", evaluated.blacklistAlert)

            // 1. Teste com Modo Casa ATIVO (onde deltaKm é altamente favorável, ex: -10 km para casa)
            val goodWithModoCasa = BubbleOverlayService.isOfferGood(evaluated, prefsModoCasaActive)
            assertFalse(
                "Permutação $idx: isOfferGood DEVE ser FALSE mesmo com Modo Casa altamente favorável!",
                goodWithModoCasa
            )

            // 2. Teste com Modo Casa INATIVO (onde ratePerKm = 10.0 R$/km ultrapassa em muito minRateKm = 2.20)
            val goodStandard = BubbleOverlayService.isOfferGood(evaluated, prefsModoCasaInactive)
            assertFalse(
                "Permutação $idx: isOfferGood DEVE ser FALSE mesmo com rentabilidade astronômica (10 R$/km)!",
                goodStandard
            )
        }
    }
}
