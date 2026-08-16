package com.inversioneswing.starkomega

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Calendar

/**
 * GreetingReceiver: Se dispara via AlarmManager a la hora programada.
 * Reproduce el saludo/despedida localmente y (si es Emisor) lo transmite al PC via NTFY.
 */
class GreetingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = context.getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE)
        val isEmisorMode = prefs.getBoolean("IS_EMISOR_MODE", true)
        val isGreeting = action == "com.inversioneswing.starkomega.ACTION_GREETING"
        
        val enabled = if (isGreeting) prefs.getBoolean("GREETING_ENABLED", false) else prefs.getBoolean("GOODBYE_ENABLED", false)
        if (!enabled) return

        val defaultMsg = if (isGreeting) "¡Buenos días, Señor! Sistemas listos y caja en línea. Hoy será un gran día para el negocio. Éxitos." else "Jornada finalizada con éxito. Excelente trabajo hoy, equipo. Nos vemos mañana."
        val msg = prefs.getString(if (isGreeting) "GREETING_MSG" else "GOODBYE_MSG", defaultMsg) ?: return
        val topic = prefs.getString("CLIENT_CODE", "") ?: return

        if (msg.isEmpty() || topic.isEmpty()) return

        // 1. Delegar al Motor en Segundo Plano (DataSyncService) para asegurar que se escuche
        // incluso si el dispositivo estaba en reposo profundo.
        val activeInst = DataSyncService.inst
        if (activeInst != null) {
            activeInst.speakWithMaxVolumeFocus(msg)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    android.service.notification.NotificationListenerService.requestRebind(
                        android.content.ComponentName(context, DataSyncService::class.java)
                    )
                } catch (e: Exception) {}
            }
            val serviceIntent = Intent(context, DataSyncService::class.java).apply {
                this.action = DataSyncService.MASTER_ACTION
                putExtra(DataSyncService.MASTER_KEY, DataSyncService.KEY_SAY)
                putExtra(DataSyncService.EXTRA_MESSAGE, msg)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                try { context.startService(serviceIntent) } catch (ignored: Exception) {}
            }
        }
        
        // 3. Reprogramar la alarma para mañana (Exactitud)
        val hour = prefs.getInt(if (isGreeting) "GREETING_HOUR" else "GOODBYE_HOUR", if (isGreeting) 7 else 19)
        val min = prefs.getInt(if (isGreeting) "GREETING_MIN" else "GOODBYE_MIN", 0)
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val newIntent = Intent(context, GreetingReceiver::class.java).apply {
            this.action = action
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context, if (isGreeting) 9999 else 9998, newIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1) // Siempre al dia siguiente
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        } else {
            alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }
    }
}
