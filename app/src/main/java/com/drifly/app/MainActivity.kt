package com.drifly.app

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var db: DatabaseHelper

    // Topo
    private lateinit var tvSubtitle: TextView
    private lateinit var btnToggleHomeMode: ImageButton
    private lateinit var btnOpenSettings: ImageButton

    // Banner de Notificações Customizado (Microinterações)
    private lateinit var layoutNotificationBanner: View
    private lateinit var ivBannerIcon: ImageView
    private lateinit var tvBannerMessage: TextView
    private val bannerHandler = Handler(Looper.getMainLooper())
    private var bannerDismissRunnable: Runnable? = null

    // Navegação e Abas
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var viewTabFinance: View
    private lateinit var viewTabRides: View
    private lateinit var viewTabSupport: View

    // Aba 1: Finanças & Resultados
    private lateinit var tvGoalProgress: TextView
    private lateinit var pbGoal: ProgressBar
    private lateinit var tvGrossToday: TextView
    private lateinit var tvExpensesToday: TextView
    private lateinit var tvNetToday: TextView
    private lateinit var btnAddExpense: View

    // Botão de Histórico de Corridas
    private lateinit var btnOpenFullHistory: View

    private lateinit var rgGoalType: RadioGroup
    private lateinit var etGoalTarget: EditText
    private lateinit var btnSaveGoalOnly: View

    // Card Proeminente de Ligar / Desligar Monitoramento (Entre Meta e Rodapé)
    private lateinit var cardMonitoringToggle: LinearLayout
    private lateinit var flMonitoringIconContainer: FrameLayout
    private lateinit var ivMonitoringPowerIcon: ImageView
    private lateinit var tvMonitoringTitle: TextView
    private lateinit var tvMonitoringSubtitle: TextView
    private lateinit var tvMonitoringBadge: TextView

    // Aba 2: Configurações de Corrida & Modo Casa
    private lateinit var rgOverlayPosition: RadioGroup
    private lateinit var swAutoAccept: Switch
    private lateinit var swAutoDecline: Switch
    private lateinit var swMotoMode: Switch
    private lateinit var etMinRate: EditText
    private lateinit var etMaxDist: EditText
    private lateinit var etMinRating: EditText
    private lateinit var etFuelPrice: EditText
    private lateinit var etFuelConsumption: EditText
    private lateinit var etMaintenance: EditText

    private lateinit var cardConfigModoCasa: LinearLayout
    private lateinit var etHomeAddress: EditText
    private lateinit var btnSaveHomeAddress: Button
    private lateinit var btnUseCurrentLocationAsHome: Button
    private lateinit var tvHomeConfigStatus: TextView

    private lateinit var btnSaveRideSettings: Button

    // Aba 3: Apoio ao Projeto & Doações
    private lateinit var tvPixKeyText: TextView
    private lateinit var btnCopyPixKey: View
    private lateinit var chipDonation5: TextView
    private lateinit var chipDonation10: TextView
    private lateinit var chipDonation20: TextView
    private lateinit var chipDonation50: TextView

    // Abertura Cinematográfica (Cold Start Video Splash)
    private lateinit var layoutSplashOverlay: FrameLayout
    private lateinit var textureSplashVideo: TextureView
    private lateinit var ivSplashPoster: ImageView
    private var splashMediaPlayer: MediaPlayer? = null
    private var splashSurface: Surface? = null
    private var isSplashFinishing = false
    private val splashFallbackRunnable = Runnable { finishSplashOverlay() }

    private var hasShownInitialPerms = false
    private var activePeriod = "DAILY"

    // Launcher do Seletor de Arquivos para Restauração de Backup
    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handleBackupImport(uri)
        }
    }

    // Receptor de Broadcast para sincronizar estado do Monitoramento em Tempo Real
    private val monitoringStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateMonitoringToggleUI(prefs.isMonitoringActive)
        }
    }

    private var activeReportDialogRefresh: (() -> Unit)? = null

    // Receptor de Broadcast para atualizações de corridas (novas ofertas, conclusões, cancelamentos)
    private val rideUpdatesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateDashboard(animate = true)
            activeReportDialogRefresh?.invoke()

            if (intent?.action == RideNotificationListenerService.ACTION_RIDE_CANCELLED_DETECTED) {
                val fee = intent.getDoubleExtra("EXTRA_FEE", 0.0)
                val platform = intent.getStringExtra("EXTRA_PLATFORM") ?: "App"
                val ptBr = Locale("pt", "BR")
                if (fee > 0.0) {
                    showNotificationBanner("Cancelamento $platform detectado! Taxa de ${String.format(ptBr, "R$ %.2f", fee)} lançada.", BannerType.INFO)
                } else {
                    showNotificationBanner("Cancelamento $platform detectado sem taxa.", BannerType.INFO)
                }
            }
        }
    }

    enum class BannerType {
        SUCCESS, ERROR, WARNING, INFO
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_main)

        prefs = PreferencesManager(this)
        db = DatabaseHelper(this)

        initViews()
        loadPreferences()
        setupListeners()

        // Agenda tarefas automáticas em segundo plano (Backup 00:00 e Lembretes 08h, 15h, 20h)
        DailyBackupWorker.scheduleDailyBackup(this)
        SupportNotificationWorker.scheduleAllSupportReminders(this)

        // Registra o broadcast receiver para mudanças no estado do monitoramento e odômetro
        val filter = IntentFilter().apply {
            addAction(MonitoringService.ACTION_MONITORING_CHANGED)
            addAction(TripOdometerManager.ACTION_ODOMETER_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(monitoringStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(monitoringStateReceiver, filter)
        }

        // Registra receiver para atualizações em tempo real do extrato e cancelamentos
        val rideFilter = IntentFilter().apply {
            addAction(RotaAccessibilityService.ACTION_RIDE_UPDATED)
            addAction(RideNotificationListenerService.ACTION_RIDE_CANCELLED_DETECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rideUpdatesReceiver, rideFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(rideUpdatesReceiver, rideFilter)
        }

        handleIncomingTabIntent(intent)

        // Se nenhuma aba específica foi solicitada, abre Resultados & Finanças
        if (intent.getStringExtra(EXTRA_OPEN_TAB) != "SUPPORT") {
            bottomNavigation.selectedItemId = R.id.nav_finance
            switchToTab(R.id.nav_finance)
        }

        updateDashboard(animate = false)
        updateHomeToggleVisual()
        updateMonitoringToggleUI(prefs.isMonitoringActive)

        val isBackgroundShortcutIntent = intent?.getStringExtra(EXTRA_OPEN_TAB) != null ||
            intent?.getBooleanExtra("INSERT_TEST_RIDE", false) == true

        if (consumeColdStartSplashEligibility(savedInstanceState != null, isBackgroundShortcutIntent)) {
            playColdStartSplash {
                checkAndShowInitialPermissionsDialog()
            }
        } else {
            checkAndShowInitialPermissionsDialog()
        }
    }

    private fun checkAndShowInitialPermissionsDialog() {
        if (isFinishing || isDestroyed) return
        // Solicita as permissões essenciais na primeira inicialização caso estejam pendentes
        if (!hasAllPermissions() && !hasShownInitialPerms) {
            hasShownInitialPerms = true
            showPermissionsDialog()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingTabIntent(intent)
    }

    private fun handleIncomingTabIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN_TAB) == "SUPPORT") {
            bottomNavigation.selectedItemId = R.id.nav_support
            switchToTab(R.id.nav_support)
        }
        if (intent?.getBooleanExtra("INSERT_TEST_RIDE", false) == true) {
            val platform = intent.getStringExtra("PLATFORM") ?: "99"
            val gross = intent.getDoubleExtra("GROSS", 25.0)
            val distance = intent.getDoubleExtra("DISTANCE", 6.0)
            val net = intent.getDoubleExtra("NET", 20.0)
            val status = intent.getStringExtra("STATUS") ?: RideRecord.STATUS_ESTIMADO
            db.insertRide(platform, gross, distance, net, status)
            updateDashboard(animate = true)
            activeReportDialogRefresh?.invoke()
        }
    }

    private var activePermissionsDialogRefresh: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        updateDashboard(animate = true)
        updateHomeToggleVisual()
        updateMonitoringToggleUI(prefs.isMonitoringActive)
        activePermissionsDialogRefresh?.invoke()
        UpdateManager.checkAndResumePendingInstall(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        bannerHandler.removeCallbacks(splashFallbackRunnable)
        releaseSplashPlayer()
        try {
            unregisterReceiver(monitoringStateReceiver)
        } catch (ignored: Exception) {}
        try {
            unregisterReceiver(rideUpdatesReceiver)
        } catch (ignored: Exception) {}
    }

    private fun hasAllPermissions(): Boolean {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val hasA11y = isAccessibilityServiceEnabled(this, RotaAccessibilityService::class.java)
        val hasBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
        val hasNotif = OemCompatibilityHelper.isNotificationListenerEnabled(this)
        return hasOverlay && hasA11y && hasBattery && hasNotif
    }

    private fun initViews() {
        tvSubtitle = findViewById(R.id.tvSubtitle)
        btnToggleHomeMode = findViewById(R.id.btnToggleHomeMode)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)

        // Banner de Alerta Flutuante
        layoutNotificationBanner = findViewById(R.id.layoutNotificationBanner)
        ivBannerIcon = findViewById(R.id.ivBannerIcon)
        tvBannerMessage = findViewById(R.id.tvBannerMessage)

        bottomNavigation = findViewById(R.id.bottomNavigation)
        viewTabFinance = findViewById(R.id.viewTabFinance)
        viewTabRides = findViewById(R.id.viewTabRides)
        viewTabSupport = findViewById(R.id.viewTabSupport)

        // Aba 1: Finanças
        tvGoalProgress = findViewById(R.id.tvGoalProgress)
        pbGoal = findViewById(R.id.pbGoal)
        tvGrossToday = findViewById(R.id.tvGrossToday)
        tvExpensesToday = findViewById(R.id.tvExpensesToday)
        tvNetToday = findViewById(R.id.tvNetToday)
        btnAddExpense = findViewById(R.id.btnAddExpense)

        btnOpenFullHistory = findViewById(R.id.btnOpenFullHistory)

        rgGoalType = findViewById(R.id.rgGoalType)
        etGoalTarget = findViewById(R.id.etGoalTarget)
        btnSaveGoalOnly = findViewById(R.id.btnSaveGoalOnly)

        // Botão Flutuante Proeminente de Ligar/Desligar Monitoramento
        cardMonitoringToggle = findViewById(R.id.cardMonitoringToggle)
        flMonitoringIconContainer = findViewById(R.id.flMonitoringIconContainer)
        ivMonitoringPowerIcon = findViewById(R.id.ivMonitoringPowerIcon)
        tvMonitoringTitle = findViewById(R.id.tvMonitoringTitle)
        tvMonitoringSubtitle = findViewById(R.id.tvMonitoringSubtitle)
        tvMonitoringBadge = findViewById(R.id.tvMonitoringBadge)

        // Aba 2: Corridas
        rgOverlayPosition = findViewById(R.id.rgOverlayPosition)
        swAutoAccept = findViewById(R.id.swAutoAccept)
        swAutoDecline = findViewById(R.id.swAutoDecline)
        swMotoMode = findViewById(R.id.swMotoMode)
        etMinRate = findViewById(R.id.etMinRate)
        etMaxDist = findViewById(R.id.etMaxDist)
        etMinRating = findViewById(R.id.etMinRating)
        etFuelPrice = findViewById(R.id.etFuelPrice)
        etFuelConsumption = findViewById(R.id.etFuelConsumption)
        etMaintenance = findViewById(R.id.etMaintenance)

        cardConfigModoCasa = findViewById(R.id.cardConfigModoCasa)
        etHomeAddress = findViewById(R.id.etHomeAddress)
        btnSaveHomeAddress = findViewById(R.id.btnSaveHomeAddress)
        btnUseCurrentLocationAsHome = findViewById(R.id.btnUseCurrentLocationAsHome)
        tvHomeConfigStatus = findViewById(R.id.tvHomeConfigStatus)

        btnSaveRideSettings = findViewById(R.id.btnSaveRideSettings)

        // Aba 3: Apoiar
        tvPixKeyText = findViewById(R.id.tvPixKeyText)
        btnCopyPixKey = findViewById(R.id.btnCopyPixKey)
        chipDonation5 = findViewById(R.id.chipDonation5)
        chipDonation10 = findViewById(R.id.chipDonation10)
        chipDonation20 = findViewById(R.id.chipDonation20)
        chipDonation50 = findViewById(R.id.chipDonation50)

        // Splash de Abertura (Cold Start)
        layoutSplashOverlay = findViewById(R.id.layoutSplashOverlay)
        textureSplashVideo = findViewById(R.id.textureSplashVideo)
        ivSplashPoster = findViewById(R.id.ivSplashPoster)

        // Aplica suporte a vírgula e ponto em todos os campos decimais
        enableCommaAndDotDecimalInput(
            etGoalTarget,
            etMinRate,
            etMaxDist,
            etMinRating,
            etFuelPrice,
            etFuelConsumption,
            etMaintenance
        )

        // Aplica microinterações táteis nos botões de destaque
        applyScaleTouchFeedback(btnAddExpense)
        applyScaleTouchFeedback(btnSaveGoalOnly)
        applyScaleTouchFeedback(btnOpenFullHistory)
        applyScaleTouchFeedback(cardMonitoringToggle)
        applyScaleTouchFeedback(btnSaveRideSettings)
        applyScaleTouchFeedback(btnSaveHomeAddress)
        applyScaleTouchFeedback(btnUseCurrentLocationAsHome)
        applyScaleTouchFeedback(btnCopyPixKey)
        applyScaleTouchFeedback(chipDonation5)
        applyScaleTouchFeedback(chipDonation10)
        applyScaleTouchFeedback(chipDonation20)
        applyScaleTouchFeedback(chipDonation50)
    }

    private fun applyScaleTouchFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }
            false
        }
    }

    /**
     * Atualiza a identidade visual do botão flutuante de monitoramento:
     * - Quando ATIVO: Glassmorphism com contorno verde esmeralda, badge ATIVO verde.
     * - Quando DESLIGADO: Dark glass com detalhes em laranja e badge DESLIGADO.
     */
    private fun updateMonitoringToggleUI(isActive: Boolean) {
        if (isActive) {
            cardMonitoringToggle.setBackgroundResource(R.drawable.bg_card_monitoring_active)
            ivMonitoringPowerIcon.setColorFilter(0xFF31D477.toInt())
            tvMonitoringTitle.text = "MONITORAMENTO ATIVO"
            tvMonitoringTitle.setTextColor(0xFFFFFFFF.toInt())
            val shiftKm = prefs.shiftDistanceKm
            if (shiftKm > 0.05f) {
                val shiftCost = shiftKm * prefs.costPerKm
                tvMonitoringSubtitle.text = String.format(Locale("pt", "BR"), "Turno: %.1f km rodados • Combustível: -R$ %.2f", shiftKm, shiftCost)
            } else {
                tvMonitoringSubtitle.text = "Uber e 99 sendo monitorados • Toque para pausar"
            }
            tvMonitoringBadge.text = "ATIVO"
            tvMonitoringBadge.setBackgroundResource(R.drawable.bg_pill_green)
            tvMonitoringBadge.setTextColor(0xFF31D477.toInt())
        } else {
            cardMonitoringToggle.setBackgroundResource(R.drawable.bg_card_monitoring_inactive)
            ivMonitoringPowerIcon.setColorFilter(0xFFFF6900.toInt())
            tvMonitoringTitle.text = "LIGAR MONITORAMENTO"
            tvMonitoringTitle.setTextColor(0xFFF5F5F7.toInt())
            val shiftKm = prefs.shiftDistanceKm
            if (shiftKm > 0.05f) {
                tvMonitoringSubtitle.text = String.format(Locale("pt", "BR"), "Último turno: %.1f km • Toque para novo turno", shiftKm)
            } else {
                tvMonitoringSubtitle.text = "Toque para iniciar o cálculo contínuo em segundo plano"
            }
            tvMonitoringBadge.text = "DESLIGADO"
            tvMonitoringBadge.setBackgroundResource(R.drawable.bg_pill_orange)
            tvMonitoringBadge.setTextColor(0xFFFF6900.toInt())
        }
    }

    private fun switchToTab(itemId: Int) {
        viewTabFinance.visibility = View.GONE
        viewTabRides.visibility = View.GONE
        viewTabSupport.visibility = View.GONE

        when (itemId) {
            R.id.nav_finance -> {
                viewTabFinance.visibility = View.VISIBLE
                viewTabFinance.alpha = 0f
                viewTabFinance.animate().alpha(1f).setDuration(180).start()
                tvSubtitle.text = "Painel de Resultados"
                updateDashboard(animate = false)
            }
            R.id.nav_rides -> {
                viewTabRides.visibility = View.VISIBLE
                viewTabRides.alpha = 0f
                viewTabRides.animate().alpha(1f).setDuration(180).start()
                tvSubtitle.text = "Configurações de Corrida"
            }
            R.id.nav_support -> {
                viewTabSupport.visibility = View.VISIBLE
                viewTabSupport.alpha = 0f
                viewTabSupport.animate().alpha(1f).setDuration(180).start()
                tvSubtitle.text = "Apoio ao Projeto DriFly"
            }
        }
    }

    fun showNotificationBanner(message: String, type: BannerType = BannerType.SUCCESS) {
        bannerDismissRunnable?.let { bannerHandler.removeCallbacks(it) }

        val iconRes = when (type) {
            BannerType.SUCCESS -> R.drawable.ic_banner_check
            BannerType.ERROR -> R.drawable.ic_banner_alert
            BannerType.WARNING -> R.drawable.ic_banner_info
            BannerType.INFO -> R.drawable.ic_banner_info
        }

        ivBannerIcon.setImageResource(iconRes)
        tvBannerMessage.text = message

        layoutNotificationBanner.visibility = View.VISIBLE
        layoutNotificationBanner.alpha = 0f
        layoutNotificationBanner.translationY = -120f

        layoutNotificationBanner.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .setListener(null)
            .start()

        val dismissRunnable = Runnable {
            layoutNotificationBanner.animate()
                .translationY(-120f)
                .alpha(0f)
                .setDuration(280)
                .setInterpolator(AccelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        layoutNotificationBanner.visibility = View.GONE
                    }
                })
                .start()
        }
        bannerDismissRunnable = dismissRunnable
        bannerHandler.postDelayed(dismissRunnable, 2800)
    }

    private fun loadPreferences() {
        val ptBr = Locale("pt", "BR")

        // Aba 1
        etGoalTarget.setText(String.format(ptBr, "%.2f", prefs.goalTargetAmount))
        when (prefs.goalType) {
            "WEEKLY" -> rgGoalType.check(R.id.rbWeekly)
            "MONTHLY" -> rgGoalType.check(R.id.rbMonthly)
            else -> rgGoalType.check(R.id.rbDaily)
        }

        // Aba 2
        when (prefs.overlayPosition) {
            "BOTTOM" -> rgOverlayPosition.check(R.id.rbPosBottom)
            "CENTER" -> rgOverlayPosition.check(R.id.rbPosCenter)
            else -> rgOverlayPosition.check(R.id.rbPosTop)
        }

        swAutoAccept.isChecked = prefs.autoAcceptEnabled
        swAutoDecline.isChecked = prefs.autoDeclineEnabled
        swMotoMode.isChecked = prefs.isMotorcycleMode

        etMinRate.setText(String.format(ptBr, "%.2f", prefs.minRatePerKm))
        etMaxDist.setText(String.format(ptBr, "%.1f", prefs.maxDistanceKm))
        etMinRating.setText(String.format(ptBr, "%.2f", prefs.minPassengerRating))

        etFuelPrice.setText(String.format(ptBr, "%.2f", prefs.fuelPrice))
        etFuelConsumption.setText(String.format(ptBr, "%.1f", prefs.fuelConsumption))
        etMaintenance.setText(String.format(ptBr, "%.2f", prefs.maintenanceCostPerKm))

        // Modo Casa
        etHomeAddress.setText(prefs.homeAddress)
        updateHomeConfigStatus()
    }

    private fun enableCommaAndDotDecimalInput(vararg fields: EditText) {
        val allowedChars = android.text.method.DigitsKeyListener.getInstance("0123456789,.")
        for (field in fields) {
            field.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            field.keyListener = allowedChars
        }
    }

    private fun parseBrFloat(text: String, defaultVal: Float): Float {
        return ParsingEngine.parseFlexibleFloat(text, defaultVal)
    }

    private fun setupListeners() {
        bottomNavigation.setOnItemSelectedListener { item ->
            switchToTab(item.itemId)
            true
        }

        btnToggleHomeMode.setOnClickListener {
            if (prefs.homeAddress.isBlank() && prefs.homeLatitude == 0f && prefs.homeLongitude == 0f) {
                showNotificationBanner("Configure o endereço da sua casa na aba Corridas!", BannerType.WARNING)
                bottomNavigation.selectedItemId = R.id.nav_rides
                switchToTab(R.id.nav_rides)
            } else {
                prefs.isModoCasaActive = !prefs.isModoCasaActive
                updateHomeToggleVisual()
                if (prefs.isModoCasaActive) {
                    showNotificationBanner("🏠 Modo Casa ATIVADO! Novas corridas destacarão rumo a casa.", BannerType.SUCCESS)
                } else {
                    showNotificationBanner("Modo Casa Desativado.", BannerType.INFO)
                }
            }
        }

        btnOpenSettings.setOnClickListener {
            showPermissionsDialog()
        }

        // Lógica de Toggle do Monitoramento Contínuo
        cardMonitoringToggle.setOnClickListener {
            if (prefs.isMonitoringActive) {
                // Desliga o monitoramento e cancela todas as notificações, lembretes de background e bolhas ativas
                MonitoringService.stop(this)
                BubbleOverlayService.stop(this)
                SupportNotificationWorker.cancelAllSupportReminders(this)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancelAll()
                manager?.cancel(MonitoringService.NOTIFICATION_ID)
                manager?.cancel(BubbleOverlayService.NOTIFICATION_ID)
                manager?.cancel(SupportNotificationWorker.NOTIFICATION_ID)
                prefs.isMonitoringActive = false
                updateMonitoringToggleUI(false)
                showNotificationBanner("Monitoramento desativado.", BannerType.INFO)
            } else {
                // Valida permissões antes de ligar
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    showNotificationBanner("Permissão de sobreposição necessária para o monitoramento", BannerType.WARNING)
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    return@setOnClickListener
                }

                if (!isAccessibilityServiceEnabled(this, RotaAccessibilityService::class.java)) {
                    showNotificationBanner("Ative o Serviço de Acessibilidade do DriFly", BannerType.WARNING)
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnClickListener
                }

                val permsToRequest = mutableListOf<String>()
                val hasFineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarseLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasFineLoc && !hasCoarseLoc) {
                    permsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                    permsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                if (permsToRequest.isNotEmpty()) {
                    requestPermissions(permsToRequest.toTypedArray(), 101)
                }

                MonitoringService.start(this)
                SupportNotificationWorker.scheduleAllSupportReminders(this)
                prefs.isMonitoringActive = true
                updateMonitoringToggleUI(true)
                showNotificationBanner("Monitoramento DriFly iniciado! Notificação contínua ativa.", BannerType.SUCCESS)
            }
        }

        btnSaveGoalOnly.setOnClickListener {
            prefs.goalTargetAmount = parseBrFloat(etGoalTarget.text.toString(), prefs.goalTargetAmount)
            prefs.goalType = when (rgGoalType.checkedRadioButtonId) {
                R.id.rbWeekly -> "WEEKLY"
                R.id.rbMonthly -> "MONTHLY"
                else -> "DAILY"
            }
            showNotificationBanner("Meta atualizada com sucesso!", BannerType.SUCCESS)
            updateDashboard(animate = true)
        }

        btnSaveRideSettings.setOnClickListener {
            prefs.overlayPosition = when (rgOverlayPosition.checkedRadioButtonId) {
                R.id.rbPosBottom -> "BOTTOM"
                R.id.rbPosCenter -> "CENTER"
                else -> "TOP"
            }

            prefs.autoAcceptEnabled = swAutoAccept.isChecked
            prefs.autoDeclineEnabled = swAutoDecline.isChecked
            prefs.isMotorcycleMode = swMotoMode.isChecked

            prefs.minRatePerKm = parseBrFloat(etMinRate.text.toString(), prefs.minRatePerKm)
            prefs.maxDistanceKm = parseBrFloat(etMaxDist.text.toString(), prefs.maxDistanceKm)
            prefs.minPassengerRating = parseBrFloat(etMinRating.text.toString(), prefs.minPassengerRating)

            prefs.fuelPrice = parseBrFloat(etFuelPrice.text.toString(), prefs.fuelPrice)
            prefs.fuelConsumption = parseBrFloat(etFuelConsumption.text.toString(), prefs.fuelConsumption)
            prefs.maintenanceCostPerKm = parseBrFloat(etMaintenance.text.toString(), prefs.maintenanceCostPerKm)

            showNotificationBanner("Configurações salvas com sucesso!", BannerType.SUCCESS)
        }

        btnSaveHomeAddress.setOnClickListener {
            val addr = etHomeAddress.text.toString().trim()
            if (addr.isNotEmpty()) {
                val coords = ModoCasaHelper.geocodeAddress(this, addr)
                if (coords != null) {
                    prefs.homeAddress = addr
                    prefs.homeLatitude = coords.first.toFloat()
                    prefs.homeLongitude = coords.second.toFloat()
                    updateHomeConfigStatus()
                    showNotificationBanner("Endereço residencial salvo com sucesso!", BannerType.SUCCESS)
                } else {
                    prefs.homeAddress = addr
                    updateHomeConfigStatus()
                    showNotificationBanner("Endereço salvo! (Coordenadas pendentes de rede)", BannerType.INFO)
                }
            } else {
                prefs.homeAddress = ""
                prefs.homeLatitude = 0f
                prefs.homeLongitude = 0f
                updateHomeConfigStatus()
                showNotificationBanner("Endereço residencial removido.", BannerType.INFO)
            }
        }

        btnUseCurrentLocationAsHome.setOnClickListener {
            val loc = ModoCasaHelper.getLastKnownLocation(this)
            if (loc != null) {
                prefs.homeLatitude = loc.latitude.toFloat()
                prefs.homeLongitude = loc.longitude.toFloat()
                try {
                    val geocoder = Geocoder(this, Locale("pt", "BR"))
                    val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                    if (!list.isNullOrEmpty()) {
                        val resolved = list[0].getAddressLine(0) ?: "Minha Residência"
                        prefs.homeAddress = resolved
                        etHomeAddress.setText(resolved)
                    } else {
                        val fallback = "GPS: ${String.format(Locale.US, "%.4f, %.4f", loc.latitude, loc.longitude)}"
                        prefs.homeAddress = fallback
                        etHomeAddress.setText(fallback)
                    }
                } catch (e: Exception) {
                    val fallback = "GPS: ${String.format(Locale.US, "%.4f, %.4f", loc.latitude, loc.longitude)}"
                    prefs.homeAddress = fallback
                    etHomeAddress.setText(fallback)
                }
                updateHomeConfigStatus()
                showNotificationBanner("Localização GPS atual definida como Casa!", BannerType.SUCCESS)
            } else {
                showNotificationBanner("Não foi possível obter sinal GPS atual. Ative a localização.", BannerType.WARNING)
            }
        }

        btnAddExpense.setOnClickListener { showAddExpenseDialog() }

        btnOpenFullHistory.setOnClickListener {
            showReportDialog("DAILY", "Histórico de Corridas")
        }

        // Aba de Apoio / Doações PIX
        val pixKey = "2953f386-d6e1-4ce7-8a4b-17f7237e9c32"

        btnCopyPixKey.setOnClickListener {
            copyPixKeyToClipboard(pixKey, "Chave Pix copiada com sucesso! Muito obrigado pelo seu apoio!")
        }

        chipDonation5.setOnClickListener {
            copyPixKeyToClipboard(pixKey, "Chave Pix copiada para apoiar com R$ 5,00! Obrigado parceiro(a) ☕")
        }

        chipDonation10.setOnClickListener {
            copyPixKeyToClipboard(pixKey, "Chave Pix copiada para apoiar com R$ 10,00! Muito obrigado 🥪")
        }

        chipDonation20.setOnClickListener {
            copyPixKeyToClipboard(pixKey, "Chave Pix copiada para apoiar com R$ 20,00! Você fortalece a comunidade ⛽")
        }

        chipDonation50.setOnClickListener {
            copyPixKeyToClipboard(pixKey, "Chave Pix copiada para apoiar com R$ 50,00! Gratidão de coração ⭐")
        }
    }

    private fun copyPixKeyToClipboard(key: String, feedbackMessage: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Chave Pix DriFly", key)
        clipboard.setPrimaryClip(clip)

        cardMonitoringToggle.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        Toast.makeText(this, "Chave copiada: $key", Toast.LENGTH_SHORT).show()
        showNotificationBanner(feedbackMessage, BannerType.SUCCESS)
    }

    private fun updateDashboard(animate: Boolean = false) {
        val ptBr = Locale("pt", "BR")
        val summary = db.getSummary(prefs.goalType)
        tvGrossToday.text = String.format(ptBr, "R$ %.2f", summary.totalGross)
        tvExpensesToday.text = String.format(ptBr, "R$ %.2f", summary.totalExpenses)
        tvNetToday.text = String.format(ptBr, "R$ %.2f", summary.totalNet)

        val target = prefs.goalTargetAmount.toDouble()
        val percent = if (target > 0) ((summary.totalNet / target) * 100).toInt().coerceIn(0, 100) else 0

        val visualProgress = if (percent > 0) percent else 2

        if (animate) {
            val anim = ObjectAnimator.ofInt(pbGoal, "progress", pbGoal.progress, visualProgress)
            anim.duration = 650
            anim.interpolator = DecelerateInterpolator()
            anim.start()
        } else {
            pbGoal.progress = visualProgress
        }

        val targetFormatted = String.format(ptBr, "R$ %.2f", target)
        val reachedFormatted = String.format(ptBr, "R$ %.2f", summary.totalNet)
        val ssb = android.text.SpannableStringBuilder()
        ssb.append("Meta: ")
        val startTarget = ssb.length
        ssb.append(targetFormatted)
        ssb.setSpan(android.text.style.ForegroundColorSpan(0xFFFF6900.toInt()), startTarget, ssb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), startTarget, ssb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val startReached = ssb.length
        ssb.append(" (Atingido: $reachedFormatted – $percent%)")
        ssb.setSpan(android.text.style.ForegroundColorSpan(0xFFA3A8B5.toInt()), startReached, ssb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        tvGoalProgress.text = ssb
    }

    private fun updateHomeToggleVisual() {
        if (prefs.isModoCasaActive) {
            btnToggleHomeMode.setBackgroundResource(R.drawable.bg_verdict_good)
            btnToggleHomeMode.setColorFilter(0xFF31D477.toInt())
        } else {
            btnToggleHomeMode.setBackgroundResource(R.drawable.bg_btn_circle_glass)
            btnToggleHomeMode.setColorFilter(0xFFF5F5F7.toInt())
        }
    }

    private fun updateHomeConfigStatus() {
        val ptBr = Locale("pt", "BR")
        if (prefs.homeAddress.isNotBlank() || (prefs.homeLatitude != 0f && prefs.homeLongitude != 0f)) {
            val addr = if (prefs.homeAddress.isNotBlank()) prefs.homeAddress else "GPS (${String.format(ptBr, "%.4f, %.4f", prefs.homeLatitude, prefs.homeLongitude)})"
            tvHomeConfigStatus.text = "✓ Casa configurada: $addr"
            tvHomeConfigStatus.setTextColor(0xFF31D477.toInt())
        } else {
            tvHomeConfigStatus.text = "⚠️ Casa ainda não configurada."
            tvHomeConfigStatus.setTextColor(0xFFFBBF24.toInt())
        }
    }

    /**
     * Modal de Configurações Redesenhado com Accordion:
     * - Seção 1: Permissões do Sistema (Sobrepor, Acessibilidade, Bateria, Notificações)
     * - Seção 2: Backup & Restauração (Automático às 00:00)
     * - Seção 3: Atualizações do Aplicativo (GitHub)
     * - Seção 4: Compatibilidade do Aparelho (Xiaomi/HyperOS, Samsung, Motorola, Android 13+)
     */
    private fun showPermissionsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_permissions, null)

        val layoutAccordionContainer = view.findViewById<ViewGroup>(R.id.layoutAccordionContainer)

        // Seção 1: Permissões
        val headerPerms = view.findViewById<View>(R.id.headerPermsAccordion)
        val contentPerms = view.findViewById<View>(R.id.contentPermsAccordion)
        val ivPermsChevron = view.findViewById<ImageView>(R.id.ivPermsChevron)
        val tvPermsHeaderStatus = view.findViewById<TextView>(R.id.tvPermsHeaderStatus)

        val tvOverlay = view.findViewById<TextView>(R.id.tvDiagOverlayStatus)
        val tvA11y = view.findViewById<TextView>(R.id.tvDiagA11yStatus)
        val tvBattery = view.findViewById<TextView>(R.id.tvDiagBatteryStatus)
        val tvNotif = view.findViewById<TextView>(R.id.tvDiagNotifStatus)
        val btnOverlay = view.findViewById<Button>(R.id.btnDiagPermOverlay)
        val btnA11y = view.findViewById<Button>(R.id.btnDiagPermA11y)
        val btnBattery = view.findViewById<Button>(R.id.btnDiagPermBattery)
        val btnNotif = view.findViewById<Button>(R.id.btnDiagPermNotif)

        // Seção 2: Backup
        val headerBackup = view.findViewById<View>(R.id.headerBackupAccordion)
        val contentBackup = view.findViewById<View>(R.id.contentBackupAccordion)
        val ivBackupChevron = view.findViewById<ImageView>(R.id.ivBackupChevron)
        val tvLastBackupInfo = view.findViewById<TextView>(R.id.tvLastBackupInfo)
        val btnExportBackup = view.findViewById<Button>(R.id.btnExportBackup)
        val btnImportBackup = view.findViewById<Button>(R.id.btnImportBackup)

        // Seção 3: Atualizações
        val headerUpdate = view.findViewById<View>(R.id.headerUpdateAccordion)
        val contentUpdate = view.findViewById<View>(R.id.contentUpdateAccordion)
        val ivUpdateChevron = view.findViewById<ImageView>(R.id.ivUpdateChevron)
        val tvUpdateHeaderStatus = view.findViewById<TextView>(R.id.tvUpdateHeaderStatus)
        val btnCheckAppUpdates = view.findViewById<Button>(R.id.btnCheckAppUpdates)
        tvUpdateHeaderStatus.text = "Versão ${UpdateManager.getCurrentVersionName(this)} instalada"

        // Seção 4: Compatibilidade do Aparelho
        val headerOem = view.findViewById<View>(R.id.headerOemAccordion)
        val contentOem = view.findViewById<View>(R.id.contentOemAccordion)
        val ivOemChevron = view.findViewById<ImageView>(R.id.ivOemChevron)
        val tvOemHeaderStatus = view.findViewById<TextView>(R.id.tvOemHeaderStatus)
        val tvOemModelDetected = view.findViewById<TextView>(R.id.tvOemModelDetected)
        val tvOemAndroidVersion = view.findViewById<TextView>(R.id.tvOemAndroidVersion)
        val tvOemOptimizationTip = view.findViewById<TextView>(R.id.tvOemOptimizationTip)
        val layoutXiaomiActions = view.findViewById<View>(R.id.layoutXiaomiActions)
        val btnOemAutostart = view.findViewById<Button>(R.id.btnOemAutostart)
        val btnOemPopup = view.findViewById<Button>(R.id.btnOemPopup)
        val btnOemBatteryOpt = view.findViewById<Button>(R.id.btnOemBatteryOpt)
        val btnOemRestrictedSettings = view.findViewById<Button>(R.id.btnOemRestrictedSettings)

        val btnClose = view.findViewById<Button>(R.id.btnDiagClose)

        fun refreshPermsState() {
            val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
            val hasA11y = isAccessibilityServiceEnabled(this, RotaAccessibilityService::class.java)
            val hasBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            } else true
            val hasNotif = OemCompatibilityHelper.isNotificationListenerEnabled(this)

            var activeCount = 0
            if (hasOverlay) activeCount++
            if (hasA11y) activeCount++
            if (hasBattery) activeCount++
            if (hasNotif) activeCount++

            if (activeCount == 4) {
                tvPermsHeaderStatus.text = "Todas as 4 permissões ativas (100%)"
                tvPermsHeaderStatus.setTextColor(0xFF31D477.toInt())
            } else {
                tvPermsHeaderStatus.text = "$activeCount de 4 ativas • Toque para ver"
                tvPermsHeaderStatus.setTextColor(0xFFFF454D.toInt())
            }

            tvOverlay.text = if (hasOverlay) "1. Sobrepor a outros apps (Ativo)" else "1. Sobrepor a outros apps (Pendente)"
            tvOverlay.setTextColor(if (hasOverlay) 0xFF31D477.toInt() else 0xFFFF454D.toInt())
            btnOverlay.isEnabled = !hasOverlay
            btnOverlay.text = if (hasOverlay) "OK" else "Ativar"

            tvA11y.text = if (hasA11y) "2. Serviço de Acessibilidade (Ativo)" else "2. Serviço de Acessibilidade (Pendente)"
            tvA11y.setTextColor(if (hasA11y) 0xFF31D477.toInt() else 0xFFFF454D.toInt())
            btnA11y.isEnabled = !hasA11y
            btnA11y.text = if (hasA11y) "OK" else "Ativar"

            tvBattery.text = if (hasBattery) "3. Bateria sem restrição (Ativo)" else "3. Bateria sem restrição (Pendente)"
            tvBattery.setTextColor(if (hasBattery) 0xFF31D477.toInt() else 0xFFFF454D.toInt())
            btnBattery.isEnabled = !hasBattery
            btnBattery.text = if (hasBattery) "OK" else "Ajustar"

            tvNotif.text = if (hasNotif) "4. Leitor de Notificações (Ativo)" else "4. Leitor de Notificações (Pendente)"
            tvNotif.setTextColor(if (hasNotif) 0xFF31D477.toInt() else 0xFFFF454D.toInt())
            btnNotif.isEnabled = !hasNotif
            btnNotif.text = if (hasNotif) "OK" else "Ativar"
        }

        fun refreshBackupState() {
            if (prefs.lastBackupTimestamp > 0) {
                val dateStr = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault()).format(Date(prefs.lastBackupTimestamp))
                tvLastBackupInfo.text = "Último backup: $dateStr"
            } else {
                tvLastBackupInfo.text = "Nenhum backup realizado hoje ainda"
            }
        }

        refreshPermsState()
        refreshBackupState()

        // Accordion 1: Toggle Permissões
        headerPerms.setOnClickListener {
            val willExpand = (contentPerms.visibility == View.GONE)
            TransitionManager.beginDelayedTransition(layoutAccordionContainer, AutoTransition().setDuration(220))
            contentPerms.visibility = if (willExpand) View.VISIBLE else View.GONE
            ivPermsChevron.animate().rotation(if (willExpand) 180f else 0f).setDuration(220).start()
        }

        // Accordion 2: Toggle Backup
        headerBackup.setOnClickListener {
            val willExpand = (contentBackup.visibility == View.GONE)
            TransitionManager.beginDelayedTransition(layoutAccordionContainer, AutoTransition().setDuration(220))
            contentBackup.visibility = if (willExpand) View.VISIBLE else View.GONE
            ivBackupChevron.animate().rotation(if (willExpand) 180f else 0f).setDuration(220).start()
        }

        // Accordion 3: Toggle Atualizações
        headerUpdate.setOnClickListener {
            val willExpand = (contentUpdate.visibility == View.GONE)
            TransitionManager.beginDelayedTransition(layoutAccordionContainer, AutoTransition().setDuration(220))
            contentUpdate.visibility = if (willExpand) View.VISIBLE else View.GONE
            ivUpdateChevron.animate().rotation(if (willExpand) 180f else 0f).setDuration(220).start()
        }

        // Accordion 4: Toggle Compatibilidade OEM
        headerOem.setOnClickListener {
            val willExpand = (contentOem.visibility == View.GONE)
            TransitionManager.beginDelayedTransition(layoutAccordionContainer, AutoTransition().setDuration(220))
            contentOem.visibility = if (willExpand) View.VISIBLE else View.GONE
            ivOemChevron.animate().rotation(if (willExpand) 180f else 0f).setDuration(220).start()
        }

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnBattery.setOnClickListener {
            OemCompatibilityHelper.openBatteryOptimizationSettings(this)
        }

        btnNotif.setOnClickListener {
            OemCompatibilityHelper.openNotificationListenerSettings(this)
        }

        // Setup Informações OEM
        tvOemModelDetected.text = "📱 Aparelho: ${OemCompatibilityHelper.deviceModelName}"
        tvOemAndroidVersion.text = "⚙️ ${OemCompatibilityHelper.androidVersionSummary}"
        tvOemOptimizationTip.text = OemCompatibilityHelper.getBrandOptimizationTip()

        when (OemCompatibilityHelper.currentBrand) {
            DeviceBrand.XIAOMI -> {
                layoutXiaomiActions.visibility = View.VISIBLE
                tvOemHeaderStatus.text = "Xiaomi / HyperOS • Ações Prontas"
                tvOemHeaderStatus.setTextColor(0xFFFFB800.toInt())
                btnOemAutostart.setOnClickListener { OemCompatibilityHelper.openAutostartSettings(this) }
                btnOemPopup.setOnClickListener { OemCompatibilityHelper.openMiuiBackgroundPopupSettings(this) }
            }
            DeviceBrand.SAMSUNG -> {
                layoutXiaomiActions.visibility = View.GONE
                tvOemHeaderStatus.text = "Samsung One UI detectado"
                tvOemHeaderStatus.setTextColor(0xFF60A5FA.toInt())
                btnOemBatteryOpt.text = "Ajustar Bateria / Device Care (One UI)"
            }
            DeviceBrand.MOTOROLA -> {
                layoutXiaomiActions.visibility = View.GONE
                tvOemHeaderStatus.text = "Motorola detectado"
                tvOemHeaderStatus.setTextColor(0xFF31D477.toInt())
            }
            DeviceBrand.GENERIC -> {
                layoutXiaomiActions.visibility = View.GONE
                tvOemHeaderStatus.text = "Sistema Otimizado"
                tvOemHeaderStatus.setTextColor(0xFF31D477.toInt())
            }
        }

        btnOemBatteryOpt.setOnClickListener {
            if (OemCompatibilityHelper.currentBrand == DeviceBrand.SAMSUNG) {
                OemCompatibilityHelper.openSamsungDeviceCare(this)
            } else {
                OemCompatibilityHelper.openBatteryOptimizationSettings(this)
            }
        }

        btnOemRestrictedSettings.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Configurações Restritas (Android 13+)")
                .setMessage(
                    "Em aparelhos com Android 13 ou superior, o Android pode bloquear a Acessibilidade sob o aviso 'Configuração restrita'.\n\n" +
                    "Para liberar:\n" +
                    "1. Toque no botão 'Abrir Detalhes do App' abaixo.\n" +
                    "2. Toque nos 3 pontinhos no canto superior direito.\n" +
                    "3. Toque em 'Permitir configurações restritas'.\n" +
                    "4. Confirme com sua biometria ou PIN e ative os serviços!"
                )
                .setPositiveButton("Abrir Detalhes do App") { _, _ ->
                    OemCompatibilityHelper.openAppDetailsSettings(this)
                }
                .setNegativeButton("Entendido", null)
                .show()
        }

        // Exportação de Backup
        btnExportBackup.setOnClickListener {
            try {
                val backupFile = BackupManager.exportBackup(this)
                refreshBackupState()
                showNotificationBanner("Backup exportado para Downloads/DriFly_Backups!", BannerType.SUCCESS)

                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", backupFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Backup DriFly - ${backupFile.name}")
                    putExtra(Intent.EXTRA_TEXT, "Arquivo de backup do histórico de corridas e preferências do DriFly.")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Enviar Backup DriFly para outro celular"))
            } catch (e: Exception) {
                e.printStackTrace()
                showNotificationBanner("Erro ao exportar backup: ${e.message}", BannerType.ERROR)
            }
        }

        // Importação de Backup
        btnImportBackup.setOnClickListener {
            try {
                importBackupLauncher.launch("*/*")
            } catch (e: Exception) {
                e.printStackTrace()
                showNotificationBanner("Erro ao abrir seletor de arquivos: ${e.message}", BannerType.ERROR)
            }
        }

        // Verificação de Atualizações no GitHub
        btnCheckAppUpdates.setOnClickListener {
            showNotificationBanner("Verificando atualizações no GitHub...", BannerType.INFO)
            UpdateManager.checkForUpdates(
                context = this,
                onUpdateAvailable = { updateInfo ->
                    showUpdateAvailableDialog(updateInfo)
                },
                onUpToDate = { currentVer ->
                    showNotificationBanner("Você já está na versão mais recente (v$currentVer)!", BannerType.SUCCESS)
                },
                onError = { errorMsg ->
                    showNotificationBanner(errorMsg, BannerType.WARNING)
                }
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        activePermissionsDialogRefresh = {
            refreshPermsState()
            refreshBackupState()
        }

        dialog.setOnDismissListener {
            activePermissionsDialogRefresh = null
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.75f)
        }
        dialog.show()
    }

    private fun handleBackupImport(uri: Uri) {
        val jsonContent = BackupManager.readTextFromUri(this, uri)
        if (jsonContent.isNullOrBlank()) {
            showNotificationBanner("Não foi possível ler o arquivo de backup selecionado.", BannerType.ERROR)
            return
        }

        val result = BackupManager.importBackup(this, jsonContent)
        if (result.isSuccess) {
            val count = result.getOrDefault(0)
            showNotificationBanner("Backup restaurado com sucesso! ($count registros inseridos)", BannerType.SUCCESS)
            loadPreferences()
            updateDashboard(animate = true)
        } else {
            showNotificationBanner("Falha ao restaurar: ${result.exceptionOrNull()?.localizedMessage ?: "Formato incompatível"}", BannerType.ERROR)
        }
    }

    private fun showUpdateAvailableDialog(updateInfo: UpdateInfo) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Nova Versão Disponível: v${updateInfo.versionName}")
        builder.setMessage("${updateInfo.title}\n\nO que há de novo:\n${updateInfo.changelog}\n\nDeseja atualizar agora?")
        builder.setPositiveButton("Baixar e Atualizar") { _, _ ->
            val progressView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 30, 50, 10)
            }
            val tvStatus = TextView(this).apply {
                text = "Baixando atualização... 0%"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
            }
            val pBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 100
                progress = 0
            }
            progressView.addView(tvStatus)
            progressView.addView(pBar)

            val downloadDialog = AlertDialog.Builder(this)
                .setTitle("Atualizando DriFly")
                .setView(progressView)
                .setCancelable(false)
                .create()
            downloadDialog.show()

            UpdateManager.downloadAndInstallApk(
                context = this,
                downloadUrl = updateInfo.downloadUrl,
                onProgress = { progress ->
                    pBar.progress = progress
                    tvStatus.text = "Baixando atualização... $progress%"
                },
                onSuccess = {
                    downloadDialog.dismiss()
                    showNotificationBanner("Download concluído! Iniciando instalação...", BannerType.SUCCESS)
                },
                onError = { err ->
                    downloadDialog.dismiss()
                    showNotificationBanner(err, BannerType.ERROR)
                }
            )
        }
        builder.setNegativeButton("Mais Tarde", null)
        builder.show()
    }

    private fun showAddExpenseDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_expense, null)
        val etCat = view.findViewById<EditText>(R.id.etExpenseCategory)
        val etVal = view.findViewById<EditText>(R.id.etExpenseAmount)
        val etDesc = view.findViewById<EditText>(R.id.etExpenseDesc)
        val btnSave = view.findViewById<Button>(R.id.btnSaveExpense)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelExpense)

        enableCommaAndDotDecimalInput(etVal)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val category = etCat.text.toString().ifBlank { "Geral" }
            val amount = ParsingEngine.parseMoneyValue(etVal.text.toString()) ?: 0.0
            val desc = etDesc.text.toString()
            if (amount > 0) {
                db.insertExpense(category, amount, desc)
                showNotificationBanner("Despesa registrada com sucesso!", BannerType.SUCCESS)
                updateDashboard(animate = true)
                dialog.dismiss()
            } else {
                showNotificationBanner("Informe um valor válido maior que zero", BannerType.WARNING)
            }
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.75f)
        }
        dialog.show()
    }

    private fun showReportDialog(initialPeriod: String = "DAILY", title: String = "Histórico Unificado") {
        val ptBr = Locale("pt", "BR")
        val timeFormat = SimpleDateFormat("HH:mm", ptBr)

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_report, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvReportTitle)
        val btnCloseIcon = view.findViewById<ImageView>(R.id.btnDialogReportCloseIcon)
        val btnChipToday = view.findViewById<Button>(R.id.btnReportChipToday)
        val btnChipWeek = view.findViewById<Button>(R.id.btnReportChipWeek)
        val btnChipMonth = view.findViewById<Button>(R.id.btnReportChipMonth)
        val btnChipYear = view.findViewById<Button>(R.id.btnReportChipYear)

        val tvRideCount = view.findViewById<TextView>(R.id.tvReportRideCount)
        val tvGross = view.findViewById<TextView>(R.id.tvReportGross)
        val tvExpenses = view.findViewById<TextView>(R.id.tvReportExpenses)
        val tvKm = view.findViewById<TextView>(R.id.tvReportKm)
        val tvNet = view.findViewById<TextView>(R.id.tvReportNet)
        val layoutRidesList = view.findViewById<LinearLayout>(R.id.layoutReportRidesList)
        val tvNoRides = view.findViewById<TextView>(R.id.tvReportNoRides)
        val btnClose = view.findViewById<Button>(R.id.btnReportClose)

        tvTitle.text = title

        var currentDialogPeriod = initialPeriod

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        fun updateChipsVisual(selected: String) {
            val chips = listOf(
                Pair(btnChipToday, "DAILY"),
                Pair(btnChipWeek, "WEEKLY"),
                Pair(btnChipMonth, "MONTHLY"),
                Pair(btnChipYear, "YEARLY")
            )
            for ((btn, key) in chips) {
                if (key == selected) {
                    btn?.setBackgroundResource(R.drawable.bg_period_selected)
                    btn?.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    btn?.setBackgroundResource(R.drawable.bg_period_unselected)
                    btn?.setTextColor(0xFFA3A8B5.toInt())
                }
            }
        }

        fun refreshReportContent() {
            updateChipsVisual(currentDialogPeriod)
            val summary = db.getSummary(currentDialogPeriod)
            val rides = db.getRidesForPeriod(currentDialogPeriod)

            tvRideCount.text = summary.rideCount.toString()
            tvGross.text = String.format(ptBr, "R$ %.2f", summary.totalGross)
            tvExpenses.text = String.format(ptBr, "R$ %.2f", summary.totalExpenses)
            tvNet.text = String.format(ptBr, "R$ %.2f", summary.totalNet)

            val totalKm = rides.sumOf { it.distanceKm }
            tvKm.text = String.format(ptBr, "%.1f km", totalKm)

            layoutRidesList.removeAllViews()
            if (rides.isEmpty()) {
                tvNoRides.visibility = View.VISIBLE
            } else {
                tvNoRides.visibility = View.GONE
                for (ride in rides) {
                    val itemRow = createRideListItemView(ride, timeFormat, ptBr) {
                        showRideAdjustDialog(ride) {
                            refreshReportContent()
                            updateDashboard(animate = true)
                        }
                    }
                    layoutRidesList.addView(itemRow)
                }
            }
        }

        btnChipToday?.setOnClickListener {
            currentDialogPeriod = "DAILY"
            refreshReportContent()
        }
        btnChipWeek?.setOnClickListener {
            currentDialogPeriod = "WEEKLY"
            refreshReportContent()
        }
        btnChipMonth?.setOnClickListener {
            currentDialogPeriod = "MONTHLY"
            refreshReportContent()
        }
        btnChipYear?.setOnClickListener {
            currentDialogPeriod = "YEARLY"
            refreshReportContent()
        }

        refreshReportContent()

        activeReportDialogRefresh = {
            refreshReportContent()
        }

        dialog.setOnDismissListener {
            activeReportDialogRefresh = null
        }

        btnCloseIcon?.setOnClickListener {
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.75f)
        }
        dialog.show()
    }

    private fun createRideListItemView(
        ride: RideRecord,
        timeFormat: SimpleDateFormat,
        ptBr: Locale,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(8))
            }
            setBackgroundResource(R.drawable.bg_card_frosted_glass)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            isClickable = true
            isFocusable = true
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        applyScaleTouchFeedback(row)
        row.setOnClickListener { onClick() }

        // Coluna Esquerda: Plataforma, Hora, Distância
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvPlatformTime = TextView(this).apply {
            val platformColor = when (ride.platform.uppercase()) {
                "UBER" -> "#FFFFFF"
                "99" -> "#FFB800"
                "IFOOD" -> "#FF454D"
                else -> "#60A5FA"
            }
            val ssb = android.text.SpannableStringBuilder()
            val pStart = 0
            ssb.append(ride.platform.uppercase())
            ssb.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor(platformColor)), pStart, ssb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), pStart, ssb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            val timeStr = timeFormat.format(Date(ride.timestamp))
            ssb.append(" • $timeStr")

            text = ssb
            textSize = 13f
            setTextColor(0xFFA3A8B5.toInt())
        }

        val tvDistance = TextView(this).apply {
            text = String.format(ptBr, "%.1f km", ride.distanceKm)
            textSize = 11f
            setTextColor(0xFF707684.toInt())
        }

        leftCol.addView(tvPlatformTime)
        leftCol.addView(tvDistance)

        // Coluna Direita: Valor e Status Badge
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
        }

        val tvPrice = TextView(this).apply {
            when (ride.status) {
                RideRecord.STATUS_CANCELADO_TAXA -> {
                    text = String.format(ptBr, "Taxa R$ %.2f", ride.cancellationFee)
                    setTextColor(0xFF60A5FA.toInt())
                }
                RideRecord.STATUS_CANCELADO -> {
                    text = "R$ 0,00"
                    setTextColor(0xFFFF454D.toInt())
                }
                else -> {
                    text = String.format(ptBr, "R$ %.2f", ride.grossPrice)
                    setTextColor(0xFFFFFFFF.toInt())
                }
            }
            textSize = 14f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }

        val tvBadge = TextView(this).apply {
            when (ride.status) {
                RideRecord.STATUS_ESTIMADO -> {
                    text = "ESTIMADO"
                    setTextColor(0xFFFFB800.toInt())
                }
                RideRecord.STATUS_CONCLUIDO -> {
                    text = "CONCLUÍDO"
                    setTextColor(0xFF31D477.toInt())
                }
                RideRecord.STATUS_CANCELADO_TAXA -> {
                    text = "TAXA"
                    setTextColor(0xFF60A5FA.toInt())
                }
                RideRecord.STATUS_CANCELADO -> {
                    text = "CANCELADO"
                    setTextColor(0xFFFF454D.toInt())
                }
                else -> {
                    text = ride.status
                    setTextColor(0xFFA3A8B5.toInt())
                }
            }
            textSize = 9f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setPadding(dpToPx(4), dpToPx(1), dpToPx(4), dpToPx(1))
        }

        rightCol.addView(tvPrice)
        rightCol.addView(tvBadge)

        row.addView(leftCol)
        row.addView(rightCol)

        return row
    }

    private fun showRideAdjustDialog(ride: RideRecord, onUpdated: () -> Unit) {
        val ptBr = Locale("pt", "BR")
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_ride_adjust, null)

        val tvTitle = view.findViewById<TextView>(R.id.tvAdjustRideTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvAdjustRideSubtitle)
        val tvBadge = view.findViewById<TextView>(R.id.tvAdjustCurrentBadge)
        val etPrice = view.findViewById<EditText>(R.id.etAdjustRealPrice)
        val btnSavePrice = view.findViewById<Button>(R.id.btnSaveAdjustedPrice)

        enableCommaAndDotDecimalInput(etPrice)

        val btnCompleted = view.findViewById<Button>(R.id.btnActionMarkCompleted)
        val btnCancelWithFee = view.findViewById<Button>(R.id.btnActionCancelWithFee)
        val btnCancelZeroFee = view.findViewById<Button>(R.id.btnActionCancelZeroFee)
        val btnDelete = view.findViewById<Button>(R.id.btnActionDeleteRide)
        val btnClose = view.findViewById<Button>(R.id.btnAdjustClose)

        tvTitle.text = "Ajuste de Corrida #${ride.id}"
        tvSubtitle.text = "${ride.platform} • R$ ${String.format(ptBr, "%.2f", ride.grossPrice)} • ${ride.distanceKm} km"
        tvBadge.text = ride.status

        val currentDisplayPrice = if (ride.status == RideRecord.STATUS_CANCELADO_TAXA) {
            ride.cancellationFee
        } else {
            ride.grossPrice
        }
        etPrice.setText(String.format(ptBr, "%.2f", currentDisplayPrice))

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        // 1. Salvar Novo Valor
        btnSavePrice.setOnClickListener {
            val newPrice = parseBrFloat(etPrice.text.toString(), currentDisplayPrice.toFloat()).toDouble()
            if (newPrice > 0.0) {
                val cost = ride.distanceKm * prefs.costPerKm
                val newNet = Math.max(0.0, Math.round((newPrice - cost) * 100.0) / 100.0)
                db.updateRidePrice(ride.id, newPrice, newNet, RideRecord.STATUS_CONCLUIDO)
                showNotificationBanner("Valor ajustado para R$ ${String.format(ptBr, "%.2f", newPrice)} (Concluída)!", BannerType.SUCCESS)
                dialog.dismiss()
                onUpdated()
            } else {
                showNotificationBanner("Informe um valor maior que zero", BannerType.WARNING)
            }
        }

        // 2. Marcar como Concluída
        btnCompleted.setOnClickListener {
            val cost = ride.distanceKm * prefs.costPerKm
            val basePrice = if (ride.grossPrice > 0.0) ride.grossPrice else ride.originalPrice
            val newNet = Math.max(0.0, Math.round((basePrice - cost) * 100.0) / 100.0)
            db.updateRidePrice(ride.id, basePrice, newNet, RideRecord.STATUS_CONCLUIDO)
            showNotificationBanner("Corrida confirmada como Concluída!", BannerType.SUCCESS)
            dialog.dismiss()
            onUpdated()
        }

        // 3. Cancelada com Taxa
        val standardFee = when {
            ride.platform.contains("99", ignoreCase = true) -> 3.00
            ride.platform.contains("uber", ignoreCase = true) -> 5.50
            else -> 3.00
        }
        btnCancelWithFee.text = "⚡  Cancelada com Taxa (R$ ${String.format(ptBr, "%.2f", standardFee)})"
        btnCancelWithFee.setOnClickListener {
            val typedVal = parseBrFloat(etPrice.text.toString(), standardFee.toFloat()).toDouble()
            // Se o usuário digitou uma taxa personalizada diferente do valor integral da corrida, usa o valor digitado
            val effectiveFee = if (typedVal > 0.0 && Math.abs(typedVal - ride.grossPrice) > 0.001) typedVal else standardFee
            db.updateRideStatus(ride.id, RideRecord.STATUS_CANCELADO_TAXA, effectiveFee)
            showNotificationBanner("Corrida marcada com taxa de R$ ${String.format(ptBr, "%.2f", effectiveFee)}!", BannerType.SUCCESS)
            dialog.dismiss()
            onUpdated()
        }

        // 4. Cancelada sem Taxa
        btnCancelZeroFee.setOnClickListener {
            db.updateRideStatus(ride.id, RideRecord.STATUS_CANCELADO, 0.0)
            showNotificationBanner("Corrida cancelada sem taxa (R$ 0,00 lançados).", BannerType.INFO)
            dialog.dismiss()
            onUpdated()
        }

        // 5. Excluir Corrida
        btnDelete.setOnClickListener {
            db.deleteRide(ride.id)
            showNotificationBanner("Corrida removida do extrato.", BannerType.INFO)
            dialog.dismiss()
            onUpdated()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.75f)
        }
        dialog.show()
    }

    private var onSplashCompletedCallback: (() -> Unit)? = null

    private fun playColdStartSplash(onComplete: () -> Unit) {
        onSplashCompletedCallback = onComplete
        isSplashFinishing = false

        layoutSplashOverlay.alpha = 1f
        layoutSplashOverlay.scaleX = 1f
        layoutSplashOverlay.scaleY = 1f
        layoutSplashOverlay.visibility = View.VISIBLE
        ivSplashPoster.alpha = 1f
        ivSplashPoster.visibility = View.VISIBLE

        val startTimeMs = System.currentTimeMillis()
        // Permite pular a abertura tocando na tela após 800ms caso o motorista esteja com pressa
        layoutSplashOverlay.setOnClickListener {
            if (System.currentTimeMillis() - startTimeMs >= 800L) {
                finishSplashOverlay()
            }
        }

        // Timeout de segurança (6.2s) para garantir que nenhum travamento de codec bloqueie o app
        bannerHandler.removeCallbacks(splashFallbackRunnable)
        bannerHandler.postDelayed(splashFallbackRunnable, 6200L)

        if (textureSplashVideo.isAvailable) {
            startSplashMediaPlayer(textureSplashVideo.surfaceTexture!!, textureSplashVideo.width, textureSplashVideo.height)
        } else {
            textureSplashVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    startSplashMediaPlayer(surface, width, height)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    applyCenterCropTransform(width, height, 1080, 1920)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releaseSplashPlayer()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    private fun startSplashMediaPlayer(surfaceTexture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        if (isSplashFinishing || isFinishing || isDestroyed) return
        try {
            applyCenterCropTransform(viewWidth, viewHeight, 1080, 1920)
            val surface = Surface(surfaceTexture)
            splashSurface = surface

            val mp = MediaPlayer()
            splashMediaPlayer = mp

            // Jamais rouba foco de áudio do Spotify / Uber / 99 do motorista
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
            }

            val afd = resources.openRawResourceFd(R.raw.splash_intro) ?: run {
                finishSplashOverlay()
                return
            }
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()

            mp.setSurface(surface)
            mp.isLooping = false
            mp.setVolume(0f, 0f)

            mp.setOnVideoSizeChangedListener { _, videoW, videoH ->
                if (videoW > 0 && videoH > 0) {
                    applyCenterCropTransform(textureSplashVideo.width, textureSplashVideo.height, videoW, videoH)
                }
            }

            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    // O hardware decoder entregou o primeiro frame real na GPU -> oculta o poster estático sem piscar
                    ivSplashPoster.animate()
                        .alpha(0f)
                        .setDuration(90L)
                        .withEndAction { ivSplashPoster.visibility = View.GONE }
                        .start()
                }
                false
            }

            mp.setOnCompletionListener {
                finishSplashOverlay()
            }

            mp.setOnErrorListener { _, _, _ ->
                finishSplashOverlay()
                true
            }

            mp.setOnPreparedListener { player ->
                if (!isSplashFinishing) {
                    player.start()
                }
            }

            mp.prepareAsync()
        } catch (e: Exception) {
            finishSplashOverlay()
        }
    }

    private fun applyCenterCropTransform(viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()

        val scaleX: Float
        val scaleY: Float
        if (videoRatio > viewRatio) {
            scaleX = videoRatio / viewRatio
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewRatio / videoRatio
        }

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        textureSplashVideo.setTransform(matrix)
    }

    private fun finishSplashOverlay() {
        if (isSplashFinishing) return
        isSplashFinishing = true
        bannerHandler.removeCallbacks(splashFallbackRunnable)

        layoutSplashOverlay.setOnClickListener(null)
        layoutSplashOverlay.isClickable = false

        layoutSplashOverlay.animate()
            .alpha(0f)
            .scaleX(1.04f)
            .scaleY(1.04f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                layoutSplashOverlay.visibility = View.GONE
                releaseSplashPlayer()
                val callback = onSplashCompletedCallback
                onSplashCompletedCallback = null
                callback?.invoke()
            }
            .start()
    }

    private fun releaseSplashPlayer() {
        try {
            splashMediaPlayer?.setOnCompletionListener(null)
            splashMediaPlayer?.setOnErrorListener(null)
            splashMediaPlayer?.setOnInfoListener(null)
            splashMediaPlayer?.stop()
        } catch (ignored: Exception) {}
        try {
            splashMediaPlayer?.release()
        } catch (ignored: Exception) {}
        splashMediaPlayer = null

        try {
            splashSurface?.release()
        } catch (ignored: Exception) {}
        splashSurface = null
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = enabledServices.split(":")
        val myServiceName = "${context.packageName}/${service.name}"
        return colonSplitter.any { it.equals(myServiceName, ignoreCase = true) }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "EXTRA_OPEN_TAB"

        @Volatile
        private var hasShownColdStartSplashInProcess = false

        /**
         * Determina se a animação de abertura deve ser exibida.
         * Retorna true SOMENTE na primeira abertura real do processo (Cold Start),
         * garantindo que reabrir o app em 2º plano (Warm/Hot Start) ou girar a tela seja instantâneo.
         */
        @Synchronized
        fun consumeColdStartSplashEligibility(
            hasSavedInstanceState: Boolean,
            isBackgroundShortcutIntent: Boolean
        ): Boolean {
            if (hasShownColdStartSplashInProcess) {
                return false
            }
            hasShownColdStartSplashInProcess = true
            if (hasSavedInstanceState || isBackgroundShortcutIntent) {
                return false
            }
            return true
        }

        @Synchronized
        fun resetColdStartSplashForTesting() {
            hasShownColdStartSplashInProcess = false
        }
    }
}
