package de.wifilogger

import android.content.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("wifi_logger_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("autostart", false)) {
                context.startForegroundService(
                    Intent(context, WifiMonitorService::class.java).apply {
                        action = WifiMonitorService.ACTION_START
                    }
                )
            }
        }
    }
}
