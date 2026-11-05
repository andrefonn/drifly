package com.rotacore.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

enum class DeviceBrand {
    XIAOMI,
    SAMSUNG,
    MOTOROLA,
    GENERIC
}

object OemCompatibilityHelper {

    fun detectBrand(manufacturer: String?): DeviceBrand {
        val lower = manufacturer?.lowercase() ?: ""
        return when {
            lower.contains("xiaomi") || lower.contains("redmi") || lower.contains("poco") -> DeviceBrand.XIAOMI
            lower.contains("samsung") -> DeviceBrand.SAMSUNG
            lower.contains("motorola") || lower.contains("lenovo") -> DeviceBrand.MOTOROLA
            else -> DeviceBrand.GENERIC
        }
    }

    val currentBrand: DeviceBrand
        get() = detectBrand(try { Build.MANUFACTURER } catch (_: Throwable) { null })

    val deviceModelName: String
        get() {
            return try {
                val man = Build.MANUFACTURER?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } ?: "Android"
                val mod = Build.MODEL ?: "Aparelho"
                if (mod.startsWith(man, ignoreCase = true)) mod else "$man $mod"
            } catch (_: Throwable) {
                "Smartphone Android"
            }
        }

    val androidVersionSummary: String
        get() {
            return try {
                val rel = Build.VERSION.RELEASE ?: "12"
                val sdk = Build.VERSION.SDK_INT
                "Android $rel (API $sdk)"
            } catch (_: Throwable) {
                "Android (Versão Atual)"
            }
        }

    val isRestrictedSettingsPotential: Boolean
        get() {
            return try {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU // Android 13+
            } catch (_: Throwable) {
                false
            }
        }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        return try {
            val packageNames = NotificationManagerCompat.getEnabledListenerPackages(context)
            if (packageNames.contains(context.packageName)) return true

            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            flat.contains(context.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } else {
                true
            }
        } catch (_: Throwable) {
            true
        }
    }

    fun openAppDetailsSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun openNotificationListenerSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun openSamsungDeviceCare(context: Context): Boolean {
        val intents = listOf(
            // Samsung Device Care / Smart Manager Battery Activities
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent("com.samsung.android.sm.ACTION_BATTERY").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            Intent("com.samsung.android.lool.ACTION_BATTERY").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }

        return openBatteryOptimizationSettings(context)
    }

    fun openAutostartSettings(context: Context): Boolean {
        val intents = listOf(
            // Xiaomi / MIUI / HyperOS
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").apply { addCategory(Intent.CATEGORY_DEFAULT) },
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.securityadd.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
            // Samsung Smart Manager
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            // Huawei
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            // Oppo
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            // Vivo
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (ignored: Exception) {}
        }

        // Fallback to Application Details
        return openAppDetailsSettings(context)
    }

    fun openMiuiBackgroundPopupSettings(context: Context): Boolean {
        val intents = listOf(
            // MIUI / HyperOS direct app permissions editor (Popup in background)
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (ignored: Exception) {}
        }

        return openAppDetailsSettings(context)
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        // Se for Samsung, tenta abrir Device Care primeiro se ainda não tentado
        if (currentBrand == DeviceBrand.SAMSUNG) {
            val samsungIntents = listOf(
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"))
            )
            for (intent in samsungIntents) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                } catch (_: Exception) {}
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            }
        } catch (ignored: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (ignored2: Exception) {}
        }
        return openAppDetailsSettings(context)
    }

    fun getBrandOptimizationTip(brand: DeviceBrand = currentBrand): String {
        return when (brand) {
            DeviceBrand.XIAOMI ->
                "Xiaomi / HyperOS detectado. É essencial ativar 'Início Automático' e 'Janelas Pop-up em Segundo Plano' no SecurityCenter para evitar que o sistema encerre o monitoramento."
            DeviceBrand.SAMSUNG ->
                "Samsung One UI detectado. Configure o DriFly como aplicativo 'Sem Restrições' no Gerenciador Inteligente / Assistência do Aparelho para não adormecer."
            DeviceBrand.MOTOROLA ->
                "Motorola detectado. Desative a 'Otimização de Bateria' para garantir leitura em tempo real e pop-up instantâneo de corridas."
            DeviceBrand.GENERIC ->
                "Configure a bateria do DriFly como 'Não Otimizada / Sem Restrições' nas configurações do sistema para operação contínua."
        }
    }
}
