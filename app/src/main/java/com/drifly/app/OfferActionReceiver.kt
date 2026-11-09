package com.drifly.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receptor para ações rápidas a partir da notificação de oferta.
 * Permite ao motorista descartar/remover uma oferta do extrato financeiro
 * diretamente pela barra de notificações com 1 toque.
 */
class OfferActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action == ACTION_DISCARD_OFFER) {
            val rideId = intent.getLongExtra(EXTRA_DISCARD_RIDE_ID, -1L)
            val notifId = intent.getIntExtra(EXTRA_DISCARD_NOTIF_ID, -1)

            if (rideId > 0) {
                CoroutineScope(Dispatchers.IO).launch {
                    val db = DatabaseHelper(context)
                    db.deleteRide(rideId)
                    context.sendBroadcast(Intent(RotaAccessibilityService.ACTION_RIDE_UPDATED).apply {
                        setPackage(context.packageName)
                    })
                }
            }

            if (notifId > 0) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(notifId)
            }
        }
    }

    companion object {
        const val ACTION_DISCARD_OFFER = "com.drifly.app.ACTION_DISCARD_OFFER"
        const val EXTRA_DISCARD_RIDE_ID = "EXTRA_DISCARD_RIDE_ID"
        const val EXTRA_DISCARD_NOTIF_ID = "EXTRA_DISCARD_NOTIF_ID"
    }
}
