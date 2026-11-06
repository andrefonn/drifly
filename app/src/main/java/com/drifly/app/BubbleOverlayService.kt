package com.drifly.app

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val ratePerHour = intent.getDoubleExtra("EXTRA_RATE_PER_HOUR", 0.0)
        val net = intent.getDoubleExtra("EXTRA_NET", 0.0)
        val cost = intent.getDoubleExtra("EXTRA_COST", 0.0)
        val rating = if (intent.hasExtra("EXTRA_RATING")) intent.getFloatExtra("EXTRA_RATING", 0f) else null
        val isLow = intent.getBooleanExtra("EXTRA_IS_LOW", false)
        val destination = intent.getStringExtra("EXTRA_DESTINATION")
        val isHeadingHome = intent.getBooleanExtra("EXTRA_IS_HEADING_HOME", false)
        val homeDeltaKm = intent.getDoubleExtra("EXTRA_HOME_DELTA_KM", 0.0)

        val offer = RideOffer(
            platform = platform,
            price = price,
            distanceKm = distance,
            durationMinutes = duration,
            ratePerKm = rate,
            ratePerHour = ratePerHour,
            estimatedFuelCost = cost,
            netProfit = net,
            passengerRating = rating,
            isLowRating = isLow,
            destinationAddress = destination,
            isHeadingHome = isHeadingHome,
            homeDistanceDeltaKm = homeDeltaKm
        )

        showOrUpdateOverlay(offer)
        return START_NOT_STICKY
    }

    private fun showOrUpdateOverlay(offer: RideOffer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Permissão SYSTEM_ALERT_WINDOW não concedida.")
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

            val closeBtn = root.findViewById<View>(R.id.btnCloseBubble)
            closeBtn?.setOnClickListener {
                animateAndRemove()
            }

            setupDragListener(root, params)

            root.visibility = View.VISIBLE
            root.alpha = 1f
            root.translationY = 0f

            try {
                windowManager?.addView(root, params)

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
                Log.e(TAG, "Erro ao adicionar view no WindowManager", e)
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
                    Log.e(TAG, "Erro ao atualizar layoutParams no WindowManager", e)
                }
            }
        }

        root?.let { view ->
            val durationMs = getDismissDelayForOffer(offer, prefs)
            bindOverlayData(view, offer, prefs)
            bindStreetViewPreview(this@BubbleOverlayService, view, offer.destinationAddress, isOfferGood(offer, prefs)) {
                resetAutoDismissTimer(AUTO_DISMISS_STREETVIEW_MS)
                startVisualTimerBar(view, AUTO_DISMISS_STREETVIEW_MS)
            }
            resetAutoDismissTimer(durationMs)
            startVisualTimerBar(view, durationMs)
        }
    }

    private fun resetAutoDismissTimer(delayMs: Long = AUTO_DISMISS_DELAY_MS) {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        autoDismissHandler.postDelayed(autoDismissRunnable, delayMs)
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
                    false
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
        private const val TAG = "BubbleOverlayService"
        const val CHANNEL_ID = "drifly_bubble_channel"
        const val NOTIFICATION_ID = 2002
        const val ACTION_STOP_BUBBLE = "com.drifly.app.ACTION_STOP_BUBBLE"
        const val AUTO_DISMISS_DELAY_MS = 20_000L
        const val AUTO_DISMISS_BAD_OFFER_MS = 4_500L
        const val AUTO_DISMISS_GOOD_OFFER_MS = 10_000L
        const val AUTO_DISMISS_STREETVIEW_MS = 15_000L

        private var instance: BubbleOverlayService? = null
        private var directBubbleView: View? = null
        private val directHandler = Handler(Looper.getMainLooper())
        private val directAutoDismiss = Runnable { dismissDirectOverlay() }
        private var timerAnimator: android.animation.ObjectAnimator? = null

        fun isRunning(): Boolean = instance != null || directBubbleView != null

        fun isOfferGood(offer: RideOffer, prefs: PreferencesManager): Boolean {
            return if (prefs.isModoCasaActive) {
                offer.homeDistanceDeltaKm <= 0.0
            } else {
                offer.ratePerKm >= prefs.minRatePerKm && offer.ratePerHour >= 30.0 && !offer.isLowRating
            }
        }

        fun getDismissDelayForOffer(offer: RideOffer, prefs: PreferencesManager): Long {
            return if (isOfferGood(offer, prefs)) {
                AUTO_DISMISS_GOOD_OFFER_MS
            } else {
                AUTO_DISMISS_BAD_OFFER_MS
            }
        }

        private fun startVisualTimerBar(view: View, durationMs: Long) {
            val pbTimer = view.findViewById<android.widget.ProgressBar>(R.id.pbOverlayTimer) ?: return
            timerAnimator?.cancel()
            pbTimer.max = 1000
            pbTimer.progress = 1000
            timerAnimator = android.animation.ObjectAnimator.ofInt(pbTimer, "progress", 1000, 0).apply {
                duration = durationMs
                interpolator = android.view.animation.LinearInterpolator()
                start()
            }
        }

        /**
         * Quando o motorista ACEITA a corrida, se houver endereço de destino, mantém o overlay por mais 8 segundos
         * com a foto da fachada do Street View aberta automaticamente e depois fecha sozinho.
         */
        fun onRideAcceptedShowStreetViewBriefly(context: Context, destination: String?) {
            directHandler.post {
                val view = directBubbleView ?: instance?.bubbleView
                if (view == null || !view.isAttachedToWindow || destination.isNullOrBlank()) {
                    stop(context)
                    return@post
                }
                val frameContainer = view.findViewById<View>(R.id.frameStreetViewContainer)
                val tvIcon = view.findViewById<TextView>(R.id.tvStreetViewActionIcon)
                if (frameContainer != null && frameContainer.visibility != View.VISIBLE) {
                    view.findViewById<View>(R.id.btnToggleStreetView)?.performClick()
                } else {
                    tvIcon?.text = "▲ Fachada (Aceita)"
                }
                val acceptedViewDelay = 8_000L
                directHandler.removeCallbacks(directAutoDismiss)
                directHandler.postDelayed(directAutoDismiss, acceptedViewDelay)
                instance?.resetAutoDismissTimer(acceptedViewDelay)
                startVisualTimerBar(view, acceptedViewDelay)
            }
        }

        fun showOffer(context: Context, offer: RideOffer) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Permissão SYSTEM_ALERT_WINDOW não concedida.")
                return
            }

            directHandler.post {
                showDirectOverlay(context.applicationContext, offer)
            }
        }

        private fun showDirectOverlay(appContext: Context, offer: RideOffer) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
                return
            }

            val currentService = instance
            if (currentService != null) {
                currentService.showOrUpdateOverlay(offer)
                return
            }

            val prefs = PreferencesManager(appContext)
            val dismissDelayMs = getDismissDelayForOffer(offer, prefs)
            val goodOffer = isOfferGood(offer, prefs)

            // Reutiliza a view existente se já estiver anexada na tela (evita criar dezenas de janelas no WindowManager)
            val existingView = directBubbleView
            if (existingView != null && existingView.isAttachedToWindow) {
                bindOverlayData(existingView, offer, prefs)
                bindStreetViewPreview(appContext, existingView, offer.destinationAddress, goodOffer) {
                    directHandler.removeCallbacks(directAutoDismiss)
                    directHandler.postDelayed(directAutoDismiss, AUTO_DISMISS_STREETVIEW_MS)
                    startVisualTimerBar(existingView, AUTO_DISMISS_STREETVIEW_MS)
                }
                directHandler.removeCallbacks(directAutoDismiss)
                directHandler.postDelayed(directAutoDismiss, dismissDelayMs)
                startVisualTimerBar(existingView, dismissDelayMs)
                return
            }

            dismissDirectOverlay()

            val wm = appContext.getSystemService(WINDOW_SERVICE) as WindowManager
            val displayMetrics = appContext.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val cardWidth = if (screenWidth > 0) (screenWidth * 0.94f).toInt() else WindowManager.LayoutParams.MATCH_PARENT

            val statusBarResId = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarHeight = if (statusBarResId > 0) resourcesDimension(appContext, statusBarResId, (28 * displayMetrics.density).toInt()) else (28 * displayMetrics.density).toInt()
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

            bindOverlayData(root, offer, prefs)
            bindStreetViewPreview(appContext, root, offer.destinationAddress, goodOffer) {
                directHandler.removeCallbacks(directAutoDismiss)
                directHandler.postDelayed(directAutoDismiss, AUTO_DISMISS_STREETVIEW_MS)
                startVisualTimerBar(root, AUTO_DISMISS_STREETVIEW_MS)
            }

            root.visibility = View.VISIBLE
            root.alpha = 1f
            root.translationY = 0f

            try {
                wm.addView(root, params)
                Log.i(TAG, "showDirectOverlay: OVERLAY DRIFLY RENDERIZADO COM SUCESSO! [${offer.platform} R$ ${offer.price}] timer=${dismissDelayMs}ms")
                directHandler.removeCallbacks(directAutoDismiss)
                directHandler.postDelayed(directAutoDismiss, dismissDelayMs)
                startVisualTimerBar(root, dismissDelayMs)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao adicionar overlay direto", e)
                directBubbleView = null
            }
        }

        private fun resourcesDimension(context: Context, resId: Int, fallback: Int): Int {
            return try {
                context.resources.getDimensionPixelSize(resId)
            } catch (_: Exception) {
                fallback
            }
        }

        private fun dismissDirectOverlay() {
            directHandler.removeCallbacks(directAutoDismiss)
            timerAnimator?.cancel()
            timerAnimator = null
            val view = directBubbleView
            directBubbleView = null
            if (view != null) {
                try {
                    val wm = view.context.getSystemService(WINDOW_SERVICE) as WindowManager
                    wm.removeViewImmediate(view)
                } catch (e: Exception) {
                    try {
                        val wm = view.context.getSystemService(WINDOW_SERVICE) as WindowManager
                        wm.removeView(view)
                    } catch (ignored: Exception) {}
                }
            }
        }

        fun bindOverlayData(root: View, offer: RideOffer, prefs: PreferencesManager) {
            val ptBr = Locale("pt", "BR")

            val tvVerdictBadge = root.findViewById<TextView>(R.id.tvVerdictBadge)
            val layoutModoCasaBadge = root.findViewById<View>(R.id.layoutModoCasaBadge)
            val tvModoCasaKm = root.findViewById<TextView>(R.id.tvModoCasaKm)

            val tvPlatform = root.findViewById<TextView>(R.id.tvPlatformBadge)
            val tvMetaGross = root.findViewById<TextView>(R.id.tvMetaGross)
            val tvMetaDistance = root.findViewById<TextView>(R.id.tvMetaDistance)
            val tvMetaDuration = root.findViewById<TextView>(R.id.tvMetaDuration)

            val tvRateKm = root.findViewById<TextView>(R.id.tvRateKm)
            val viewBarRateKm = root.findViewById<View>(R.id.viewBarRateKm)
            val tvRateHour = root.findViewById<TextView>(R.id.tvRateHour)
            val viewBarRateHour = root.findViewById<View>(R.id.viewBarRateHour)
            val tvRating = root.findViewById<TextView>(R.id.tvRating)
            val viewBarRating = root.findViewById<View>(R.id.viewBarRating)

            val tvNetProfit = root.findViewById<TextView>(R.id.tvNetProfit)
            val tvFuelCost = root.findViewById<TextView>(R.id.tvFuelCost)

            val layoutAlert = root.findViewById<View>(R.id.layoutRatingAlert)
            val tvRatingWarning = root.findViewById<TextView>(R.id.tvRatingWarning)

            // Metadados
            tvPlatform?.text = offer.platform
            tvMetaGross?.text = String.format(ptBr, "Bruto R$ %.2f", offer.price)
            tvMetaDistance?.text = String.format(ptBr, "%.1f km", offer.distanceKm)
            tvMetaDuration?.text = if (offer.durationMinutes > 0) "${offer.durationMinutes} min" else "-- min"

            // Hero 1: R$ / KM
            tvRateKm?.text = String.format(ptBr, "R$ %.2f", offer.ratePerKm)
            val barKmRes = when {
                offer.ratePerKm >= 2.50 -> R.drawable.bg_hero_bar_green
                offer.ratePerKm >= 2.00 -> R.drawable.bg_hero_bar_yellow
                else -> R.drawable.bg_hero_bar_red
            }
            viewBarRateKm?.setBackgroundResource(barKmRes)

            // Hero 2: R$ / HORA
            tvRateHour?.text = if (offer.ratePerHour > 0) String.format(ptBr, "R$ %.0f", offer.ratePerHour) else "--"
            val barHourRes = when {
                offer.ratePerHour >= 45.0 -> R.drawable.bg_hero_bar_green
                offer.ratePerHour >= 30.0 -> R.drawable.bg_hero_bar_yellow
                else -> R.drawable.bg_hero_bar_red
            }
            viewBarRateHour?.setBackgroundResource(barHourRes)

            // Hero 3: NOTA
            val ratingVal = offer.passengerRating ?: 5.0f
            tvRating?.text = if (offer.passengerRating != null) String.format(ptBr, "%.2f", offer.passengerRating) else "5.00"
            val barRatingRes = if (ratingVal >= prefs.minPassengerRating) R.drawable.bg_hero_bar_green else R.drawable.bg_hero_bar_red
            viewBarRating?.setBackgroundResource(barRatingRes)

            // Card de Lucro Real
            tvNetProfit?.text = String.format(ptBr, "R$ %.2f", offer.netProfit)
            tvFuelCost?.text = String.format(ptBr, "- R$ %.2f", offer.estimatedFuelCost)

            // Alerta de Nota Baixa (Oculto conforme solicitado: nota vermelha e veredito Recusar ❌ já indicam claramente)
            layoutAlert?.visibility = View.GONE

            // Veredito & Modo Casa
            val isModoCasa = prefs.isModoCasaActive
            if (!isModoCasa) {
                layoutModoCasaBadge?.visibility = View.GONE
                val isProfitable = (offer.ratePerKm >= prefs.minRatePerKm && offer.ratePerHour >= 30.0 && !offer.isLowRating)
                if (isProfitable) {
                    root.setBackgroundResource(R.drawable.bg_bubble_glass_good)
                    tvVerdictBadge?.setBackgroundResource(R.drawable.bg_verdict_good)
                    tvVerdictBadge?.setTextColor(0xFF31D477.toInt())
                    tvVerdictBadge?.text = "Aceitar ✅"
                } else {
                    root.setBackgroundResource(R.drawable.bg_bubble_glass_bad)
                    tvVerdictBadge?.setBackgroundResource(R.drawable.bg_verdict_bad)
                    tvVerdictBadge?.setTextColor(0xFFFF454D.toInt())
                    tvVerdictBadge?.text = "Recusar ❌"
                }
            } else {
                layoutModoCasaBadge?.visibility = View.VISIBLE
                val delta = offer.homeDistanceDeltaKm
                val kmText = if (delta > 0) String.format(ptBr, "+%.1f km", delta) else String.format(ptBr, "%.1f km", delta)
                tvModoCasaKm?.text = kmText

                if (delta <= 0) {
                    // Aproxima de casa
                    layoutModoCasaBadge?.setBackgroundResource(R.drawable.bg_modo_casa_good)
                    tvModoCasaKm?.setTextColor(0xFF31D477.toInt())
                    root.setBackgroundResource(R.drawable.bg_bubble_glass_good)
                    tvVerdictBadge?.setBackgroundResource(R.drawable.bg_verdict_good)
                    tvVerdictBadge?.setTextColor(0xFF31D477.toInt())

                    when {
                        delta <= -8.0 -> tvVerdictBadge?.text = "Perfeita Direção ✅"
                        delta <= -4.0 -> tvVerdictBadge?.text = "Excelente Direção ✅"
                        else -> tvVerdictBadge?.text = "Boa Direção ✅"
                    }
                } else {
                    // Afasta de casa
                    layoutModoCasaBadge?.setBackgroundResource(R.drawable.bg_modo_casa_bad)
                    tvModoCasaKm?.setTextColor(0xFFFF6B72.toInt())
                    root.setBackgroundResource(R.drawable.bg_bubble_glass_bad)
                    tvVerdictBadge?.setBackgroundResource(R.drawable.bg_verdict_bad)
                    tvVerdictBadge?.setTextColor(0xFFFF454D.toInt())

                    when {
                        delta <= 4.0 -> tvVerdictBadge?.text = "Ruim Direção ❌"
                        delta <= 9.0 -> tvVerdictBadge?.text = "Péssima Direção ❌"
                        else -> tvVerdictBadge?.text = "Evitar Direção ❌"
                    }
                }
            }
        }

        @android.annotation.SuppressLint("SetJavaScriptEnabled")
        private fun bindStreetViewPreview(
            context: Context,
            view: View,
            destination: String?,
            autoOpen: Boolean = false,
            onExpand: () -> Unit
        ) {
            val layoutStreetView = view.findViewById<View>(R.id.layoutStreetViewPreview) ?: return
            val btnToggle = view.findViewById<View>(R.id.btnToggleStreetView) ?: return
            val frameContainer = view.findViewById<View>(R.id.frameStreetViewContainer) ?: return
            val tvLabel = view.findViewById<TextView>(R.id.tvStreetViewLabel) ?: return
            val tvIcon = view.findViewById<TextView>(R.id.tvStreetViewActionIcon) ?: return
            val ivStreetView = view.findViewById<ImageView>(R.id.ivStreetViewImage)
            val pbLoading = view.findViewById<View>(R.id.pbStreetViewLoading)
            val webView = view.findViewById<android.webkit.WebView>(R.id.wvStreetView)

            if (destination.isNullOrBlank()) {
                layoutStreetView.visibility = View.GONE
                return
            }

            layoutStreetView.visibility = View.VISIBLE
            tvLabel.text = "📍 $destination"
            frameContainer.visibility = View.GONE
            ivStreetView?.visibility = View.GONE
            pbLoading?.visibility = View.GONE
            webView?.visibility = View.GONE
            tvIcon.text = "▼ Fachada"
            var isLoaded = false

            fun loadStreetViewNow() {
                if (isLoaded) return
                isLoaded = true
                pbLoading?.visibility = View.VISIBLE
                CoroutineScope(Dispatchers.Main).launch {
                    val result = StreetViewHelper.fetchStreetViewPreview(context, destination)
                    pbLoading?.visibility = View.GONE
                    if (result.bitmap != null && ivStreetView != null) {
                        ivStreetView.setImageBitmap(result.bitmap)
                        ivStreetView.visibility = View.VISIBLE
                        webView?.visibility = View.GONE
                    } else if (result.embedHtml.isNotEmpty() && webView != null) {
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.visibility = View.VISIBLE
                        webView.loadDataWithBaseURL("https://maps.googleapis.com", result.embedHtml, "text/html", "UTF-8", null)
                    }
                }
            }

            // Pré-carrega o Street View em segundo plano quando a corrida é boa para abrir instantaneamente
            if (autoOpen) {
                loadStreetViewNow()
            }

            btnToggle.setOnClickListener {
                onExpand()
                val isVisible = frameContainer.visibility == View.VISIBLE
                if (isVisible) {
                    frameContainer.visibility = View.GONE
                    tvIcon.text = "▼ Fachada"
                } else {
                    frameContainer.visibility = View.VISIBLE
                    tvIcon.text = "▲ Ocultar"
                    loadStreetViewNow()
                }
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
