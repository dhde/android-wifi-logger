package de.dhde.wifilogger

import android.content.Context
import android.content.SharedPreferences

class BssidAliasManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bssid_aliases", Context.MODE_PRIVATE)

    fun saveAlias(bssid: String, name: String) {
        val key = bssid.uppercase()
        if (name.isBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, name).apply()
        }
    }

    fun getAlias(bssid: String?): String? {
        if (bssid == null) return null
        return prefs.getString(bssid.uppercase(), null)
    }

    var showMacWithAlias: Boolean
        get() = prefs.getBoolean("show_mac_with_alias", true)
        set(value) = prefs.edit().putBoolean("show_mac_with_alias", value).apply()

    fun getName(bssid: String?): String {
        if (bssid == null) return "nicht verbunden"
        val alias = getAlias(bssid)
        return if (alias != null) {
            if (showMacWithAlias) "$alias ($bssid)" else alias
        } else bssid
    }
    
    fun getAliasOnly(bssid: String?): String {
        if (bssid == null) return ""
        return getAlias(bssid) ?: ""
    }

    fun getAllAliases(): Map<String, String> {
        return prefs.all.mapValues { it.value.toString() }
    }
}
