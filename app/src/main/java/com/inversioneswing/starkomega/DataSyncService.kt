package com.inversioneswing.starkomega

import android.app.*
import android.content.*
import android.media.AudioAttributes
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.regex.Pattern

class DataSyncService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var lock: PowerManager.WakeLock
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    
    private var topic: String = "wingpay_client_A2ZQV4"
    private var isEmisorMode: Boolean = true
    private var lastSosTime = 0L
    private var sirenTone: ToneGenerator? = null
    private var panicLockActive = false

    // Control de Duplicados (Sección 3: Ventana de 5 segundos)
    private val processedNotifications = mutableMapOf<String, Long>()

    companion object {
        internal var inst: DataSyncService? = null
        fun isServiceRunning(): Boolean = inst != null
        
        const val MASTER_ACTION = "com.stark.MASTER_EXECUTE"
        const val MASTER_KEY = "STARK_COMMAND"
        const val EXTRA_MESSAGE = "STARK_MESSAGE"
        const val KEY_SOS = 5001
        const val KEY_POLICE = 5002
        const val KEY_TEST = 5003
        const val KEY_SAY = 5004

        // Lista Negra (Sección 5)
        private val BLACKLIST_TERMS = listOf(
            "préstamo", "prestó", "crédito", "pre-aprobado", "solicita", 
            "pide tu", "promoción", "aprovecha", "línea de crédito", "cuota", 
            "descuento", "evaluación"
        )

        // Lista Blanca (Sección 5)
        private val WHITELIST_TERMS = listOf(
            "te envió", "recibiste s/", "te yapeó", "te plinó", "abono", 
            "depósito", "confirmación"
        )
    }

    override fun onCreate() {
        super.onCreate()
        inst = this
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stark:omega_v72")
        if (!lock.isHeld) lock.acquire()
        
        tts = TextToSpeech(this, this)
        
        val prefs = getSharedPreferences("STARK_PREFS", MODE_PRIVATE)
        topic = prefs.getString("CLIENT_CODE", topic)!!
        isEmisorMode = prefs.getBoolean("IS_EMISOR_MODE", true)

        startPhantomListener()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale("es", "PE"))
            if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsOk = true
                // AudioAttributes USAGE_MEDIA / USAGE_NOTIFICATION (Sección 3)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(attrs)
                }
            }
        }
    }

    // --------------------------------------------------------------------------------
    // RECEPTOR MODO COMPAÑERO / TRANSMISIÓN ESPEJO (SECCIÓN 1-B & 7)
    // --------------------------------------------------------------------------------
    private fun startPhantomListener() {
        job?.cancel()
        job = serviceScope.launch {
            val endpoint = "https://ntfy.sh/$topic/json"
            while (isActive) {
                try {
                    val conn = URL(endpoint).openConnection() as HttpURLConnection
                    conn.readTimeout = 0
                    conn.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> if (line.isNotBlank()) processRemoteSignal(line) }
                    }
                } catch (e: Exception) { delay(5000) }
            }
        }
    }

    private fun processRemoteSignal(line: String) {
        try {
            val root = JSONObject(line)
            val msgRaw = root.optString("message", "")
            if (msgRaw.isEmpty()) return

            val j = JSONObject(msgRaw)
            val sender = j.optString("sender")
            val type = j.optString("type")

            if (sender == "PHONE" || sender == "PHONE_PANIC") return

            if (sender == "PC" && type == "SOS") triggerLocalAlarm()
            if (sender == "PC" && type == "SAY") {
                val msg = j.optString("message", "")
                if (msg.isNotEmpty()) speak(msg, false)
            }

            // Modo Compañero: Recibe transmisión de Caja / Emisor (Sección 1-B)
            if (!isEmisorMode && type == "PAYMENT_TRANSMISSION") {
                val bank = j.optString("bank", "PAGO")
                val name = j.optString("name", "Cliente")
                val amt = j.optString("amt", "0.00")

                val spokenAmount = speakAmount(cleanAmountString(amt))
                val vocalization = "Confirmado en Caja: $bank de $name por $spokenAmount."
                speak(vocalization, false)

                dispatchHUD(name, amt, bank, vocalization, true)
            }

        } catch (e: Exception) {}
    }

    // --------------------------------------------------------------------------------
    // NOTIFICATION LISTENER ENGINE (SECCIÓN 1-A, 2, 3, 5)
    // --------------------------------------------------------------------------------
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // En Modo Compañero se desactiva la captura de notificaciones locales (Sección 1-B)
        if (!isEmisorMode || panicLockActive) return

        val pkg = sbn.packageName.lowercase()
        if (pkg.contains("ntfy")) return

        // Identificación por nombre de paquete oficial (Sección 2)
        val validPackages = listOf(
            "com.bcp.bank.yape", "com.bcp.bank.bcap", 
            "com.bbva.netcash", "com.bbva.mobile",
            "com.interbank.mobilebanking", "com.scotiabank.peru",
            "com.whatsapp", "com.whatsapp.w4b"
        )
        val isTargetApp = validPackages.any { pkg.contains(it) } || listOf("yape", "plin", "bcp", "bbva", "interbank", "scotia", "banco").any { pkg.contains(it) }

        if (!isTargetApp) return

        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val rawContent = "$title $text".trim()
        val lowerContent = rawContent.lowercase()

        // --------------------------------------------------------------------------------
        // 5. FILTRO ANTI-PUBLICIDAD Y ANTI-PRÉSTAMOS (CERO FALSOS POSITIVOS)
        // --------------------------------------------------------------------------------
        val hasBlacklist = BLACKLIST_TERMS.any { lowerContent.contains(it) }
        if (hasBlacklist) return // Descarte en 0ms sin log ni voz

        val hasWhitelist = WHITELIST_TERMS.any { lowerContent.contains(it) } || rawContent.contains("S/", ignoreCase = true)
        if (!hasWhitelist) return

        // --------------------------------------------------------------------------------
        // 2. EXPRESIONES REGULARES DE ALTA PRECISIÓN (MONTO & REMITENTE)
        // --------------------------------------------------------------------------------
        val amountPattern = Pattern.compile("(?i)(?:S/|S/\\.)\\s*([\\d.,]+)")
        val amountMatcher = amountPattern.matcher(rawContent)

        if (amountMatcher.find()) {
            val rawAmountStr = amountMatcher.group(1) ?: "0.00"
            val cleanedAmount = cleanAmountString(rawAmountStr)

            val senderPattern = Pattern.compile("(?:de|por|remitente:)\\s+([A-Za-zÁÉÍÓÚáéíóúñÑ\\s]+)", Pattern.CASE_INSENSITIVE)
            val senderMatcher = senderPattern.matcher(rawContent)
            var senderName = if (senderMatcher.find()) senderMatcher.group(1)?.trim() ?: "Cliente" else "Cliente"
            senderName = cleanSenderName(senderName)

            val bankName = identifyBank(pkg, rawContent)

            // --------------------------------------------------------------------------------
            // 3. MAPA DE CONTROL DE DUPLICADOS (VENTANA DE 5 SEGUNDOS)
            // --------------------------------------------------------------------------------
            val dedupKey = "$bankName|$senderName|$cleanedAmount"
            val now = System.currentTimeMillis()
            val lastSeen = processedNotifications[dedupKey] ?: 0L
            if (now - lastSeen < 5000) return
            processedNotifications[dedupKey] = now

            // --------------------------------------------------------------------------------
            // VOCALIZACIÓN EN LENGUAJE NATURAL PERUANO
            // --------------------------------------------------------------------------------
            val spokenAmount = speakAmount(cleanedAmount)
            val speechText = "Depósito de $senderName por $spokenAmount en $bankName."
            speak(speechText, false)

            dispatchHUD(senderName, cleanedAmount, bankName, speechText, false)

            // --------------------------------------------------------------------------------
            // 7. TRANSMISIÓN PARALELA MULTIDESTINO Y RED ESPEJO (NTFY + UDP BROADCAST 5005)
            // --------------------------------------------------------------------------------
            syncParallelMultidestino(bankName, senderName, cleanedAmount, speechText)
        }
    }

    // --------------------------------------------------------------------------------
    // FORMATEO & VOZ PERUANA (SECCIÓN 2)
    // --------------------------------------------------------------------------------
    private fun cleanAmountString(amountStr: String?): String {
        if (amountStr.isNullOrEmpty()) return "0"
        val cleanStr = amountStr.replace(Regex("[^\\d.,]"), "").trim('.', ',')
        if (cleanStr.contains(',') && cleanStr.contains('.')) {
            return if (cleanStr.lastIndexOf('.') > cleanStr.lastIndexOf(',')) cleanStr.replace(",", "") else cleanStr.replace(".", "").replace(",", ".")
        }
        if (cleanStr.contains(',')) {
            if (cleanStr.length - cleanStr.lastIndexOf(',') == 3) return cleanStr.replace(Regex(",(?=\\d{2}$)"), ".")
            return cleanStr.replace(",", "")
        }
        return cleanStr
    }

    private fun speakAmount(cleanedAmount: String): String {
        val parts = cleanedAmount.split('.')
        val soles = parts.getOrNull(0)?.toIntOrNull() ?: 0
        var centimos = 0
        if (parts.size > 1) {
            val centStr = (parts[1] + "0").substring(0, 2)
            centimos = centStr.toIntOrNull() ?: 0
        }

        val strSoles = when (soles) {
            0 -> ""
            1 -> "Un sol"
            else -> "$soles soles"
        }

        val strCentimos = when (centimos) {
            0 -> ""
            1 -> "Un céntimo"
            else -> "$centimos céntimos"
        }

        return when {
            soles > 0 && centimos > 0 -> "$strSoles con $strCentimos"
            soles > 0 -> strSoles
            centimos > 0 -> strCentimos
            else -> "Cero soles"
        }
    }

    private fun cleanSenderName(raw: String): String {
        var clean = raw.replace(Regex("(?i)\\b(yapeaste|recibiste|transferencia|de|pago|enviado|recibido|te envió|soles|notificación|operación|código|nro|id|transacción|dni|banco|ahorros|corriente|has|un|por|a|comisión|ventas|exitoso|exitosa|cod|op|ref|vta|yape|plin)\\b"), " ")
        clean = clean.replace(Regex("[^a-zA-ZñÑáéíóúÁÉÍÓÚ\\s]"), "").replace(Regex("\\s+"), " ").trim()
        return clean.split(" ").filter { it.length > 1 }.joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    private fun identifyBank(pkg: String, raw: String): String = when {
        pkg.contains("yape") || raw.contains("yape", true) -> "YAPE"
        pkg.contains("plin") || raw.contains("plin", true) -> "PLIN"
        pkg.contains("bcp") -> "BCP Directo"
        pkg.contains("bbva") -> "BBVA"
        pkg.contains("interbank") -> "Interbank"
        pkg.contains("scotia") -> "Scotiabank"
        pkg.contains("whatsapp") -> "WhatsApp"
        else -> "Banco"
    }

    // --------------------------------------------------------------------------------
    // SECCIÓN 7: TRANSMISIÓN PARALELA (NTFY CLOUD + UDP BROADCAST PORT 5005)
    // --------------------------------------------------------------------------------
    private fun syncParallelMultidestino(b: String, n: String, a: String, msg: String) {
        serviceScope.launch {
            // Emisión 1: Servidor NTFY en la Nube
            try {
                val url = URL("https://ntfy.sh/$topic")
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    val json = JSONObject().apply {
                        put("sender", "EMISOR_APK")
                        put("type", "PAYMENT_TRANSMISSION")
                        put("bank", b)
                        put("name", n)
                        put("amt", a)
                        put("message", msg)
                    }
                    OutputStreamWriter(outputStream).use { it.write(json.toString()) }
                    responseCode
                    disconnect()
                }
            } catch (e: Exception) {}

            // Emisión 2: Paquete UDP Broadcast Local al puerto 5005
            try {
                val udpSocket = DatagramSocket()
                udpSocket.broadcast = true
                val payload = JSONObject().apply {
                    put("type", "WINGPAY_UDP")
                    put("bank", b)
                    put("name", n)
                    put("amt", a)
                }.toString().toByteArray()

                val address = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(payload, payload.size, address, 5005)
                udpSocket.send(packet)
                udpSocket.close()
            } catch (e: Exception) {}
        }
    }

    fun silenceAudio() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
        }
    }

    private fun dispatchHUD(n: String, a: String, b: String, msg: String, isRemote: Boolean) {
        sendBroadcast(Intent("STARK_HUD_UPDATE").apply {
            setPackage(packageName)
            putExtra("NAME", n)
            putExtra("AMT", a)
            putExtra("BANK", b)
            putExtra("MSG", msg)
            putExtra("IS_REMOTE", isRemote)
        })
    }

    fun speak(text: String, flush: Boolean = true) {
        if (text.isEmpty() || !ttsOk) return
        val p = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, p, "WINGPAY_TTS_" + System.currentTimeMillis())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val newTopic = it.getStringExtra("UPDATE_CODE") ?: ""
            if (newTopic.isNotEmpty() && newTopic != topic) {
                topic = newTopic
                getSharedPreferences("STARK_PREFS", MODE_PRIVATE).edit().putString("CLIENT_CODE", topic).apply()
                startPhantomListener()
            }
            if (it.action == MASTER_ACTION) {
                when (it.getIntExtra(MASTER_KEY, 0)) {
                    KEY_SOS -> sendSOS()
                    KEY_POLICE -> executePoliceProtocol()
                    KEY_TEST -> {
                        speak("WINGPAY SISTEMA ONLINE. ENLACE VERIFICADO.")
                        dispatchHUD("VERIFICACIÓN", "1.00", "WINGPAY", "TEST", false)
                        syncParallelMultidestino("WINGPAY", "TEST", "1.00", "PRUEBA DE TRANSMISION")
                    }
                    KEY_SAY -> {
                        val msg = it.getStringExtra(EXTRA_MESSAGE) ?: ""
                        if (msg.isNotEmpty()) sendVoiceToPC(msg)
                    }
                }
            }
        }
        setupForegroundNotification()
        return START_STICKY
    }

    private fun sendVoiceToPC(msg: String) {
        serviceScope.launch {
            try {
                val url = URL("https://ntfy.sh/$topic")
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    val json = JSONObject().apply {
                        put("sender", "PHONE")
                        put("type", "SAY")
                        put("message", msg)
                    }
                    OutputStreamWriter(outputStream).use { it.write(json.toString()) }
                    responseCode
                    disconnect()
                }
            } catch (e: Exception) {}
        }
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

    private fun executePoliceProtocol() {
        panicLockActive = true
        val msg = "ATENCION. Se ha activado la alarma de seguridad. La policia ya fue notificada y las camaras estan transmitiendo en vivo."
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        speak(msg, true)
    }

    fun sendSOS() {
        serviceScope.launch {
            try {
                val url = URL("https://ntfy.sh/$topic")
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    val json = JSONObject().apply { put("sender", "PHONE"); put("type", "SOS") }
                    OutputStreamWriter(outputStream).use { it.write(json.toString()) }
                    responseCode
                    disconnect()
                }
            } catch (e: Exception) {}
        }
    }

    private fun setupForegroundNotification() {
        val channelId = "WINGPAY_SERVICE_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "WingPay Engine", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WingPay Titan Engine")
            .setContentText("Vigilancia descentralizada activa")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        tts?.shutdown()
        super.onDestroy()
    }
}
