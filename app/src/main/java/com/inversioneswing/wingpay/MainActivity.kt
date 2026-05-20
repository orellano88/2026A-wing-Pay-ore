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
import android.view.ViewGroup
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
    private var terminalView: TextView? = null
    private var statusLED: View? = null
    private var syncLED: View? = null
    private var centralLogo: ImageView? = null
    private var sosStopBtn: Button? = null
    private var sosAnimator: ValueAnimator? = null
    private var currentTopic = "wingpay_client_A2ZQV4"
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val barcodeLauncher: ActivityResultLauncher<ScanOptions> = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { vincularCodigo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
            currentTopic = prefs.getString("CLIENT_CODE", currentTopic) ?: currentTopic

            // Contenedor Principal con fondo STARK NEURAL (Azul Profundo)
            mainLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(35, 40, 35, 40)
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, 
                    intArrayOf(Color.parseColor("#0a1a2f"), Color.parseColor("#050A15"), Color.BLACK))
            }

            setupHeader()
            setupTerminal()
            setupCentralLogo()
            setupSOSButton()
            setupActionButtons()
            
            setContentView(mainLayout)
            
            startStatusMonitor()
            handleSOSIntent(intent)
        } catch (e: Exception) {
            val errorView = TextView(this).apply { text = "CRITICAL_ERR: ${e.message}" }
            setContentView(errorView)
        }
    }

    private fun setupHeader() {
        val header = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,25) }
        }
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "IMPORTACIONES WING"
                textSize = 20f
                setTextColor(Color.parseColor("#00FFFF"))
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "2026 MASTER STARK v66.1-DEF"
                textSize = 10f
                setTextColor(Color.WHITE)
                alpha = 0.7f
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
                layoutParams = LinearLayout.LayoutParams(42, 42).apply { setMargins(10, 0, 10, 0) }
                background = getCircleDrawable(Color.RED)
            }
            syncLED = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(42, 42).apply { setMargins(10, 0, 10, 0) }
                background = getCircleDrawable(Color.GRAY)
            }
            addView(statusLED)
            addView(syncLED)
        }
        header.addView(ledContainer)
        mainLayout.addView(header)
    }

    private fun setupTerminal() {
        val termContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 380).apply { setMargins(0, 15, 0, 15) }
            background = getGlassDrawable(Color.parseColor("#CC000000"))
            setPadding(25, 20, 25, 20)
        }
        terminalView = TextView(this).apply {
            text = "[SISTEMA]: WingPay Core v66.1 Online\n[INFO]: Tópico: $currentTopic"
            textSize = 10.5f
            setTextColor(Color.parseColor("#00FF41"))
            setTypeface(Typeface.MONOSPACE)
        }
        termContainer.addView(ScrollView(this).apply { addView(terminalView) })
        mainLayout.addView(termContainer)
    }

    private fun setupCentralLogo() {
        val visualContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        centralLogo = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(380, 380, Gravity.CENTER)
            setImageResource(R.drawable.stark_logo)
            alpha = 0.6f
        }
        centralLogo?.let {
            ObjectAnimator.ofFloat(it, "alpha", 0.35f, 0.85f).apply {
                duration = 2500; repeatCount = -1; repeatMode = ValueAnimator.REVERSE; start()
            }
            visualContainer.addView(it)
        }
        mainLayout.addView(visualContainer)
    }

    private fun setupSOSButton() {
        sosStopBtn = Button(this).apply {
            text = "🛑 DETENER ALERTA MÓVIL"
            layoutParams = LinearLayout.LayoutParams(-1, 130).apply { setMargins(0, 15, 0, 15) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#BBFF0000"))
                cornerRadius = 18f
                setStroke(2, Color.WHITE)
            }
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            visibility = View.GONE
            setOnClickListener { stopSOSProtocol() }
        }
        sosStopBtn?.let { mainLayout.addView(it) }
    }

    private fun setupActionButtons() {
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 5f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 10) }
        }
        // BOTONERA MAESTRA RECONSTRUIDA
        btnLayout.addView(createActionButton("🛑 PC", 1f) { stopSOSProtocol() })
        btnLayout.addView(createActionButton("⚙", 1f) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        btnLayout.addView(createActionButton("📷", 1f) { openQRScanner() })
        btnLayout.addView(createActionButton("🧪", 1f) { triggerTest() })
        btnLayout.addView(createActionButton("🚨 SOS", 1f) { triggerSOS() })
        mainLayout.addView(btnLayout)
    }

    private fun triggerVisualSOS() {
        log("ALERTA: EMERGENCIA REMOTA")
        sosStopBtn?.visibility = View.VISIBLE

        sosAnimator?.cancel()
        sosAnimator = ValueAnimator.ofObject(ArgbEvaluator(), Color.RED, Color.TRANSPARENT).apply {
            duration = 500; repeatCount = 30; repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                mainLayout.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, Color.BLACK))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    stopSOSProtocol()
                }
            })
        }
        sosAnimator?.start()

        val msg = "¡ATENCIÓN! NUESTRO LOCAL ESTÁ EN EMERGENCIA ALERTA. NUESTRO LOCAL NECESITA SER REVISADO POR CÁMARAS."
        try {
            val intent = Intent(this, DataSyncService::class.java).apply {
                putExtra("CMD_PAYMENT", true); putExtra("NAME", msg); putExtra("BANK", "ALERTA")
            }
            startService(intent)
        } catch (e: Exception) {}
    }

    private fun stopSOSProtocol() {
        sosAnimator?.cancel()
        sosAnimator = null
        sosStopBtn?.visibility = View.GONE
        mainLayout.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, 
            intArrayOf(Color.parseColor("#0a1a2f"), Color.parseColor("#050A15"), Color.BLACK))
        log("SISTEMA: ALERTA SILENCIADA")
    }

    private fun vincularCodigo(data: String) {
        if (data.contains("wingpay_client")) {
            currentTopic = data
            getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit().putString("CLIENT_CODE", data).apply()
            log("VINCULACIÓN: OK")
            relaunchService()
        }
    }

    private fun relaunchService() {
        try {
            val i = Intent(this, DataSyncService::class.java).apply { putExtra("UPDATE_CODE", currentTopic) }
            startService(i)
        } catch (e: Exception) { log("ERR: Serv. no activo") }
    }

    private fun triggerTest() {
        log("SISTEMA: DISPARANDO PULSO DE PRUEBA...")
        val i = Intent(this, DataSyncService::class.java).apply {
            putExtra("CMD_PAYMENT", true); putExtra("BANK", "WING"); putExtra("NAME", "TEST_STARK"); putExtra("AMT", "0.10")
        }
        startService(i)
    }

    private fun triggerSOS() {
        log("CMD: SOS_TO_PC")
        val i = Intent(this, DataSyncService::class.java).apply { putExtra("CMD_SOS", true) }
        startService(i)
    }

    private fun openQRScanner() {
        barcodeLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("ESCANEE CÓDIGO PC")
            setBeepEnabled(true); setOrientationLocked(false)
        })
    }

    private fun log(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        terminalView?.append("\n> [$time] $text")
    }

    private fun startStatusMonitor() {
        mainScope.launch {
            while (isActive) {
                val isRunning = DataSyncService.isServiceRunning()
                statusLED?.background = getCircleDrawable(if (isRunning) Color.GREEN else Color.RED)
                delay(3000)
            }
        }
    }

    private fun handleSOSIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("VISUAL_SOS", false) == true) triggerVisualSOS()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleSOSIntent(intent)
    }

    private fun getCircleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun getGlassDrawable(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = 18f; setStroke(2, Color.parseColor("#4400FFFF"))
    }

    private fun createActionButton(txt: String, w: Float, action: () -> Unit) = Button(this).apply {
        text = txt
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { setMargins(5, 5, 5, 5) }
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#2200FFFF")); cornerRadius = 12f; setStroke(1, Color.parseColor("#6600FFFF"))
        }
        setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); textSize = 12f
        setOnClickListener { action() }
    }

    override fun onDestroy() {
        mainScope.cancel(); super.onDestroy()
    }
}
