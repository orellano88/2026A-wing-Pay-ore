package com.inversioneswing.wingpay

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
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
    
    private var topic: String = "wingpay_client_A2ZQV4"
    private var lastSosTime = 0L
    private var sirenTone: ToneGenerator? = null

    companion object {
        internal var inst: DataSyncService? = null
        fun triggerSOS() { inst?.sendSOS() }
        fun isServiceRunning(): Boolean = inst != null
        
        const val ACTION_SOS = "STARK_ACTION_SOS_CRITICAL"
        const val ACTION_POLICE = "STARK_ACTION_POLICE_DISUASION"
        const val ACTION_TEST = "STARK_ACTION_TEST_LINK"
    }

    override fun onCreate() {
        super.onCreate()
        inst = this
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wingpay:wakeup")
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
                    conn.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> if (line.isNotBlank()) processRemoteCommand(line) }
                    }
                } catch (e: Exception) { delay(5000) }
            }
        }
    }

    private fun processRemoteCommand(line: String) {
        try {
            val json = JSONObject(JSONObject(line).getString("message"))
            if (json.optString("sender") == "PC" && json.optString("type") == "SOS") triggerLocalAlarm()
        } catch (e: Exception) {}
    }

    private fun triggerLocalAlarm() {
        if (System.currentTimeMillis() - lastSosTime < 30000) return
        lastSosTime = System.currentTimeMillis()
        
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        
        startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP); putExtra("VISUAL_SOS", true) })

        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 500), 0))
        
        sirenTone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        serviceScope.launch {
            repeat(8) { sirenTone?.startTone(ToneGenerator.TONE_SUP_ERROR, 800); delay(1500) }
            stopSiren()
        }
        speak("¡ALERTA SÍSMICA STARK! LOCAL BAJO AMENAZA. REVISIÓN DE CÁMARAS.")
    }

    fun stopSiren() { sirenTone?.stopTone(); (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() }

    fun speak(text: String) {
        if (text.isEmpty() || !ttsOk) return
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "STARK_" + System.currentTimeMillis())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SOS -> sendSOS()
            ACTION_POLICE -> {
                // VOZ POLICIAL - PRIORIDAD 100
                val msg = "⚠️ ATENCIÓN ⚠️. Se ha activado la alarma de seguridad. La policía ya fue notificada y las cámaras están transmitiendo en vivo. Retírense inmediatamente. Sus rostros ya fueron registrados. Unidad de patrullaje en camino. Repito: unidad de patrullaje en camino."
                speak(msg)
                syncToMirror("POLICIA", "STARK_GUARD", "0", msg)
            }
            ACTION_TEST -> {
                speak("SISTEMA STARK: CONFIRMANDO PULSO DE PRUEBA. ENLACE OMEGA ACTIVO.")
                dispatchHUD("TEST_STARK", "0.10", "WING")
                syncToMirror("WING", "TEST_STARK", "0.10", "PAGO DE PRUEBA")
            }
        }
        setupForegroundNotification()
        return START_STICKY
    }

    private fun dispatchHUD(n: String, a: String, b: String) {
        sendBroadcast(Intent("STARK_HUD_UPDATE").apply { setPackage(packageName); putExtra("NAME", n); putExtra("AMT", a); putExtra("BANK", b) })
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName.lowercase()
        if (listOf("yape", "plin", "bcp", "interbank", "bbva", "scotia", "banco", "pay").any { pkg.contains(it) }) {
            val ex = sbn.notification.extras
            val raw = listOf(ex.getCharSequence(Notification.EXTRA_TITLE), ex.getCharSequence(Notification.EXTRA_TEXT), ex.getCharSequence(Notification.EXTRA_BIG_TEXT)).maxByOrNull { it?.length ?: 0 }?.toString() ?: ""
            
            val m = Pattern.compile("(?i)(S/\\s*|S\\.\\s*|S\\s*|soles\\s*)([\\d,]+\\.\\d{2}|[\\d,]+)").matcher(raw)
            if (m.find()) {
                val amount = m.group(2)?.replace(",", "") ?: "0.00"
                var sender = raw.replace(m.group(0)!!, "", true)
                val garbage = "(?i)(yapeaste|recibiste|transferencia|de|pago|enviado|recibido|te envió|soles|notificación|operación|código|nro|id|transacción|dni|banco|ahorros|corriente|has|un|por|a|comisión|ventas|exitoso|exitosa|cod|op|ref|vta|yape)".toRegex()
                garbage.split("|").forEach { sender = sender.replace(Regex("(?i)\\b$it\\b"), " ") }
                sender = sender.replace(Regex("\\b\\d+\\b"), " ").replace(Regex("[^a-zA-ZñÑáéíóúÁÉÍÓÚ\\s]"), "").replace(Regex("\\s+"), " ").trim()
                sender = sender.lowercase().split(" ").filter { it.length > 1 }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                
                val bank = identifyBank(pkg, raw)
                // NUEVA VOZ: "CRÉDITO" EN LUGAR DE "PAGO" PARA CONFIRMAR VERSIÓN
                speak("CRÉDITO DE... $sender POR $amount SOLES EN $bank.")
                dispatchHUD(sender, amount, bank)
                syncToMirror(bank, sender, amount, "CONFIRMACIÓN DE PAGO: DE... $sender TE ENVIÓ UN PAGO POR $amount SOLES. GRACIAS POR CONFIAR EN INVERSIONES WING")
            }
        }
    }

    private fun identifyBank(pkg: String, raw: String): String = when {
        pkg.contains("yape") || raw.contains("yape", true) -> "YAPE"
        pkg.contains("plin") || raw.contains("plin", true) -> "PLIN"
        pkg.contains("bcp") -> "BCP"
        else -> "BANCO"
    }

    private fun syncToMirror(b: String, n: String, a: String, msg: String) {
        serviceScope.launch {
            try {
                val url = URL("https://ntfy.sh/$topic")
                (url.openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json"); val json = JSONObject().apply { put("sender", "PHONE"); put("bank", b); put("name", n); put("amt", a); put("message", msg) }; OutputStreamWriter(outputStream).use { it.write(json.toString()) }; responseCode; disconnect() }
            } catch (e: Exception) {}
        }
    }

    fun sendSOS() {
        serviceScope.launch {
            try {
                val url = URL("https://ntfy.sh/$topic")
                (url.openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json"); val json = JSONObject().apply { put("sender", "PHONE"); put("type", "SOS") }; OutputStreamWriter(outputStream).use { it.write(json.toString()) }; responseCode; disconnect() }
            } catch (e: Exception) {}
        }
    }

    private fun setupForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(CID, "Sync", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, CID).setContentTitle("WingPay Omega Active").setSmallIcon(android.R.drawable.ic_secure).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(2026, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(2026, n)
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) { tts?.language = Locale("es", "PE"); ttsOk = true } }
    override fun onDestroy() { inst = null; serviceScope.cancel(); tts?.shutdown(); stopSiren(); super.onDestroy() }
}
