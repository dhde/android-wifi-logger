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
    private var lastRssi: Int? = null

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
        val caps = connectivityManager.getNetworkCapabilities(network)
        val lp = connectivityManager.getLinkProperties(network)
        val ssid = getSsid(network)
        val bssid = getBssid(network)
        
        val ipv4 = lp?.linkAddresses?.find { it.address is java.net.Inet4Address }?.address?.hostAddress
        val ipv6 = lp?.linkAddresses?.find { it.address is java.net.Inet6Address && !it.address.isLinkLocalAddress }?.address?.hostAddress
            ?: lp?.linkAddresses?.find { it.address is java.net.Inet6Address }?.address?.hostAddress
        val ips = listOfNotNull(ipv4, ipv6).joinToString(", ")
        
        val gw4 = lp?.routes?.find { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
        val gw6 = lp?.routes?.find { it.isDefaultRoute && it.gateway is java.net.Inet6Address }?.gateway?.hostAddress
        val dns4 = lp?.dnsServers?.find { it is java.net.Inet4Address }?.hostAddress
        val dns6 = lp?.dnsServers?.find { it is java.net.Inet6Address }?.hostAddress

        val routes = listOfNotNull(
            gw4?.let { "  - IPv4 GW: $it" },
            gw6?.let { "  - IPv6 GW: $it" },
            dns4?.let { if (it != gw4) "  - DNS v4: $it" else null },
            dns6?.let { if (it != gw6) "  - DNS v6: $it" else null }
        ).joinToString("\n")
        
        val info = caps?.transportInfo as? WifiInfo
        val freq = info?.frequency ?: 0
        val rssi = info?.rssi ?: -127
        
        lifecycleScope.launch(Dispatchers.IO) {
            val isNetValidated = connectivityManager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val resDns4 = dns4?.let { checkReachability(it, network) } ?: "-"
            val resDns6 = dns6?.let { checkReachability(it, network) } ?: "-"
            
            val netStatus = if (isNetValidated) "🌐 Internet OK" else "⚠️ Internet eingeschränkt"
            val reachability = "Ping DNS v4: $resDns4 | v6: $resDns6 | $netStatus"
            
            val wasOtherSsid = currentSsid != null && currentSsid != ssid
            dao.insert(WifiEvent(
                eventType = if (wasOtherSsid) EventType.ROAMING else EventType.CONNECTED,
                ssid = ssid ?: "Unbekannt", bssid = bssid, rssi = rssi, frequency = freq,
                ipAddress = ips, routes = routes, gatewayReachability = reachability,
                previousSsid = if (wasOtherSsid) currentSsid else null
            ))
        }
        currentSsid = ssid; currentBssid = bssid; currentIp = ips; currentRoutes = routes; lastRssi = rssi
        updateNotification(ssid)
    }

    private fun handleNetworkStateChanged(intent: Intent) {
        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
        if (networkInfo?.state == NetworkInfo.State.CONNECTED && currentSsid == null) {
            // Fallback: Wir sind verbunden, aber haben kein Event bekommen
            forceCheckCurrentStatus("Fallback: NetworkStateChanged")
        } else if (networkInfo?.state == NetworkInfo.State.DISCONNECTED && currentSsid != null) {
            // Fallback: Getrennt
            handleNetworkLost(connectivityManager.activeNetwork ?: return)
        }
    }

    private fun forceCheckCurrentStatus(source: String) {
        val network = connectivityManager.activeNetwork ?: return
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val ssid = getSsid(network)
            if (ssid != null && currentSsid == null) {
                Log.i(TAG, "Force sync: Re-triggering connection from $source")
                handleNetworkAvailable(network)
            }
        }
    }

    private fun handleNetworkLost(network: Network) {
        lifecycleScope.launch(Dispatchers.IO) {
            dao.insert(WifiEvent(
                eventType = EventType.DISCONNECTED,
                ssid = currentSsid, bssid = currentBssid, reason = "Network lost"
            ))
        }
        currentSsid = null; currentBssid = null; currentIp = null
        updateNotification(null)
    }

    private fun handleCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        val info = caps.transportInfo as? WifiInfo
        val newRssi = info?.rssi ?: -127
        
        // BSSID aus Capabilities sichern (kommt meist erst hier zuverlässig an)
        val bssidFromCaps = info?.bssid
        if (bssidFromCaps != null && bssidFromCaps != "02:00:00:00:00:00" && bssidFromCaps != "00:00:00:00:00:00") {
            if (currentBssid != null && currentBssid != bssidFromCaps && currentSsid != null) {
                // BSSID changed - Roaming detected!
                val ssid = currentSsid
                val oldBssid = currentBssid
                lifecycleScope.launch(Dispatchers.IO) {
                    val lp = connectivityManager.getLinkProperties(network)
                    val dns4 = lp?.dnsServers?.find { it is java.net.Inet4Address }?.hostAddress
                    val dns6 = lp?.dnsServers?.find { it is java.net.Inet6Address }?.hostAddress
                    val isNetValidated = connectivityManager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    
                    val resDns4 = dns4?.let { checkReachability(it, network) } ?: "-"
                    val resDns6 = dns6?.let { checkReachability(it, network) } ?: "-"
                    val netStatus = if (isNetValidated) "🌐 Internet OK" else "⚠️ Internet eingeschränkt"
                    val reachability = "Ping DNS v4: $resDns4 | v6: $resDns6 | $netStatus"

                    dao.insert(WifiEvent(
                        eventType = EventType.ROAMING, ssid = ssid,
                        bssid = bssidFromCaps, rssi = newRssi, frequency = info?.frequency ?: 0,
                        ipAddress = currentIp, routes = currentRoutes, gatewayReachability = reachability
                    ))
                }
            }
            currentBssid = bssidFromCaps
        }
        
        if (lastRssi != null && Math.abs(newRssi - lastRssi!!) >= 10 && currentSsid != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                dao.insert(WifiEvent(
                    eventType = EventType.SIGNAL_CHANGE, ssid = currentSsid,
                    bssid = currentBssid, rssi = newRssi, frequency = info?.frequency ?: 0,
                    reason = "RSSI changed: $lastRssi -> $newRssi dBm"
                ))
            }
            lastRssi = newRssi
        }
    }

    private var currentRoutes: String? = null

    private fun handleLinkPropertiesChanged(network: Network, lp: LinkProperties) {
        val ipv4 = lp.linkAddresses.find { it.address is java.net.Inet4Address }?.address?.hostAddress
        val ipv6 = lp.linkAddresses.find { it.address is java.net.Inet6Address && !it.address.isLinkLocalAddress }?.address?.hostAddress
            ?: lp.linkAddresses.find { it.address is java.net.Inet6Address }?.address?.hostAddress
        val ips = listOfNotNull(ipv4, ipv6).joinToString(", ")
        
        val gw4 = lp.routes.find { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
        val gw6 = lp.routes.find { it.isDefaultRoute && it.gateway is java.net.Inet6Address }?.gateway?.hostAddress
        val dns4 = lp.dnsServers.find { it is java.net.Inet4Address }?.hostAddress
        val dns6 = lp.dnsServers.find { it is java.net.Inet6Address }?.hostAddress

        val routes = listOfNotNull(
            gw4?.let { "  - IPv4 GW: $it" },
            gw6?.let { "  - IPv6 GW: $it" },
            dns4?.let { if (it != gw4) "  - DNS v4: $it" else null },
            dns6?.let { if (it != gw6) "  - DNS v6: $it" else null }
        ).joinToString("\n")

        val changed = (ips != currentIp || routes != currentRoutes)
        if (changed && ips.isNotBlank()) {
             lifecycleScope.launch(Dispatchers.IO) {
                val isNetValidated = connectivityManager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                val resDns4 = dns4?.let { checkReachability(it, network) } ?: "-"
                val resDns6 = dns6?.let { checkReachability(it, network) } ?: "-"
                
                val netStatus = if (isNetValidated) "🌐 Internet OK" else "⚠️ Internet eingeschränkt"
                val reachability = "Ping DNS v4: $resDns4 | v6: $resDns6 | $netStatus"
                
                dao.insert(WifiEvent(
                    eventType = EventType.IP_CHANGE, ssid = currentSsid,
                    bssid = currentBssid, ipAddress = ips, routes = routes,
                    gatewayReachability = reachability
                ))
            }
            currentIp = ips
            currentRoutes = routes
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
        if (intent?.action == ACTION_STOP) { stopMonitoring(); return START_NOT_STICKY }
        startMonitoring()
        forceCheckCurrentStatus("onStartCommand")
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
