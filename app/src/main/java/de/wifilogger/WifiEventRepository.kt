package de.wifilogger

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class WifiEventRepository(context: Context) {
    private val dao = AppDatabase.getInstance(context).wifiEventDao()
    val allEvents = dao.getAllEvents()
    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAll() }
    suspend fun deleteOlderThan(days: Int) = withContext(Dispatchers.IO) {
        dao.deleteOlderThan(System.currentTimeMillis() - (days * 86_400_000L))
    }
    suspend fun exportCsv(context: Context): String = withContext(Dispatchers.IO) {
        val events = dao.getRecentEvents(2000)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        buildString {
            appendLine("timestamp,datetime,event_type,ssid,bssid,rssi_dbm,band,ip_address,routes,gw_status,reason,previous_ssid")
            for (e in events) appendLine(listOf(
                e.timestamp, sdf.format(Date(e.timestamp)), e.eventType.name,
                e.ssid?.replace(",", ";") ?: "", e.bssid ?: "",
                e.rssi ?: "", e.band ?: "", e.ipAddress ?: "", 
                e.routes?.replace(",", ";") ?: "", e.gatewayReachability ?: "",
                e.reason?.replace(",", ";") ?: "",
                e.previousSsid?.replace(",", ";") ?: ""
            ).joinToString(","))
        }
    }
}
