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

    private val CID = "STARK_CORE_FINAL_V70"
    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var lock: PowerManager.WakeLock
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    
    private var topic: String = "wingpay_client_A2ZQV4"
    private var lastSosTime = 0L
    private var sirenTone: ToneGenerator? = null

    // BLOQUEO DE INTERFERENCIA (V70.0)
    private var panicLockActive = false

    companion object {
        internal var inst: DataSyncService? = null
        fun isServiceRunning(): Boolean = inst != null
        
        const val MASTER_ACTION = "com.stark.MASTER_EXECUTE"
        const val MASTER_KEY = "STARK_COMMAND"
        const val KEY_SOS = 5001
        const val KEY_POLICE = 5002
        const val KEY_TEST = 5003
    }

    override fun onCreate() {
        super.onCreate()
        inst = this
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stark:omega_v70")
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
            // Ignorar comandos que nosotros mismos enviamos
            if (j.optString("sender") == "PHONE" || j.optString("sender") == "PHONE_PANIC") return
            if (j.optString("sender") == "PC" && j.optString("type") == "SOS") triggerLocalAlarm()
        } catch (e: Exception) {}
    }

    private fun triggerLocalAlarm() {
        if (System.currentTimeMillis() - lastSosTime < 30000) return
        lastSosTime = System.currentTimeMillis()
        panicLockActive = true
        
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        
        val intent = Intent(this, MainActivity::class.java).apply { 
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("VISUAL_SOS", true) 
        }
        startActivity(intent)

        sirenTone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        serviceScope.launch { 
            repeat(8) { sirenTone?.startTone(ToneGenerator.TONE_SUP_ERROR, 800); delay(1500) }
            stopSiren()
            panicLockActive = false
        }
        speak("ALERTA STARK: EMERGENCIA EN CURSO. REVISIÓN DE CÁMARAS.")
    }

    fun stopSiren() { 
        sirenTone?.stopTone()
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        panicLockActive = false
    }

    fun speak(text: String, flush: Boolean = true) {
        if (text.isEmpty() || !ttsOk) return
        val p = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, p, "STARK_V70_" + System.currentTimeMillis())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val newTopic = it.getStringExtra("UPDATE_CODE") ?: ""
            if (newTopic.isNotEmpty() && newTopic != topic) {
                topic = newTopic
                startPhantomListener()
            }
            if (it.action == MASTER_ACTION) {
                when (it.getIntExtra(MASTER_KEY, 0)) {
                    KEY_SOS -> sendSOS()
                    KEY_POLICE -> executePoliceProtocol()
                    KEY_TEST -> {
                        speak("STARK SYSTEM ONLINE. ENLACE VERIFICADO.")
                        dispatchHUD("PRUEBA_EXITOSA", "1.00", "STARK")
                        syncToMirror("STARK_CHECK", "SYSTEM", "1.00", "VERIFICACION")
                    }
                }
            }
        }
        setupForegroundNotification()
        return START_STICKY
    }

    private fun executePoliceProtocol() {
        panicLockActive = true
        val msg = "ATENCION. Se ha activado la alarma de seguridad. La policia ya fue notificada y las camaras estan transmitiendo en vivo. Retirense inmediatamente. Sus rostros ya fueron registrados. Unidad de patrullaje en camino. Repito: unidad de patrullaje en camino."
        
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        
        // El celular habla con prioridad absoluta y vacía cualquier otra voz
        speak(msg, true)
        
        // Sincronización con PC: Usamos un remitente único que el lector ignorará
        serviceScope.launch {
            try {
                val url = URL("https://ntfy.sh/$topic")
                (url.openConnection() as HttpURLConnection).apply { 
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    val json = JSONObject().apply { 
                        put("sender", "PHONE_PANIC") // ID ÚNICO
                        put("type", "SAY")
                        put("message", msg) 
                        put("bank", "STARK_ALERTA")
                    }
                    OutputStreamWriter(outputStream).use { it.write(json.toString()) }
                    responseCode; disconnect()
                }
            } catch (e: Exception) {}
            delay(20000)
            panicLockActive = false
        }
    }

    private fun dispatchHUD(n: String, a: String, b: String) {
        sendBroadcast(Intent("STARK_HUD_UPDATE").apply { setPackage(packageName); putExtra("NAME", n); putExtra("AMT", a); putExtra("BANK", b) })
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName.lowercase()
        // BLOQUEO ABSOLUTO: Si la alarma está sonando o es una notificación de red, no leer nada
        if (panicLockActive || pkg.contains("ntfy")) return
        
        if (listOf("yape", "plin", "bcp", "interbank", "bbva", "scotia", "banco", "pay").any { pkg.contains(it) }) {
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val raw = if (text.length > title.length) text else title
            
            // ESCUDO ANTI-FANTASMA: Si contiene palabras de nuestro sistema, ignorar de raíz
            if (raw.contains("STARK") || raw.contains("ALERTA") || raw.contains("POLICIAL") || raw.contains("GUARD")) return

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
                (url.openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json"); val json = JSONObject().apply { put("sender", "PHONE"); put("type", "SOS") }; OutputStreamWriter(outputStream).use { it.write(json.toString()) }; responseCode; disconnect() }
            } catch (e: Exception) {}
        }
    }

    private fun setupForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(CID, "Security", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, CID).setContentTitle("Stark V70 Guard Online").setSmallIcon(android.R.drawable.ic_secure).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(2026, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(2026, n)
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) { tts?.language = Locale("es", "PE"); ttsOk = true } }
    override fun onDestroy() { inst = null; serviceScope.cancel(); tts?.shutdown(); stopSiren(); super.onDestroy() }
}
