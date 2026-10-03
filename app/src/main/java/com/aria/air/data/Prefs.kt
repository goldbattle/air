package com.aria.air.data

// Small settings in SharedPreferences, and the monthly count of paid status lookups.

import com.aria.air.util.*
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf

/** Small settings in SharedPreferences, mirrored in Compose state so screens update when they change. */
object Prefs {
    private var sp: SharedPreferences? = null // until init, values live in memory only (unit tests)
    private val values = mutableStateMapOf<String, String>()

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        sp = p
        p.all.forEach { (k, v) -> values[k] = v.toString() }
    }

    operator fun get(k: String) = values[k] ?: ""
    operator fun set(k: String, v: String) {
        values[k] = v
        sp?.edit()?.putString(k, v)?.apply()
    }

    fun flag(k: String, default: Boolean = true) = values[k]?.let { it == "1" } ?: default
    fun setFlag(k: String, v: Boolean) = set(k, if (v) "1" else "0")

    var name: String get() = this["name"]; set(v) { this["name"] = v }
    var aeroKey: String get() = this["aeroKey"]; set(v) { this["aeroKey"] = v }
    var adbKey: String get() = this["adbKey"]; set(v) { this["adbKey"] = v }
    val hasStatusKey get() = aeroKey.isNotBlank() || adbKey.isNotBlank()
    /** Whether flights emailed to this address were work trips last time, or null if never imported. */
    fun workFor(address: String): Boolean? = values["workTo:$address"]?.let { it == "1" }
    fun setWorkFor(address: String, work: Boolean) = setFlag("workTo:$address", work)
    /** Hours before departure for the reminder on my flights, 0 = off. */
    var reminderHours: Int get() = this["reminderHours"].toIntOrNull() ?: 3; set(v) { this["reminderHours"] = v.toString() }
    /** Hours before my departures that the ongoing countdown notification appears. */
    var liveLeadHours: Int get() = this["liveLeadHours"].toIntOrNull() ?: 12; set(v) { this["liveLeadHours"] = v.toString() }
}
/** Lookups this calendar month, shown in Settings › Data so quota use is visible. */
object Lookups {
    fun count() {
        val k = "lookups-" + java.time.YearMonth.now()
        Prefs[k] = ((Prefs[k].toIntOrNull() ?: 0) + 1).toString()
    }

    fun thisMonth() = Prefs["lookups-" + java.time.YearMonth.now()].toIntOrNull() ?: 0
}
