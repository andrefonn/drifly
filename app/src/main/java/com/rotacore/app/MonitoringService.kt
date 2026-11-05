package com.rotacore.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MonitoringService : Service() {

    private lateinit var prefs: PreferencesManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PreferencesManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_MONITORING) {
            stopMonitoringInternal()
            return START_NOT_STICKY
        }

        if (intent == null && !prefs.isMonitoringActive) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Inicia o serviço e fixa a notificação contínua
        prefs.isMonitoringActive = true
        notifyStateChanged(true)

        val notification = buildOngoingNotification()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    fun stopMonitoringInternal() {
        prefs.isMonitoringActive = false
        notifyStateChanged(false)

        // Encerra imediatamente qualquer overlay flutuante ativo
        BubbleOverlayService.stop(this)

        // Cancela qualquer agendamento de notificação avulsa de suporte
        SupportNotificationWorker.cancelAllSupportReminders(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        // Cancela explicitamente TODAS as notificações do app para não deixar nenhum resíduo na barra
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancelAll()
        manager?.cancel(NOTIFICATION_ID)
        manager?.cancel(BubbleOverlayService.NOTIFICATION_ID)
        manager?.cancel(SupportNotificationWorker.NOTIFICATION_ID)

        stopSelf()
    }

    private fun buildOngoingNotification(): Notification {
        // Ao clicar no corpo da notificação: Abre a tela principal do DriFly
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpenApp = PendingIntent.getActivity(
            this,
            1001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Ao clicar no botão da direita (Desligar): Dispara broadcast de desligamento imediato
        val stopIntent = Intent(this, MonitoringActionReceiver::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val pStopIntent = PendingIntent.getBroadcast(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DriFly Ativo • Monitorando Uber e 99")
            .setContentText("Calculando rentabilidade e lucro líquido em tempo real")
            .setSmallIcon(R.drawable.ic_drifly_logo)
            .setContentIntent(pOpenApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_power_off,
                "Desligar",
                pStopIntent
            )
            .build()
    }

    private fun notifyStateChanged(isActive: Boolean) {
        val broadcastIntent = Intent(ACTION_MONITORING_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_ACTIVE, isActive)
        }
        sendBroadcast(broadcastIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitoramento DriFly",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação contínua indicando que o DriFly está ativo monitorando ofertas de corrida."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Mesmo tirando o app dos recentes, mantém o monitoramento contínuo ativo
        if (prefs.isMonitoringActive) {
            val notification = buildOngoingNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        if (!prefs.isMonitoringActive) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancelAll()
            manager?.cancel(NOTIFICATION_ID)
            manager?.cancel(BubbleOverlayService.NOTIFICATION_ID)
            manager?.cancel(SupportNotificationWorker.NOTIFICATION_ID)
            BubbleOverlayService.stop(this)
            SupportNotificationWorker.cancelAllSupportReminders(this)
        }
    }

    companion object {
        const val CHANNEL_ID = "drifly_monitoring_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_MONITORING = "com.rotacore.app.ACTION_STOP_MONITORING"
        const val ACTION_MONITORING_CHANGED = "com.rotacore.app.ACTION_MONITORING_CHANGED"
        const val EXTRA_IS_ACTIVE = "EXTRA_IS_ACTIVE"

        private var instance: MonitoringService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            val intent = Intent(context, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val current = instance
            if (current != null) {
                current.stopMonitoringInternal()
            } else {
                val prefs = PreferencesManager(context)
                prefs.isMonitoringActive = false

                // Garante que o overlay flutuante também seja cancelado e fechado
                BubbleOverlayService.stop(context)
                SupportNotificationWorker.cancelAllSupportReminders(context)

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancelAll()
                manager?.cancel(NOTIFICATION_ID)
                manager?.cancel(BubbleOverlayService.NOTIFICATION_ID)
                manager?.cancel(SupportNotificationWorker.NOTIFICATION_ID)

                val broadcastIntent = Intent(ACTION_MONITORING_CHANGED).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_IS_ACTIVE, false)
                }
                context.sendBroadcast(broadcastIntent)
            }
        }
    }
}

class MonitoringActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MonitoringService.ACTION_STOP_MONITORING) {
            MonitoringService.stop(context)
            BubbleOverlayService.stop(context)
            SupportNotificationWorker.cancelAllSupportReminders(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancelAll()
            manager?.cancel(MonitoringService.NOTIFICATION_ID)
            manager?.cancel(BubbleOverlayService.NOTIFICATION_ID)
            manager?.cancel(SupportNotificationWorker.NOTIFICATION_ID)
        }
    }
}
