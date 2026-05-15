package de.dhde.wifilogger

import android.graphics.Typeface
import android.text.*
import android.text.style.*
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
            buildFullDetails(event, prevEvent)
        } else {
            buildDiffDetails(event, prevEvent)
        }

        holder.tvDetails.text = details
        holder.tvDetails.visibility = if (details.isBlank()) View.GONE else View.VISIBLE
    }

    private fun buildFullDetails(event: WifiEvent, prev: WifiEvent?): CharSequence {
        val builder = SpannableStringBuilder()
        val highlightColor = 0xFFFFD54F.toInt() // Material Amber 300

        fun appendLine(text: String, isChanged: Boolean) {
            val start = builder.length
            if (start > 0) builder.append("\n")
            val lineStart = builder.length
            builder.append(text)
            if (isChanged && prev != null) {
                builder.setSpan(ForegroundColorSpan(highlightColor), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StyleSpan(Typeface.BOLD), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        fun appendField(label: String, value: String, isChanged: Boolean) {
            val start = builder.length
            if (start > 0) builder.append("\n")
            builder.append(label)
            val valueStart = builder.length
            builder.append(value)
            if (isChanged && prev != null) {
                builder.setSpan(ForegroundColorSpan(highlightColor), valueStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StyleSpan(Typeface.BOLD), valueStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // SSID & RSSI
        val startSSID = builder.length
        if (startSSID > 0) builder.append("\n")
        builder.append("SSID: ")
        
        val ssidVal = event.ssid ?: "nicht verbunden"
        val ssidStart = builder.length
        builder.append(ssidVal)
        if (prev != null && event.ssid != prev.ssid) {
            builder.setSpan(ForegroundColorSpan(highlightColor), ssidStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.BOLD), ssidStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        if (showRssi) {
            event.band?.let {
                val bStart = builder.length
                builder.append(" ($it)")
                if (prev != null && event.band != prev.band) {
                    builder.setSpan(ForegroundColorSpan(highlightColor), bStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StyleSpan(Typeface.BOLD), bStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            event.rssi?.let {
                val rStart = builder.length
                builder.append(" ($it dBm)")
                if (prev != null && event.rssi != prev.rssi) {
                    builder.setSpan(ForegroundColorSpan(highlightColor), rStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StyleSpan(Typeface.BOLD), rStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        // BSSID
        val bssidChanged = prev != null && event.bssid != prev.bssid
        appendField("BSSID: ", event.bssid ?: "nicht verbunden", bssidChanged)

        // IPs
        val currentIps = event.ipAddress?.split(", ")?.filter { it.isNotBlank() } ?: emptyList()
        val prevIps = prev?.ipAddress?.split(", ")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        currentIps.forEach { ip ->
            val isNew = ip !in prevIps
            if (ip.contains(":")) appendField("IPv6: ", ip, isNew)
            else if (ip != "0.0.0.0") appendField("IPv4: ", ip, isNew)
        }

        // Reachability - Granulares Highlighting für Status-Teile
        if (event.gatewayReachability != null) {
            val start = builder.length
            if (start > 0) builder.append("\n")
            val lineStart = builder.length
            builder.append(event.gatewayReachability)
            
            if (prev?.gatewayReachability != null) {
                val cur = event.gatewayReachability
                val pre = prev.gatewayReachability
                
                // Hilfsfunktion zum Vergleichen und Markieren einzelner Segmente
                fun highlightIfChanged(label: String, after: String, until: String) {
                    val curVal = cur.substringAfter(after, "").substringBefore(until, "").trim()
                    val preVal = pre.substringAfter(after, "").substringBefore(until, "").trim()
                    
                    if (curVal.isNotEmpty() && curVal != preVal) {
                        if (curVal == "-") {
                            val idx = cur.indexOf(label, cur.indexOf("Ping DNS"))
                            if (idx >= 0) {
                                builder.setSpan(ForegroundColorSpan(highlightColor), lineStart + idx, lineStart + idx + label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                builder.setSpan(StyleSpan(Typeface.BOLD), lineStart + idx, lineStart + idx + label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        } else {
                            val startPosInCur = cur.indexOf(curVal, cur.indexOf(after))
                            if (startPosInCur >= 0) {
                                // Granularer Vergleich: Nur die Symbole markieren, die sich geändert haben
                                for (i in curVal.indices) {
                                    val c = curVal[i]
                                    val p = if (i < preVal.length) preVal[i] else null
                                    if (c != p) {
                                        // Emoji-Check: Emojis können 2 Chars lang sein (Surrogates)
                                        val isHigh = c.isHighSurrogate()
                                        val length = if (isHigh && i + 1 < curVal.length) 2 else 1
                                        val absPos = lineStart + startPosInCur + i
                                        builder.setSpan(ForegroundColorSpan(highlightColor), absPos, absPos + length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                        builder.setSpan(StyleSpan(Typeface.BOLD), absPos, absPos + length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                        if (isHigh) continue // Skip next char if surrogate
                                    }
                                }
                            }
                        }
                    }
                }

                highlightIfChanged("v4", "v4: ", " |")
                highlightIfChanged("v6", "v6: ", " |")
                
                // Internet Status (Spezialfall, da am Ende der Zeile)
                val curNet = cur.substringAfter("Internet ", "").trim()
                val preNet = pre.substringAfter("Internet ", "").trim()
                if (curNet.isNotEmpty() && curNet != preNet) {
                    val startPos = cur.indexOf(curNet, cur.indexOf("Internet "))
                    if (startPos >= 0) {
                        builder.setSpan(ForegroundColorSpan(highlightColor), lineStart + startPos, lineStart + startPos + curNet.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        builder.setSpan(StyleSpan(Typeface.BOLD), lineStart + startPos, lineStart + startPos + curNet.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }

        // Routes
        if (event.routes != null) {
            appendLine("Infos:\n${event.routes}", false)
        }

        // Previous SSID
        if (event.previousSsid != null) {
            appendField("Vorher: ", event.previousSsid, false)
        }

        // Reason (filtered)
        val cleanReason = event.reason?.trim()
        if (cleanReason != null && cleanReason != "IP changed" && cleanReason != "Routing changed" && 
            !cleanReason.startsWith("BSSID changed") && !cleanReason.startsWith("RSSI changed") &&
            cleanReason != "Network lost") {
            val reasonChanged = prev != null && event.reason != prev.reason
            appendField("Grund: ", cleanReason, reasonChanged)
        }

        return builder
    }

    private fun buildDiffDetails(event: WifiEvent, prev: WifiEvent?): String = buildString {
        if (prev == null) {
            append(buildFullDetails(event, null))
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
        
        if (event.bssid != prev.bssid && event.bssid != null) {
            changes.add("BSSID: ${event.bssid}")
        }
        
        // Granulare IP-Pruefung
        val currentIps = event.ipAddress?.split(", ")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val prevIps = prev.ipAddress?.split(", ")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val newIps = currentIps - prevIps
        newIps.forEach { ip ->
            if (ip.contains(":")) changes.add("IPv6: $ip")
            else changes.add("IPv4: $ip")
        }

        // Reachability nur in Full View anzeigen (oder hier auskommentieren/entfernen)
        
        // RSSI-Aenderungen filtern, wenn sich sonst nix ändert (nur anzeigen wenn SSID sich auch ändert oder in Full View)
        
        if (event.reason != prev.reason && event.reason != null && event.eventType != EventType.SIGNAL_CHANGE) {
             val cleanReason = event.reason.trim()
             if (cleanReason != "IP changed" && cleanReason != "Routing changed" && 
                 !cleanReason.startsWith("BSSID changed") && !cleanReason.startsWith("RSSI changed")) {
                changes.add(cleanReason)
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
