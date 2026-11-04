package com.rotacore.app

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("drifly_preferences", Context.MODE_PRIVATE)

    // Configurações de Veículo & Combustível
    var fuelPrice: Float
        get() = prefs.getFloat("fuel_price", 5.80f)
        set(value) = prefs.edit().putFloat("fuel_price", value).apply()

    var fuelConsumption: Float
        get() = prefs.getFloat("fuel_consumption", 10.0f)
        set(value) = prefs.edit().putFloat("fuel_consumption", value).apply()

    var maintenanceCostPerKm: Float
        get() = prefs.getFloat("maintenance_cost_km", 0.20f)
        set(value) = prefs.edit().putFloat("maintenance_cost_km", value).apply()

    // Regras de Automação & Filtros
    var autoAcceptEnabled: Boolean
        get() = prefs.getBoolean("auto_accept_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_accept_enabled", value).apply()

    var autoDeclineEnabled: Boolean
        get() = prefs.getBoolean("auto_decline_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_decline_enabled", value).apply()

    var minRatePerKm: Float
        get() = prefs.getFloat("min_rate_km", 2.20f)
        set(value) = prefs.edit().putFloat("min_rate_km", value).apply()

    var maxDistanceKm: Float
        get() = prefs.getFloat("max_distance_km", 12.0f)
        set(value) = prefs.edit().putFloat("max_distance_km", value).apply()

    var minPassengerRating: Float
        get() = prefs.getFloat("min_passenger_rating", 4.75f)
        set(value) = prefs.edit().putFloat("min_passenger_rating", value).apply()

    var isMotorcycleMode: Boolean
        get() = prefs.getBoolean("is_motorcycle_mode", false)
        set(value) = prefs.edit().putBoolean("is_motorcycle_mode", value).apply()

    // Posição de Exibição da Bolha: "TOP" (Topo), "CENTER" (Meio) ou "BOTTOM" (Base)
    var overlayPosition: String
        get() = prefs.getString("overlay_position", "TOP") ?: "TOP"
        set(value) = prefs.edit().putString("overlay_position", value).apply()

    // Metas de Ganho
    var goalType: String // "DAILY", "WEEKLY", "MONTHLY"
        get() = prefs.getString("goal_type", "DAILY") ?: "DAILY"
        set(value) = prefs.edit().putString("goal_type", value).apply()

    var goalTargetAmount: Float
        get() = prefs.getFloat("goal_target_amount", 250.0f)
        set(value) = prefs.edit().putFloat("goal_target_amount", value).apply()

    // Estado do Monitoramento Contínuo
    var isMonitoringActive: Boolean
        get() = prefs.getBoolean("is_monitoring_active", false)
        set(value) = prefs.edit().putBoolean("is_monitoring_active", value).apply()

    // Metadados de Backup
    var lastBackupTimestamp: Long
        get() = prefs.getLong("last_backup_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_backup_timestamp", value).apply()

    var lastBackupPath: String
        get() = prefs.getString("last_backup_path", "") ?: ""
        set(value) = prefs.edit().putString("last_backup_path", value).apply()

    // Custo Total por KM = (Preço do Combustível / Consumo) + Custo Manutenção
    val costPerKm: Double
        get() {
            val cons = if (fuelConsumption > 0f) fuelConsumption else 10f
            return ((fuelPrice / cons) + maintenanceCostPerKm).toDouble()
        }
}
