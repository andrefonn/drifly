package com.drifly.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

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
        activeOverlayOffer = offer
        isAcceptTriggered = false
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

            setupOverlayTouchHandling(
                root = root,
                params = params,
                wm = windowManager,
                offer = offer,
                prefs = prefs,
                onCloseRequested = { animateAndRemove() },
                onInteraction = { resetAutoDismissTimer() }
            )

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
            bindStreetViewPreview(this@BubbleOverlayService, view, offer.destinationAddress, true) {
                pauseAutoDismiss(view)
            }
            resetAutoDismissTimer(durationMs)
            startVisualTimerBar(view, durationMs)
        }
    }

    private fun resetAutoDismissTimer(delayMs: Long = AUTO_DISMISS_DELAY_MS) {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        autoDismissHandler.postDelayed(autoDismissRunnable, delayMs)
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
            lastDismissedTimestamp = System.currentTimeMillis()
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
        // 7s: o motorista precisa de tempo para ler os números antes de decidir (antes eram 4,5s)
        const val AUTO_DISMISS_BAD_OFFER_MS = 7_000L
        // 15s para a oferta boa: dá tempo de ler com calma e decidir
        const val AUTO_DISMISS_GOOD_OFFER_MS = 15_000L
        const val AUTO_DISMISS_STREETVIEW_MS = 15_000L
        // Enquanto a fachada está aberta a contagem fica pausada, mas não para sempre:
        // um card preso mantém isRunning()=true e bloqueia a captura (OCR) de novas ofertas.
        private const val PAUSED_OVERLAY_MAX_MS = 60_000L

        private var instance: BubbleOverlayService? = null
        private var directBubbleView: View? = null
        private val directHandler = Handler(Looper.getMainLooper())
        var activeOverlayOffer: RideOffer? = null
        var activeOverlayRideId: Long? = null
        @Volatile
        var isAcceptTriggered: Boolean = false
        @Volatile
        var lastAcceptTapTimestamp: Long = 0L
        private var activeAppContext: Context? = null

        private val directAutoDismiss = Runnable {
            val offer = activeOverlayOffer
            val rideId = activeOverlayRideId
            val ctx = activeAppContext
            if (offer != null && rideId != null && ctx != null) {
                val prefs = PreferencesManager(ctx)
                if (!isOfferGood(offer, prefs)) {
                    // Oferta ruim/não lucrativa que expirou sem o motorista aceitar: descarta do banco
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            DatabaseHelper(ctx).deleteRide(rideId)
                            ctx.sendBroadcast(Intent(RotaAccessibilityService.ACTION_RIDE_UPDATED).apply {
                                setPackage(ctx.packageName)
                            })
                        } catch (_: Exception) {}
                    }
                }
            }
            activeOverlayRideId = null
            activeOverlayOffer = null
            dismissDirectOverlay()
        }
        private var timerAnimator: android.animation.ObjectAnimator? = null

        // Notificações de oferta (histórico na barra, opção B: uma por oferta)
        const val OFFERS_CHANNEL_ID = "drifly_offers_channel"
        private const val OFFERS_GROUP_KEY = "drifly_offers_group"
        private const val OFFERS_SUMMARY_ID = 2100
        private const val OFFER_NOTIFICATION_BASE_ID = 2200
        private val offerNotificationSeq = AtomicInteger(0)

        @Volatile
        private var lastDismissedTimestamp: Long = 0L

        fun isRunning(): Boolean = instance != null || directBubbleView != null

        fun wasRecentlyDismissed(cooldownMs: Long = 1_200L): Boolean =
            (System.currentTimeMillis() - lastDismissedTimestamp) < cooldownMs

        fun isOfferGood(offer: RideOffer, prefs: PreferencesManager): Boolean {
            if (offer.isBlacklistBlocked) {
                return false
            }
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
            pbTimer.visibility = View.VISIBLE
            pbTimer.max = 1000
            pbTimer.progress = 1000
            timerAnimator = android.animation.ObjectAnimator.ofInt(pbTimer, "progress", 1000, 0).apply {
                duration = durationMs
                interpolator = android.view.animation.LinearInterpolator()
                start()
            }
        }

        /**
         * Para a contagem regressiva e mantém o card na tela até o motorista fechar no X.
         * Usado quando ele abre a fachada: precisa de tempo para olhar a foto e decidir.
         */
        private fun pauseAutoDismiss(view: View) {
            directHandler.removeCallbacks(directAutoDismiss)
            timerAnimator?.cancel()
            timerAnimator = null
            instance?.let { current ->
                current.autoDismissHandler.removeCallbacks(current.autoDismissRunnable)
            }
            view.findViewById<View>(R.id.pbOverlayTimer)?.visibility = View.GONE
            // Backstop: sem isso, deixar a fachada aberta prende o card indefinidamente e o
            // monitoramento deixa de capturar novas ofertas (BubbleOverlayService.isRunning()).
            directHandler.postDelayed(directAutoDismiss, PAUSED_OVERLAY_MAX_MS)
            Log.i(TAG, "Contagem pausada: o card fecha no X ou em ${PAUSED_OVERLAY_MAX_MS / 1000}s")
        }

        /**
         * Modo "transparente": esconde o overlay e deixa os toques passarem por ele. Usado enquanto o
         * DriFly clica/desliza no app da plataforma e tira print, para o card não tapar o botão nem
         * aparecer no OCR.
         */
        fun setPassThrough(context: Context, enabled: Boolean) {
            directHandler.post {
                val view = directBubbleView ?: instance?.bubbleView ?: return@post
                val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@post
                lp.flags = if (enabled) {
                    lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                view.alpha = if (enabled) 0f else 1f
                try {
                    if (view.isAttachedToWindow) {
                        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(view, lp)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao alternar modo transparente do overlay", e)
                }
            }
        }

        /**
         * Quando o motorista ACEITA a corrida, se houver endereço de destino, mantém o overlay por mais 8 segundos
         * com a foto da fachada do Street View aberta automaticamente e depois fecha sozinho.
         */
        fun onRideAcceptedShowStreetViewBriefly(context: Context, destination: String?) {
            // Garante que uma corrida aceita pelo motorista nunca seja apagada por auto-dismiss ou descarte
            activeOverlayRideId = null
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

        /**
         * Listener de toque unificado para o Overlay Flutuante (usado tanto no serviço quanto no overlay direto).
         * Trata:
         * 1. Arraste e reposicionamento da janela pela tela (deslocamento > touchSlop).
         * 2. Zona de exclusão circular de 52 dp ao redor do centro do botão fechar X (fecha sem aceitar).
         * 3. Toque no botão de alternar Street View / Fachada (expande/recolhe).
         * 4. Aceite manual ao tocar no corpo do card (com proteção contra duplo toque/debounce) se ativado.
         */
        fun setupOverlayTouchHandling(
            root: View,
            params: WindowManager.LayoutParams,
            wm: WindowManager?,
            offer: RideOffer,
            prefs: PreferencesManager,
            onCloseRequested: () -> Unit,
            onInteraction: () -> Unit
        ) {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false

            root.setOnTouchListener { _, event ->
                val density = root.resources.displayMetrics.density
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        onInteraction()
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (OverlayTouchMath.isDrag(initialTouchX, initialTouchY, event.rawX, event.rawY, density)) {
                            isDragging = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            try {
                                if (root.isAttachedToWindow && wm != null) {
                                    wm.updateViewLayout(root, params)
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
                        onInteraction()
                        if (isDragging) {
                            true
                        } else {
                            val closeBtn = root.findViewById<View>(R.id.btnCloseBubble)
                            val (centerX, centerY) = if (closeBtn != null && closeBtn.isAttachedToWindow) {
                                val loc = IntArray(2)
                                closeBtn.getLocationOnScreen(loc)
                                Pair(loc[0] + closeBtn.width / 2f, loc[1] + closeBtn.height / 2f)
                            } else {
                                Pair(0f, 0f)
                            }

                            // 1. Zona de exclusão circular do botão fechar X (~52 dp)
                            if (closeBtn != null && OverlayTouchMath.isTouchInExclusionZone(event.rawX, event.rawY, centerX, centerY, density)) {
                                Log.i(TAG, "Toque na zona de proteção do botão X (raio <= 52dp). Fechando overlay sem aceitar.")
                                onCloseRequested()
                                return@setOnTouchListener true
                            }

                            // 2. Toque no botão de alternar Street View / Fachada
                            val btnToggleStreetView = root.findViewById<View>(R.id.btnToggleStreetView)
                            if (btnToggleStreetView != null && btnToggleStreetView.visibility == View.VISIBLE) {
                                val svLoc = IntArray(2)
                                btnToggleStreetView.getLocationOnScreen(svLoc)
                                if (OverlayTouchMath.isInsideBounds(event.rawX, event.rawY, svLoc[0], svLoc[1], btnToggleStreetView.width, btnToggleStreetView.height)) {
                                    btnToggleStreetView.performClick()
                                    return@setOnTouchListener true
                                }
                            }

                            // 3. Toque no corpo do overlay flutuante
                            if (prefs.tapToAcceptEnabled) {
                                val now = SystemClock.uptimeMillis()
                                if (OverlayTouchMath.shouldAllowAcceptTap(isAcceptTriggered, lastAcceptTapTimestamp, now)) {
                                    isAcceptTriggered = true
                                    lastAcceptTapTimestamp = now
                                    val currentOffer = activeOverlayOffer ?: offer
                                    Log.i(TAG, "Aceite manual via toque no overlay acionado para oferta [${currentOffer.platform} R$ ${currentOffer.price}]!")
                                    // O serviço de acessibilidade executa o clique/gesto, confere a tela e só então
                                    // salva a corrida e mostra a fachada (onRideAcceptedShowStreetViewBriefly).
                                    val service = RotaAccessibilityService.instance
                                    if (service != null) {
                                        service.triggerManualAccept()
                                    } else {
                                        isAcceptTriggered = false
                                        android.widget.Toast.makeText(
                                            root.context,
                                            "Ative o serviço de acessibilidade do DriFly para aceitar pelo toque.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } else {
                                    Log.d(TAG, "Toque no corpo ignorado: debounce ou aceite já disparado.")
                                }
                            } else {
                                Log.d(TAG, "Toque no corpo ignorado: tapToAcceptEnabled está desativado.")
                            }
                            true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        false
                    }
                    else -> false
                }
            }
        }

        fun showOffer(context: Context, offer: RideOffer, rideId: Long? = null) {
            val prefs = PreferencesManager(context.applicationContext)
            val evaluatedOffer = if (prefs.blacklistEnabled && !offer.isBlacklistBlocked) {
                val blacklistItems = DatabaseHelper(context.applicationContext).getAllBlacklistItems()
                BlacklistManager.evaluateOffer(offer, blacklistItems)
            } else {
                offer
            }

            try {
                RotaAccessibilityService.setPendingOffer(evaluatedOffer, System.currentTimeMillis(), rideId)
            } catch (_: Throwable) {}

            // Publica a oferta na barra (histórico) ANTES de tentar o overlay: assim, mesmo que a
            // permissão de sobreposição esteja bloqueada, os valores continuam acessíveis.
            try {
                postOfferNotification(context.applicationContext, evaluatedOffer, prefs, rideId)
            } catch (t: Throwable) {
                try { Log.w(TAG, "Falha ao publicar notificação da oferta", t) } catch (ignored: Throwable) {}
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Permissão SYSTEM_ALERT_WINDOW não concedida.")
                return
            }

            directHandler.post {
                showDirectOverlay(context.applicationContext, evaluatedOffer, rideId)
            }
        }

        private fun showDirectOverlay(appContext: Context, offer: RideOffer, rideId: Long? = null) {
            activeOverlayOffer = offer
            activeOverlayRideId = rideId
            isAcceptTriggered = false
            activeAppContext = appContext

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
                bindStreetViewPreview(appContext, existingView, offer.destinationAddress, true) {
                    pauseAutoDismiss(existingView)
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

            val closeListener = {
                val idToDelete = activeOverlayRideId
                if (idToDelete != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            DatabaseHelper(appContext).deleteRide(idToDelete)
                            appContext.sendBroadcast(Intent(RotaAccessibilityService.ACTION_RIDE_UPDATED).apply {
                                setPackage(appContext.packageName)
                            })
                        } catch (_: Exception) {}
                    }
                }
                activeOverlayRideId = null
                activeOverlayOffer = null
                dismissDirectOverlay()
            }

            root.findViewById<View>(R.id.btnCloseBubble)?.setOnClickListener {
                closeListener()
            }

            setupOverlayTouchHandling(
                root = root,
                params = params,
                wm = wm,
                offer = offer,
                prefs = prefs,
                onCloseRequested = closeListener,
                onInteraction = {
                    directHandler.removeCallbacks(directAutoDismiss)
                    directHandler.postDelayed(directAutoDismiss, dismissDelayMs)
                }
            )

            bindOverlayData(root, offer, prefs)
            bindStreetViewPreview(appContext, root, offer.destinationAddress, true) {
                pauseAutoDismiss(root)
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
                lastDismissedTimestamp = System.currentTimeMillis()
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

            // Veredito, Lista Negra & Modo Casa
            val isModoCasa = prefs.isModoCasaActive
            if (offer.isBlacklistBlocked) {
                // Alerta vermelho de recusa obrigatório para Lista Negra (sobrepõe inclusive Modo Casa)
                root.setBackgroundResource(R.drawable.bg_bubble_glass_bad)
                tvVerdictBadge?.setBackgroundResource(R.drawable.bg_verdict_bad)
                tvVerdictBadge?.setTextColor(0xFFFF454D.toInt())
                tvVerdictBadge?.text = "Recusar ❌"

                layoutAlert?.visibility = View.VISIBLE
                tvRatingWarning?.text = offer.blacklistAlert ?: "⛔ Bairro na Lista Negra"

                if (isModoCasa) {
                    layoutModoCasaBadge?.visibility = View.VISIBLE
                    val delta = offer.homeDistanceDeltaKm
                    val kmText = if (delta > 0) String.format(ptBr, "+%.1f km", delta) else String.format(ptBr, "%.1f km", delta)
                    tvModoCasaKm?.text = kmText
                    layoutModoCasaBadge?.setBackgroundResource(R.drawable.bg_modo_casa_bad)
                    tvModoCasaKm?.setTextColor(0xFFFF6B72.toInt())
                } else {
                    layoutModoCasaBadge?.visibility = View.GONE
                }
            } else {
                // Alerta de Nota Baixa (Oculto conforme solicitado: nota vermelha e veredito Recusar ❌ já indicam claramente)
                layoutAlert?.visibility = View.GONE

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

            // Pré-carrega a fachada SEMPRE (não só nas ofertas boas): assim o toque em "Fachada"
            // mostra a foto na hora, sem esperar geocodificação + download.
            if (autoOpen) {
                loadStreetViewNow()
            }

            btnToggle.setOnClickListener {
                val isVisible = frameContainer.visibility == View.VISIBLE
                if (isVisible) {
                    frameContainer.visibility = View.GONE
                    tvIcon.text = "▼ Fachada"
                } else {
                    frameContainer.visibility = View.VISIBLE
                    tvIcon.text = "▲ Ocultar"
                    loadStreetViewNow()
                    // Motorista abriu a fachada: para de contar o tempo, ele fecha no X
                    onExpand()
                }
            }
        }

        /**
         * Publica a oferta na barra de notificações.
         *
         * Objetivo: se o motorista aceitar e o overlay fechar, ele continua tendo acesso aos valores,
         * ao endereço e à fachada pela notificação. Cada oferta vira uma notificação própria (histórico),
         * agrupadas sob o mesmo grupo para não poluir a barra.
         */
        fun postOfferNotification(
            context: Context,
            offer: RideOffer,
            prefs: PreferencesManager,
            rideId: Long? = null
        ) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        OFFERS_CHANNEL_ID,
                        "Ofertas recebidas",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Histórico das ofertas com valores, lucro e acesso à fachada"
                        setShowBadge(true)
                    }
                    manager.createNotificationChannel(channel)
                }

                val ptBr = Locale("pt", "BR")
                val isGood = isOfferGood(offer, prefs)
                val verdict = if (isGood) "Aceitar ✅" else "Recusar ❌"
                val accent = if (isGood) 0xFF31D477.toInt() else 0xFFFF454D.toInt()

                val title = String.format(ptBr, "%s • %s • R$ %.2f", verdict, offer.platform, offer.price)

                val summaryLine = buildString {
                    append(String.format(ptBr, "%.1f km", offer.distanceKm))
                    append(String.format(ptBr, " • R$/km %.2f", offer.ratePerKm))
                    if (offer.ratePerHour > 0.0) append(String.format(ptBr, " • R$/h %.0f", offer.ratePerHour))
                    append(String.format(ptBr, " • Lucro R$ %.2f", offer.netProfit))
                    offer.passengerRating?.let { append(String.format(ptBr, " • Nota %.2f", it)) }
                }

                val expanded = buildString {
                    if (offer.isBlacklistBlocked && !offer.blacklistAlert.isNullOrBlank()) {
                        append("${offer.blacklistAlert}\n")
                    }
                    append(summaryLine)
                    if (offer.durationMinutes > 0) append(String.format(ptBr, "\n⏱ %d min", offer.durationMinutes))
                    val dest = offer.destinationAddress
                    if (!dest.isNullOrBlank()) append("\n📍 $dest")
                    append("\nToque para abrir o DriFly")
                }

                val notificationId = OFFER_NOTIFICATION_BASE_ID + (offerNotificationSeq.incrementAndGet() % 400)

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openPi = PendingIntent.getActivity(
                    context,
                    notificationId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val builder = NotificationCompat.Builder(context, OFFERS_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_drifly_logo)
                    .setContentTitle(title)
                    .setContentText(summaryLine)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
                    .setColor(accent)
                    .setContentIntent(openPi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setGroup(OFFERS_GROUP_KEY)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)

                val dest = offer.destinationAddress
                if (!dest.isNullOrBlank()) {
                    val streetViewUrl = StreetViewHelper.buildStaticStreetViewUrl(rawAddress = dest)
                    val facadeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(streetViewUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val facadePi = PendingIntent.getActivity(
                        context,
                        notificationId + 1000,
                        facadeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(0, "Ver fachada", facadePi)
                }

                if (rideId != null && rideId > 0) {
                    val discardIntent = Intent(context, OfferActionReceiver::class.java).apply {
                        action = OfferActionReceiver.ACTION_DISCARD_OFFER
                        putExtra(OfferActionReceiver.EXTRA_DISCARD_RIDE_ID, rideId)
                        putExtra(OfferActionReceiver.EXTRA_DISCARD_NOTIF_ID, notificationId)
                    }
                    val discardPi = PendingIntent.getBroadcast(
                        context,
                        notificationId + 2000,
                        discardIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(0, "🗑️ Descartar", discardPi)
                }

                builder.addAction(0, "Abrir DriFly", openPi)

                manager.notify(notificationId, builder.build())

                val summary = NotificationCompat.Builder(context, OFFERS_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_drifly_logo)
                    .setContentTitle("Ofertas DriFly")
                    .setContentText("Histórico das ofertas recentes")
                    .setGroup(OFFERS_GROUP_KEY)
                    .setGroupSummary(true)
                    .setSilent(true)
                    .setAutoCancel(true)
                    .build()
                manager.notify(OFFERS_SUMMARY_ID, summary)
            } catch (t: Throwable) {
                try { Log.w(TAG, "Falha ao publicar notificação da oferta", t) } catch (ignored: Throwable) {}
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
