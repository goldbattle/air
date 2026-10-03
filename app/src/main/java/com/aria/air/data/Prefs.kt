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

    /** Loads saved values into memory. Before this (unit tests) values just live in memory. */
    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        sp = p
        p.all.forEach { (k, v) -> values[k] = v.toString() }
    }

    /** The value for [k], or "" when unset. */
    operator fun get(k: String) = values[k] ?: ""

    /** Sets [k]: screens update at once, and it's written to disk in the background. */
    operator fun set(k: String, v: String) {
        values[k] = v
        sp?.edit()?.putString(k, v)?.apply()
    }

    /** A switch stored as "1"/"0", or [default] when never set. */
    fun flag(k: String, default: Boolean = true) = values[k]?.let { it == "1" } ?: default

    /** Saves a switch as "1"/"0". */
    fun setFlag(k: String, v: Boolean) = set(k, if (v) "1" else "0")

    /** The traveler's name, for the passport and to recognize my bookings in imports. */
    var name: String get() = this["name"]; set(v) { this["name"] = v }

    /** FlightAware AeroAPI key, or "". */
    var aeroKey: String get() = this["aeroKey"]; set(v) { this["aeroKey"] = v }

    /** AeroDataBox (RapidAPI) key, or "". */
    var adbKey: String get() = this["adbKey"]; set(v) { this["adbKey"] = v }

    /** Whether a status service can be asked at all; without one, takeoff and landing come from ADS-B alone. */
    val hasStatusKey get() = aeroKey.isNotBlank() || adbKey.isNotBlank()

    /** Whether flights emailed to this address were work trips last time, or null if never imported. */
    fun workFor(address: String): Boolean? = values["workTo:$address"]?.let { it == "1" }

    /** Remembers whether flights emailed to [address] were work trips, so the next import starts the same way. */
    fun setWorkFor(address: String, work: Boolean) = setFlag("workTo:$address", work)

    /** Hours before departure for the reminder on my flights, 0 = off. */
    var reminderHours: Int get() = this["reminderHours"].toIntOrNull() ?: 3; set(v) { this["reminderHours"] = v.toString() }

    /** Hours before my departures that the ongoing countdown notification appears. */
    var liveLeadHours: Int get() = this["liveLeadHours"].toIntOrNull() ?: 12; set(v) { this["liveLeadHours"] = v.toString() }
}

/** Lookups this calendar month, shown in Settings › Data so quota use is visible. */
object Lookups {
    /** Counts one paid status lookup (AeroDataBox or FlightAware) in this month's total. */
    fun count() {
        val k = "lookups-" + java.time.YearMonth.now()
        Prefs[k] = ((Prefs[k].toIntOrNull() ?: 0) + 1).toString()
    }

    /** Paid lookups made this calendar month. */
    fun thisMonth() = Prefs["lookups-" + java.time.YearMonth.now()].toIntOrNull() ?: 0
}
