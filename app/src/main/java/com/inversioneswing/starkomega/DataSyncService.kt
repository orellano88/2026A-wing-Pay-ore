package com.inversioneswing.starkomega

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

    private val CID = "STARK_CORE_2026_FINAL"
    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var lock: PowerManager.WakeLock
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    
    private var topic: String = "wingpay_client_A2ZQV4"
    private var lastSosTime = 0L
    private var sirenTone: ToneGenerator? = null

    // PROTOCOLO OMEGA: Inhibidor de Interferencia
    private var isAlarmActive = false

    companion object {
        internal var inst: DataSyncService? = null
        fun isServiceRunning(): Boolean = inst != null
        
        const val MASTER_ACTION = "com.stark.ACTION_EXECUTE"
        const val MASTER_KEY = "STARK_KEY"
        const val KEY_SOS = 1001
        const val KEY_POLICE = 1002
        const val KEY_TEST = 1003
    }

    override fun onCreate() {
        super.onCreate()
        inst = this
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stark:omega")
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
                        lines.forEach { line -> if (line.isNotBlank()) processRemote(line) }
                    }
                } catch (e: Exception) { delay(5000) }
            }
        }
    }

    private fun processRemote(line: String) {
        try {
            val j = JSONObject(JSONObject(line).getString("message"))
            if (j.optString("sender") == "PC" && j.optString("type") == "SOS") triggerLocalAlarm()
        } catch (e: Exception) {}
    }

    private fun triggerLocalAlarm() {
        if (System.currentTimeMillis() - lastSosTime < 30000) return
        lastSosTime = System.currentTimeMillis()
        isAlarmActive = true
        
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        
        startActivity(Intent(this, MainActivity::class.java).apply { 
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("VISUAL_SOS", true) 
        })

        sirenTone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        serviceScope.launch { 
            repeat(8) { sirenTone?.startTone(ToneGenerator.TONE_SUP_ERROR, 800); delay(1500) }
            stopSiren()
            isAlarmActive = false
        }
        speak("ALERTA STARK: REVISIÓN DE CÁMARAS EN CURSO.")
    }

    fun stopSiren() { 
        sirenTone?.stopTone()
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        isAlarmActive = false
    }

    fun speak(text: String, flush: Boolean = true) {
        if (text.isEmpty() || !ttsOk) return
        val p = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, p, "ID_" + System.currentTimeMillis())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MASTER_ACTION) {
            when (intent.getIntExtra(MASTER_KEY, 0)) {
                KEY_SOS -> sendSOS()
                KEY_POLICE -> {
                    isAlarmActive = true
                    val msg = "ATENCION. Se ha activado la alarma de seguridad. La policía ya fue notificada y las cámaras están transmitiendo en vivo. Retírense inmediatamente. Sus rostros ya fueron registrados. Unidad de patrullaje en camino. Repito: unidad de patrullaje en camino."
                    speak(msg)
                    syncToMirror("POLICIA", "STARK_SHIELD", "0", msg)
                    serviceScope.launch { delay(20000); isAlarmActive = false }
                }
                KEY_TEST -> {
                    speak("STARK SYSTEM ONLINE. VERIFICACIÓN DE DEPÓSITO EXITOSA.")
                    dispatchHUD("PRUEBA_STARK", "1.00", "STARK")
                    syncToMirror("STARK", "VERIFICACION", "1.00", "OMEGA_TEST")
                }
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
        if (isAlarmActive || pkg.contains("ntfy")) return
        
        if (listOf("yape", "plin", "bcp", "interbank", "bbva", "scotia", "banco", "pay").any { pkg.contains(it) }) {
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val raw = if (text.length > title.length) text else title
            
            if (raw.contains("STARK") || raw.contains("ALERTA")) return

            val m = Pattern.compile("(?i)(S/\\s*|S\\.\\s*|S\\s*|soles\\s*)([\\d,]+\\.\\d{2}|[\\d,]+)").matcher(raw)
            if (m.find()) {
                val amount = m.group(2)?.replace(",", "") ?: "0.00"
                var sender = raw.replace(m.group(0)!!, "", true)
                val garbage = listOf("yapeaste", "recibiste", "transferencia", "de", "pago", "enviado", "recibido", "te envió", "soles", "notificación", "operación", "código", "nro", "id", "transacción", "dni", "banco", "ahorros", "corriente", "has", "un", "por", "a", "comisión", "ventas", "exitoso", "exitosa", "cod", "op", "ref", "vta", "yape")
                garbage.forEach { word -> sender = sender.replace(Regex("(?i)\\b$word\\b"), " ") }
                sender = sender.replace(Regex("\\b\\d+\\b"), " ").replace(Regex("[^a-zA-ZñÑáéíóúÁÉÍÓÚ\\s]"), "").replace(Regex("\\s+"), " ").trim()
                sender = sender.lowercase().split(" ").filter { it.length > 1 }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                val bank = identifyBank(pkg, raw)
                
                speak("DEPÓSITO DE... $sender POR $amount SOLES EN $bank.", false)
                dispatchHUD(sender, amount, bank)
                syncToMirror(bank, sender, amount, "DEPÓSITO CONFIRMADO: DE... $sender POR S/ $amount. INVERSIONES WING.")
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
                (url.openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json")
                    val json = JSONObject().apply { put("sender", "PHONE"); put("type", "SOS") }
                    OutputStreamWriter(outputStream).use { it.write(json.toString()) }; responseCode; disconnect() }
            } catch (e: Exception) {}
        }
    }

    private fun setupForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(CID, "Security", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, CID).setContentTitle("Stark Omega Active").setSmallIcon(android.R.drawable.ic_secure).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(2026, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(2026, n)
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) { tts?.language = Locale("es", "PE"); ttsOk = true } }
    override fun onDestroy() { inst = null; serviceScope.cancel(); tts?.shutdown(); stopSiren(); super.onDestroy() }
}
