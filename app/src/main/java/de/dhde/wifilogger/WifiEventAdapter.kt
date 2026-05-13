package de.dhde.wifilogger

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.*
import java.text.SimpleDateFormat
import java.util.*

class WifiEventAdapter : ListAdapter<WifiEvent, WifiEventAdapter.ViewHolder>(DiffCallback) {
    private val sdf = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault())
    private val expandedIds = mutableSetOf<Long>()
    var showRssi: Boolean = false

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tv_time)
        val tvEvent: TextView = view.findViewById(R.id.tv_event)
        val tvDetails: TextView = view.findViewById(R.id.tv_details)
        val indicator: View = view.findViewById(R.id.event_indicator)

        init {
            view.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val id = getItem(position).id
                    if (expandedIds.contains(id)) expandedIds.remove(id)
                    else expandedIds.add(id)
                    notifyItemChanged(position)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_wifi_event, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = getItem(position)
        val prevEvent = if (position + 1 < itemCount) getItem(position + 1) else null
        val isExpanded = expandedIds.contains(event.id)

        holder.tvTime.text = sdf.format(Date(event.timestamp))
        holder.tvEvent.text = eventLabel(event.eventType)
        holder.indicator.setBackgroundColor(eventColor(holder.itemView, event.eventType))

        val details = if (isExpanded) {
            buildFullDetails(event)
        } else {
            buildDiffDetails(event, prevEvent)
        }

        holder.tvDetails.text = details
        holder.tvDetails.visibility = if (details.isBlank()) View.GONE else View.VISIBLE
    }

    private fun buildFullDetails(event: WifiEvent): String = buildString {
        append("SSID: ${event.ssid ?: "nicht verbunden"}")
        if (showRssi) {
            event.band?.let { append(" ($it)") }
            event.rssi?.let { append(" ($it dBm)") }
        }
        
        append("\nBSSID: ${event.bssid ?: "nicht verbunden"}")
        val ips = event.ipAddress?.split(", ") ?: emptyList()
        ips.forEach { ip ->
            if (ip.contains(":")) append("\nIPv6: $ip")
            else if (ip != "0.0.0.0") append("\nIPv4: $ip")
        }
        event.gatewayReachability?.let { append("\n$it") }
        event.reason?.let { append("\n$it") }
        event.previousSsid?.let { append("\nVorher: $it") }
    }

    private fun buildDiffDetails(event: WifiEvent, prev: WifiEvent?): String = buildString {
        if (prev == null) {
            append(buildFullDetails(event))
            return@buildString
        }

        val changes = mutableListOf<String>()
        
        if ((event.ssid != prev.ssid || event.eventType == EventType.SIGNAL_CHANGE) && event.ssid != null) {
            val ssidLine = StringBuilder("SSID: ${event.ssid}")
            if (showRssi) {
                event.band?.let { ssidLine.append(" ($it)") }
                event.rssi?.let { ssidLine.append(" ($it dBm)") }
            }
            changes.add(ssidLine.toString())
        }
        
        if (event.bssid != prev.bssid && event.bssid != null) changes.add("BSSID: ${event.bssid}")
        
        // Granulare IP-Pruefung
        val currentIps = event.ipAddress?.split(", ")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val prevIps = prev.ipAddress?.split(", ")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val newIps = currentIps - prevIps
        newIps.forEach { ip ->
            if (ip.contains(":")) changes.add("IPv6: $ip")
            else changes.add("IPv4: $ip")
        }

        if (event.gatewayReachability != prev.gatewayReachability) {
            event.gatewayReachability?.let { changes.add(it) }
        }
        
        // RSSI-Aenderungen filtern, wenn sich sonst nix ändert (nur anzeigen wenn SSID sich auch ändert oder in Full View)
        
        if (event.reason != prev.reason && event.reason != null && event.eventType != EventType.SIGNAL_CHANGE) {
             if (event.reason != "IP changed" && event.reason != "Routing changed") {
                changes.add(event.reason)
             }
        }

        append(changes.joinToString("\n"))
    }

    private fun eventLabel(type: EventType) = when (type) {
        EventType.CONNECTED              -> "Verbunden"
        EventType.DISCONNECTED           -> "Getrennt"
        EventType.SIGNAL_CHANGE          -> "Signal geaendert"
        EventType.NETWORK_CHANGE         -> "Netzwerkwechsel"
        EventType.IP_CHANGE              -> "IP geaendert"
        EventType.ROAMING                -> "Roaming (AP-Wechsel)"
        EventType.AUTHENTICATION_FAILURE -> "Auth-Fehler"
        EventType.DHCP_FAILURE           -> "DHCP-Fehler"
        EventType.APP_START              -> "Logging gestartet"
        EventType.APP_STOP               -> "Logging gestoppt"
    }

    private fun eventColor(view: View, type: EventType): Int {
        val ctx = view.context
        return when (type) {
            EventType.CONNECTED -> ctx.getColor(R.color.event_connected)
            EventType.DISCONNECTED -> ctx.getColor(R.color.event_disconnected)
            EventType.ROAMING -> ctx.getColor(R.color.event_roaming)
            EventType.AUTHENTICATION_FAILURE, EventType.DHCP_FAILURE -> ctx.getColor(R.color.event_error)
            EventType.SIGNAL_CHANGE -> ctx.getColor(R.color.event_signal)
            else -> ctx.getColor(R.color.event_neutral)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WifiEvent>() {
        override fun areItemsTheSame(a: WifiEvent, b: WifiEvent) = a.id == b.id
        override fun areContentsTheSame(a: WifiEvent, b: WifiEvent) = a == b
    }
}
