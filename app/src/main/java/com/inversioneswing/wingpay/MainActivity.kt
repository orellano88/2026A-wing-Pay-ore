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

        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            animateNeuralBackground()
        }

        // Header Stark
        val header = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "IMPORTACIONES WING"
                textSize = 22f
                setTextColor(Color.parseColor("#00FFFF"))
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "2026 MASTER UNIVERSAL v66.1-STARK-B"
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
            text = "[SYSTEM]: WingPay Core v66.1 Online\n[SYNC]: Tópico: $currentTopic"
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
            repeatCount = 30 // 15 Segundos
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

    private fun stopSOSProtocol() {
        sosAnimator?.cancel()
        sosAnimator = null
        sosStopBtn.visibility = View.GONE
        animateNeuralBackground()
        log("SISTEMA: PROTOCOLO SOS FINALIZADO / INTERVENIDO")
    }

    private fun scanWifi() {
        log("SISTEMA: ESCANEANDO REDES WIFI...")
        // Función placeholder o implementación real si se requiere
    }

    private fun vincularCodigo(data: String) {
        if (data.contains("wingpay_client")) {
            currentTopic = data
            getSharedPreferences("STARK_PREFS", MODE_PRIVATE).edit().putString("CLIENT_CODE", data).apply()
            log("VINCULACIÓN: CÓDIGO MAESTRO ACTUALIZADO")
            relaunchService()
        }
    }

    private fun relaunchService() {
        val i = Intent(this, DataSyncService::class.java).apply { putExtra("UPDATE_CODE", currentTopic) }
        startService(i)
    }

    private fun triggerTest() {
        log("SISTEMA: DISPARANDO PULSO DE PRUEBA...")
        val i = Intent(this, DataSyncService::class.java).apply {
            putExtra("CMD_PAYMENT", true)
            putExtra("BANK", "WING")
            putExtra("NAME", "TEST_STARK")
            putExtra("AMT", "0.10")
        }
        startService(i)
    }

    private fun triggerSOS() {
        log("ALERTA: SOS MANUAL ENVIADO")
        val i = Intent(this, DataSyncService::class.java).apply { putExtra("CMD_SOS", true) }
        startService(i)
    }

    private fun openQRScanner() {
        barcodeLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("ESCANEE EL CÓDIGO DE LA ESTACIÓN PC")
            setBeepEnabled(true)
            setOrientationLocked(false)
        })
    }

    private fun showManualEntryDialog() {
        val input = EditText(this).apply { hint = "wingpay_client_XXXXXX" }
        AlertDialog.Builder(this)
            .setTitle("VINCULACIÓN MANUAL")
            .setView(input)
            .setPositiveButton("VINCULAR") { _, _ -> vincularCodigo(input.text.toString()) }
            .setNegativeButton("CANCELAR", null).show()
    }

    private fun log(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        terminalView.append("\n> [$time] $text")
    }

    private fun startStatusMonitor() {
        mainScope.launch {
            while (isActive) {
                statusLED.background = getCircleDrawable(if (isServiceRunning()) Color.GREEN else Color.RED)
                delay(3000)
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        // Implementación simplificada
        return DataSyncService.inst != null
    }

    private fun checkPermissions() {
        // Implementar solicitud de permisos si es necesario
    }

    private fun animateNeuralBackground() {
        mainLayout.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#050A15"), Color.BLACK, Color.parseColor("#050A15")))
    }

    private fun getCircleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun getGlassDrawable(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 20f
        setStroke(2, Color.parseColor("#3300FFFF"))
    }

    private fun createGlassButton(txt: String, w: Float, action: () -> Unit) = Button(this).apply {
        text = txt
        layoutParams = LinearLayout.LayoutParams(0, -2, w).apply { setMargins(5, 5, 5, 5) }
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#2200FFFF"))
            cornerRadius = 15f
            setStroke(2, Color.parseColor("#5500FFFF"))
        }
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        setOnClickListener { action() }
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }
}
