package com.rotacore.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import java.util.Locale

class BubbleOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private lateinit var prefs: PreferencesManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val platform = intent.getStringExtra("EXTRA_PLATFORM") ?: "CORRIDA"
        val price = intent.getDoubleExtra("EXTRA_PRICE", 0.0)
        val distance = intent.getDoubleExtra("EXTRA_DISTANCE", 0.0)
        val duration = intent.getIntExtra("EXTRA_DURATION", 0)
        val rate = intent.getDoubleExtra("EXTRA_RATE", 0.0)
        val net = intent.getDoubleExtra("EXTRA_NET", 0.0)
        val cost = intent.getDoubleExtra("EXTRA_COST", 0.0)
        val rating = if (intent.hasExtra("EXTRA_RATING")) intent.getFloatExtra("EXTRA_RATING", 0f) else null
        val isLow = intent.getBooleanExtra("EXTRA_IS_LOW", false)

        showOrUpdateOverlay(platform, price, distance, duration, rate, net, cost, rating, isLow)
        return START_NOT_STICKY
    }

    private fun showOrUpdateOverlay(
        platform: String,
        price: Double,
        distance: Double,
        duration: Int,
        rate: Double,
        net: Double,
        cost: Double,
        rating: Float?,
        isLow: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val isFirstLaunch = (bubbleView == null)

        if (isFirstLaunch) {
            val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_DriFly)
            val inflater = LayoutInflater.from(themedContext)
            val root = inflater.inflate(R.layout.overlay_bubble, null)
            bubbleView = root

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val (gravityVal, initialY) = when (prefs.overlayPosition) {
                "BOTTOM" -> Pair(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 60)
                "CENTER" -> Pair(Gravity.CENTER, 0)
                else -> Pair(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 60)
            }

            val displayWidth = resources.displayMetrics.widthPixels
            val cardWidth = (displayWidth * 0.94f).toInt()

            val params = WindowManager.LayoutParams(
                cardWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = gravityVal
                x = 0
                y = initialY
            }
            layoutParams = params

            // Listener de toque para fechar imediatamente
            val closeBtn = root.findViewById<View>(R.id.btnCloseBubble)
            closeBtn?.setOnClickListener {
                animateAndRemove()
            }

            setupDragListener(root, params)

            try {
                windowManager?.addView(root, params)

                // Animação de entrada suave com Property Animator (sem depender de animação XML de parent)
                root.alpha = 0f
                val entryOffsetY = if (prefs.overlayPosition == "BOTTOM") 120f else -120f
                root.translationY = entryOffsetY
                root.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(280)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } catch (e: Exception) {
                e.printStackTrace()
                bubbleView = null
                stopSelf()
                return
            }
        }

        bubbleView?.let { view ->
            val tvPlatform = view.findViewById<TextView>(R.id.tvPlatformBadge)
            val tvRentabilidade = view.findViewById<TextView>(R.id.tvRentabilidade)
            val tvNetProfit = view.findViewById<TextView>(R.id.tvNetProfit)
            val tvFuelCost = view.findViewById<TextView>(R.id.tvFuelCost)
            val tvPreco = view.findViewById<TextView>(R.id.tvPreco)
            val tvDistancia = view.findViewById<TextView>(R.id.tvDistancia)
            val tvTempo = view.findViewById<TextView>(R.id.tvTempo)
            val layoutAlert = view.findViewById<View>(R.id.layoutRatingAlert)
            val tvRatingWarning = view.findViewById<TextView>(R.id.tvRatingWarning)

            val ptBr = Locale("pt", "BR")
            tvPlatform.text = platform
            tvRentabilidade.text = String.format(ptBr, "R$ %.2f /km", rate)
            tvNetProfit.text = String.format(ptBr, "R$ %.2f", net)
            tvFuelCost.text = String.format(ptBr, "- R$ %.2f", cost)
            tvPreco.text = String.format(ptBr, "Bruto: R$ %.2f", price)
            tvDistancia.text = String.format(ptBr, "%.1f km", distance)
            tvTempo.text = if (duration > 0) "$duration min" else "-- min"

            if (rate >= 2.50) {
                tvRentabilidade.setTextColor(0xFF31D477.toInt())
            } else if (rate >= 2.00) {
                tvRentabilidade.setTextColor(0xFFFBBF24.toInt())
            } else {
                tvRentabilidade.setTextColor(0xFFFF454D.toInt())
            }

            if (isLow && rating != null) {
                layoutAlert.visibility = View.VISIBLE
                tvRatingWarning.text = String.format(ptBr, "⚠️ PASSAGEIRO COM NOTA BAIXA: %.2f (Aceite Bloqueado)", rating)
            } else {
                layoutAlert.visibility = View.GONE
            }
        }
    }

    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false // Permite que filhos (como botão fechar) recebam o clique
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(view, params)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun animateAndRemove() {
        val target = bubbleView ?: return
        val exitY = if (prefs.overlayPosition == "BOTTOM") 120f else -120f
        target.animate()
            .alpha(0f)
            .translationY(exitY)
            .setDuration(220)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                removeBubbleImmediate()
                stopSelf()
            }
            .start()
    }

    private fun removeBubbleImmediate() {
        if (bubbleView != null) {
            try {
                windowManager?.removeView(bubbleView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            bubbleView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitor DriFly",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val notification: Notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DriFly Ativo")
                .setContentText("Calculando rentabilidade e lucro líquido")
                .setSmallIcon(R.drawable.ic_drifly_logo)
                .build()

            // android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE só foi adicionado na API 34 (Android 14)
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
        removeBubbleImmediate()
    }

    companion object {
        private const val CHANNEL_ID = "drifly_bubble_channel"
        private const val NOTIFICATION_ID = 2002

        fun showOffer(context: Context, offer: RideOffer) {
            val intent = Intent(context, BubbleOverlayService::class.java).apply {
                putExtra("EXTRA_PLATFORM", offer.platform)
                putExtra("EXTRA_PRICE", offer.price)
                putExtra("EXTRA_DISTANCE", offer.distanceKm)
                putExtra("EXTRA_DURATION", offer.durationMinutes)
                putExtra("EXTRA_RATE", offer.ratePerKm)
                putExtra("EXTRA_NET", offer.netProfit)
                putExtra("EXTRA_COST", offer.estimatedFuelCost)
                putExtra("EXTRA_IS_LOW", offer.isLowRating)
                if (offer.passengerRating != null) {
                    putExtra("EXTRA_RATING", offer.passengerRating)
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
