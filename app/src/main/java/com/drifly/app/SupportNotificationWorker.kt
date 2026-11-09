package com.drifly.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SupportNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        postReminderIfAllowed(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "SupportWorker"
        const val NOTIFICATION_CHANNEL_ID = "drifly_support_channel"
        const val NOTIFICATION_ID = 3003
        const val WORK_NAME_8H = "drifly_support_reminder_8h"
        const val WORK_NAME_15H = "drifly_support_reminder_15h"
        const val WORK_NAME_20H = "drifly_support_reminder_20h"

        /**
         * Exibe o lembrete de apoio (3x ao dia), se o monitoramento estiver ativo.
         *
         * Antes havia aqui um bloqueio do tipo "se o serviço de monitoramento está rodando, não
         * notifica". Como o serviço fica ativo o turno inteiro, o lembrete simplesmente nunca
         * aparecia. A notificação de apoio usa ID próprio (3003) e convive com a notificação
         * contínua do monitoramento (1001) sem conflito.
         */
        fun postReminderIfAllowed(context: Context): Boolean {
            val prefs = PreferencesManager(context)
            Log.i(TAG, "lembrete de apoio: monitoring=${prefs.isMonitoringActive}")
            if (!prefs.isMonitoringActive) return false

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false

            val channelId = NOTIFICATION_CHANNEL_ID
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Engajamento e Apoio ao DriFly",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Lembretes para apoiar e fortalecer o projeto independente do DriFly"
                    }
                )
            }

            val openSupportIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_TAB, "SUPPORT")
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                2001,
                openSupportIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle("Está gostando do DriFly? Apoie esse Projeto!")
                .setContentText("O DriFly é 100% gratuito e mantido pela comunidade. Toque para contribuir!")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("O DriFly é 100% gratuito e mantido por doações da comunidade para continuar sem propagandas e sem mensalidades. Toque para ver como apoiar!")
                )
                .setSmallIcon(R.drawable.ic_drifly_logo)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "lembrete de apoio publicado")
            return true
        }

        fun scheduleAllSupportReminders(context: Context) {
            scheduleReminderForHour(context, 8, WORK_NAME_8H)
            scheduleReminderForHour(context, 15, WORK_NAME_15H)
            scheduleReminderForHour(context, 20, WORK_NAME_20H)
        }

        fun cancelAllSupportReminders(context: Context) {
            try {
                val wm = WorkManager.getInstance(context)
                wm.cancelUniqueWork(WORK_NAME_8H)
                wm.cancelUniqueWork(WORK_NAME_15H)
                wm.cancelUniqueWork(WORK_NAME_20H)
            } catch (e: Exception) {
                // Falha silenciosa de WorkManager se o contexto estiver destruído
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID)
        }

        private fun scheduleReminderForHour(context: Context, targetHour: Int, uniqueWorkName: String) {
            val now = Calendar.getInstance()
            val targetCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, targetHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val initialDelay = targetCal.timeInMillis - now.timeInMillis

            val work = PeriodicWorkRequestBuilder<SupportNotificationWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueWorkName,
                ExistingPeriodicWorkPolicy.KEEP,
                work
            )
        }
    }
}
