package de.wifilogger

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.*
import java.text.SimpleDateFormat
import java.util.*

class WifiEventAdapter : ListAdapter<WifiEvent, WifiEventAdapter.ViewHolder>(DiffCallback) {
    private val sdf = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tv_time)
        val tvEvent: TextView = view.findViewById(R.id.tv_event)
        val tvDetails: TextView = view.findViewById(R.id.tv_details)
        val indicator: View = view.findViewById(R.id.event_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_wifi_event, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = getItem(position)
        holder.tvTime.text = sdf.format(Date(event.timestamp))
        holder.tvEvent.text = eventLabel(event.eventType)
        holder.indicator.setBackgroundColor(eventColor(holder.itemView, event.eventType))
        val details = buildString {
            val header = StringBuilder()
            event.ssid?.let { header.append("SSID: $it") }
            event.band?.let { header.append(" ($it)") }
            event.rssi?.let { header.append(" ($it dBm)") }
            append(header.toString())

            event.bssid?.let { append("\nBSSID: $it") }
            val ips = event.ipAddress?.split(", ") ?: emptyList()
            ips.forEach { ip ->
                if (ip.contains(":")) append("\nIPv6: $ip")
                else if (ip != "0.0.0.0") append("\nIPv4: $ip")
            }
            event.gatewayReachability?.let { append("\n$it") }
            event.reason?.let { append("\n$it") }
            event.previousSsid?.let { append("\nVorher: $it") }
        }
        holder.tvDetails.text = details
        holder.tvDetails.visibility = if (details.isBlank()) View.GONE else View.VISIBLE
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
