package com.drifly.app

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bolinha flutuante do tanque.
 *
 * Arquitetura em janelas separadas para que a bolinha nunca se mova ao abrir
 * ou fechar o menu:
 *  - janela da bolinha (58dp, sempre no mesmo lugar, arrastável);
 *  - janela do leque (criada ao abrir, destruída ao fechar);
 *  - janela da zona de descarte (só durante o arraste).
 */
class FuelBubbleService : Service() {

    companion object {
        private const val ACTION_START = "com.drifly.app.action.FUEL_BUBBLE_START"
        private const val ACTION_STOP = "com.drifly.app.action.FUEL_BUBBLE_STOP"
        private const val ACTION_REFRESH = "com.drifly.app.action.FUEL_BUBBLE_REFRESH"

        private const val MENU_RADIUS_DP = 92
        private const val ITEM_SIZE_DP = 46

        private const val OVERLAY_CHANNEL_ID = "drifly_overlay_channel"
        private const val OVERLAY_NOTIFICATION_ID = 1003

        @Volatile
        private var instance: FuelBubbleService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            try {
                context.startService(
                    Intent(context, FuelBubbleService::class.java).setAction(ACTION_START)
                )
            } catch (error: Exception) {
                AppLogger.w("FuelBubbleService", "Falha ao iniciar bolinha: ${error.message}", error)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, FuelBubbleService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
            }
        }

