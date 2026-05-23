package com.inversioneswing.starkomega

import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import android.view.WindowManager
import android.app.KeyguardManager
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
    private var lastClientLabel: TextView? = null
    private var lastAmountLabel: TextView? = null
    private var statusLED: View? = null
    private var syncLED: View? = null
    private var centralLogo: ImageView? = null
    private var sosStopBtn: Button? = null
    private var sosAnimator: ValueAnimator? = null
    private var currentTopic = "wingpay_client_A2ZQV4"
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val hudReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val name = it.getStringExtra("NAME") ?: ""
                val amt = it.getStringExtra("AMT") ?: ""
                val bank = it.getStringExtra("BANK") ?: "PAGO"
                lastClientLabel?.post { 
                    lastClientLabel?.text = "$bank DE... $name"
                    lastAmountLabel?.text = "S/ $amt"
                }
                log("HUD: $bank ACTUALIZADO")
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
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        try {
            val prefs = getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
            currentTopic = prefs.getString("CLIENT_CODE", currentTopic) ?: currentTopic

            mainLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(35, 40, 35, 40)
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, 
                    intArrayOf(Color.parseColor("#0a1a2f"), Color.parseColor("#050A15"), Color.BLACK))
            }

            setupHeader(); setupHUD(); setupTerminal(); setupCentralLogo(); setupSOSButton(); setupActionButtons()
            setContentView(mainLayout)
            
            startStatusMonitor()
            handleSOSIntent(intent)
            
            registerReceiver(hudReceiver, IntentFilter("STARK_HUD_UPDATE"), Context.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            val errorView = TextView(this).apply { text = "STARK_ERR: ${e.message}" }
            setContentView(errorView)
        }
    }

    private fun setupHeader() {
        val header = RelativeLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,20) } }
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = "IMPORTACIONES WING"; textSize = 20f; setTextColor(Color.parseColor("#00FFFF")); setTypeface(null, Typeface.BOLD) })
            addView(TextView(this@MainActivity).apply { text = "2026 MASTER STARK v69.0-EXCALIBUR"; textSize = 10f; setTextColor(Color.WHITE); alpha = 0.7f })
        }
        header.addView(titleLayout)
        val ledContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RelativeLayout.LayoutParams(-2, -2).apply { addRule(RelativeLayout.ALIGN_PARENT_RIGHT); addRule(RelativeLayout.CENTER_VERTICAL) }
            statusLED = View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(42, 42).apply { setMargins(10, 0, 10, 0) }; background = getCircleDrawable(Color.RED) }
            syncLED = View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(42, 42).apply { setMargins(10, 0, 10, 0) }; background = getCircleDrawable(Color.GRAY) }
            addView(statusLED); addView(syncLED)
        }
        header.addView(ledContainer); mainLayout.addView(header)
    }

    private fun setupHUD() {
        val hudContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 15) }; background = getGlassDrawable(Color.parseColor("#3300FFFF")); setPadding(30, 25, 30, 25) }
        hudContainer.addView(TextView(this).apply { text = "VIGILANCIA DE FLUJO EXCALIBUR"; textSize = 8f; setTextColor(Color.GRAY); gravity = Gravity.CENTER })
        lastClientLabel = TextView(this).apply { text = "ESPERANDO TRANSMISIÓN..."; textSize = 15f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        lastAmountLabel = TextView(this).apply { text = "S/ 0.00"; textSize = 30f; setTextColor(Color.parseColor("#00FFFF")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        hudContainer.addView(lastClientLabel); hudContainer.addView(lastAmountLabel); mainLayout.addView(hudContainer)
    }

    private fun setupTerminal() {
        val termContainer = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 350).apply { setMargins(0, 10, 0, 10) }; background = getGlassDrawable(Color.parseColor("#CC000000")); setPadding(25, 20, 25, 20) }
        terminalView = TextView(this).apply { text = "[SISTEMA]: WingPay EXCALIBUR v69.0 Online"; textSize = 10f; setTextColor(Color.parseColor("#00FF41")); setTypeface(Typeface.MONOSPACE) }
        termContainer.addView(ScrollView(this).apply { addView(terminalView) }); mainLayout.addView(termContainer)
    }

    private fun setupCentralLogo() {
        val visualContainer = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        centralLogo = ImageView(this).apply { layoutParams = FrameLayout.LayoutParams(350, 350, Gravity.CENTER); setImageResource(R.drawable.stark_logo); alpha = 0.6f }
        centralLogo?.let { ObjectAnimator.ofFloat(it, "alpha", 0.4f, 0.8f).apply { duration = 2000; repeatCount = -1; repeatMode = ValueAnimator.REVERSE; start() }; visualContainer.addView(it) }
        mainLayout.addView(visualContainer)
    }

    private fun setupSOSButton() {
        sosStopBtn = Button(this).apply { text = "🛑 DETENER ALERTA MÓVIL"; layoutParams = LinearLayout.LayoutParams(-1, 130).apply { setMargins(0, 15, 0, 15) }; background = GradientDrawable().apply { setColor(Color.parseColor("#BBFF0000")); cornerRadius = 18f; setStroke(2, Color.WHITE) }; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, Typeface.BOLD); visibility = View.GONE; setOnClickListener { stopSOSProtocol() } }
        sosStopBtn?.let { mainLayout.addView(it) }
    }

    private fun setupActionButtons() {
        val btnLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        row1.addView(createActionButton("🛑 PC", 1f) { stopSOSProtocol() })
        row1.addView(createActionButton("🚨 SOS", 1f) { triggerCommand(DataSyncService.KEY_SOS) })
        row1.addView(createActionButton("⚠️ POLICÍA", 1f) { triggerCommand(DataSyncService.KEY_POLICE) })
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        row2.addView(createActionButton("⚙", 1f) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        row2.addView(createActionButton("📷 QR", 1f) { openQRScanner() })
        row2.addView(createActionButton("🧪 TEST", 1f) { triggerCommand(DataSyncService.KEY_TEST) })
        btnLayout.addView(row1); btnLayout.addView(row2); mainLayout.addView(btnLayout)
    }

    private fun triggerVisualSOS() {
        sosStopBtn?.visibility = View.VISIBLE
        sosAnimator?.cancel()
        sosAnimator = ValueAnimator.ofObject(ArgbEvaluator(), Color.RED, Color.TRANSPARENT).apply { duration = 500; repeatCount = 30; repeatMode = ValueAnimator.REVERSE; addUpdateListener { animator -> val color = animator.animatedValue as Int; mainLayout.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, Color.BLACK)) }; addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { stopSOSProtocol() } }) }
        sosAnimator?.start()
    }

    private fun stopSOSProtocol() {
        sosAnimator?.cancel(); sosAnimator = null; sosStopBtn?.visibility = View.GONE
        mainLayout.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0a1a2f"), Color.parseColor("#050A15"), Color.BLACK))
        log("SISTEMA: SILENCIADO"); DataSyncService.inst?.stopSiren()
    }

    private fun triggerCommand(key: Int) {
        log("STARK_CMD_ID: $key")
        val i = Intent(this, DataSyncService::class.java).apply { 
            action = DataSyncService.MASTER_ACTION
            putExtra(DataSyncService.MASTER_KEY, key)
        }
        startService(i)
    }

    private fun vincularCodigo(data: String) { if (data.contains("wingpay_client")) { currentTopic = data; getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE).edit().putString("CLIENT_CODE", data).apply(); log("VINCULACIÓN: OK"); relaunchService() } }
    private fun relaunchService() { try { startService(Intent(this, DataSyncService::class.java).apply { putExtra("UPDATE_CODE", currentTopic) }) } catch (e: Exception) { log("ERR: Serv. inactivo") } }
    private fun openQRScanner() { barcodeLauncher.launch(ScanOptions().apply { setDesiredBarcodeFormats(ScanOptions.QR_CODE); setPrompt("ESCANEE CÓDIGO PC"); setBeepEnabled(true); setOrientationLocked(false) }) }
    private fun log(text: String) { 
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        terminalView?.post { terminalView?.append("\n> [$time] $text") }
    }
    private fun startStatusMonitor() { mainScope.launch { while (isActive) { statusLED?.background = getCircleDrawable(if (DataSyncService.isServiceRunning()) Color.GREEN else Color.RED); delay(3000) } } }
    private fun handleSOSIntent(intent: Intent?) { if (intent?.getBooleanExtra("VISUAL_SOS", false) == true) triggerVisualSOS() }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleSOSIntent(intent) }
    private fun getCircleDrawable(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun getGlassDrawable(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 18f; setStroke(2, Color.parseColor("#4400FFFF")) }
    private fun createActionButton(txt: String, w: Float, action: () -> Unit) = Button(this).apply { text = txt; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { setMargins(5, 5, 5, 5) }; background = GradientDrawable().apply { setColor(Color.parseColor("#2200FFFF")); cornerRadius = 12f; setStroke(1, Color.parseColor("#6600FFFF")) }; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); textSize = 11f; setOnClickListener { action() } }
    override fun onDestroy() { mainScope.cancel(); try { unregisterReceiver(hudReceiver) } catch (e: Exception) {}; super.onDestroy() }
}
