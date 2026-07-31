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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    // Modo Dual State
    private var isEmisorMode = true
    private var currentTopic = "wingpay_ferreteria_" + UUID.randomUUID().toString().substring(0, 6)
    private var licenseKey = ""
    
    // UIs
    private lateinit var mainLayout: LinearLayout
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var rbEmisor: RadioButton
    private lateinit var rbCompanero: RadioButton
    private lateinit var btnActionQR: Button
    
    // Visual Cards (Sección 6 FERRETERÍA MAX)
    private lateinit var tvTotalYape: TextView
    private lateinit var tvTotalPlin: TextView
    private lateinit var tvTotalBcp: TextView
    private lateinit var tvTotalOtros: TextView
    private lateinit var tvGranTotal: TextView
    private lateinit var tvCantPagos: TextView
    private lateinit var tvUltimoPago: TextView
    private lateinit var tvUltimoPagoNombre: TextView
    
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
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                val amtVal = amtStr.toDoubleOrNull() ?: 0.0
                val item = PaymentItem(bank, name, amtVal, timeStr, dateStr)
                
                runOnUiThread {
                    adapter.addPayment(item)
                    updateTotals()
                    savePaymentsToStorage() // PERSISTENCIA DE 7 DÍAS CON CARPETA DOWNLOADS
                    
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
        
        if (!prefs.contains("CLIENT_CODE")) {
            prefs.edit().putString("CLIENT_CODE", currentTopic).apply()
        } else {
            currentTopic = prefs.getString("CLIENT_CODE", currentTopic) ?: currentTopic
        }
        
        isEmisorMode = prefs.getBoolean("IS_EMISOR_MODE", true)
        licenseKey = prefs.getString("LICENSE_KEY", "") ?: ""

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        buildUI()
        mainLayout.layoutParams = ViewGroup.LayoutParams(-1, -1)
        setContentView(mainLayout)
        
        // CARGAR HISTORIAL 7 DÍAS CAJA
        loadPaymentsFromStorage()
        requestBatteryOptimizationExemption()

        startStatusMonitor()
        handleSOSIntent(intent)
        
        val filter = IntentFilter("STARK_HUD_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hudReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(hudReceiver, filter)
        }

        checkAndShowLicenseDialogOnLaunch()
    }

    private fun checkAndShowLicenseDialogOnLaunch() {
        val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
        val key = prefs.getString("LICENSE_KEY", "") ?: ""
        val name = prefs.getString("USER_NAME", "") ?: ""
        
        if (key.isEmpty() || name.isEmpty()) {
            showLicenseActivationDialog(
                "👑 ACTIVACIÓN IMPORTACIONES WING GOLD", 
                "Para obtener su clave de licencia contacte a Importaciones Wing al WhatsApp 921665833."
            )
        } else {
            verifyLicenseWithGoogleSheet(key)
        }
    }

    private fun verifyLicenseWithGoogleSheet(key: String, isManualUpdate: Boolean = false) {
        val currentDeviceId = getHardwareDeviceId()
        mainScope.launch(Dispatchers.IO) {
            var status = "OFFLINE_OK"
            var expStr = ""
            try {
                val csvUrl = "https://docs.google.com/spreadsheets/d/1N7OgRlXECNBUwFWBaVTBl_b1t_aiLegdGRj_9gHMXXY/gviz/tq?tqx=out:csv"
                val urlObj = java.net.URL(csvUrl)
                val conn = (urlObj.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                
                val lines = conn.inputStream.bufferedReader().readLines()
                var keyFound = false
                var isExpired = false
                var isDeviceBlocked = false

                for (line in lines) {
                    val cols = line.split(",").map { it.replace("\"", "").trim() }
                    if (cols.size >= 2) {
                        val rowCode = cols[1]
                        val rowExpiration = cols.getOrNull(2) ?: ""
                        val rowDeviceId = cols.getOrNull(4) ?: ""

                        if (rowCode.equals(key, ignoreCase = true)) {
                            keyFound = true
                            expStr = rowExpiration

                            if (rowExpiration.isEmpty()) {
                                isExpired = true
                                expStr = "SIN FECHA REGISTRADA"
                            } else {
                                try {
                                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                    val expDate = sdf.parse(rowExpiration)
                                    val today = Date()
                                    if (expDate != null && today.after(expDate)) {
                                        isExpired = true
                                    }
                                } catch (e: Exception) {
                                    isExpired = true
                                }
                            }

                            if (rowDeviceId.isNotEmpty() && !rowDeviceId.equals(currentDeviceId, ignoreCase = true)) {
                                isDeviceBlocked = true
                            }

                            break
                        }
                    }
                }

                if (!keyFound) status = "NOT_FOUND"
                else if (isDeviceBlocked) status = "DEVICE_BLOCKED"
                else if (isExpired) status = "EXPIRED"
                else status = "VALID"

            } catch (e: Exception) {
                status = "OFFLINE_OK"
            }

            withContext(Dispatchers.Main) {
                when (status) {
                    "NOT_FOUND" -> showLicenseActivationDialog("❌ CLAVE INVÁLIDA O NO REGISTRADA", "La clave '$key' no existe en los servidores de Wing.\nContacte a Soporte WhatsApp (921665833).")
                    "DEVICE_BLOCKED" -> showLicenseActivationDialog("📱 LICENCIA VINCULADA A OTRO CELULAR", "Esta licencia ya se encuentra activa en otro equipo.\nContacte a Soporte WhatsApp (921665833).")
                    "EXPIRED" -> showLicenseActivationDialog("⏳ LICENCIA VENCIDA ($expStr)", "Su licencia ha vencido en los servidores de Wing.\nContacte a Soporte WhatsApp (921665833).")
                    else -> {
                        getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit()
                            .putString("LICENSE_KEY", key).apply()
                        if (isManualUpdate) recreate()
                    }
                }
            }
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

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }
    }

    // --------------------------------------------------------------------------------
    // PERSISTENCIA DE HASTA 7 DÍAS CON LIMPIEZA AUTOMÁTICA DE REGISTROS ANTIGUOS
    // --------------------------------------------------------------------------------
    private fun savePaymentsToStorage() {
        try {
            val jsonArray = JSONArray()
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -7)
            val cutoffDate = cal.time

            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            for (p in paymentList) {
                val pDate = try { sdf.parse(p.date) } catch (e: Exception) { Date() }
                // Guardar solo si es de los últimos 7 días
                if (pDate != null && !pDate.before(cutoffDate)) {
                    val obj = JSONObject().apply {
                        put("bank", p.bank)
                        put("name", p.name)
                        put("amount", p.amount)
                        put("time", p.time)
                        put("date", p.date)
                    }
                    jsonArray.put(obj)
                }
            }
            getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit()
                .putString("SAVED_PAYMENTS_JSON", jsonArray.toString())
                .apply()
        } catch (e: Exception) {}
    }

    private fun loadPaymentsFromStorage() {
        try {
            val jsonStr = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
                .getString("SAVED_PAYMENTS_JSON", null) ?: return
            val jsonArray = JSONArray(jsonStr)
            paymentList.clear()
            
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val itemDate = obj.optString("date", todayStr)
                paymentList.add(PaymentItem(
                    obj.getString("bank"),
                    obj.getString("name"),
                    obj.getDouble("amount"),
                    obj.getString("time"),
                    itemDate
                ))
            }
            adapter.notifyDataSetChanged()
            updateTotals()
        } catch (e: Exception) {}
    }

    // --------------------------------------------------------------------------------
    // EXPORTACIÓN AUTOMÁTICA A LA CARPETA DE DESCARGAS (DOWNLOADS) DE ANDROID
    // --------------------------------------------------------------------------------
    private fun exportCierreDeCajaCSV() {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val fileName = "Cierre_Caja_Ferreteria_$dateStr.csv"
            
            // Guardar en la carpeta publica de Downloads de Android
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            
            val file = File(downloadsDir, fileName)

            val sb = java.lang.StringBuilder()
            sb.append("FECHA,HORA,BANCO,CLIENTE,MONTO SOLES\n")

            var totalYape = 0.0
            var totalPlin = 0.0
            var totalBcp = 0.0
            var totalOtros = 0.0

            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val todayPayments = paymentList.filter { it.date == todayStr }

            for (p in todayPayments) {
                sb.append("${p.date},${p.time},${p.bank},\"${p.name}\",${p.amount}\n")
                when (p.bank.uppercase()) {
                    "YAPE" -> totalYape += p.amount
                    "PLIN" -> totalPlin += p.amount
                    "BCP", "BCP DIRECTO" -> totalBcp += p.amount
                    else -> totalOtros += p.amount
                }
            }

            val granTotal = totalYape + totalPlin + totalBcp + totalOtros
            sb.append("\nRESUMEN DE CIERRE FERRETERO DEL DIA ($todayStr)\n")
            sb.append("TOTAL YAPE,S/ $totalYape\n")
            sb.append("TOTAL PLIN,S/ $totalPlin\n")
            sb.append("TOTAL BCP,S/ $totalBcp\n")
            sb.append("TOTAL OTROS,S/ $totalOtros\n")
            sb.append("GRAN TOTAL DEL DIA,S/ $granTotal\n")
            sb.append("TOTAL OPERACIONES HOY,${todayPayments.size}\n")

            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }

            Toast.makeText(this, "📁 Guardado en Descargas:\n$fileName", Toast.LENGTH_LONG).show()

            // Abrir menú para compartir por WhatsApp / Telegram
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Cierre de Caja Ferretera $dateStr")
                putExtra(Intent.EXTRA_TEXT, "Adjunto reporte de cierre de caja ferretera acumulado en Descargas ($dateStr).\nGran Total Hoy: S/ $granTotal (${todayPayments.size} operaciones).")
                val fileUri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Enviar Cierre de Caja en Descargas"))

        } catch (e: Exception) {
            Toast.makeText(this, "Cierre de caja procesado correctamente", Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun buildUI() {
        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 20, 25, 20)
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
        val header = RelativeLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,10) } }
        val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
        val userName = prefs.getString("USER_NAME", "") ?: ""
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = "👑 IMPORTACIONES WING PAGOS • v999.0 GOLD"; textSize = 16f; setTextColor(Color.parseColor("#FFD700")); setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)) })
            addView(TextView(this@MainActivity).apply { 
                text = if (userName.isNotEmpty()) "¡BIENVENIDO $userName! | CAJA GOLD WING • CANAL: $currentTopic" else "¡BIENVENIDO! | CAJA GOLD WING • CANAL: $currentTopic"
                textSize = 13f; setTextColor(Color.parseColor("#FFF8DC")); setTypeface(null, Typeface.BOLD); alpha = 0.9f 
            })
        }
        header.addView(titleLayout)

        val ledContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -2).apply { addRule(RelativeLayout.ALIGN_PARENT_RIGHT); addRule(RelativeLayout.CENTER_VERTICAL) }
            
            statusLED = View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(18, 18); background = getCircleDrawable(Color.RED) }
            val statusText = TextView(this@MainActivity).apply { text = " CAJA"; textSize = 8f; setTextColor(Color.WHITE); setPadding(4, 0, 8, 0); alpha = 0.8f }
            
            syncLED = View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(18, 18); background = getCircleDrawable(Color.GRAY) }
            val syncText = TextView(this@MainActivity).apply { text = " RED"; textSize = 8f; setTextColor(Color.WHITE); setPadding(4, 0, 0, 0); alpha = 0.8f }
            
            addView(statusLED); addView(statusText)
            addView(syncLED); addView(syncText)
        }
        header.addView(ledContainer)
        mainLayout.addView(header)
    }

    private fun setupDualModeSelector() {
        val selectorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(15, 12, 15, 12)
            background = getGlassDrawable(Color.parseColor("#1500E5FF"), Color.parseColor("#3300E5FF"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) }
        }

        val tvTitle = TextView(this).apply {
            text = "MODALIDAD DE TRABAJO EN ESTE DISPOSITIVO"
            textSize = 9f
            TextColorHex("#00E5FF")
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setMargins(0, 0, 0, 6)
        }

        modeRadioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            weightSum = 2f
        }

        rbEmisor = RadioButton(this).apply {
            id = View.generateViewId()
            text = "📱 EMISOR"
            setTextColor(Color.WHITE)
            textSize = 10f
            layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
        }

        rbCompanero = RadioButton(this).apply {
            id = View.generateViewId()
            text = "📱 RECEPTOR"
            setTextColor(Color.WHITE)
            textSize = 10f
            layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
        }
        
        // Asignar check después de definir los IDs para evitar disparos múltiples
        if (isEmisorMode) {
            rbEmisor.isChecked = true
        } else {
            rbCompanero.isChecked = true
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
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, (40 * resources.displayMetrics.density).toInt()).apply { setMargins(0, 8, 0, 0) }
        }

        selectorCard.addView(tvTitle)
        selectorCard.addView(modeRadioGroup)
        selectorCard.addView(btnActionQR)
        mainLayout.addView(selectorCard)

        updateModeUI()
    }

    private fun updateModeUI() {
        if (isEmisorMode) {
            btnActionQR.text = "📱 MOSTRAR MI QR A VENDEDORES (MODO EMISOR)"
            btnActionQR.background = getGlassDrawable(Color.parseColor("#2200FF7F"), Color.parseColor("#8800FF7F"))
            btnActionQR.setOnClickListener { showEmisorQRDialog() }
        } else {
            btnActionQR.text = "📷 VENDEDOR: ESCANEAR QR DE LA CAJA EMISORA"
            btnActionQR.background = getGlassDrawable(Color.parseColor("#2200E5FF"), Color.parseColor("#8800E5FF"))
            btnActionQR.setOnClickListener { openQRScanner() }
        }
    }

    private fun setupFinancialCards() {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) }
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val cardYape = createMiniCard("🟣 YAPE CAJA", "#FF007F").also { tvTotalYape = it.second }
        val cardPlin = createMiniCard("🔵 PLIN CAJA", "#00E5FF").also { tvTotalPlin = it.second }
        row1.addView(cardYape.first, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 4, 0) })
        row1.addView(cardPlin.first, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 0, 0) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setMargins(0, 6, 0, 0) }
        val cardBcp = createMiniCard("🟠 BCP TELECRÉDITO", "#FFC107").also { tvTotalBcp = it.second }
        val cardOtros = createMiniCard("🟢 OTROS / BANCOS", "#2ECC71").also { tvTotalOtros = it.second }
        row2.addView(cardBcp.first, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 4, 0) })
        row2.addView(cardOtros.first, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 0, 0) })

        val cardGranTotal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(15, 12, 15, 12)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                Color.parseColor("#44FFD700"),
                Color.parseColor("#15000000"),
                Color.parseColor("#66DAA520")
            )).apply {
                cornerRadius = 16f
                setStroke(3, Color.parseColor("#FFD700"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 8, 0, 0) }
        }
        val headerRow = RelativeLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        val lblUltimo = TextView(this).apply { text = "⚡ ÚLTIMO COBRO RECIBIDO"; textSize = 11f; setTextColor(Color.parseColor("#FFD700")); setTypeface(Typeface.MONOSPACE, Typeface.BOLD) }
        val btnExport = Button(this).apply {
            text = "📁 DESCARGAS CSV"
            textSize = 9f
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFD700"))
                cornerRadius = 12f
            }
            setTypeface(null, Typeface.BOLD)
            layoutParams = RelativeLayout.LayoutParams(-2, (32 * resources.displayMetrics.density).toInt()).apply { addRule(RelativeLayout.ALIGN_PARENT_RIGHT) }
            setOnClickListener { exportCierreDeCajaCSV() }
        }
        headerRow.addView(lblUltimo)
        headerRow.addView(btnExport)

        tvUltimoPago = TextView(this).apply { 
            text = "S/ 0.00"; 
            textSize = 28f; 
            setTextColor(Color.parseColor("#FFD700")); 
            setTypeface(Typeface.DEFAULT_BOLD);
            setMargins(0, 4, 0, 0)
        }
        tvUltimoPagoNombre = TextView(this).apply { 
            text = "Esperando pagos..."; 
            textSize = 12f; 
            setTextColor(Color.WHITE); 
            alpha = 0.9f 
        }

        val cardRecaudacion = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            padding(12, 10, 12, 10)
            background = getGlassDrawable(Color.parseColor("#15FFFFFF"), Color.parseColor("#FFD700"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 12, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        }
        val lblRecaudacion = TextView(this).apply { 
            text = "💰 RECAUDACIÓN DEL DÍA:"; 
            textSize = 10f; 
            setTextColor(Color.parseColor("#FFD700")); 
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        tvGranTotal = TextView(this).apply { 
            text = "S/ 0.00"; 
            textSize = 14f; 
            setTextColor(Color.WHITE); 
            setTypeface(Typeface.DEFAULT_BOLD) 
        }
        cardRecaudacion.addView(lblRecaudacion)
        cardRecaudacion.addView(tvGranTotal)
        
        tvCantPagos = TextView(this).apply { text = "0 cobro(s) registrados hoy"; textSize = 9f; setTextColor(Color.parseColor("#FFF8DC")); alpha = 0.8f; setMargins(0, 8, 0, 0); gravity = Gravity.CENTER_HORIZONTAL }

        cardGranTotal.addView(headerRow)
        cardGranTotal.addView(tvUltimoPago)
        cardGranTotal.addView(tvUltimoPagoNombre)
        cardGranTotal.addView(cardRecaudacion)
        cardGranTotal.addView(tvCantPagos)

        grid.addView(row1)
        grid.addView(row2)
        grid.addView(cardGranTotal)
        mainLayout.addView(grid)
    }

    private fun createMiniCard(title: String, accentHex: String): Pair<LinearLayout, TextView> {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(12, 10, 12, 10)
            background = getGlassDrawable(Color.parseColor("#15FFFFFF"), Color.parseColor(accentHex))
        }
        val tvTitle = TextView(this).apply { text = title; textSize = 8f; setTextColor(Color.WHITE); alpha = 0.8f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD) }
        val tvValue = TextView(this).apply { text = "S/ 0.00"; textSize = 14f; setTextColor(Color.parseColor(accentHex)); setTypeface(Typeface.DEFAULT_BOLD) }
        container.addView(tvTitle)
        container.addView(tvValue)
        return Pair(container, tvValue)
    }

    private fun setupHistoryRecyclerView() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, 4, 0, 8) }
            background = getGlassDrawable(Color.parseColor("#AA000000"), Color.parseColor("#2200E5FF"))
            padding(12, 10, 12, 10)
        }

        val headerRow = RelativeLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 6) } }
        val header = TextView(this).apply {
            text = "📋 HISTORIAL DE VENTAS Y COBROS"
            textSize = 9f
            setTextColor(Color.parseColor("#8800E5FF"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val btnFiltrarSemanales = TextView(this).apply {
            text = "🔍 ÚLTIMOS 7 DÍAS"
            textSize = 9f
            setTextColor(Color.parseColor("#00E5FF"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = RelativeLayout.LayoutParams(-2, -2).apply { addRule(RelativeLayout.ALIGN_PARENT_RIGHT) }
            setOnClickListener { showWeeklyDialog() }
        }
        headerRow.addView(header)
        headerRow.addView(btnFiltrarSemanales)
        container.addView(headerRow)

        rvPayments = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            isNestedScrollingEnabled = true
        }
        adapter = PaymentAdapter(paymentList)
        rvPayments.adapter = adapter
        container.addView(rvPayments)

        mainLayout.addView(container)
    }

    private fun showWeeklyDialog() {
        val grouped = paymentList.groupBy { it.date }.toSortedMap(reverseOrder())
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(30, 20, 30, 20)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F141C")); cornerRadius = 24f }
        }
        val title = TextView(this).apply { text = "📅 ÚLTIMOS 7 DÍAS"; textSize = 14f; setTextColor(Color.parseColor("#FFD700")); setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setMargins(0, 0, 0, 15) }
        container.addView(title)
        
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, (300 * resources.displayMetrics.density).toInt())
        }
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        for ((date, payments) in grouped) {
            val totalDay = payments.sumOf { it.amount }
            val btnDay = Button(this).apply {
                text = "$date • ${payments.size} pagos • S/ ${String.format(Locale.US, "%.2f", totalDay)}"
                textSize = 10f
                setTextColor(Color.WHITE)
                background = getGlassDrawable(Color.parseColor("#2200E5FF"), Color.parseColor("#8800E5FF"))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) }
                setOnClickListener { showDayDetailsDialog(date, payments) }
            }
            listContainer.addView(btnDay)
        }
        
        scrollView.addView(listContainer)
        container.addView(scrollView)
        
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setView(container)
            .setPositiveButton("CERRAR", null)
            .show()
    }

    private fun showDayDetailsDialog(date: String, payments: List<PaymentItem>) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(30, 20, 30, 20)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F141C")); cornerRadius = 24f }
        }
        val title = TextView(this).apply { text = "📝 PAGOS DEL $date"; textSize = 12f; setTextColor(Color.parseColor("#00E5FF")); setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setMargins(0, 0, 0, 15) }
        container.addView(title)
        
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, (300 * resources.displayMetrics.density).toInt())
        }
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        for (p in payments) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                padding(15, 10, 15, 10)
                background = getGlassDrawable(Color.parseColor("#15FFFFFF"), Color.parseColor("#55FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) }
            }
            val tvName = TextView(this).apply { text = p.name; textSize = 11f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) }
            val tvDetails = TextView(this).apply { text = "${p.bank} • S/ ${p.amount} • ${p.time}"; textSize = 9f; setTextColor(Color.parseColor("#00E5FF")) }
            card.addView(tvName)
            card.addView(tvDetails)
            listContainer.addView(card)
        }
        
        scrollView.addView(listContainer)
        container.addView(scrollView)
        
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setView(container)
            .setPositiveButton("VOLVER", null)
            .show()
    }

    private fun updateTotals() {
        var totalYape = 0.0
        var totalPlin = 0.0
        var totalBcp = 0.0
        var totalOtros = 0.0

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todayPayments = paymentList.filter { it.date == todayStr }

        for (p in todayPayments) {
            when (p.bank.uppercase()) {
                "YAPE" -> totalYape += p.amount
                "PLIN" -> totalPlin += p.amount
                "BCP", "BCP DIRECTO" -> totalBcp += p.amount
                else -> totalOtros += p.amount
            }
        }

        val granTotal = totalYape + totalPlin + totalBcp + totalOtros
        tvTotalYape.text = String.format(Locale.US, "S/ %.2f", totalYape)
        tvTotalPlin.text = String.format(Locale.US, "S/ %.2f", totalPlin)
        tvTotalBcp.text = String.format(Locale.US, "S/ %.2f", totalBcp)
        tvTotalOtros.text = String.format(Locale.US, "S/ %.2f", totalOtros)
        tvGranTotal.text = String.format(Locale.US, "S/ %.2f", granTotal)
        tvCantPagos.text = "${todayPayments.size} cobro(s) registrados hoy (${paymentList.size} en 7 días)"
        
        if (todayPayments.isNotEmpty()) {
            val lastPayment = todayPayments.first() // Asumiendo orden descendente
            tvUltimoPago.text = String.format(Locale.US, "S/ %.2f", lastPayment.amount)
            tvUltimoPagoNombre.text = "de ${lastPayment.name} en ${lastPayment.bank}"
        } else {
            tvUltimoPago.text = "S/ 0.00"
            tvUltimoPagoNombre.text = "Esperando pagos..."
        }
    }

    private fun showCompaneroPopup(bank: String, name: String, amount: String) {
        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            padding(35, 25, 35, 25)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F20F141C"))
                cornerRadius = 30f
                setStroke(4, Color.parseColor("#00E5FF"))
            }
        }

        val title = TextView(this).apply {
            text = "⚡ COBRO FERRETERO CONFIRMADO"
            textSize = 13f
            setTextColor(Color.parseColor("#00E5FF"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "$bank • S/ $amount"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setMargins(0, 8, 0, 4)
        }
        val client = TextView(this).apply {
            text = "Cliente: $name"
            textSize = 15f
            setTextColor(Color.parseColor("#2ECC71"))
        }

        popupView.addView(title)
        popupView.addView(sub)
        popupView.addView(client)

        popupWindow?.dismiss()
        popupWindow = PopupWindow(popupView, (300 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            animationStyle = android.R.style.Animation_Dialog
            showAtLocation(mainLayout, Gravity.CENTER, 0, 0)
        }

        mainScope.launch {
            delay(5000)
            popupWindow?.dismiss()
        }
    }

    private fun showEmisorQRDialog() {
        val qrBitmap = generateQRCode(currentTopic)
        val imgView = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            layoutParams = LinearLayout.LayoutParams(550, 550).apply { gravity = Gravity.CENTER }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(30, 25, 30, 25)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F141C"))
                cornerRadius = 25f
            }
            addView(TextView(this@MainActivity).apply { text = "QR VINCULACION VENDEDOR/MOZO"; textSize = 13f; setTextColor(Color.parseColor("#00E5FF")); setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setMargins(0,0,0,15) })
            addView(imgView)
            addView(TextView(this@MainActivity).apply { text = currentTopic; textSize = 11f; setTextColor(Color.WHITE); setMargins(0,15,0,0) })
        }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setView(container)
            .setPositiveButton("CERRAR", null)
            .setNeutralButton("✏️ EDICIÓN MANUAL") { _, _ -> showCustomTokenDialog() }
            .show()
    }

    private fun showCustomTokenDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(35, 25, 35, 20)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F141C")); cornerRadius = 24f }
        }
        val label = TextView(this).apply {
            text = "🔑 PERSONALIZAR TOKEN DE FERRETERÍA"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setMargins(0, 0, 0, 15)
        }
        val input = EditText(this).apply {
            setText(currentTopic)
            setHintTextColor(Color.parseColor("#55FFFFFF"))
            setTextColor(Color.WHITE)
            background = getGlassDrawable(Color.parseColor("#15FFFFFF"), Color.parseColor("#00E5FF"))
            padding(20, 15, 20, 15)
        }
        container.addView(label)
        container.addView(input)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setView(container)
            .setPositiveButton("💾 GUARDAR TOKEN") { _, _ ->
                val newToken = input.text.toString().trim()
                if (newToken.isNotEmpty()) {
                    currentTopic = newToken
                    getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit().putString("CLIENT_CODE", newToken).apply()
                    Toast.makeText(this, "Token actualizado a: $newToken", Toast.LENGTH_SHORT).show()
                    relaunchService()
                    recreate()
                }
            }
            .setNegativeButton("Cancelar", null)
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
        sosStopBtn = Button(this).apply { text = "🛑 DETENER ALERTA MÓVIL"; layoutParams = LinearLayout.LayoutParams(-1, 100).apply { setMargins(0, 4, 0, 4) }; background = GradientDrawable().apply { setColor(Color.parseColor("#BBFF0000")); cornerRadius = 16f; setStroke(2, Color.WHITE) }; setTextColor(Color.WHITE); textSize = 13f; setTypeface(null, Typeface.BOLD); visibility = View.GONE; setOnClickListener { stopSOSProtocol() } }
        sosStopBtn?.let { mainLayout.addView(it) }
    }

    private fun setupActionButtons() {
        val btnLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        row1.addView(createActionButton("📡\nPC", 1f) { showVoiceMessageDialog() })
        row1.addView(createActionButton("🚨\nSOS", 1f) { triggerCommand(DataSyncService.KEY_SOS) })
        row1.addView(createActionButton("👮\nPOLICÍA", 1f) { triggerCommand(DataSyncService.KEY_POLICE) })
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        row2.addView(createActionButton("⚙️\nAJUSTES", 1f) { showCustomTokenDialog() })
        row2.addView(createActionButton("📷\nQR", 1f) { openQRScanner() })
        row2.addView(createActionButton("🔌\nTEST", 1f) { triggerCommand(DataSyncService.KEY_TEST) })
        btnLayout.addView(row1); btnLayout.addView(row2); mainLayout.addView(btnLayout)
    }

    private fun getHardwareDeviceId(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
        } catch (e: Exception) {
            "UNKNOWN_DEVICE"
        }
    }

    private fun showLicenseActivationDialog(title: String, message: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(35, 25, 35, 20)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F141C")); cornerRadius = 24f }
        }
        val lblTitle = TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(Color.parseColor("#FF007F"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            val p = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) }
            layoutParams = p
        }
        val lblMsg = TextView(this).apply {
            text = message
            textSize = 10f
            setTextColor(Color.WHITE)
            val p = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 15) }
            layoutParams = p
        }
        val input = EditText(this).apply {
            hint = "Ingrese su Clave de Licencia..."
            setText(licenseKey)
            setHintTextColor(Color.parseColor("#55FFFFFF"))
            setTextColor(Color.WHITE)
            background = getGlassDrawable(Color.parseColor("#15FFFFFF"), Color.parseColor("#FFD700"))
            padding(20, 15, 20, 15)
            val p = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 15) }
            layoutParams = p
        }

        val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
        val savedName = prefs.getString("USER_NAME", "") ?: ""
        
        val inputName = EditText(this).apply {
            hint = "Su Nombre (Ej. Wilson)..."
            setText(savedName)
            setHintTextColor(Color.parseColor("#55FFFFFF"))
            setTextColor(Color.WHITE)
            background = getGlassDrawable(Color.parseColor("#15FFFFFF"), Color.parseColor("#00E5FF"))
            padding(20, 15, 20, 15)
            val p = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 15) }
            layoutParams = p
        }

        val btnWhatsApp = Button(this).apply {
            text = "💬 CONTACTAR SOPORTE WHATSAPP (921665833)"
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = getGlassDrawable(Color.parseColor("#332ECC71"), Color.parseColor("#2ECC71"))
            layoutParams = LinearLayout.LayoutParams(-1, (45 * resources.displayMetrics.density).toInt()).apply { setMargins(0, 5, 0, 0) }
            setOnClickListener {
                val devId = getHardwareDeviceId()
                val url = "https://api.whatsapp.com/send?phone=51921665833&text=" + Uri.encode("Hola Importaciones Wing, solicito soporte de licencia para equipo ID: $devId")
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
            }
        }

        val devIdText = TextView(this).apply {
            text = "Soporte: 921665833 • ID Equipo: ${getHardwareDeviceId()}"
            textSize = 8f
            setTextColor(Color.parseColor("#88FFFFFF"))
            gravity = Gravity.CENTER
            val p = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 12, 0, 0) }
            layoutParams = p
        }

        container.addView(lblTitle)
        container.addView(lblMsg)
        container.addView(input)
        container.addView(inputName)
        container.addView(btnWhatsApp)
        container.addView(devIdText)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("🔑 GUARDAR Y ACTIVAR") { _, _ ->
                val key = input.text.toString().trim()
                val name = inputName.text.toString().trim()
                if (key.isNotEmpty()) {
                    licenseKey = key
                    getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit()
                        .putString("LICENSE_KEY", key)
                        .putString("USER_NAME", name)
                        .apply()
                    verifyLicenseWithGoogleSheet(key, true)
                } else if (name.isNotEmpty()) {
                     getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit()
                        .putString("USER_NAME", name)
                        .apply()
                     recreate()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }
    private fun showVoiceMessageDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(35, 25, 35, 20)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0F141C")); cornerRadius = 24f }
        }
        val input = EditText(this).apply {
            hint = "Mensaje directo a PC..."
            setHintTextColor(Color.parseColor("#55FFFFFF"))
            setTextColor(Color.WHITE)
            background = getGlassDrawable(Color.parseColor("#15FFFFFF"), Color.parseColor("#00E5FF"))
            padding(20, 15, 20, 15)
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
        val tokenLimpio = data.trim()
        if (tokenLimpio.isNotEmpty()) { 
            currentTopic = tokenLimpio
            getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit().putString("CLIENT_CODE", tokenLimpio).apply()
            Toast.makeText(this, "VINCULADO A TOKEN: $tokenLimpio", Toast.LENGTH_SHORT).show()
            relaunchService() 
            recreate()
        }
    }
    private fun relaunchService() { try { startService(Intent(this, DataSyncService::class.java).apply { putExtra("UPDATE_CODE", currentTopic) }) } catch (e: Exception) {} }
    private fun openQRScanner() { barcodeLauncher.launch(ScanOptions().apply { setDesiredBarcodeFormats(ScanOptions.QR_CODE); setPrompt("ESCANEE CÓDIGO QR"); setBeepEnabled(true); setOrientationLocked(false) }) }
    private fun startStatusMonitor() { mainScope.launch { while (isActive) { statusLED?.background = getCircleDrawable(if (DataSyncService.isServiceRunning()) Color.GREEN else Color.RED); delay(3000) } } }
    private fun handleSOSIntent(intent: Intent?) { if (intent?.getBooleanExtra("VISUAL_SOS", false) == true) triggerVisualSOS() }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleSOSIntent(intent) }
    private fun getCircleDrawable(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun getGlassDrawable(color: Int, strokeColor: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 16f; setStroke(2, strokeColor) }
    private fun createActionButton(txt: String, w: Float, action: () -> Unit) = Button(this).apply {
        text = txt
        val heightPx = (55 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(0, heightPx, w).apply { setMargins(3, 3, 3, 3) }
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
// ADAPTER HISTORIAL (RECYCLERVIEW) CON SOPORTE PARA FECHAS Y 7 DÍAS
// --------------------------------------------------------------------------------
data class PaymentItem(val bank: String, val name: String, val amount: Double, val time: String, val date: String = "")

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
        holder.tvTime.text = if (item.date.isNotEmpty()) "${item.date} ${item.time}" else item.time
        holder.tvAmt.text = String.format(Locale.US, "S/ %.2f", item.amount)

        val colorHex = when (item.bank.uppercase()) {
            "YAPE" -> "#FF007F"
            "PLIN" -> "#00E5FF"
            "BCP", "BCP DIRECTO" -> "#FFC107"
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
