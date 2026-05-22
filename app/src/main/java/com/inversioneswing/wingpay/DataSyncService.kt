package com.inversioneswing.wingpay

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.*
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.regex.Pattern

class DataSyncService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private val CID = "WING_ORE_SYNC_CH_2026"
    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var lock: PowerManager.WakeLock
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private val queue = Collections.synchronizedList(mutableListOf<String>())
    
    private var topic: String = "wingpay_client_A2ZQV4"
    
    // ESCUDO ANTI-DUPLICADOS (Evita el Eco)
    private var lastProcessedSignature = ""
    private var lastProcessedTime = 0L

    companion object {
        internal var inst: DataSyncService? = null
        fun triggerSOS() { inst?.sendSOS() }
        fun isServiceRunning(): Boolean = inst != null
    }

    override fun onCreate() {
        super.onCreate()
        inst = this
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.android.system.service:DataSync")
        if (!lock.isHeld) lock.acquire()
        
        tts = TextToSpeech(this, this)
        val prefs = getSharedPreferences("STARK_PREFS", MODE_PRIVATE)
        topic = prefs.getString("CLIENT_CODE", topic)!!
        
        startPhantomListener()
    }

    private fun startPhantomListener() {
        job?.cancel()
        job = serviceScope.launch {
            val endpoint = "https://ntfy.sh/$topic/json"
            while (isActive) {
                try {
                    val conn = URL(endpoint).openConnection() as HttpURLConnection
                    conn.readTimeout = 0
                    conn.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (!isActive || line.isBlank()) return@forEach
                            processRemoteCommand(line)
                        }
                    }
                } catch (e: Exception) { delay(7000) }
            }
        }
    }

    private fun processRemoteCommand(line: String) {
        try {
            val data = JSONObject(line)
            if (data.has("message")) {
                val json = JSONObject(data.getString("message"))
                if (json.optString("sender") == "PC" && json.optString("type") == "SOS") triggerLocalAlarm()
            }
        } catch (e: Exception) {}
    }

    private fun triggerLocalAlarm() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("VISUAL_SOS", true)
        }
        startActivity(intent)

        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 200, 800), -1))
        } else v.vibrate(3000)
        
        speak("¡ATENCIÓN! NUESTRO LOCAL ESTÁ EN EMERGENCIA ALERTA. NUESTRO LOCAL NECESITA SER REVISADO POR CÁMARAS.")
    }

    fun speak(text: String) {
        if (text.isEmpty()) return
        if (ttsOk) {
            val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "ORE_" + System.currentTimeMillis())
        } else queue.add(text)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.getBooleanExtra("CMD_SOS", false)) sendSOS()
            if (it.getBooleanExtra("CMD_PAYMENT", false)) {
                val b = it.getStringExtra("BANK") ?: "TEST"
                val n = it.getStringExtra("NAME") ?: "STARK_NODE"
                val a = it.getStringExtra("AMT") ?: "0.10"
                dispatchPayment(b, n, a, "PAGO DE PRUEBA EXITOSO")
            }
        }
        setupForegroundNotification()
        return START_STICKY
    }

    private fun dispatchPayment(bank: String, name: String, amount: String, elegantMsg: String) {
        // ESCUDO DE DUPLICADOS: Bloquea si la misma firma llega en menos de 5 segundos
        val signature = "$bank|$name|$amount"
        val now = System.currentTimeMillis()
        if (signature == lastProcessedSignature && (now - lastProcessedTime) < 5000) return
        
        lastProcessedSignature = signature
        lastProcessedTime = now

        // 1. Audio Purificado
        speak("$bank de $name por $amount soles.")

        // 2. Sincronización PC
        serviceScope.launch { syncToMirror(bank, name, amount, elegantMsg) }

        // 3. Actualización HUD (Vía Broadcast Explícito)
        val hudIntent = Intent("STARK_HUD_UPDATE").apply {
            setPackage(packageName)
            putExtra("NAME", name); putExtra("AMT", amount); putExtra("BANK", bank)
        }
        sendBroadcast(hudIntent)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName.lowercase()
        val targets = listOf("yape", "plin", "bcp", "interbank", "bbva", "scotia", "banco", "pay")
        
        if (targets.any { pkg.contains(it) }) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            
            val candidates = listOf(title, text, bigText)
            val raw = candidates.maxByOrNull { it.length } ?: ""
            
            if (raw.isNotEmpty()) {
                val regex = Pattern.compile("(?i)(S/\\s*|S\\.\\s*|S\\s*|soles\\s*)([\\d,]+\\.\\d{2}|[\\d,]+)")
                val m = regex.matcher(raw)
                
                if (m.find()) {
                    val amount = m.group(2)?.replace(",", "") ?: "0.00"
                    var sender = raw.replace(m.group(0)!!, "", true)
                    
                    // PURIFICACIÓN TITÁN 3.0
                    val garbage = "(?i)(yapeaste|recibiste|transferencia|de|pago|enviado|recibido|te envió|soles|notificación|operación|código|nro|id|transacción|dni|banco|ahorros|corriente|has|un|por|a|comisión|ventas|exitoso|exitosa|cod|op|ref|vta|\\||\\.|\\,|:|\\!|\\?|\\#|\\*)".toRegex()
                    sender = sender.replace(garbage, " ").replace(Regex("\\d+"), " ").replace(Regex("[^a-zA-ZñÑáéíóúÁÉÍÓÚ\\s]"), "").replace(Regex("\\s+"), " ").trim()
                    sender = sender.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                    
                    if (sender.length < 3) sender = "Cliente Particular"
                    val bank = identifyBank(pkg, raw)
                    val elegant = "CONFIRMACIÓN DE PAGO: DE... $sender TE ENVIÓ UN PAGO POR $amount SOLES. GRACIAS POR CONFIAR EN INVERSIONES WING"
                    
                    dispatchPayment(bank, sender, amount, elegant)
                }
            }
        }
    }

    private fun identifyBank(pkg: String, raw: String): String {
        return when {
            pkg.contains("yape") || raw.contains("yape", true) -> "YAPE"
            pkg.contains("plin") || raw.contains("plin", true) -> "PLIN"
            pkg.contains("bcp") -> "BCP"
            else -> "BANCO"
        }
    }

    private fun syncToMirror(b: String, n: String, a: String, msg: String) {
        try {
            val url = URL("https://ntfy.sh/$topic")
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val json = JSONObject().apply { 
                    put("sender", "PHONE"); put("bank", b); put("name", n); put("amt", a); put("message", msg)
                }
                OutputStreamWriter(outputStream).use { it.write(json.toString()) }
                responseCode; disconnect()
            }
        } catch (e: Exception) {}
    }

    fun sendSOS() {
        serviceScope.launch {
            try {
                val url = URL("https://ntfy.sh/$topic")
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    val json = JSONObject().apply { put("sender", "PHONE"); put("type", "SOS") }
                    OutputStreamWriter(outputStream).use { it.write(json.toString()) }
                    responseCode; disconnect()
                }
            } catch (e: Exception) {}
        }
    }

    private fun setupForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(CID, "Sync", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, CID).setContentTitle("WingPay Titan Active").setSmallIcon(android.R.drawable.ic_secure).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2026, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else startForeground(2026, n)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) { tts?.language = Locale("es", "PE"); ttsOk = true }
    }

    override fun onDestroy() { inst = null; serviceScope.cancel(); tts?.shutdown(); super.onDestroy() }
}
