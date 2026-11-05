package com.rotacore.app

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
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

    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { animateAndRemove() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP_BUBBLE) {
            dismissAndStop()
            return START_NOT_STICKY
        }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w("BubbleOverlayService", "Permissão SYSTEM_ALERT_WINDOW não concedida.")
            dismissAndStop()
            return
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val cardWidth = if (screenWidth > 0) (screenWidth * 0.94f).toInt() else WindowManager.LayoutParams.MATCH_PARENT

        val statusBarResId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarResId > 0) resources.getDimensionPixelSize(statusBarResId) else (28 * displayMetrics.density).toInt()
        val marginY = (16 * displayMetrics.density).toInt()

        val (gravityVal, initialY) = when (prefs.overlayPosition) {
            "BOTTOM" -> Pair(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, (24 * displayMetrics.density).toInt())
            "CENTER" -> Pair(Gravity.CENTER, 0)
            else -> Pair(Gravity.TOP or Gravity.CENTER_HORIZONTAL, statusBarHeight + marginY)
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        @Suppress("DEPRECATION")
        val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON

        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            windowFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityVal
            x = 0
            y = initialY
            windowAnimations = android.R.style.Animation_Dialog
        }
        layoutParams = params

        var root = bubbleView
        val isFirstLaunch = (root == null || !root.isAttachedToWindow)

        if (isFirstLaunch) {
            dismissDirectOverlay()
            removeBubbleImmediate()
            val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_DriFly)
            val inflater = LayoutInflater.from(themedContext)
            root = inflater.inflate(R.layout.overlay_bubble, null)
            bubbleView = root

            // Botão Fechar imediato
            val closeBtn = root.findViewById<View>(R.id.btnCloseBubble)
            closeBtn?.setOnClickListener {
                animateAndRemove()
            }

            setupDragListener(root, params)

            // Garante visibilidade e opacidade imediatas (alpha = 1f) para nunca ficar invisível
            root.visibility = View.VISIBLE
            root.alpha = 1f
            root.translationY = 0f

            try {
                windowManager?.addView(root, params)

                // Animação de entrada suave no eixo Y sem zerar a opacidade
                val entryOffsetY = if (prefs.overlayPosition == "BOTTOM") 50f else -50f
                root.translationY = entryOffsetY
                root.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        root.translationY = 0f
                        root.alpha = 1f
                    }
                    .start()
            } catch (e: Exception) {
                Log.e("BubbleOverlayService", "Erro ao adicionar view no WindowManager", e)
                bubbleView = null
                dismissAndStop()
                return
            }
        } else {
            root?.let {
                it.visibility = View.VISIBLE
                it.alpha = 1f
                try {
                    windowManager?.updateViewLayout(it, params)
                } catch (e: Exception) {
                    Log.e("BubbleOverlayService", "Erro ao atualizar layoutParams no WindowManager", e)
                }
            }
        }

        root?.let { view ->
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

        // Configura temporizador de fechamento automático (20 segundos) para liberar tela e evitar residual
        resetAutoDismissTimer()
    }

    private fun resetAutoDismissTimer() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        autoDismissHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_DELAY_MS)
    }

    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetAutoDismissTimer()
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false // Permite que filhos (como botão fechar) recebam o clique se não arrastar
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            if (view.isAttachedToWindow) {
                                windowManager?.updateViewLayout(view, params)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    resetAutoDismissTimer()
                    isDragging
                }
                else -> false
            }
        }
    }

    private fun animateAndRemove() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        val target = bubbleView
        if (target == null) {
            dismissAndStop()
            return
        }
        val exitY = if (prefs.overlayPosition == "BOTTOM") 80f else -80f
        target.animate()
            .alpha(0f)
            .translationY(exitY)
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                dismissAndStop()
            }
            .start()
    }

    private fun removeBubbleImmediate() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        val view = bubbleView
        if (view != null) {
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeView(view)
                }
            } catch (e: Exception) {
                try {
                    windowManager?.removeViewImmediate(view)
                } catch (ignored: Exception) {}
            }
            bubbleView = null
        }
    }

    fun dismissAndStop() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        removeBubbleImmediate()
        dismissDirectOverlay()
        // Cancela qualquer notificação residual remanescente
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        removeBubbleImmediate()
        dismissDirectOverlay()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "drifly_bubble_channel"
        const val NOTIFICATION_ID = 2002
        const val ACTION_STOP_BUBBLE = "com.rotacore.app.ACTION_STOP_BUBBLE"
        const val AUTO_DISMISS_DELAY_MS = 20_000L

        private var instance: BubbleOverlayService? = null
        private var directBubbleView: View? = null
        private val directHandler = Handler(Looper.getMainLooper())
        private val directAutoDismiss = Runnable { dismissDirectOverlay() }

        fun isRunning(): Boolean = instance != null || directBubbleView != null

        fun showOffer(context: Context, offer: RideOffer) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.w("BubbleOverlayService", "Permissão SYSTEM_ALERT_WINDOW não concedida.")
                return
            }

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
                // Inicia como serviço normal (sem FGS / sem notificação na barra)
                context.startService(intent)
            } catch (e: Exception) {
                Log.w("BubbleOverlayService", "startService falhou, renderizando overlay diretamente via WindowManager", e)
                directHandler.post {
                    showDirectOverlay(context.applicationContext, offer)
                }
            }
        }

        private fun showDirectOverlay(appContext: Context, offer: RideOffer) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
                return
            }

            val currentService = instance
            if (currentService != null) {
                currentService.showOrUpdateOverlay(
                    platform = offer.platform,
                    price = offer.price,
                    distance = offer.distanceKm,
                    duration = offer.durationMinutes,
                    rate = offer.ratePerKm,
                    net = offer.netProfit,
                    cost = offer.estimatedFuelCost,
                    rating = offer.passengerRating,
                    isLow = offer.isLowRating
                )
                return
            }

            dismissDirectOverlay()

            val wm = appContext.getSystemService(WINDOW_SERVICE) as WindowManager
            val prefs = PreferencesManager(appContext)
            val displayMetrics = appContext.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val cardWidth = if (screenWidth > 0) (screenWidth * 0.94f).toInt() else WindowManager.LayoutParams.MATCH_PARENT

            val statusBarResId = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarHeight = if (statusBarResId > 0) appContext.resources.getDimensionPixelSize(statusBarResId) else (28 * displayMetrics.density).toInt()
            val marginY = (16 * displayMetrics.density).toInt()

            val (gravityVal, initialY) = when (prefs.overlayPosition) {
                "BOTTOM" -> Pair(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, (24 * displayMetrics.density).toInt())
                "CENTER" -> Pair(Gravity.CENTER, 0)
                else -> Pair(Gravity.TOP or Gravity.CENTER_HORIZONTAL, statusBarHeight + marginY)
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            @Suppress("DEPRECATION")
            val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON

            val params = WindowManager.LayoutParams(
                cardWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                windowFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = gravityVal
                x = 0
                y = initialY
                windowAnimations = android.R.style.Animation_Dialog
            }

            val themedContext = androidx.appcompat.view.ContextThemeWrapper(appContext, R.style.Theme_DriFly)
            val root = LayoutInflater.from(themedContext).inflate(R.layout.overlay_bubble, null)
            directBubbleView = root

            root.findViewById<View>(R.id.btnCloseBubble)?.setOnClickListener {
                dismissDirectOverlay()
            }

            val tvPlatform = root.findViewById<TextView>(R.id.tvPlatformBadge)
            val tvRentabilidade = root.findViewById<TextView>(R.id.tvRentabilidade)
            val tvNetProfit = root.findViewById<TextView>(R.id.tvNetProfit)
            val tvFuelCost = root.findViewById<TextView>(R.id.tvFuelCost)
            val tvPreco = root.findViewById<TextView>(R.id.tvPreco)
            val tvDistancia = root.findViewById<TextView>(R.id.tvDistancia)
            val tvTempo = root.findViewById<TextView>(R.id.tvTempo)
            val layoutAlert = root.findViewById<View>(R.id.layoutRatingAlert)
            val tvRatingWarning = root.findViewById<TextView>(R.id.tvRatingWarning)

            val ptBr = Locale("pt", "BR")
            tvPlatform.text = offer.platform
            tvRentabilidade.text = String.format(ptBr, "R$ %.2f /km", offer.ratePerKm)
            tvNetProfit.text = String.format(ptBr, "R$ %.2f", offer.netProfit)
            tvFuelCost.text = String.format(ptBr, "- R$ %.2f", offer.estimatedFuelCost)
            tvPreco.text = String.format(ptBr, "Bruto: R$ %.2f", offer.price)
            tvDistancia.text = String.format(ptBr, "%.1f km", offer.distanceKm)
            tvTempo.text = if (offer.durationMinutes > 0) "${offer.durationMinutes} min" else "-- min"

            if (offer.ratePerKm >= 2.50) {
                tvRentabilidade.setTextColor(0xFF31D477.toInt())
            } else if (offer.ratePerKm >= 2.00) {
                tvRentabilidade.setTextColor(0xFFFBBF24.toInt())
            } else {
                tvRentabilidade.setTextColor(0xFFFF454D.toInt())
            }

            if (offer.isLowRating && offer.passengerRating != null) {
                layoutAlert.visibility = View.VISIBLE
                tvRatingWarning.text = String.format(ptBr, "⚠️ PASSAGEIRO COM NOTA BAIXA: %.2f (Aceite Bloqueado)", offer.passengerRating)
            } else {
                layoutAlert.visibility = View.GONE
            }

            root.visibility = View.VISIBLE
            root.alpha = 1f
            root.translationY = 0f

            try {
                wm.addView(root, params)
                directHandler.removeCallbacks(directAutoDismiss)
                directHandler.postDelayed(directAutoDismiss, AUTO_DISMISS_DELAY_MS)
            } catch (e: Exception) {
                Log.e("BubbleOverlayService", "Falha ao adicionar overlay direto", e)
                directBubbleView = null
            }
        }

        private fun dismissDirectOverlay() {
            directHandler.removeCallbacks(directAutoDismiss)
            val view = directBubbleView
            if (view != null) {
                try {
                    val wm = view.context.getSystemService(WINDOW_SERVICE) as WindowManager
                    if (view.isAttachedToWindow) {
                        wm.removeView(view)
                    }
                } catch (e: Exception) {
                    try {
                        val wm = view.context.getSystemService(WINDOW_SERVICE) as WindowManager
                        wm.removeViewImmediate(view)
                    } catch (ignored: Exception) {}
                }
                directBubbleView = null
            }
        }

        fun stop(context: Context) {
            val current = instance
            if (current != null) {
                current.dismissAndStop()
            } else {
                val intent = Intent(context, BubbleOverlayService::class.java).apply {
                    action = ACTION_STOP_BUBBLE
                }
                try {
                    context.stopService(intent)
                } catch (ignored: Exception) {}
            }

            directHandler.post {
                dismissDirectOverlay()
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID)
        }
    }
}
