package com.rotacore.app

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
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
    private lateinit var btnReportDaily: Button
    private lateinit var btnReportWeekly: Button
    private lateinit var btnReportMonthly: Button
    private lateinit var btnReportYearly: Button
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

    // Aba 2: Configurações de Corrida
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
    private lateinit var btnSaveRideSettings: Button
    private lateinit var btnSimulateRide: Button

    // Aba 3: Apoio ao Projeto & Doações
    private lateinit var tvPixKeyText: TextView
    private lateinit var btnCopyPixKey: View
    private lateinit var chipDonation5: TextView
    private lateinit var chipDonation10: TextView
    private lateinit var chipDonation20: TextView
    private lateinit var chipDonation50: TextView

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

    enum class BannerType {
        SUCCESS, ERROR, WARNING, INFO
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PreferencesManager(this)
        db = DatabaseHelper(this)

        initViews()
        loadPreferences()
        setupListeners()

        // Agenda tarefas automáticas em segundo plano (Backup 00:00 e Lembretes 08h, 15h, 20h)
        DailyBackupWorker.scheduleDailyBackup(this)
        SupportNotificationWorker.scheduleAllSupportReminders(this)

        // Registra o broadcast receiver para mudanças no estado do monitoramento
        val filter = IntentFilter(MonitoringService.ACTION_MONITORING_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(monitoringStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(monitoringStateReceiver, filter)
        }

        handleIncomingTabIntent(intent)

        // Se nenhuma aba específica foi solicitada, abre Resultados & Finanças
        if (intent.getStringExtra(EXTRA_OPEN_TAB) != "SUPPORT") {
            bottomNavigation.selectedItemId = R.id.nav_finance
            switchToTab(R.id.nav_finance)
        }

        updatePeriodButtonsVisual("DAILY")
        updateDashboard(animate = false)
        updateMonitoringToggleUI(prefs.isMonitoringActive)

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
    }

    private var activePermissionsDialogRefresh: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        updateDashboard(animate = true)
        updateMonitoringToggleUI(prefs.isMonitoringActive)
        activePermissionsDialogRefresh?.invoke()
        UpdateManager.checkAndResumePendingInstall(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(monitoringStateReceiver)
        } catch (ignored: Exception) {}
    }

    private fun hasAllPermissions(): Boolean {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val hasA11y = isAccessibilityServiceEnabled(this, RotaAccessibilityService::class.java)
        val hasBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
        return hasOverlay && hasA11y && hasBattery
    }

    private fun initViews() {
        tvSubtitle = findViewById(R.id.tvSubtitle)
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
        btnReportDaily = findViewById(R.id.btnReportDaily)
        btnReportWeekly = findViewById(R.id.btnReportWeekly)
        btnReportMonthly = findViewById(R.id.btnReportMonthly)
        btnReportYearly = findViewById(R.id.btnReportYearly)
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
        btnSaveRideSettings = findViewById(R.id.btnSaveRideSettings)
        btnSimulateRide = findViewById(R.id.btnSimulateRide)

        // Aba 3: Apoiar
        tvPixKeyText = findViewById(R.id.tvPixKeyText)
        btnCopyPixKey = findViewById(R.id.btnCopyPixKey)
        chipDonation5 = findViewById(R.id.chipDonation5)
        chipDonation10 = findViewById(R.id.chipDonation10)
        chipDonation20 = findViewById(R.id.chipDonation20)
        chipDonation50 = findViewById(R.id.chipDonation50)

        // Aplica microinterações táteis nos botões de destaque
        applyScaleTouchFeedback(btnAddExpense)
        applyScaleTouchFeedback(btnSaveGoalOnly)
        applyScaleTouchFeedback(cardMonitoringToggle)
        applyScaleTouchFeedback(btnSaveRideSettings)
        applyScaleTouchFeedback(btnSimulateRide)
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
            tvMonitoringSubtitle.text = "Uber e 99 sendo monitorados • Toque para pausar"
            tvMonitoringBadge.text = "ATIVO"
            tvMonitoringBadge.setBackgroundResource(R.drawable.bg_pill_green)
            tvMonitoringBadge.setTextColor(0xFF31D477.toInt())
        } else {
            cardMonitoringToggle.setBackgroundResource(R.drawable.bg_card_monitoring_inactive)
            ivMonitoringPowerIcon.setColorFilter(0xFFFF6900.toInt())
            tvMonitoringTitle.text = "LIGAR MONITORAMENTO"
            tvMonitoringTitle.setTextColor(0xFFF5F5F7.toInt())
            tvMonitoringSubtitle.text = "Toque para iniciar o cálculo contínuo em segundo plano"
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
    }

    private fun parseBrFloat(text: String, defaultVal: Float): Float {
        val cleaned = text.trim().replace(".", "").replace(",", ".")
        return cleaned.toFloatOrNull() ?: defaultVal
    }

    private fun updatePeriodButtonsVisual(selected: String) {
        activePeriod = selected
        val buttons = listOf(
            Triple(btnReportDaily, "DAILY", "Diário"),
            Triple(btnReportWeekly, "WEEKLY", "Semanal"),
            Triple(btnReportMonthly, "MONTHLY", "Mensal"),
            Triple(btnReportYearly, "YEARLY", "Anual")
        )

        for ((btn, periodKey, _) in buttons) {
            if (periodKey == selected) {
                btn.setBackgroundResource(R.drawable.bg_period_selected)
                btn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn.setBackgroundResource(R.drawable.bg_period_unselected)
                btn.setTextColor(0xFFA3A8B5.toInt())
            }
        }
    }

    private fun setupListeners() {
        bottomNavigation.setOnItemSelectedListener { item ->
            switchToTab(item.itemId)
            true
        }

        btnOpenSettings.setOnClickListener {
            showPermissionsDialog()
        }

        // Lógica de Toggle do Monitoramento Contínuo
        cardMonitoringToggle.setOnClickListener {
            if (prefs.isMonitoringActive) {
                // Desliga o monitoramento
                MonitoringService.stop(this)
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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                    }
                }

                MonitoringService.start(this)
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

        btnAddExpense.setOnClickListener { showAddExpenseDialog() }

        btnReportDaily.setOnClickListener {
            updatePeriodButtonsVisual("DAILY")
            showReportDialog("DAILY", "Relatório Diário")
        }
        btnReportWeekly.setOnClickListener {
            updatePeriodButtonsVisual("WEEKLY")
            showReportDialog("WEEKLY", "Relatório Semanal")
        }
        btnReportMonthly.setOnClickListener {
            updatePeriodButtonsVisual("MONTHLY")
            showReportDialog("MONTHLY", "Relatório Mensal")
        }
        btnReportYearly.setOnClickListener {
            updatePeriodButtonsVisual("YEARLY")
            showReportDialog("YEARLY", "Relatório Anual")
        }

        // Testar Alerta Flutuante na Tela
        btnSimulateRide.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                showNotificationBanner("Permissão de sobreposição necessária para o flutuante", BannerType.WARNING)
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }

            val simulated = RideOffer(
                platform = "UBER",
                price = 32.50,
                distanceKm = 8.4,
                durationMinutes = 19,
                ratePerKm = 3.87,
                estimatedFuelCost = 6.55,
                netProfit = 25.95,
                passengerRating = 4.65f,
                isLowRating = 4.65f < prefs.minPassengerRating
            )

            try {
                BubbleOverlayService.showOffer(this, simulated)
                showNotificationBanner("Alerta DriFly exibido na tela!", BannerType.SUCCESS)
            } catch (e: Exception) {
                e.printStackTrace()
                showNotificationBanner("Erro ao exibir flutuante: ${e.message}", BannerType.ERROR)
            }
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

    /**
     * Modal de Configurações Redesenhado com Accordion:
     * - Seção 1: Permissões do Sistema (Minimizada por padrão, expande/recolhe ao tocar)
     * - Seção 2: Backup & Restauração (Minimizada por padrão, expande/recolhe ao tocar)
     * - Seção 3: Atualizações do Aplicativo (Minimizada por padrão, verifica no GitHub)
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
        val btnOverlay = view.findViewById<Button>(R.id.btnDiagPermOverlay)
        val btnA11y = view.findViewById<Button>(R.id.btnDiagPermA11y)
        val btnBattery = view.findViewById<Button>(R.id.btnDiagPermBattery)

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
        val btnCheckAppUpdates = view.findViewById<Button>(R.id.btnCheckAppUpdates)

        val btnClose = view.findViewById<Button>(R.id.btnDiagClose)

        fun refreshPermsState() {
            val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
            val hasA11y = isAccessibilityServiceEnabled(this, RotaAccessibilityService::class.java)
            val hasBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            } else true

            var activeCount = 0
            if (hasOverlay) activeCount++
            if (hasA11y) activeCount++
            if (hasBattery) activeCount++

            if (activeCount == 3) {
                tvPermsHeaderStatus.text = "Todas as 3 permissões ativas (100%)"
                tvPermsHeaderStatus.setTextColor(0xFF31D477.toInt())
            } else {
                tvPermsHeaderStatus.text = "$activeCount de 3 ativas • Toque para ajustar"
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

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnBattery.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }

        // Exportação de Backup
        btnExportBackup.setOnClickListener {
            try {
                val backupFile = BackupManager.exportBackup(this)
                refreshBackupState()
                showNotificationBanner("Backup exportado para Downloads/DriFly_Backups!", BannerType.SUCCESS)

                // Oferece envio/compartilhamento direto do arquivo (ex: WhatsApp, Drive, Gmail)
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

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val category = etCat.text.toString().ifBlank { "Geral" }
            val rawVal = etVal.text.toString().trim().replace(".", "").replace(",", ".")
            val amount = rawVal.toDoubleOrNull() ?: 0.0
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

    private fun showReportDialog(period: String, title: String) {
        val ptBr = Locale("pt", "BR")
        val summary = db.getSummary(period)

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_report, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvReportTitle)
        val tvRideCount = view.findViewById<TextView>(R.id.tvReportRideCount)
        val tvGross = view.findViewById<TextView>(R.id.tvReportGross)
        val tvExpenses = view.findViewById<TextView>(R.id.tvReportExpenses)
        val tvNet = view.findViewById<TextView>(R.id.tvReportNet)
        val btnClose = view.findViewById<Button>(R.id.btnReportClose)

        tvTitle.text = title
        tvRideCount.text = summary.rideCount.toString()
        tvGross.text = String.format(ptBr, "R$ %.2f", summary.totalGross)
        tvExpenses.text = String.format(ptBr, "R$ %.2f", summary.totalExpenses)
        tvNet.text = String.format(ptBr, "R$ %.2f", summary.totalNet)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

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
    }
}
