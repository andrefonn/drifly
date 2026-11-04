package com.rotacore.app

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

        fun scheduleAllSupportReminders(context: Context) {
            scheduleReminderForHour(context, 8, "drifly_support_reminder_8h")
            scheduleReminderForHour(context, 15, "drifly_support_reminder_15h")
            scheduleReminderForHour(context, 20, "drifly_support_reminder_20h")
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
