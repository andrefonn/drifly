package com.drifly.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
        val prefs = PreferencesManager(applicationContext)
        // Se o monitoramento estiver desligado, não deve haver nenhuma notificação ativa ou residual
        if (!prefs.isMonitoringActive) {
            return Result.success()
        }

        // Garante que não existam 2 notificações simultâneas ativas na barra.
        // Se o serviço de monitoramento estiver rodando (com notificação contínua na barra),
        // não exibimos notificação avulsa de suporte.
        if (MonitoringService.isRunning()) {
            return Result.success()
        }

        showSupportNotification(applicationContext)
        return Result.success()
    }

    private fun showSupportNotification(context: Context) {
        val channelId = "drifly_support_channel"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Engajamento e Apoio ao DriFly",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Lembretes para apoiar e fortalecer o projeto independente do DriFly"
            }
            manager.createNotificationChannel(channel)
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
    }

    companion object {
        const val NOTIFICATION_ID = 3003
        const val WORK_NAME_8H = "drifly_support_reminder_8h"
        const val WORK_NAME_15H = "drifly_support_reminder_15h"
        const val WORK_NAME_20H = "drifly_support_reminder_20h"

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
