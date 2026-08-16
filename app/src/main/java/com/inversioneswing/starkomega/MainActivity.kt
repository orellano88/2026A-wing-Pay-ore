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
    private var ttsIndicatorLED: View? = null
    private lateinit var rbEmisor: RadioButton
    private lateinit var rbCompanero: RadioButton
    private lateinit var btnActionQR: Button
    private lateinit var btnPermWarning: Button
    
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
    private val todayPaymentsList = mutableListOf<PaymentItem>()
    
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
    private var welcomeSpoken = false

    private val hudReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val name = it.getStringExtra("NAME") ?: "Anónimo"
                val amtStr = it.getStringExtra("AMT") ?: "0.00"
                val bank = it.getStringExtra("BANK") ?: "PAGO"
                val direction = it.getStringExtra("DIRECTION") ?: "INGRESO"
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val timeExtra = it.getStringExtra("TIME") ?: ""
                val timeStr = if (timeExtra.isNotEmpty()) timeExtra else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                val amtVal = amtStr.toDoubleOrNull() ?: 0.0
                val item = PaymentItem(bank, name, amtVal, timeStr, dateStr, direction)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    try {
                        val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
                        val lastOpened = prefs.getString("LAST_OPENED_DATE", "")
                        if (!lastOpened.isNullOrEmpty() && lastOpened != dateStr) {
                            loadPaymentsFromStorage()
                        }

                        adapter.addPayment(item)
                        paymentList.add(0, item)
                        if (dateStr != todayPaymentsList.firstOrNull()?.date && todayPaymentsList.isNotEmpty()) {
                            // Día cambió, la lista todayPaymentsList ya fue limpiada por loadPaymentsFromStorage
                        }
                        updateTotals()
                        savePaymentsToStorage() // PERSISTENCIA DE 7 DÍAS CON CARPETA DOWNLOADS
                        
                        // MOSTRAR POPUP VISUAL GIGANTE EN PANTALLA SIEMPRE QUE LLEGUE UN PAGO
                        showIncomingPaymentPopup(bank, name, amtStr, timeStr)
                    } catch (e: Exception) {}
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
                "ACTIVACIÓN INVERSIONES WING PREMIUM", 
                "Ingrese sus datos para activar el sistema. Si no cuenta con una licencia, contacte al soporte técnico."
            )
        } else {
            verifyLicenseWithGoogleSheet(key, name)
        }
    }

    private fun verifyLicenseWithGoogleSheet(key: String, name: String, isManualUpdate: Boolean = false) {
        val currentDeviceId = getHardwareDeviceId()
        mainScope.launch(Dispatchers.IO) {
            var status = "OFFLINE_OK"
            try {
                val scriptUrl = "https://script.google.com/macros/s/AKfycbyKeJw96jrjZuavudsHLmY_C8zjcjqM8to78u-G_3TBZcc9iKg_R2aNTEdMZBQlXbaQtg/exec"
                val encodedKey = Uri.encode(key)
                val encodedName = Uri.encode(name)
                val encodedDevId = Uri.encode(currentDeviceId)
                val fullUrl = "$scriptUrl?codigo=$encodedKey&nombre=$encodedName&id_equipo=$encodedDevId"
                
                var targetUrl = fullUrl
                var redirects = 0
                var conn: java.net.HttpURLConnection? = null
                
                while (redirects < 5) {
                    val urlObj = java.net.URL(targetUrl)
                    conn = (urlObj.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12000
                        readTimeout = 12000
                        instanceFollowRedirects = true
                    }
                    
                    val code = conn.responseCode
                    if (code == java.net.HttpURLConnection.HTTP_MOVED_TEMP || code == java.net.HttpURLConnection.HTTP_MOVED_PERM || code == 307 || code == 308) {
                        val newUrl = conn.getHeaderField("Location")
                        if (!newUrl.isNullOrEmpty()) {
                            targetUrl = newUrl
                            redirects++
                            conn.disconnect()
                            continue
                        }
                    }
                    
                    if (code == java.net.HttpURLConnection.HTTP_OK) {
                        status = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    } else {
                        status = "HTTP_ERROR_$code"
                    }
                    break
                }
            } catch (e: Exception) {
                status = "OFFLINE_OK"
            }

            withContext(Dispatchers.Main) {
                when (status) {
                    "LICENCIA_INVALIDA" -> showLicenseActivationDialog("❌ CLAVE INVÁLIDA O VENCIDA", "La clave no existe o ha vencido.\nContacte a Soporte WhatsApp (921665833).")
                    "ERROR_DISPOSITIVO_NO_AUTORIZADO" -> showLicenseActivationDialog("📱 ERROR: DISPOSITIVO NO AUTORIZADO", "Error: Esta clave ya fue activada en otro dispositivo. Contacte a soporte para reasignar su licencia.")
                    "ACTIVADO_CORRECTAMENTE", "LICENCIA_VALIDA", "OFFLINE_OK" -> {
                        getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit()
                            .putString("LICENSE_KEY", key)
                            .putString("USER_NAME", name).apply()
                        if (isManualUpdate || status == "ACTIVADO_CORRECTAMENTE") {
                            Toast.makeText(this@MainActivity, "✅ Licencia Validada", Toast.LENGTH_SHORT).show()
                            recreate()
                        }
                    }
                    else -> showLicenseActivationDialog("❌ ERROR DE RED", "No se pudo verificar la licencia. Respuesta: $status")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
        val lastOpened = prefs.getString("LAST_OPENED_DATE", "")
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (!lastOpened.isNullOrEmpty() && lastOpened != todayStr) {
            loadPaymentsFromStorage()
        }

        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        updatePermissionWarningVisibility()
        if (isNotificationServiceEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    android.service.notification.NotificationListenerService.requestRebind(
                        ComponentName(this, DataSyncService::class.java)
                    )
                } catch (e: Exception) {}
            }
            relaunchService()
        }

        if (!welcomeSpoken) {
            val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val lastWelcomeDate = prefs.getString("last_welcome_date", "")
            
            if (lastWelcomeDate != todayStr) {
                val greetingEnabled = prefs.getBoolean("GREETING_ENABLED", false)
                if (greetingEnabled) {
                    val savedMsg = prefs.getString("GREETING_MSG", "¡Buenos días, Señor! Sistemas listos y caja en línea. Hoy será un gran día para el negocio. Éxitos.") ?: ""
                    mainScope.launch {
                        var retries = 0
                        while (DataSyncService.inst == null && retries < 10) {
                            delay(500)
                            retries++
                        }
                        DataSyncService.inst?.speakWithMaxVolumeFocus(savedMsg)
                        prefs.edit().putString("last_welcome_date", todayStr).apply()
                    }
                } else {
                    prefs.edit().putString("last_welcome_date", todayStr).apply()
                }
            }
            welcomeSpoken = true
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }

    private fun updatePermissionWarningVisibility() {
        if (::btnPermWarning.isInitialized) {
            val hasPerm = isNotificationServiceEnabled()
            btnPermWarning.visibility = if (hasPerm) View.GONE else View.VISIBLE
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
                        put("direction", p.direction)
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
            val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("SAVED_PAYMENTS_JSON", null) ?: "[]"
            val jsonArray = JSONArray(jsonStr)
            
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            
            // Actualizar la fecha de última apertura
            prefs.edit().putString("LAST_OPENED_DATE", todayStr).apply()
            
            paymentList.clear()
            todayPaymentsList.clear()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val itemDate = obj.optString("date", todayStr)
                val dir = obj.optString("direction", "INGRESO")
                val item = PaymentItem(
                    obj.getString("bank"),
                    obj.getString("name"),
                    obj.getDouble("amount"),
                    obj.getString("time"),
                    itemDate,
                    dir
                )
                paymentList.add(item)
                if (itemDate == todayStr) {
                    todayPaymentsList.add(item)
                }
            }
            adapter.notifyDataSetChanged()
            updateTotals()
        } catch (e: Exception) {}
    }

    // --------------------------------------------------------------------------------
    // EXPORTACIÓN AUTOMÁTICA A LA CARPETA DE DESCARGAS (DOWNLOADS) DE ANDROID
    // --------------------------------------------------------------------------------
    private fun exportCierreDeCajaCSV() {
        // En Android 11+ necesitamos permiso especial para escribir fuera del directorio de la app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Se requiere permiso de almacenamiento. Actívalo en la siguiente pantalla.", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
                return
            }
        }

        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val fileName = "Cierre_Caja_Ferreteria_$dateStr.csv"
            
            // Guardar en la carpeta /wingpagos/
            val wingpagosDir = File(Environment.getExternalStorageDirectory(), "wingpagos")
            if (!wingpagosDir.exists()) {
                val created = wingpagosDir.mkdirs()
                if (!created) {
                    Toast.makeText(this, "❌ Error: No se pudo crear la carpeta wingpagos", Toast.LENGTH_LONG).show()
                    return
                }
            }
            
            val file = File(wingpagosDir, fileName)

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

            Toast.makeText(this, "📁 CSV guardado en:\nwingpagos/$fileName", Toast.LENGTH_LONG).show()

            val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
            val goodbyeEnabled = prefs.getBoolean("GOODBYE_ENABLED", false)
            if (goodbyeEnabled) {
                val savedGoodbyeMsg = prefs.getString("GOODBYE_MSG", "Jornada finalizada con éxito. Excelente trabajo hoy, equipo. Nos vemos mañana.") ?: ""
                DataSyncService.inst?.speakWithMaxVolumeFocus(savedGoodbyeMsg)
            }

            // Compartir por WhatsApp / Telegram
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_SUBJECT, "Cierre de Caja Ferretera $dateStr")
                    putExtra(Intent.EXTRA_TEXT, "Adjunto reporte de cierre de caja ($dateStr).\nGran Total Hoy: S/ $granTotal (${todayPayments.size} operaciones).")
                    val fileUri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Enviar Cierre de Caja"))
            } catch (e: Exception) {
                // El archivo ya se guardó, solo falló el compartir
            }

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Error al exportar: ${e.message}", Toast.LENGTH_LONG).show()
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
                intArrayOf(Color.parseColor("#0A0E17"), Color.parseColor("#0D1117"), Color.parseColor("#060810")))
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
            addView(TextView(this@MainActivity).apply { text = "👑 GRUPO INVERSIONES WING • v10.0 PREMIUM"; textSize = 15f; setTextColor(Color.parseColor("#FFD700")); setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)) })
            addView(TextView(this@MainActivity).apply { 
                text = if (userName.isNotEmpty()) "¡BIENVENIDO $userName! | CAJA PREMIUM • CANAL: $currentTopic" else "¡BIENVENIDO! | CAJA PREMIUM • CANAL: $currentTopic"
                textSize = 12f; setTextColor(Color.parseColor("#FFF8DC")); setTypeface(null, Typeface.BOLD); alpha = 0.9f 
            })
        }
        header.addView(titleLayout)

        val ledContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -2).apply { addRule(RelativeLayout.ALIGN_PARENT_RIGHT); addRule(RelativeLayout.CENTER_VERTICAL) }
            
            // BOTÓN PARLANTE SALUDO PROGRAMADO Y DESPEDIDA
            val btnSpeaker = TextView(this@MainActivity).apply {
                text = "🔊"
                textSize = 20f
                setPadding(8, 0, 4, 0)
                setOnClickListener { showGreetingConfigDialog() }
            }
            addView(btnSpeaker)

            ttsIndicatorLED = View(this@MainActivity).apply { 
                layoutParams = LinearLayout.LayoutParams(18, 18).apply { setMargins(0,0,12,0) }
                updateTtsIndicator()
            }
            addView(ttsIndicatorLED)

            statusLED = View(this@MainActivity).apply { 
                layoutParams = LinearLayout.LayoutParams(18, 18)
                background = getCircleDrawable(Color.RED)
                setOnClickListener {
                    Toast.makeText(this@MainActivity, "🔄 Revinculando motor de pagos...", Toast.LENGTH_SHORT).show()
                    relaunchService()
                }
            }
            val statusText = TextView(this@MainActivity).apply { 
                text = " CAJA"; 
                textSize = 8f; 
                setTextColor(Color.WHITE); 
                setPadding(4, 0, 8, 0); 
                alpha = 0.8f 
                setOnClickListener {
                    Toast.makeText(this@MainActivity, "🔄 Revinculando motor de pagos...", Toast.LENGTH_SHORT).show()
                    relaunchService()
                }
            }
            
            syncLED = View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(18, 18); background = getCircleDrawable(Color.GRAY) }
            val syncText = TextView(this@MainActivity).apply { text = " RED"; textSize = 8f; setTextColor(Color.WHITE); setPadding(4, 0, 0, 0); alpha = 0.8f }
            
            addView(statusLED); addView(statusText)
            addView(syncLED); addView(syncText)
        }
        header.addView(ledContainer)
        mainLayout.addView(header)

        btnPermWarning = Button(this).apply {
            text = "⚠️ ¡PERMISO DESACTIVADO! TOCA AQUÍ PARA OTORGAR ACCESO Y LEER YAPES AUTOMÁTICAMENTE"
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CCFF0033"))
                cornerRadius = 14f
                setStroke(2, Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 4, 0, 10) }
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    Toast.makeText(this@MainActivity, "Busca 'WingPay' en la lista y ACTÍVALO", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Abre Ajustes > Notificaciones > Acceso a notificaciones y activa WingPay", Toast.LENGTH_LONG).show()
                }
            }
        }
        mainLayout.addView(btnPermWarning)
        updatePermissionWarningVisibility()
    }

    private fun setupDualModeSelector() {
        val selectorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(15, 12, 15, 12)
            background = getGlassDrawable(Color.parseColor("#1500E5FF"), Color.parseColor("#3300E5FF"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) }
        }

        val tvTitle = TextView(this).apply {
            text = "⚙️ MODO DE OPERACIÓN"
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
            text = "🏪 CAJA PRINCIPAL"
            setTextColor(Color.WHITE)
            textSize = 10f
            layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
        }

        rbCompanero = RadioButton(this).apply {
            id = View.generateViewId()
            text = "🧑‍💼 VENDEDOR"
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

    private fun updateTtsIndicator() {
        val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
        val isTtsActive = prefs.getBoolean("GREETING_ENABLED", false) || prefs.getBoolean("GOODBYE_ENABLED", false)
        ttsIndicatorLED?.background = getCircleDrawable(if (isTtsActive) Color.GREEN else Color.GRAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ttsIndicatorLED?.tooltipText = if (isTtsActive) "Audio Activado" else "Audio Desactivado"
        }
    }

    private fun updateModeUI() {
        if (isEmisorMode) {
            btnActionQR.text = "📱 MOSTRAR MI QR A VENDEDORES (CAJA PRINCIPAL)"
            btnActionQR.background = getGlassDrawable(Color.parseColor("#2200FF7F"), Color.parseColor("#8800FF7F"))
            btnActionQR.setOnClickListener { showEmisorQRDialog() }
        } else {
            btnActionQR.text = "📷 VENDEDOR: ESCANEAR QR DE LA CAJA PRINCIPAL"
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
                Color.parseColor("#33C9A029"),
                Color.parseColor("#10000000"),
                Color.parseColor("#44B8860B")
            )).apply {
                cornerRadius = 16f
                setStroke(3, Color.parseColor("#FFD700"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 8, 0, 0) }
        }
        val headerRow = RelativeLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        val lblUltimo = TextView(this).apply { text = "⚡ ÚLTIMO COBRO"; textSize = 11f; setTextColor(Color.parseColor("#FFD700")); setTypeface(Typeface.MONOSPACE, Typeface.BOLD) }
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
            text = "💰 TOTAL DEL DÍA:"; 
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
        adapter = PaymentAdapter(todayPaymentsList)
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
        var totalEgresos = 0.0

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todayPayments = paymentList.filter { it.date == todayStr }

        for (p in todayPayments) {
            if (p.direction.uppercase() == "EGRESO") {
                totalEgresos += p.amount
            } else {
                when (p.bank.uppercase()) {
                    "YAPE" -> totalYape += p.amount
                    "PLIN" -> totalPlin += p.amount
                    "BCP", "BCP DIRECTO" -> totalBcp += p.amount
                    else -> totalOtros += p.amount
                }
            }
        }

        val granTotal = (totalYape + totalPlin + totalBcp + totalOtros) - totalEgresos
        tvTotalYape.text = String.format(Locale.US, "S/ %.2f", totalYape)
        tvTotalPlin.text = String.format(Locale.US, "S/ %.2f", totalPlin)
        tvTotalBcp.text = String.format(Locale.US, "S/ %.2f", totalBcp)
        tvTotalOtros.text = String.format(Locale.US, "S/ %.2f", totalOtros)
        tvGranTotal.text = String.format(Locale.US, "S/ %.2f", granTotal)
        tvCantPagos.text = "${todayPayments.size} pago(s) hoy (${paymentList.size} en 7 días)"
        
        if (todayPayments.isNotEmpty()) {
            val lastPayment = todayPayments.first()
            tvUltimoPago.text = String.format(Locale.US, "S/ %.2f", lastPayment.amount)
            val bankBadge = when (lastPayment.bank.uppercase()) {
                "YAPE" -> "🟣 YAPE"
                "PLIN" -> "🔵 PLIN"
                "BCP", "BCP DIRECTO" -> "🟠 BCP"
                "BBVA" -> "🟢 BBVA"
                "INTERBANK" -> "🔵 INTERBANK"
                else -> "🟢 ${lastPayment.bank}"
            }
            tvUltimoPagoNombre.text = "👤 ${lastPayment.name.uppercase()}  •  $bankBadge  •  ⏰ ${lastPayment.time}"
        } else {
            tvUltimoPago.text = "S/ 0.00"
            tvUltimoPagoNombre.text = "Esperando pagos..."
        }
    }

    private fun showIncomingPaymentPopup(bank: String, name: String, amount: String, time: String) {
        val bankColorHex = when (bank.uppercase()) {
            "YAPE" -> "#FF007F"
            "PLIN" -> "#00E5FF"
            "BCP", "BCP DIRECTO" -> "#FFC107"
            "BBVA" -> "#00E676"
            "INTERBANK" -> "#00B0FF"
            else -> "#2ECC71"
        }

        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            padding(35, 25, 35, 25)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F20F141C"))
                cornerRadius = 30f
                setStroke(5, Color.parseColor(bankColorHex))
            }
        }

        val title = TextView(this).apply {
            text = "⚡ ¡NUEVO PAGO RECIBIDO!"
            textSize = 14f
            setTextColor(Color.parseColor("#FFD700"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val bankTag = TextView(this).apply {
            text = "BANCO: ${bank.uppercase()}"
            textSize = 16f
            setTextColor(Color.parseColor(bankColorHex))
            setTypeface(Typeface.DEFAULT_BOLD)
            setMargins(0, 6, 0, 4)
        }

        val sub = TextView(this).apply {
            text = "S/ $amount"
            textSize = 34f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setMargins(0, 4, 0, 4)
        }

        val client = TextView(this).apply {
            text = "👤 DE: ${name.uppercase()}"
            textSize = 18f
            setTextColor(Color.parseColor("#2ECC71"))
            setTypeface(null, Typeface.BOLD)
        }

        val timeText = TextView(this).apply {
            text = "⏰ HORA: $time"
            textSize = 12f
            setTextColor(Color.parseColor("#FFF8DC"))
            alpha = 0.8f
            setMargins(0, 6, 0, 0)
        }

        popupView.addView(title)
        popupView.addView(bankTag)
        popupView.addView(sub)
        popupView.addView(client)
        popupView.addView(timeText)

        try {
            popupWindow?.dismiss()
            popupWindow = PopupWindow(popupView, (320 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                animationStyle = android.R.style.Animation_Dialog
                showAtLocation(mainLayout, Gravity.CENTER, 0, 0)
            }

            mainScope.launch {
                delay(6000)
                try { popupWindow?.dismiss() } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            // Ignorar error si la app está en segundo plano y el window token no es válido
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
            addView(TextView(this@MainActivity).apply { text = "QR VINCULACION VENDEDOR"; textSize = 13f; setTextColor(Color.parseColor("#00E5FF")); setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setMargins(0,0,0,15) })
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
            text = "💬 CONTACTAR SOPORTE WHATSAPP"
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = getGlassDrawable(Color.parseColor("#332ECC71"), Color.parseColor("#2ECC71"))
            layoutParams = LinearLayout.LayoutParams(-1, (45 * resources.displayMetrics.density).toInt()).apply { setMargins(0, 5, 0, 0) }
            setOnClickListener {
                val devId = getHardwareDeviceId()
                val url = "https://api.whatsapp.com/send?phone=51921665833&text=" + Uri.encode("Hola Inversiones Wing, solicito soporte de licencia para la aplicación WINGPAY. ID de equipo: $devId")
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
            }
        }

        val devIdText = TextView(this).apply {
            text = "Soporte: +51 921 665 833  •  ID Equipo: ${getHardwareDeviceId()}"
            textSize = 10f
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
                    verifyLicenseWithGoogleSheet(key, name, true)
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
    private fun relaunchService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(this, DataSyncService::class.java)
                )
            }
            startService(Intent(this, DataSyncService::class.java).apply { putExtra("UPDATE_CODE", currentTopic) })
        } catch (e: Exception) {}
    }
    private fun openQRScanner() { barcodeLauncher.launch(ScanOptions().apply { setDesiredBarcodeFormats(ScanOptions.QR_CODE); setPrompt("ESCANEE CÓDIGO QR"); setBeepEnabled(true); setOrientationLocked(false) }) }
    private fun startStatusMonitor() {
        mainScope.launch {
            while (isActive) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        statusLED?.background = getCircleDrawable(if (DataSyncService.isServiceRunning()) Color.GREEN else Color.RED)
                    }
                }
                val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
                val lastOpened = prefs.getString("LAST_OPENED_DATE", "")
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                if (!lastOpened.isNullOrEmpty() && lastOpened != todayStr) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            loadPaymentsFromStorage()
                        }
                    }
                }
                delay(3000)
            }
        }
    }
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

    // --------------------------------------------------------------------------------
    // SISTEMA DE SALUDO POR VOZ PROGRAMADO (MULTI-DISPOSITIVO: CAJA + VENDEDOR + PC)
    // --------------------------------------------------------------------------------
    private fun showGreetingConfigDialog() {
        val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
        val savedMsg = prefs.getString("GREETING_MSG", "¡Buenos días, Señor! Sistemas listos y caja en línea. Hoy será un gran día para el negocio. Éxitos.") ?: ""
        val savedHour = prefs.getInt("GREETING_HOUR", 7)
        val savedMin = prefs.getInt("GREETING_MIN", 0)
        val greetingEnabled = prefs.getBoolean("GREETING_ENABLED", false)

        val savedGoodbyeMsg = prefs.getString("GOODBYE_MSG", "Jornada finalizada con éxito. Excelente trabajo hoy, equipo. Nos vemos mañana.") ?: ""
        val savedGoodbyeHour = prefs.getInt("GOODBYE_HOUR", 19)
        val savedGoodbyeMin = prefs.getInt("GOODBYE_MIN", 0)
        val goodbyeEnabled = prefs.getBoolean("GOODBYE_ENABLED", false)

        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 20)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#1A1A2E"), Color.parseColor("#16213E")))
        }

        // --- SECCION SALUDO ---
        dialogLayout.addView(TextView(this).apply { text = "🌅 SALUDO APERTURA"; textSize = 15f; setTextColor(Color.parseColor("#FFD700")); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 10) })
        val switchGreeting = android.widget.Switch(this).apply { isChecked = greetingEnabled }
        dialogLayout.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(TextView(this@MainActivity).apply { text = "Activar saludo:"; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }); addView(switchGreeting); setPadding(0, 0, 0, 10) })
        
        val timePickerG = TimePicker(this).apply { setIs24HourView(false); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { hour = savedHour; minute = savedMin } else { @Suppress("DEPRECATION") currentHour = savedHour; @Suppress("DEPRECATION") currentMinute = savedMin } }
        dialogLayout.addView(timePickerG)
        
        val editMsgG = EditText(this).apply { setText(savedMsg); textSize = 13f; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.parseColor("#0D1117")); cornerRadius = 12f; setStroke(1, Color.parseColor("#333333")) }; setPadding(20, 16, 20, 16); minLines = 2; maxLines = 4; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 10) } }
        dialogLayout.addView(editMsgG)

        // Botón probar audio SALUDO
        val btnTestGreeting = Button(this).apply {
            text = "🔊 PROBAR AUDIO SALUDO"
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#2E7D32")); cornerRadius = 12f }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 20) }
            setOnClickListener {
                val msg = editMsgG.text.toString().trim()
                if (msg.isNotEmpty()) {
                    val i = Intent(this@MainActivity, DataSyncService::class.java).apply {
                        action = DataSyncService.MASTER_ACTION
                        putExtra(DataSyncService.MASTER_KEY, DataSyncService.KEY_SAY)
                        putExtra(DataSyncService.EXTRA_MESSAGE, msg)
                    }
                    startService(i)
                    Toast.makeText(this@MainActivity, "🔊 Reproduciendo saludo...", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialogLayout.addView(btnTestGreeting)

        // --- SECCION DESPEDIDA ---
        dialogLayout.addView(TextView(this).apply { text = "🌇 DESPEDIDA JORNADA"; textSize = 15f; setTextColor(Color.parseColor("#FFD700")); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 10) })
        val switchGoodbye = android.widget.Switch(this).apply { isChecked = goodbyeEnabled }
        dialogLayout.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(TextView(this@MainActivity).apply { text = "Activar despedida:"; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }); addView(switchGoodbye); setPadding(0, 0, 0, 10) })
        
        val timePickerGB = TimePicker(this).apply { setIs24HourView(false); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { hour = savedGoodbyeHour; minute = savedGoodbyeMin } else { @Suppress("DEPRECATION") currentHour = savedGoodbyeHour; @Suppress("DEPRECATION") currentMinute = savedGoodbyeMin } }
        dialogLayout.addView(timePickerGB)
        
        val editMsgGB = EditText(this).apply { setText(savedGoodbyeMsg); textSize = 13f; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.parseColor("#0D1117")); cornerRadius = 12f; setStroke(1, Color.parseColor("#333333")) }; setPadding(20, 16, 20, 16); minLines = 2; maxLines = 4; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 10) } }
        dialogLayout.addView(editMsgGB)

        // Botón probar audio DESPEDIDA
        val btnTestGoodbye = Button(this).apply {
            text = "🔊 PROBAR AUDIO DESPEDIDA"
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#1565C0")); cornerRadius = 12f }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 20) }
            setOnClickListener {
                val msg = editMsgGB.text.toString().trim()
                if (msg.isNotEmpty()) {
                    val i = Intent(this@MainActivity, DataSyncService::class.java).apply {
                        action = DataSyncService.MASTER_ACTION
                        putExtra(DataSyncService.MASTER_KEY, DataSyncService.KEY_SAY)
                        putExtra(DataSyncService.EXTRA_MESSAGE, msg)
                    }
                    startService(i)
                    Toast.makeText(this@MainActivity, "🔊 Reproduciendo despedida...", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialogLayout.addView(btnTestGoodbye)

        // --- BOTONES ---
        val btnSave = Button(this).apply { text = "💾 GUARDAR TODO"; setTextColor(Color.BLACK); setTypeface(null, Typeface.BOLD); background = GradientDrawable().apply { setColor(Color.parseColor("#FFD700")); cornerRadius = 12f } }
        dialogLayout.addView(btnSave)

        val scrollView = ScrollView(this).apply { addView(dialogLayout); layoutParams = ViewGroup.LayoutParams(-1, -2) }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).setView(scrollView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnSave.setOnClickListener {
            val gMsg = editMsgG.text.toString().trim()
            val gHour = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePickerG.hour else @Suppress("DEPRECATION") timePickerG.currentHour
            val gMin = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePickerG.minute else @Suppress("DEPRECATION") timePickerG.currentMinute
            val gEnabled = switchGreeting.isChecked

            val gbMsg = editMsgGB.text.toString().trim()
            val gbHour = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePickerGB.hour else @Suppress("DEPRECATION") timePickerGB.currentHour
            val gbMin = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePickerGB.minute else @Suppress("DEPRECATION") timePickerGB.currentMinute
            val gbEnabled = switchGoodbye.isChecked

            prefs.edit()
                .putString("GREETING_MSG", gMsg).putInt("GREETING_HOUR", gHour).putInt("GREETING_MIN", gMin).putBoolean("GREETING_ENABLED", gEnabled)
                .putString("GOODBYE_MSG", gbMsg).putInt("GOODBYE_HOUR", gbHour).putInt("GOODBYE_MIN", gbMin).putBoolean("GOODBYE_ENABLED", gbEnabled)
                .apply()

            if (gEnabled) scheduleAlarm(gHour, gMin, true) else cancelAlarm(true)
            if (gbEnabled) scheduleAlarm(gbHour, gbMin, false) else cancelAlarm(false)

            updateTtsIndicator()
            Toast.makeText(this, "✅ Configuración TTS guardada.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun scheduleAlarm(hour: Int, minute: Int, isGreeting: Boolean) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val action = if (isGreeting) "com.inversioneswing.starkomega.ACTION_GREETING" else "com.inversioneswing.starkomega.ACTION_GOODBYE"
        val intent = Intent(this, GreetingReceiver::class.java).apply { this.action = action }
        val pendingIntent = android.app.PendingIntent.getBroadcast(this, if (isGreeting) 9999 else 9998, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            } else {
                alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            // Fallback si no hay permiso SCHEDULE_EXACT_ALARM en Android 12+
            alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }
    }

    private fun cancelAlarm(isGreeting: Boolean) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val action = if (isGreeting) "com.inversioneswing.starkomega.ACTION_GREETING" else "com.inversioneswing.starkomega.ACTION_GOODBYE"
        val intent = Intent(this, GreetingReceiver::class.java).apply { this.action = action }
        val pendingIntent = android.app.PendingIntent.getBroadcast(this, if (isGreeting) 9999 else 9998, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
    }
}

// --------------------------------------------------------------------------------
// ADAPTER HISTORIAL (RECYCLERVIEW) CON SOPORTE PARA FECHAS Y 7 DÍAS
// --------------------------------------------------------------------------------
data class PaymentItem(
    val bank: String, 
    val name: String, 
    val amount: Double, 
    val time: String, 
    val date: String = "", 
    val direction: String = "INGRESO"
)

class PaymentAdapter(private val items: MutableList<PaymentItem>) : RecyclerView.Adapter<PaymentAdapter.ViewHolder>() {
    class ViewHolder(val view: LinearLayout, val tvBank: TextView, val tvName: TextView, val tvAmt: TextView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 4) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#15FFFFFF"))
                cornerRadius = 10f
            }
        }
        val tvBank = TextView(ctx).apply { textSize = 11f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setPadding(0, 0, 10, 0) }
        val tvName = TextView(ctx).apply { 
            textSize = 12f 
            setTextColor(Color.WHITE) 
            setTypeface(Typeface.DEFAULT_BOLD) 
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            maxLines = 1
        }
        val tvAmt = TextView(ctx).apply { textSize = 13f; setTypeface(Typeface.DEFAULT_BOLD) }

        container.addView(tvBank)
        container.addView(tvName)
        container.addView(tvAmt)

        return ViewHolder(container, tvBank, tvName, tvAmt)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isEgreso = item.direction.uppercase() == "EGRESO"

        val bankTag = when (item.bank.uppercase()) {
            "YAPE" -> "🟣 YAPE"
            "PLIN" -> "🔵 PLIN"
            "BCP", "BCP DIRECTO" -> "🟠 BCP"
            "BBVA" -> "🟢 BBVA"
            "INTERBANK" -> "🔵 INTERBANK"
            else -> "🟢 ${item.bank}"
        }

        holder.tvBank.text = bankTag
        val prefixName = if (isEgreso) "🔴 Para:" else "👤 De:"
        holder.tvName.text = "$prefixName ${item.name.uppercase()}"
        
        val prefix = if (isEgreso) "-S/ " else "S/ "
        val formattedAmt = String.format(Locale.US, "%s%.2f", prefix, item.amount)
        holder.tvAmt.text = "$formattedAmt • ${item.time}"

        if (isEgreso) {
            holder.tvBank.setTextColor(Color.parseColor("#FF4444"))
            holder.tvAmt.setTextColor(Color.parseColor("#FF4444"))
        } else {
            val colorHex = when (item.bank.uppercase()) {
                "YAPE" -> "#FF007F"
                "PLIN" -> "#00E5FF"
                "BCP", "BCP DIRECTO" -> "#FFC107"
                "BBVA" -> "#00E676"
                "INTERBANK" -> "#00B0FF"
                else -> "#2ECC71"
            }
            holder.tvBank.setTextColor(Color.parseColor(colorHex))
            holder.tvAmt.setTextColor(Color.parseColor(colorHex))
        }
    }

    override fun getItemCount() = items.size

    fun addPayment(item: PaymentItem) {
        items.add(0, item)
        notifyItemInserted(0)
    }
}
