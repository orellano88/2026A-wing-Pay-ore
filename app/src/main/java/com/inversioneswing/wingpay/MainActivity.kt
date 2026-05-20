package com.inversioneswing.wingpay

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ToneGenerator
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var mainLayout: LinearLayout
    private lateinit var terminalView: TextView
    private lateinit var statusLED: View
    private lateinit var syncLED: View
    private lateinit var centralLogo: ImageView
    private lateinit var sosStopBtn: Button
    private var sosAnimator: ValueAnimator? = null
    private var toneGenerator: ToneGenerator? = null
    private var currentTopic = "wingpay_client_A2ZQV4"
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val barcodeLauncher: ActivityResultLauncher<ScanOptions> = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { vincularCodigo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("STARK_PREFS", MODE_PRIVATE)
        currentTopic = prefs.getString("CLIENT_CODE", currentTopic)!!

        try { toneGenerator = ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100) } catch (e: Exception) {}

        // --- UI PROGRAMMABLE UI (STARK OS STYLE) ---
        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding(40)
        }
        animateNeuralBackground()

        // Header
        val header = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 260)
        }

        val logoIcon = ImageView(this).apply {
            id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(160, 160).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setImageResource(R.drawable.stark_logo)
        }
        header.addView(logoIcon)

        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -2).apply {
                addRule(RelativeLayout.RIGHT_OF, logoIcon.id)
                leftMargin = 30
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            addView(TextView(this@MainActivity).apply {
                text = "IMPORTACIONES WING"
                textSize = 22f
                setTextColor(Color.parseColor("#00FFFF"))
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "2026 MASTER UNIVERSAL v66.0-STARK-B"
                textSize = 10f
                setTextColor(Color.WHITE)
                alpha = 0.6f
            })
        }
        header.addView(titleLayout)

        val ledContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RelativeLayout.LayoutParams(-2, -2).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            statusLED = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(35, 35).apply { setMargins(10, 0, 10, 0) }
                background = getCircleDrawable(Color.RED)
            }
            syncLED = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(35, 35).apply { setMargins(10, 0, 10, 0) }
                background = getCircleDrawable(Color.GRAY)
            }
            addView(statusLED)
            addView(syncLED)
        }
        header.addView(ledContainer)
        mainLayout.addView(header)

        // Terminal Log
        val termContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 450).apply { setMargins(0, 30, 0, 30) }
            background = getGlassDrawable(Color.parseColor("#77000000"))
            setPadding(25, 25, 25, 25)
        }
        terminalView = TextView(this).apply {
            text = "[SYSTEM]: WingPay Core v66.0 Online\n[SYNC]: Tópico: $currentTopic"
            textSize = 11f
            setTextColor(Color.parseColor("#00FF00"))
            setTypeface(Typeface.MONOSPACE)
        }
        val scroll = ScrollView(this).apply { addView(terminalView) }
        termContainer.addView(scroll)
        mainLayout.addView(termContainer)

        // Central Visual
        val visualContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        centralLogo = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(400, 400).apply { gravity = Gravity.CENTER }
            setImageResource(R.drawable.stark_logo)
            alpha = 0.4f
        }
        ObjectAnimator.ofFloat(centralLogo, "alpha", 0.2f, 0.7f).apply {
            duration = 2000; repeatCount = -1; repeatMode = ValueAnimator.REVERSE; start()
        }
        visualContainer.addView(centralLogo)
        mainLayout.addView(visualContainer)

        // SOS STOP BUTTON (Hidden by default)
        sosStopBtn = createGlassButton("🛑 DETENER ALERTA", 5f) {
            stopSOSProtocol()
        }.apply { 
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#88FF0000"))
        }
        mainLayout.addView(sosStopBtn)

        // Buttons
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
        }
        btnLayout.addView(createGlassButton("🛑 PC", 1f) { stopSOSProtocol() })
        btnLayout.addView(createGlassButton("⚙", 1f) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        btnLayout.addView(createGlassButton("📷 QR", 1f) { openQRScanner() })
        btnLayout.addView(createGlassButton("🧪 TEST", 1f) { triggerTest() })
        btnLayout.addView(createGlassButton("🚨 SOS", 1f) { triggerSOS() })
        mainLayout.addView(btnLayout)

        val manualLink = TextView(this).apply {
            text = "VINCULACIÓN MANUAL"
            textSize = 10f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40)
            setOnClickListener { showManualEntryDialog() }
        }
        mainLayout.addView(manualLink)

        setContentView(mainLayout)
        checkPermissions()
        startStatusMonitor()
        handleSOSIntent(intent)
    }

    private fun handleSOSIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("VISUAL_SOS", false) == true) {
            triggerVisualSOS()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleSOSIntent(intent)
    }

    private fun triggerVisualSOS() {
        log("ALERTA: SOS REMOTO ACTIVADO - PROTOCOLO DE EMERGENCIA")
        sosStopBtn.visibility = View.VISIBLE

        sosAnimator?.cancel()
        sosAnimator = ValueAnimator.ofObject(ArgbEvaluator(), Color.RED, Color.TRANSPARENT).apply {
            duration = 500
            repeatCount = 30 // 15 Segundos (30 ciclos de 0.5s)
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                mainLayout.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, Color.BLACK, color))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    stopSOSProtocol()
                }
            })
        }
        sosAnimator?.start()

        // Voz Master en el móvil
        val msg = "¡ATENCIÓN! NUESTRO LOCAL ESTÁ EN EMERGENCIA ALERTA. NUESTRO LOCAL NECESITA SER REVISADO POR CÁMARAS."
        try {
            val intent = Intent(this, DataSyncService::class.java).apply {
                putExtra("CMD_PAYMENT", true)
                putExtra("NAME", msg)
                putExtra("BANK", "ALERTA")
            }
            startService(intent)
        } catch (e: Exception) {}
    }

    private fun stopRemotePCAlarms() {
        log("SISTEMA: ENVIANDO ORDEN DE PARADA A LA PC...")
        mainScope.launch(Dispatchers.IO) {
            try {
                val payload = "{\"tipo\": \"STOP_SOS\", \"msg\": \"DETENER ALERTA DESDE MOVIL\"}".toByteArray()
                val socket = java.net.DatagramSocket()
                val address = java.net.InetAddress.getByName("192.168.1.100") 
                val packet = java.net.DatagramPacket(payload, payload.size, address, 5005)
                socket.send(packet)
                socket.close()
                withContext(Dispatchers.Main) { log("[OK]: SEÑAL DE PARADA ENVIADA") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { log("[ERROR]: PC NO ALCANZABLE") }
            }
        }
    }

    private fun scanWifi() {
        sosAnimator?.cancel()
        sosAnimator = null
        sosStopBtn.visibility = View.GONE
        animateNeuralBackground()
        log("SISTEMA: PROTOCOLO SOS FINALIZADO / INTERVENIDO")
    }

    private fun scanWifi() {
        log("SISTEMA: ESCANEANDO REDES WIFI...")
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (!wifiManager.isWifiEnabled) {
                log("ERROR: WIFI DESACTIVADO")
                return
            }
            // Note: On Android 10+, scan results might be restricted or require location permission
            val results = wifiManager.scanResults
            log("INFO: ENCONTRADAS ${results.size} REDES")
            results.take(5).forEach { res ->
                log("-> ${res.SSID} (${res.level}dBm)")
            }
        } catch (e: Exception) {
            log("ERROR: FALLO DE ESCANEO: ${e.message}")
        }
    }

    private fun openQRScanner() {
        barcodeLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Escanea tu Estación PC")
            setBeepEnabled(true)
            setOrientationLocked(false)
        })
    }

    private fun triggerTest() {
        log("CMD: LANZANDO PRUEBA DE PAGO...")
        val intent = Intent(this, DataSyncService::class.java).apply {
            putExtra("CMD_PAYMENT", true)
            putExtra("BANK", "STARK_OS")
            putExtra("NAME", "TEST_USER_65")
            putExtra("AMT", "999.00")
        }
        startService(intent)
    }

    private fun triggerSOS() {
        log("SOS: ALERTA MAESTRA ENVIADA")
        val intent = Intent(this, DataSyncService::class.java).apply {
            putExtra("CMD_SOS", true)
        }
        startService(intent)
    }

    private fun vincularCodigo(code: String) {
        log("SISTEMA: VINCULANDO $code")
        currentTopic = code
        getSharedPreferences("STARK_PREFS", MODE_PRIVATE).edit().putString("CLIENT_CODE", code).apply()
        val intent = Intent(this, DataSyncService::class.java).apply {
            putExtra("UPDATE_CODE", code)
        }
        startService(intent)
        triggerTest()
    }

    private fun showManualEntryDialog() {
        val input = EditText(this).apply { hint = "wingpay_client_..." }
        AlertDialog.Builder(this)
            .setTitle("VINCULACIÓN MANUAL")
            .setView(input)
            .setPositiveButton("VINCULAR") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) vincularCodigo(code)
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        terminalView.append("\n[$time] $msg")
        (terminalView.parent as ScrollView).post { (terminalView.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    private fun startStatusMonitor() {
        mainScope.launch {
            while (isActive) {
                val isEnabled = isNotificationServiceEnabled()
                statusLED.background = getCircleDrawable(if (isEnabled) Color.GREEN else Color.RED)
                statusLED.alpha = 0.4f
                delay(200)
                statusLED.alpha = 1.0f
                
                syncLED.background = getCircleDrawable(if (DataSyncService.isServiceRunning()) Color.CYAN else Color.GRAY)
                
                delay(4800)
            }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun checkPermissions() {
        if (!isNotificationServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("ENLACE REQUERIDO")
                .setMessage("El sistema requiere acceso a las notificaciones para funcionar.")
                .setPositiveButton("CONECTAR") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .show()
        }
    }

    private fun animateNeuralBackground() {
        val color1 = Color.parseColor("#001524")
        val color2 = Color.parseColor("#003249")
        val color3 = Color.parseColor("#001524")
        val gradient = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color1, color2, color3))
        mainLayout.background = gradient

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 8000; repeatCount = -1; repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val evaluator = ArgbEvaluator()
                val midColor = evaluator.evaluate(fraction, color2, Color.parseColor("#004e64")) as Int
                gradient.colors = intArrayOf(color1, midColor, color3)
            }
            start()
        }
    }

    private fun createGlassButton(txt: String, w: Float, action: () -> Unit): Button {
        return Button(this).apply {
            text = txt; textColor(Color.WHITE); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, 160, w).apply { setMargins(6, 10, 6, 10) }
            background = getGlassDrawable(Color.parseColor("#33FFFFFF"))
            setOnClickListener {
                try { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100) } catch (e: Exception) {}
                action()
            }
        }
    }

    private fun getGlassDrawable(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = 35f; setStroke(3, Color.parseColor("#44FFFFFF"))
    }

    private fun getCircleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun View.padding(v: Int) = setPadding(v, v, v, v)
    private fun Button.textColor(c: Int) = setTextColor(c)

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }
}
