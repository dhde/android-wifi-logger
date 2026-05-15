package de.dhde.wifilogger

import android.app.*
import android.content.*
import android.net.*
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.*
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WifiMonitorService : LifecycleService() {
    companion object {
        const val ACTION_START = "de.dhde.wifilogger.START"
        const val ACTION_STOP = "de.dhde.wifilogger.STOP"
        const val ACTION_SYNC = "de.dhde.wifilogger.SYNC"
        const val CHANNEL_ID = "wifi_logger_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "WifiMonitorService"
        var isRunning = false
            private set
    }

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private lateinit var dao: WifiEventDao
    private var currentSsid: String? = null
    private var currentBssid: String? = null
    private var currentIp: String? = null
    private var currentRoutes: String? = null
    private var lastRssi: Int? = null
    private var lastFreq: Int? = null

    private fun logEvent(type: EventType, reason: String? = null, rssi: Int? = null, freq: Int? = null) {
        val ssid = currentSsid
        val bssid = currentBssid
        val ip = currentIp
        val routes = currentRoutes
        val rVal = rssi ?: lastRssi
        val fVal = freq ?: lastFreq
        
        lifecycleScope.launch(Dispatchers.IO) {
            val reachability = if (ssid != null) {
                val network = connectivityManager.activeNetwork
                if (network != null) {
                    val lp = connectivityManager.getLinkProperties(network)
                    val dnsServers = lp?.dnsServers ?: emptyList()
                    val dns4 = dnsServers.filter { it is java.net.Inet4Address }.map { it.hostAddress }
                    val dns6 = dnsServers.filter { it is java.net.Inet6Address }.map { it.hostAddress }
                    
                    val res4 = dns4.map { checkReachability(it, network) }.joinToString("")
                    val res6 = dns6.map { checkReachability(it, network) }.joinToString("")
                    
                    val v4Str = if (res4.isEmpty()) "-" else res4
                    val v6Str = if (res6.isEmpty()) "-" else res6
                    
                    val isNetValidated = connectivityManager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    val netStatus = if (isNetValidated) "🌐 Internet OK" else "⚠️ Internet eingeschränkt"
                    "Ping DNS v4: $v4Str | v6: $v6Str | $netStatus"
                } else null
            } else null

            dao.insert(WifiEvent(
                eventType = type,
                ssid = ssid,
                bssid = bssid,
                ipAddress = ip,
                routes = routes,
                rssi = rVal,
                frequency = fVal,
                gatewayReachability = reachability,
                reason = reason
            ))
        }
    }

    private val wifiBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> handleWifiStateChanged(intent)
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> handleNetworkStateChanged(intent)
            }
        }
    }

    private val networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) = handleNetworkAvailable(network)
            override fun onLost(network: Network) = handleNetworkLost(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = handleCapabilitiesChanged(network, caps)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = handleLinkPropertiesChanged(network, lp)
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = handleNetworkAvailable(network)
            override fun onLost(network: Network) = handleNetworkLost(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = handleCapabilitiesChanged(network, caps)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = handleLinkPropertiesChanged(network, lp)
        }
    }

    @Suppress("DEPRECATION")
    private fun getSsid(network: Network? = null): String? {
        val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
            ?: connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val info = caps?.transportInfo as? WifiInfo ?: wifiManager.connectionInfo
        val ssid = info?.ssid?.trim('"')
        return if (ssid == null || ssid == "<unknown ssid>" || ssid == "0x") {
            val legacySsid = wifiManager.connectionInfo?.ssid?.trim('"')
            if (legacySsid == "<unknown ssid>" || legacySsid == "0x") null else legacySsid
        } else {
            ssid
        }
    }

    @Suppress("DEPRECATION")
    private fun getBssid(network: Network? = null): String? {
        val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
            ?: connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val infoFromCaps = caps?.transportInfo as? WifiInfo
        val infoFromManager = wifiManager.connectionInfo

        val dummies = setOf("02:00:00:00:00:00", "00:00:00:00:00:00", null)

        // Caps-BSSID bevorzugen, aber bei Dummy-Adresse auf WifiManager zurückfallen
        val bssid = infoFromCaps?.bssid?.takeIf { it !in dummies }
            ?: infoFromManager?.bssid?.takeIf { it !in dummies }
        return bssid
    }


    @Suppress("DEPRECATION")
    private fun handleNetworkAvailable(network: Network) {
        val ssid = getSsid(network)
        val bssid = getBssid(network)
        val caps = connectivityManager.getNetworkCapabilities(network)
        val info = caps?.transportInfo as? WifiInfo
        
        currentSsid = ssid
        currentBssid = bssid
        lastRssi = info?.rssi
        lastFreq = info?.frequency
        
        // IP und Routes initial sichern
        val lp = connectivityManager.getLinkProperties(network)
        if (lp != null) updateIpsAndRoutes(lp)
        
        logEvent(EventType.CONNECTED)
        updateNotification(ssid)
    }

    private fun updateIpsAndRoutes(lp: LinkProperties) {
        val ipv4 = lp.linkAddresses.find { it.address is java.net.Inet4Address }?.address?.hostAddress
        val ipv6 = lp.linkAddresses.find { it.address is java.net.Inet6Address && !it.address.isLinkLocalAddress }?.address?.hostAddress
            ?: lp.linkAddresses.find { it.address is java.net.Inet6Address }?.address?.hostAddress
        currentIp = listOfNotNull(ipv4, ipv6).joinToString(", ")
        
        val gw4 = lp.routes.find { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
        val gw6 = lp.routes.find { it.isDefaultRoute && it.gateway is java.net.Inet6Address }?.gateway?.hostAddress
        val dnsServers = lp.dnsServers
        val dns4List = dnsServers.filter { it is java.net.Inet4Address }.map { it.hostAddress }
        val dns6List = dnsServers.filter { it is java.net.Inet6Address }.map { it.hostAddress }

        currentRoutes = buildString {
            gw4?.let { append("  - IPv4 GW: $it\n") }
            gw6?.let { append("  - IPv6 GW: $it\n") }
            dns4List.forEach { append("  - DNS v4: $it\n") }
            dns6List.forEach { append("  - DNS v6: $it\n") }
        }.trimEnd()
    }

    @Suppress("DEPRECATION")
    private fun handleNetworkStateChanged(intent: Intent) {
        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
        } else {
            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
        }
        
        val state = networkInfo?.state
        
        if (state == NetworkInfo.State.CONNECTED && currentSsid == null) {
            forceCheckCurrentStatus("Fallback: NetworkStateChanged")
        } else if (state == NetworkInfo.State.DISCONNECTED && currentSsid != null) {
            connectivityManager.activeNetwork?.let { handleNetworkLost(it) }
        }
    }

    private fun forceCheckCurrentStatus(source: String, forceLog: Boolean = false) {
        val network = connectivityManager.activeNetwork
        if (network == null) {
            if (forceLog) {
                logEvent(EventType.DISCONNECTED, reason = "Manuelle Pruefung: Nicht verbunden")
            }
            return
        }
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val ssid = getSsid(network)
            if (ssid != null) {
                if (currentSsid == null || forceLog) {
                    Log.i(TAG, "Force sync: Re-triggering connection from $source")
                    handleNetworkAvailable(network)
                }
            }
        }
    }

    private fun handleNetworkLost(network: Network) {
        logEvent(EventType.DISCONNECTED, reason = "Network lost")
        currentSsid = null; currentBssid = null; currentIp = null; currentRoutes = null
        updateNotification(null)
    }

    private fun handleCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        val info = caps.transportInfo as? WifiInfo
        val newRssi = info?.rssi ?: -127
        val newFreq = info?.frequency ?: 0
        
        val bssidFromCaps = info?.bssid
        val dummies = setOf("02:00:00:00:00:00", "00:00:00:00:00:00")
        
        if (bssidFromCaps != null && bssidFromCaps !in dummies && currentBssid != null && currentBssid != bssidFromCaps) {
            currentBssid = bssidFromCaps
            logEvent(EventType.ROAMING, rssi = newRssi, freq = newFreq)
            lastRssi = newRssi // RSSI Stand nach Roaming merken
        } else if (lastRssi != null && Math.abs(newRssi - lastRssi!!) >= 10 && currentSsid != null) {
            logEvent(EventType.SIGNAL_CHANGE, reason = "RSSI changed: $lastRssi -> $newRssi dBm", rssi = newRssi, freq = newFreq)
            lastRssi = newRssi
        }
        lastFreq = newFreq
    }

    private fun handleLinkPropertiesChanged(network: Network, lp: LinkProperties) {
        val oldIp = currentIp
        val oldRoutes = currentRoutes
        updateIpsAndRoutes(lp)
        
        if (currentIp != oldIp || currentRoutes != oldRoutes) {
            if (currentIp != null && currentIp!!.isNotBlank() && currentSsid != null) {
                logEvent(EventType.IP_CHANGE)
            }
        }
    }

    private fun checkReachability(host: String, network: Network? = null): String {
        return try {
            val inet = java.net.InetAddress.getByName(host)

            // Methode 1: TCP Port 53 ohne Binding
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(inet, 53), 1500)
                    return "✅"
                }
            } catch (_: Exception) {}

            // Methode 2: TCP via SocketFactory (network-gebunden)
            try {
                val s = network?.socketFactory?.createSocket() ?: java.net.Socket()
                s.use { it.connect(java.net.InetSocketAddress(inet, 53), 1500); return "✅" }
            } catch (_: Exception) {}

            // Methode 3: Echter DNS UDP-Request
            try {
                val udp = java.net.DatagramSocket()
                network?.bindSocket(udp)
                udp.soTimeout = 2000
                val q = byteArrayOf(
                    0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x06, 'g'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'g'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                    0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x01, 0x00, 0x01
                )
                udp.send(java.net.DatagramPacket(q, q.size, inet, 53))
                udp.receive(java.net.DatagramPacket(ByteArray(512), 512))
                udp.close()
                return "✅"
            } catch (_: Exception) {}

            // Methode 4: Ping Fallback
            val pingHost = if (host.startsWith("fe80") && !host.contains("%")) "$host%wlan0" else host
            if (Runtime.getRuntime().exec("ping -c 1 -W 2 $pingHost").waitFor() == 0) "✅" else "❌"
        } catch (e: Exception) {
            "❌"
        }
    }
    private fun handleWifiStateChanged(intent: Intent) {
        if (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1) == WifiManager.WIFI_STATE_DISABLED) {
            lifecycleScope.launch(Dispatchers.IO) {
                dao.insert(WifiEvent(eventType = EventType.DISCONNECTED, ssid = currentSsid, reason = "WiFi disabled"))
            }
            currentSsid = null; currentBssid = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        dao = AppDatabase.getInstance(this).wifiEventDao()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_SYNC -> { forceCheckCurrentStatus("Manual", forceLog = true) }
            else -> { 
                startMonitoring()
                forceCheckCurrentStatus("onStartCommand")
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        isRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification("Ueberwache WLAN..."), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Ueberwache WLAN..."))
        }
        registerNetworkCallback()
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        registerReceiver(wifiBroadcastReceiver, filter)
        lifecycleScope.launch(Dispatchers.IO) {
            dao.insert(WifiEvent(eventType = EventType.APP_START, reason = "Logging gestartet"))
        }
        Log.i(TAG, "WiFi monitoring started")
    }

    private fun createNetworkRequest(): NetworkRequest {
        return NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
    }

    private fun registerNetworkCallback() {
        connectivityManager.registerNetworkCallback(
            createNetworkRequest(),
            networkCallback
        )
    }

    private fun stopMonitoring() {
        isRunning = false
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            unregisterReceiver(wifiBroadcastReceiver)
        } catch (e: Exception) { Log.w(TAG, e.message ?: "") }
        lifecycleScope.launch(Dispatchers.IO) {
            dao.insert(WifiEvent(eventType = EventType.APP_STOP, reason = "Logging gestoppt"))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "WiFi Logger", NotificationManager.IMPORTANCE_LOW).apply {
                description = "WLAN-Ereignisse werden aufgezeichnet"; setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Logger aktiv").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_delete, "Stopp",
                PendingIntent.getService(this, 0,
                    Intent(this, WifiMonitorService::class.java).apply { action = ACTION_STOP },
                    PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()
    }

    private fun updateNotification(ssid: String?) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
            buildNotification(if (ssid != null) "Verbunden: $ssid" else "Nicht verbunden"))
    }

    override fun onDestroy() { super.onDestroy(); isRunning = false }
}