        /** Reaplica nível/estado na bolinha visível, se houver. */
        fun refresh(context: Context) {
            try {
                context.startService(
                    Intent(context, FuelBubbleService::class.java).setAction(ACTION_REFRESH)
                )
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var prefs: PreferencesManager
    private var windowManager: WindowManager? = null

    private lateinit var bubbleView: FuelTankView
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var menuRoot: FrameLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private val menuItems = mutableListOf<ImageView>()

    private var killRoot: FrameLayout? = null
    private var killParams: WindowManager.LayoutParams? = null

    private var menuOpen = false
    private var menuBubbleCenterX = 0f
    private var menuBubbleCenterY = 0f
    private var screenW = 0
    private var screenH = 0

    private val bubbleSize by lazy { dp(58) }
    private val itemSize by lazy { dp(ITEM_SIZE_DP) }
    private val menuRadius by lazy { dp(MENU_RADIUS_DP) }
    private val killZoneSize by lazy { dp(170) }

    private var sensorManager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null

    // Velocímetro GPS: janela complementar + leitura compartilhada de localização.
    private var speedMonitor: GpsSpeedMonitor? = null
    private var speedOverlay: SpeedReadoutOverlay? = null
    private var speedScope: CoroutineScope? = null
    private var speedJob: Job? = null

    private var foregroundStarted = false
    private var foregroundHasLocation = false

    private val monitoringStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MonitoringService.ACTION_MONITORING_CHANGED) {
                syncForegroundState()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val fuelTicker = object : Runnable {
        override fun run() {
            applyLevelFromPrefs()
            handler.postDelayed(this, 8_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PreferencesManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        createOverlayNotificationChannel()
        try {
            ContextCompat.registerReceiver(
                this,
                monitoringStateReceiver,
                IntentFilter(MonitoringService.ACTION_MONITORING_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (error: Exception) {
            AppLogger.w("FuelBubbleService", "Falha ao registrar receptor de monitoramento: ${error.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                removeBubble()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                if (bubbleParams == null) {
                    showBubble()
                } else {
                    applyLevelFromPrefs()
                    styleMenuItems()
                    attachSpeedometer()
                    followSpeedometer()
                    syncForegroundState()
                }
                return START_STICKY
            }
        }

        if (!prefs.floatingBubbleEnabled) {
            removeBubble()
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            removeBubble()
            stopSelf()
            return START_NOT_STICKY
        }

        showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        removeBubble()
        try {
            unregisterReceiver(monitoringStateReceiver)
        } catch (_: Exception) {
        }
        instance = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

        val lp = bubbleParams ?: return
        lp.x = lp.x.coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(dp(36), (screenH - bubbleSize - dp(48)).coerceAtLeast(dp(36)))
        try {
            windowManager?.updateViewLayout(bubbleView, lp)
        } catch (_: Exception) {
        }
        followSpeedometer()
        saveBubblePosition(lp.x, lp.y)
    }

    // ------------------------------------------------------------ lifecycle

    private fun showBubble() {
        if (bubbleParams != null) {
            applyLevelFromPrefs()
            attachSpeedometer()
            followSpeedometer()
            syncForegroundState()
            return
        }
        windowManager = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager)

        bubbleView = FuelTankView(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
            level = FuelTankEngine.level(prefs)
            setOnTouchListener(BubbleTouchHandler())
        }

        val lp = overlayParams(bubbleSize, bubbleSize).apply {
            val savedX = prefs.bubbleXFraction
            val savedY = prefs.bubbleYFraction
            x = if (savedX >= 0f) {
                (savedX * screenW).roundToInt()
                    .coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
            } else {
                screenW - bubbleSize - dp(10)
            }
            y = if (savedY >= 0f) {
                (savedY * screenH).roundToInt()
                    .coerceIn(dp(36), (screenH - bubbleSize - dp(48)).coerceAtLeast(dp(36)))
            } else {
                (screenH * 0.42f).toInt()
            }
        }
        bubbleParams = lp

        try {
            windowManager?.addView(bubbleView, lp)
        } catch (e: Exception) {
            AppLogger.e("FuelBubbleService", "Falha ao adicionar bolinha: ${e.message}")
            stopSelf()
            return
        }

        attachSpeedometer()
        followSpeedometer()
        syncForegroundState()

        registerSensors()
        handler.removeCallbacks(fuelTicker)
        handler.post(fuelTicker)
    }

    private fun removeBubble() {
        closeMenu()
        hideKillZone()
        detachSpeedometer()
        stopForegroundIfNeeded()
        handler.removeCallbacks(fuelTicker)
        unregisterSensors()
        try {
            if (bubbleParams != null) windowManager?.removeView(bubbleView)
        } catch (_: Exception) {
        }
        bubbleParams = null
    }

    private fun applyLevelFromPrefs() {
        if (bubbleParams == null) return
        val level = FuelTankEngine.level(prefs)
        bubbleView.level = level
    }

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    // ------------------------------------------------------- speedometer

    /**
     * Cria a janela do velocímetro vinculada à bolinha. É idempotente: chamado
     * no início, no refresh e na recriação do serviço, nunca duplica janelas.
     */
    private fun attachSpeedometer() {
        if (speedOverlay != null || bubbleParams == null) return

        val overlay = SpeedReadoutOverlay(this)
        overlay.show()
        if (!overlay.isAttached) return
        speedOverlay = overlay
        followSpeedometer()

        val monitor = GpsSpeedMonitor(this)
        speedMonitor = monitor
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        speedScope = scope
        speedJob = scope.launch {
            monitor.state.collect { state -> speedOverlay?.render(state) }
        }
        // Só o texto muda quando a velocidade muda; a janela nunca é recriada.
        monitor.start()
    }

    private fun detachSpeedometer() {
        speedMonitor?.stop()
        speedMonitor = null
        speedJob?.cancel()
        speedJob = null
        speedScope?.cancel()
        speedScope = null
        speedOverlay?.hide()
        speedOverlay = null
    }

    /** Reposiciona o velocímetro a partir das dimensões reais da bolinha. */
    private fun followSpeedometer() {
        val overlay = speedOverlay ?: return
        val lp = bubbleParams ?: return
        overlay.follow(
            bubbleX = lp.x,
            bubbleY = lp.y,
            bubbleWidth = if (bubbleView.width > 0) bubbleView.width else bubbleSize,
            bubbleHeight = if (bubbleView.height > 0) bubbleView.height else bubbleSize,
            screenWidth = screenW,
            screenHeight = screenH
        )
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        if (screenW <= 0 || screenH <= 0) return
        AppLogger.d("FuelBubbleService", "saveBubblePosition x=$x y=$y screen=${screenW}x$screenH size=$bubbleSize")
        prefs.bubbleXFraction = ((x + bubbleSize / 2f) / screenW).coerceIn(0f, 1f)
        prefs.bubbleYFraction = ((y + bubbleSize / 2f) / screenH).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------- foreground

    private fun syncForegroundState() {
        // O monitoramento cobre o processo enquanto estiver realmente ativo.
        // A preferência é checada junto porque o broadcast de desligamento é
        // enviado antes de o MonitoringService terminar de encerrar.
        val monitoringCoversProcess = MonitoringService.isRunning() && prefs.isMonitoringActive
        if (bubbleParams != null && !monitoringCoversProcess) {
            ensureForeground()
        } else {
            stopForegroundIfNeeded()
        }
    }

    /**
     * Para o GPS continuar chegando com o app em segundo plano, o serviço da
     * bolinha vira primeiro plano com tipo `location` quando há permissão.
     * Havendo monitoramento ativo, a notificação dele já cobre o processo.
     */
    private fun ensureForeground() {
        if (bubbleParams == null) return
        val hasLocationPermission = GpsLocationStream.hasAnyLocationPermission(this)
        if (foregroundStarted && (foregroundHasLocation || !hasLocationPermission)) return

        val notification = buildOverlayNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (hasLocationPermission) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(OVERLAY_NOTIFICATION_ID, notification, types)
            } else {
                startForeground(OVERLAY_NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            foregroundHasLocation = hasLocationPermission
        } catch (error: Exception) {
            AppLogger.w("FuelBubbleService", "Falha ao entrar em primeiro plano: ${error.message}", error)
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(
                        OVERLAY_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(OVERLAY_NOTIFICATION_ID, notification)
                }
                foregroundStarted = true
                foregroundHasLocation = false
            } catch (_: Exception) {
            }
        }
    }

    private fun stopForegroundIfNeeded() {
        if (!foregroundStarted) return
        foregroundStarted = false
        foregroundHasLocation = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun buildOverlayNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            2101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, OVERLAY_CHANNEL_ID)
            .setContentTitle("DriFly • Indicador flutuante")
            .setContentText("Velocidade GPS e combustível ativos sobre os aplicativos")
            .setSmallIcon(R.drawable.ic_drifly_logo)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createOverlayNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(OVERLAY_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            OVERLAY_CHANNEL_ID,
            "Indicador flutuante DriFly",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantém a bolinha de combustível e o velocímetro GPS sobre outros aplicativos."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    // -------------------------------------------------------------- sensors

    private fun registerSensors() {
        if (sensorManager != null) return
        val manager = getSystemService(SENSOR_SERVICE) as? SensorManager ?: return
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                bubbleView.setTilt(event.values[0] / SensorManager.GRAVITY_EARTH * 0.9f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager = manager
        sensorListener = listener
        manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregisterSensors() {
        sensorListener?.let { sensorManager?.unregisterListener(it) }
        sensorListener = null
        sensorManager = null
    }

    // ----------------------------------------------------------------- menu

    private fun toggleMenu() {
        if (menuOpen) closeMenu() else openMenu()
    }

    private fun openMenu() {
        if (menuOpen) return
        val bubble = bubbleParams ?: return
        menuOpen = true
        bubbleView.menuMode = true
        // O leque ocupa o espaço acima da bolinha: o velocímetro sai de cena
        // apenas enquanto o menu está aberto e volta ao fechar.
        speedOverlay?.setVisible(false)

        val centerX = bubble.x + bubbleSize / 2f
        val centerY = bubble.y + bubbleSize / 2f
        val menuSize = (menuRadius * 2) + itemSize

        val container = FrameLayout(this)
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    closeMenu()
                    false
                }
                // A janela do leque fica acima da bolinha: um toque no X fecha o menu
                MotionEvent.ACTION_DOWN -> {
                    val insideBubble = hypot(
                        event.x - menuBubbleCenterX,
                        event.y - menuBubbleCenterY
                    ) <= bubbleSize / 2f + dp(4)
                    if (insideBubble) {
                        closeMenu()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        val lp = overlayParams(menuSize, menuSize).apply {
            x = (centerX - menuSize / 2f).toInt().coerceIn(0, (screenW - menuSize).coerceAtLeast(0))
            y = (centerY - menuSize / 2f).toInt().coerceIn(0, (screenH - menuSize).coerceAtLeast(0))
        }

        menuItems.clear()
        val specs = listOf(
            Triple("mon", R.drawable.ic_power_off, "Monitoramento"),
            Triple("casa", R.drawable.ic_home_mode, "Modo Casa"),
            Triple("desp", R.drawable.ic_plus_white, "Lançar despesa"),
            Triple("conf", R.drawable.ic_settings_gear, "Aceite e limites")
        )

        val rightSide = centerX > screenW / 2f
        val verticalBias = when {
            centerY < dp(190) -> 48f
            centerY > screenH - dp(210) -> -48f
            else -> 0f
        }
        val baseAngle = (if (rightSide) 180f else 0f) + (if (rightSide) -1f else 1f) * verticalBias

        val bubbleCenterInWindowX = centerX - lp.x
        val bubbleCenterInWindowY = centerY - lp.y
        menuBubbleCenterX = bubbleCenterInWindowX
        menuBubbleCenterY = bubbleCenterInWindowY

        specs.forEachIndexed { index, (key, icon, label) ->
            val angle = Math.toRadians((baseAngle + (index - (specs.size - 1) / 2f) * 42f).toDouble())
            val dx = cos(angle).toFloat() * menuRadius
            val dy = sin(angle).toFloat() * menuRadius

            val targetCenterX = (bubbleCenterInWindowX + dx)
                .coerceIn(itemSize / 2f, menuSize - itemSize / 2f)
            val targetCenterY = (bubbleCenterInWindowY + dy)
                .coerceIn(itemSize / 2f, menuSize - itemSize / 2f)

            val item = ImageView(this).apply {
                setImageResource(icon)
                contentDescription = label
                setPadding(dp(11), dp(11), dp(11), dp(11))
                layoutParams = FrameLayout.LayoutParams(itemSize, itemSize).apply {
                    leftMargin = (targetCenterX - itemSize / 2f).roundToInt()
                    topMargin = (targetCenterY - itemSize / 2f).roundToInt()
                }
                scaleX = 0.35f
                scaleY = 0.35f
                alpha = 0f
                translationX = -dx * 0.45f
                translationY = -dy * 0.45f
                setOnClickListener { handleMenuAction(key) }
            }
            container.addView(item)
            menuItems.add(item)
        }

        menuRoot = container
        menuParams = lp
        try {
            windowManager?.addView(container, lp)
        } catch (e: Exception) {
            AppLogger.e("FuelBubbleService", "Falha ao abrir leque: ${e.message}")
            menuRoot = null
            menuParams = null
            menuOpen = false
            bubbleView.menuMode = false
            speedOverlay?.setVisible(true)
            return
        }

        styleMenuItems()

        val interpolator = OvershootInterpolator(1.35f)
        menuItems.forEachIndexed { index, item ->
            item.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setStartDelay(index * 45L)
                .setDuration(300L)
                .setInterpolator(interpolator)
                .start()
        }
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        bubbleView.menuMode = false

        val container = menuRoot
        val items = menuItems.toList()
        menuItems.clear()
        menuRoot = null
        menuParams = null

        if (container == null) return

        items.forEachIndexed { index, item ->
            val cx = container.width / 2f - item.left
            val cy = container.height / 2f - item.top
            item.animate()
                .alpha(0f)
                .scaleX(0.4f)
                .scaleY(0.4f)
                .translationX(cx * 0.25f)
                .translationY(cy * 0.25f)
                .setStartDelay((items.size - 1 - index) * 25L)
                .setDuration(150L)
                .start()
        }

        handler.postDelayed({
            try {
                windowManager?.removeView(container)
            } catch (_: Exception) {
            }
            if (!menuOpen) {
                speedOverlay?.setVisible(true)
                followSpeedometer()
            }
        }, 320L)
    }

    private fun styleMenuItems() {
        val monitoringOn = prefs.isMonitoringActive
        val casaOn = prefs.isModoCasaActive

        menuItems.forEachIndexed { index, item ->
            val active = when (index) {
                0 -> monitoringOn
                1 -> casaOn
                else -> false
            }
            item.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF6F00"))
                if (active) setStroke(dp(2), Color.WHITE)
            }
            item.setColorFilter(if (active) Color.WHITE else Color.parseColor("#FFE9D6"))
            item.elevation = dp(6).toFloat()
        }
    }

    private fun handleMenuAction(key: String) {
        closeMenu()
        when (key) {
            "mon" -> {
                if (prefs.isMonitoringActive) {
                    MonitoringService.stop(this)
                    prefs.isMonitoringActive = false
                    toast("Monitoramento desligado")
                } else {
                    MonitoringService.start(this)
                    prefs.isMonitoringActive = true
                    toast("Monitoramento ligado")
                }
                syncForegroundState()
            }
            "casa" -> {
                prefs.isModoCasaActive = !prefs.isModoCasaActive
                toast(if (prefs.isModoCasaActive) "Modo Casa ativado" else "Modo Casa desativado")
            }
            "desp" -> openApp("expense")
            "conf" -> openApp("rides")
        }
        styleMenuItems()
    }

    private fun openApp(destination: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("drifly_destination", destination)
        }
        startActivity(intent)
    }

    // -------------------------------------------------------------- touch

    private inner class BubbleTouchHandler : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var originX = 0
        private var originY = 0
        private var dragging = false
        private var lastX = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val lp = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    originX = lp.x
                    originY = lp.y
                    lastX = event.rawX
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!dragging && hypot(dx, dy) > dp(8)) {
                        dragging = true
                        if (menuOpen) closeMenu()
                        showKillZone()
                    }
                    if (dragging) {
                        lp.x = (originX + dx).toInt().coerceIn(0, screenW - bubbleSize)
                        lp.y = (originY + dy).toInt().coerceIn(dp(36), screenH - bubbleSize - dp(48))
                        try {
                            windowManager?.updateViewLayout(bubbleView, lp)
                        } catch (_: Exception) {
                        }
                        followSpeedometer()
                        bubbleView.setTilt(((event.rawX - lastX) / dp(24)).coerceIn(-1f, 1f))
                        lastX = event.rawX
                        updateKillZoneState(lp)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        toggleMenu()
                        return true
                    }
                    hideKillZone()
                    bubbleView.alpha = 1f
                    if (isInsideKillZone(lp)) {
                        prefs.floatingBubbleEnabled = false
                        toast("Bolinha fechada")
                        removeBubble()
                        stopSelf()
                        return true
                    }
                    snapToEdge(lp)
                    saveBubblePosition(lp.x, lp.y)
                    return true
                }
            }
            return false
        }
    }

    // ----------------------------------------------------------- kill zone

    private fun showKillZone() {
        if (killParams != null) return
        val container = FrameLayout(this)
        container.background = getDrawable(R.drawable.bg_kill_zone)

        val cross = ImageView(this).apply {
            setImageResource(R.drawable.ic_overlay_close)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(34)).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(22)
                bottomMargin = dp(22)
            }
            alpha = 0.9f
        }
        container.addView(cross)
        container.alpha = 0.45f

        val lp = overlayParams(killZoneSize, killZoneSize).apply {
            x = screenW - killZoneSize
            y = screenH - killZoneSize
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        killParams = lp
        killRoot = container

        try {
            windowManager?.addView(container, lp)
        } catch (_: Exception) {
            killRoot = null
            killParams = null
        }
    }

    private fun hideKillZone() {
        val container = killRoot ?: return
        killRoot = null
        killParams = null
        try {
            windowManager?.removeView(container)
        } catch (_: Exception) {
        }
    }

    private fun updateKillZoneState(lp: WindowManager.LayoutParams) {
        val container = killRoot ?: return
        val inside = isInsideKillZone(lp)
        bubbleView.alpha = if (inside) 0.65f else 1f
        container.animate()
            .alpha(if (inside) 1f else 0.45f)
            .scaleX(if (inside) 1.12f else 1f)
            .scaleY(if (inside) 1.12f else 1f)
            .setDuration(140L)
            .start()
    }

    private fun isInsideKillZone(lp: WindowManager.LayoutParams): Boolean {
        val bubbleCx = lp.x + bubbleSize / 2f
        val bubbleCy = lp.y + bubbleSize / 2f
        val zoneCx = screenW - killZoneSize / 2f
        val zoneCy = screenH - killZoneSize / 2f
        return hypot(bubbleCx - zoneCx, bubbleCy - zoneCy) < dp(96)
    }

    // ---------------------------------------------------------------- misc

    private fun snapToEdge(lp: WindowManager.LayoutParams) {
        val targetX = if (lp.x + bubbleSize / 2f < screenW / 2f) dp(8) else screenW - bubbleSize - dp(8)
        bubbleView.alpha = 1f
        saveBubblePosition(targetX, lp.y)
        ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 180
            addUpdateListener { animator ->
                lp.x = animator.animatedValue as Int
                try {
                    windowManager?.updateViewLayout(bubbleView, lp)
                } catch (_: Exception) {
                }
                followSpeedometer()
            }
            start()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
