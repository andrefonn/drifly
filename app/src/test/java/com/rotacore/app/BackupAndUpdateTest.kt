package com.rotacore.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupAndUpdateTest {

    @Test
    fun testBackupJsonStructureValidation() {
        val root = JSONObject().apply {
            put("app", "DriFly")
            put("backup_version", 1)
            put("timestamp", 1730000000000L)
            put("preferences", JSONObject().apply {
                put("fuel_price", 5.80)
                put("min_rate_km", 2.20)
                put("goal_target_amount", 300.0)
            })
            put("rides", JSONArray().apply {
                put(JSONObject().apply {
                    put("platform", "UBER")
                    put("gross_price", 35.0)
                    put("distance_km", 10.0)
                    put("net_profit", 27.2)
                    put("timestamp", 1730000000000L)
                })
            })
            put("expenses", JSONArray().apply {
                put(JSONObject().apply {
                    put("category", "Combustível")
                    put("amount", 50.0)
                    put("description", "Gasolina Comum")
                    put("timestamp", 1730000000000L)
                })
            })
        }

        assertEquals("DriFly", root.getString("app"))
        assertTrue(root.has("rides"))
        assertTrue(root.has("expenses"))
        assertTrue(root.has("preferences"))

        val rides = root.getJSONArray("rides")
        assertEquals(1, rides.length())
        val ride0 = rides.getJSONObject(0)
        assertEquals("UBER", ride0.getString("platform"))
        assertEquals(35.0, ride0.getDouble("gross_price"), 0.001)

        val expenses = root.getJSONArray("expenses")
        assertEquals(1, expenses.length())
        val exp0 = expenses.getJSONObject(0)
        assertEquals(50.0, exp0.getDouble("amount"), 0.001)
    }

    @Test
    fun testVersionComparisonLogic() {
        assertTrue(UpdateManager.isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(UpdateManager.isNewerVersion("v1.1.0", "1.0.9"))
        assertTrue(UpdateManager.isNewerVersion("v2.0.0", "1.9.9"))
        assertTrue(UpdateManager.isNewerVersion("2.1", "2.0.5"))
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(UpdateManager.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "1.0.1"))
        assertFalse(UpdateManager.isNewerVersion("0.9.5", "1.0.0"))
    }
}
