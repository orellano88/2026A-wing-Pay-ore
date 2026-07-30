package com.inversioneswing.starkomega

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    // Modo Dual State
    private var isEmisorMode = true
    private var currentTopic = "wingpay_client_A2ZQV4"
    
    // UIs
    private lateinit var mainLayout: LinearLayout
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var rbEmisor: RadioButton
    private lateinit var rbCompanero: RadioButton
    private lateinit var btnActionQR: Button
    
    // Visual Cards (Sección 6)
    private lateinit var tvTotalYape: TextView
    private lateinit var tvTotalPlin: TextView
    private lateinit var tvTotalBcp: TextView
    private lateinit var tvTotalOtros: TextView
    private lateinit var tvGranTotal: TextView
    private lateinit var tvCantPagos: TextView
    
    // RecyclerView Historial
    private lateinit var rvPayments: RecyclerView
    private lateinit var adapter: PaymentAdapter
    private val paymentList = mutableListOf<PaymentItem>()
    
    // Popup Receptor (Modo Compañero)
    private var popupWindow: PopupWindow? = null
    
    // LEDs & Sensors
    private var statusLED: View? = null
    private var syncLED: View? = null
    private var sosStopBtn: Button? = null
    private var sosAnimator: ValueAnimator? = null
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val hudReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val name = it.getStringExtra("NAME") ?: "Anónimo"
                val amtStr = it.getStringExtra("AMT") ?: "0.00"
                val bank = it.getStringExtra("BANK") ?: "PAGO"
                val rawMsg = it.getStringExtra("MSG") ?: ""
                val isRemote = it.getBooleanExtra("IS_REMOTE", false)
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                val amtVal = amtStr.toDoubleOrNull() ?: 0.0
                val item = PaymentItem(bank, name, amtVal, timeStr)
                
                runOnUiThread {
                    adapter.addPayment(item)
                    updateTotals()
                    
                    if (!isEmisorMode && isRemote) {
                        showCompaneroPopup(bank, name, amtStr)
                    }
                }
            }
        }
    }

    private val barcodeLauncher: ActivityResultLauncher<ScanOptions> = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { vincularCodigo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
        currentTopic = prefs.getString("CLIENT_CODE", currentTopic) ?: currentTopic
        isEmisorMode = prefs.getBoolean("IS_EMISOR_MODE", true)

        // Sensor Shake
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        buildUI()
        setContentView(mainLayout)
        
        startStatusMonitor()
        handleSOSIntent(intent)
        
        val filter = IntentFilter("STARK_HUD_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hudReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(hudReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    // --------------------------------------------------------------------------------
    // SENSOR SHAKE-TO-SILENCE (SECCIÓN 4)
    // --------------------------------------------------------------------------------
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val acceleration = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat() - SensorManager.GRAVITY_EARTH
        if (acceleration > 12.0f) {
            DataSyncService.inst?.silenceAudio()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --------------------------------------------------------------------------------
    // UI BUILDER (DARK THEME AMOLED #0F141C - SECCIÓN 6 & 1)
    // --------------------------------------------------------------------------------
    private fun buildUI() {
        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 25, 25, 25)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, 
                intArrayOf(Color.parseColor("#0F141C"), Color.parseColor("#080B10"), Color.BLACK))
        }

        setupHeader()
        setupDualModeSelector()
        setupFinancialCards()
        setupHistoryRecyclerView()
        setupSOSButton()
        setupActionButtons()
    }

    private fun setupHeader() {
        val header = RelativeLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,15) } }
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = "WINGPAY TITAN MAX • v72.0"; textSize = 18f; setTextColor(Color.parseColor("#00E5FF")); setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)) })
            addView(TextView(this@MainActivity).apply { text = "SISTEMA DESCENTRALIZADO DE PAGOS MULTI-CANAL"; textSize = 9f; setTextColor(Color.WHITE); alpha = 0.6f })
        }
        header.addView(titleLayout)

        val ledContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -2).apply { addRule(RelativeLayout.ALIGN_PARENT_RIGHT); addRule(RelativeLayout.CENTER_VERTICAL) }
            
            statusLED = View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(20, 20); background = getCircleDrawable(Color.RED) }
            val statusText = TextView(this@MainActivity).apply { text = " ESTADO"; textSize = 8f; setTextColor(Color.WHITE); setPadding(4, 0, 10, 0); alpha = 0.8f }
            
            syncLED = View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(20, 20); background = getCircleDrawable(Color.GRAY) }
            val syncText = TextView(this@MainActivity).apply { text = " RED"; textSize = 8f; setTextColor(Color.WHITE); setPadding(4, 0, 0, 0); alpha = 0.8f }
            
            addView(statusLED); addView(statusText)
            addView(syncLED); addView(syncText)
        }
        header.addView(ledContainer)
        mainLayout.addView(header)
    }

    // --------------------------------------------------------------------------------
    // SELECTOR DUAL (SECCIÓN 1)
    // --------------------------------------------------------------------------------
    private fun setupDualModeSelector() {
        val selectorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(20, 15, 20, 15)
            background = getGlassDrawable(Color.parseColor("#1500E5FF"), Color.parseColor("#3300E5FF"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 15) }
        }

        val tvTitle = TextView(this).apply {
            text = "MODALIDAD DE TRABAJO EN APK"
            textSize = 10f
            TextColorHex("#00E5FF")
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setMargins(0, 0, 0, 8)
        }

        modeRadioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            weightSum = 2f
        }

        rbEmisor = RadioButton(this).apply {
            text = "📱 MODO EMISOR (CAJA)"
            setTextColor(Color.WHITE)
            textSize = 11f
            isChecked = isEmisorMode
            layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
        }

        rbCompanero = RadioButton(this).apply {
            text = "📱 MODO COMPAÑERO (RECEPTOR)"
            setTextColor(Color.WHITE)
            textSize = 11f
            isChecked = !isEmisorMode
            layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
        }

        modeRadioGroup.addView(rbEmisor)
        modeRadioGroup.addView(rbCompanero)

        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            isEmisorMode = (checkedId == rbEmisor.id)
            getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit()
                .putBoolean("IS_EMISOR_MODE", isEmisorMode).apply()
            
            updateModeUI()
            relaunchService()
        }

        btnActionQR = Button(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, (45 * resources.displayMetrics.density).toInt()).apply { setMargins(0, 10, 0, 0) }
        }

        selectorCard.addView(tvTitle)
        selectorCard.addView(modeRadioGroup)
        selectorCard.addView(btnActionQR)
        mainLayout.addView(selectorCard)

        updateModeUI()
    }

    private fun updateModeUI() {
        if (isEmisorMode) {
            btnActionQR.text = "📱 VER MI QR EMISOR"
            btnActionQR.background = getGlassDrawable(Color.parseColor("#2200FF7F"), Color.parseColor("#8800FF7F"))
            btnActionQR.setOnClickListener { showEmisorQRDialog() }
        } else {
            btnActionQR.text = "📷 ESCANEAR QR DEL EMISOR"
            btnActionQR.background = getGlassDrawable(Color.parseColor("#2200E5FF"), Color.parseColor("#8800E5FF"))
            btnActionQR.setOnClickListener { openQRScanner() }
        }
    }

    // --------------------------------------------------------------------------------
    // TARJETAS DE RESUMEN FINANCIERO (SECCIÓN 6)
    // --------------------------------------------------------------------------------
    private fun setupFinancialCards() {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 15) }
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val cardYape = createMiniCard("🟣 TOTAL YAPE", "#FF007F").also { tvTotalYape = it.second }
        val cardPlin = createMiniCard("🔵 TOTAL PLIN", "#00E5FF").also { tvTotalPlin = it.second }
        row1.addView(cardYape.first, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 6, 0) })
        row1.addView(cardPlin.first, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 0, 0, 0) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setMargins(0, 10, 0, 0) }
        val cardBcp = createMiniCard("🟠 BCP DIRECTO", "#FFC107").also { tvTotalBcp = it.second }
        val cardOtros = createMiniCard("🟢 OTROS / WA", "#2ECC71").also { tvTotalOtros = it.second }
        row2.addView(cardBcp.first, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 6, 0) })
        row2.addView(cardOtros.first, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 0, 0, 0) })

        // Gran Total Card
        val cardGranTotal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(20, 15, 20, 15)
            background = getGlassDrawable(Color.parseColor("#2500E5FF"), Color.parseColor("#AA00E5FF"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 0) }
        }
        val lblGran = TextView(this).apply { text = "💰 GRAN TOTAL DEL DÍA"; textSize = 11f; setTextColor(Color.WHITE); setTypeface(Typeface.MONOSPACE, Typeface.BOLD) }
        tvGranTotal = TextView(this).apply { text = "S/ 0.00"; textSize = 26f; setTextColor(Color.parseColor("#00E5FF")); setTypeface(Typeface.DEFAULT_BOLD) }
        tvCantPagos = TextView(this).apply { text = "0 pagos registrados hoy"; textSize = 9f; setTextColor(Color.WHITE); alpha = 0.7f }
        
        cardGranTotal.addView(lblGran)
        cardGranTotal.addView(tvGranTotal)
        cardGranTotal.addView(tvCantPagos)

        grid.addView(row1)
        grid.addView(row2)
        grid.addView(cardGranTotal)
        mainLayout.addView(grid)
    }

    private fun createMiniCard(title: String, accentHex: String): Pair<LinearLayout, TextView> {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(15, 12, 15, 12)
            background = getGlassDrawable(Color.parseColor("#15FFFFFF"), Color.parseColor(accentHex))
        }
        val tvTitle = TextView(this).apply { text = title; textSize = 9f; setTextColor(Color.WHITE); alpha = 0.8f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD) }
        val tvValue = TextView(this).apply { text = "S/ 0.00"; textSize = 15f; setTextColor(Color.parseColor(accentHex)); setTypeface(Typeface.DEFAULT_BOLD) }
        container.addView(tvTitle)
        container.addView(tvValue)
        return Pair(container, tvValue)
    }

    // --------------------------------------------------------------------------------
    // RECYCLERVIEW HISTORIAL (SECCIÓN 6)
    // --------------------------------------------------------------------------------
    private fun setupHistoryRecyclerView() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, 5, 0, 10) }
            background = getGlassDrawable(Color.parseColor("#AA000000"), Color.parseColor("#2200E5FF"))
            padding(15, 15, 15, 15)
        }

        val header = TextView(this).apply {
            text = "📋 HISTORIAL DE TRANSACCIONES HOY"
            textSize = 9f
            setTextColor(Color.parseColor("#8800E5FF"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setMargins(0, 0, 0, 10)
        }
        container.addView(header)

        rvPayments = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }
        adapter = PaymentAdapter(paymentList)
        rvPayments.adapter = adapter
        container.addView(rvPayments)

        mainLayout.addView(container)
    }

    private fun updateTotals() {
        var totalYape = 0.0
        var totalPlin = 0.0
        var totalBcp = 0.0
        var totalOtros = 0.0

        for (p in paymentList) {
            when (p.bank.uppercase()) {
                "YAPE" -> totalYape += p.amount
                "PLIN" -> totalPlin += p.amount
                "BCP" -> totalBcp += p.amount
                else -> totalOtros += p.amount
            }
        }

        val granTotal = totalYape + totalPlin + totalBcp + totalOtros
        tvTotalYape.text = String.format(Locale.US, "S/ %.2f", totalYape)
        tvTotalPlin.text = String.format(Locale.US, "S/ %.2f", totalPlin)
        tvTotalBcp.text = String.format(Locale.US, "S/ %.2f", totalBcp)
        tvTotalOtros.text = String.format(Locale.US, "S/ %.2f", totalOtros)
        tvGranTotal.text = String.format(Locale.US, "S/ %.2f", granTotal)
        tvCantPagos.text = "${paymentList.size} cobro(s) acumulado(s) el día de hoy"
    }

    // --------------------------------------------------------------------------------
    // POPUP MODO COMPAÑERO (SECCIÓN 1-B)
    // --------------------------------------------------------------------------------
    private fun showCompaneroPopup(bank: String, name: String, amount: String) {
        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            padding(40, 30, 40, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F20F141C"))
                cornerRadius = 35f
                setStroke(4, Color.parseColor("#00E5FF"))
            }
        }

        val title = TextView(this).apply {
            text = "⚡ CONFIRMADO EN CAJA"
            textSize = 14f
            setTextColor(Color.parseColor("#00E5FF"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "$bank • S/ $amount"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setMargins(0, 10, 0, 5)
        }
        val client = TextView(this).apply {
            text = "Cliente: $name"
            textSize = 16f
            setTextColor(Color.parseColor("#2ECC71"))
        }

        popupView.addView(title)
        popupView.addView(sub)
        popupView.addView(client)

        popupWindow?.dismiss()
        popupWindow = PopupWindow(popupView, (320 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            animationStyle = android.R.style.Animation_Dialog
            showAtLocation(mainLayout, Gravity.CENTER, 0, 0)
        }

        mainScope.launch {
            delay(5000)
            popupWindow?.dismiss()
        }
    }

    // --------------------------------------------------------------------------------
    // ACCIONES & QR DIALOGS
    // --------------------------------------------------------------------------------
    private fun showEmisorQRDialog() {
        val qrBitmap = generateQRCode(currentTopic)
        val imgView = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            layoutParams = LinearLayout.LayoutParams(600, 600).apply { gravity = Gravity.CENTER }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(40, 30, 40, 30)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F141C"))
                cornerRadius = 25f
            }
            addView(TextView(this@MainActivity).apply { text = "QR EMISOR - CLIENT_CODE"; textSize = 14f; setTextColor(Color.parseColor("#00E5FF")); setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setMargins(0,0,0,20) })
            addView(imgView)
            addView(TextView(this@MainActivity).apply { text = currentTopic; textSize = 12f; setTextColor(Color.WHITE); setMargins(0,20,0,0) })
        }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setView(container)
            .setPositiveButton("CERRAR", null)
            .show()
    }

    private fun generateQRCode(text: String): Bitmap? {
        return try {
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) { null }
    }

    private fun setupSOSButton() {
        sosStopBtn = Button(this).apply { text = "🛑 DETENER ALERTA MÓVIL"; layoutParams = LinearLayout.LayoutParams(-1, 110).apply { setMargins(0, 5, 0, 5) }; background = GradientDrawable().apply { setColor(Color.parseColor("#BBFF0000")); cornerRadius = 18f; setStroke(2, Color.WHITE) }; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, Typeface.BOLD); visibility = View.GONE; setOnClickListener { stopSOSProtocol() } }
        sosStopBtn?.let { mainLayout.addView(it) }
    }

    private fun setupActionButtons() {
        val btnLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        row1.addView(createActionButton("📡\nPC", 1f) { showVoiceMessageDialog() })
        row1.addView(createActionButton("🚨\nSOS", 1f) { triggerCommand(DataSyncService.KEY_SOS) })
        row1.addView(createActionButton("👮\nPOLICÍA", 1f) { triggerCommand(DataSyncService.KEY_POLICE) })
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        row2.addView(createActionButton("⚙️\nAJUSTES", 1f) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        row2.addView(createActionButton("📷\nQR", 1f) { openQRScanner() })
        row2.addView(createActionButton("🔌\nTEST", 1f) { triggerCommand(DataSyncService.KEY_TEST) })
        btnLayout.addView(row1); btnLayout.addView(row2); mainLayout.addView(btnLayout)
    }

    private fun showVoiceMessageDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(40, 30, 40, 20)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F141C")); cornerRadius = 24f }
        }
        val input = EditText(this).apply {
            hint = "Escribe tu mensaje a la PC..."
            setHintTextColor(Color.parseColor("#55FFFFFF"))
            setTextColor(Color.WHITE)
            background = getGlassDrawable(Color.parseColor("#15FFFFFF"), Color.parseColor("#00E5FF"))
            padding(25, 20, 25, 20)
        }
        container.addView(input)
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setView(container)
            .setPositiveButton("🔊 ENVIAR") { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isNotEmpty()) {
                    val i = Intent(this, DataSyncService::class.java).apply {
                        action = DataSyncService.MASTER_ACTION
                        putExtra(DataSyncService.MASTER_KEY, DataSyncService.KEY_SAY)
                        putExtra(DataSyncService.EXTRA_MESSAGE, msg)
                    }
                    startService(i)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun triggerVisualSOS() {
        sosStopBtn?.visibility = View.VISIBLE
        sosAnimator?.cancel()
        sosAnimator = ValueAnimator.ofObject(ArgbEvaluator(), Color.RED, Color.TRANSPARENT).apply { duration = 500; repeatCount = 30; repeatMode = ValueAnimator.REVERSE; addUpdateListener { animator -> val color = animator.animatedValue as Int; mainLayout.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, Color.BLACK)) }; addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { stopSOSProtocol() } }) }
        sosAnimator?.start()
    }

    private fun stopSOSProtocol() {
        sosAnimator?.cancel(); sosAnimator = null; sosStopBtn?.visibility = View.GONE
        mainLayout.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0F141C"), Color.parseColor("#080B10"), Color.BLACK))
        DataSyncService.inst?.stopSiren()
    }

    private fun triggerCommand(key: Int) {
        val i = Intent(this, DataSyncService::class.java).apply { 
            action = DataSyncService.MASTER_ACTION
            putExtra(DataSyncService.MASTER_KEY, key)
        }
        startService(i)
    }

    private fun vincularCodigo(data: String) { 
        if (data.contains("wingpay")) { 
            currentTopic = data
            getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit().putString("CLIENT_CODE", data).apply()
            relaunchService() 
        }
    }
    private fun relaunchService() { try { startService(Intent(this, DataSyncService::class.java).apply { putExtra("UPDATE_CODE", currentTopic) }) } catch (e: Exception) {} }
    private fun openQRScanner() { barcodeLauncher.launch(ScanOptions().apply { setDesiredBarcodeFormats(ScanOptions.QR_CODE); setPrompt("ESCANEE CÓDIGO QR"); setBeepEnabled(true); setOrientationLocked(false) }) }
    private fun startStatusMonitor() { mainScope.launch { while (isActive) { statusLED?.background = getCircleDrawable(if (DataSyncService.isServiceRunning()) Color.GREEN else Color.RED); delay(3000) } } }
    private fun handleSOSIntent(intent: Intent?) { if (intent?.getBooleanExtra("VISUAL_SOS", false) == true) triggerVisualSOS() }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleSOSIntent(intent) }
    private fun getCircleDrawable(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun getGlassDrawable(color: Int, strokeColor: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 18f; setStroke(2, strokeColor) }
    private fun createActionButton(txt: String, w: Float, action: () -> Unit) = Button(this).apply {
        text = txt
        val heightPx = (65 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(0, heightPx, w).apply { setMargins(4, 4, 4, 4) }
        background = getGlassDrawable(Color.parseColor("#1500E5FF"), Color.parseColor("#4400E5FF"))
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        textSize = 9f
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }
    override fun onDestroy() { mainScope.cancel(); try { unregisterReceiver(hudReceiver) } catch (e: Exception) {}; super.onDestroy() }

    private fun View.padding(l: Int, t: Int, r: Int, b: Int) = setPadding(l, t, r, b)
    private fun View.setMargins(l: Int, t: Int, r: Int, b: Int) {
        val p = layoutParams as? LinearLayout.LayoutParams ?: return
        p.setMargins(l, t, r, b)
        layoutParams = p
    }
    private fun TextView.TextColorHex(hex: String) = setTextColor(Color.parseColor(hex))
}

// --------------------------------------------------------------------------------
// ADAPTER HISTORIAL (RECYCLERVIEW)
// --------------------------------------------------------------------------------
data class PaymentItem(val bank: String, val name: String, val amount: Double, val time: String)

class PaymentAdapter(private val items: MutableList<PaymentItem>) : RecyclerView.Adapter<PaymentAdapter.ViewHolder>() {
    class ViewHolder(val view: LinearLayout, val tvBank: TextView, val tvName: TextView, val tvAmt: TextView, val tvTime: TextView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(15, 12, 15, 12)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 8) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#15FFFFFF"))
                cornerRadius = 14f
            }
        }
        val tvBank = TextView(ctx).apply { textSize = 10f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setPadding(0,0,15,0) }
        val infoLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val tvName = TextView(ctx).apply { textSize = 12f; setTextColor(Color.WHITE); setTypeface(Typeface.DEFAULT_BOLD) }
        val tvTime = TextView(ctx).apply { textSize = 9f; setTextColor(Color.WHITE); alpha = 0.6f }
        infoLayout.addView(tvName); infoLayout.addView(tvTime)

        val tvAmt = TextView(ctx).apply { textSize = 14f; setTypeface(Typeface.DEFAULT_BOLD) }

        container.addView(tvBank)
        container.addView(infoLayout)
        container.addView(tvAmt)

        return ViewHolder(container, tvBank, tvName, tvAmt, tvTime)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvBank.text = item.bank
        holder.tvName.text = item.name
        holder.tvTime.text = item.time
        holder.tvAmt.text = String.format(Locale.US, "S/ %.2f", item.amount)

        val colorHex = when (item.bank.uppercase()) {
            "YAPE" -> "#FF007F"
            "PLIN" -> "#00E5FF"
            "BCP" -> "#FFC107"
            else -> "#2ECC71"
        }
        holder.tvBank.setTextColor(Color.parseColor(colorHex))
        holder.tvAmt.setTextColor(Color.parseColor(colorHex))
    }

    override fun getItemCount() = items.size

    fun addPayment(item: PaymentItem) {
        items.add(0, item)
        notifyItemInserted(0)
    }
}
