package de.dhde.wifilogger

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class EventType {
    CONNECTED, DISCONNECTED, SIGNAL_CHANGE, NETWORK_CHANGE,
    IP_CHANGE, ROAMING, AUTHENTICATION_FAILURE, DHCP_FAILURE,
    APP_START, APP_STOP
}

@Entity(tableName = "wifi_events")
data class WifiEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: EventType,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val frequency: Int? = null,
    val ipAddress: String? = null,
    val routes: String? = null,
    val gatewayReachability: String? = null,
    val reason: String? = null,
    val previousSsid: String? = null
) {
    val band: String?
        get() = frequency?.let {
            when {
                it in 2400..2500 -> "2.4\u00A0GHz"
                it in 5000..6000 -> "5\u00A0GHz"
                it in 6000..7200 -> "6\u00A0GHz"
                else -> "$it\u00A0MHz"
            }
        }
}
