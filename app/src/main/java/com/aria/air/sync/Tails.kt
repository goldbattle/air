package com.aria.air.sync

// Aircraft lookups by tail number: asked only when you open an aircraft's page, and saved for a few hours.

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.util.*
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Aircraft pages' data by tail number, saved in filesDir/tails.json. The airframe comes from AeroDataBox; its last
 * week of flights from AeroDataBox too, or FlightAware when that's the only key. Nothing is asked in the background.
 */
object Tails {
    private var file: File? = null // null in unit tests: memory only

    /** Reports loaded so far, as Compose state so an open page redraws when its lookup finishes. */
    val reports = mutableStateMapOf<String, TailReport>()

    /** Tails being looked up right now. */
    val busy = mutableStateMapOf<String, Boolean>()

    /** The last lookup error per tail, in words. */
    val errors = mutableStateMapOf<String, String>()

    /** A saved report is shown without asking again for this long. */
    val MAX_AGE: Duration = Duration.ofHours(6)

    /** Reports older than this are dropped from the file. */
    private val KEEP: Duration = Duration.ofDays(30)

    fun init(ctx: android.content.Context) {
        val f = File(ctx.filesDir, "tails.json").also { file = it }
        runCatching {
            val j = JSONObject(f.readText())
            j.keys().forEach { k -> TailReport.fromJson(j.getJSONObject(k))?.let { reports[k] = it } }
        }
    }

    private fun save() {
        val now = Instant.now(clock)
        file?.writeText(JSONObject().apply {
            reports.values.filter { Duration.between(it.at, now) < KEEP }.forEach { put(it.reg, it.toJson()) }
        }.toString())
    }

    /**
     * [reg]'s report: the saved one while it's fresh, else looked up (also when [force]). Needs an AeroDataBox or
     * FlightAware key; without one, or when the lookup fails, the saved report stays and the reason is recorded.
     */
    suspend fun get(reg: String, force: Boolean = false): TailReport? = withContext(Dispatchers.IO) {
        val key = AeroDataBox.tailKey(reg)
        val have = reports[key]
        if (have != null && !force && Duration.between(have.at, Instant.now(clock)) < MAX_AGE) return@withContext have
        if (Prefs.adbKey.isEmpty() && Prefs.aeroKey.isEmpty()) return@withContext have
        if (busy[key] == true) return@withContext have
        busy[key] = true
        try {
            val today = localToday()
            Lookups.count()
            if (Prefs.adbKey.isNotEmpty()) Lookups.count() // AeroDataBox takes two calls: the airframe and its flights
            val r = if (Prefs.adbKey.isNotEmpty()) TailReport(
                key, AeroDataBox.aircraft(key, Prefs.adbKey) ?: have?.info,
                AeroDataBox.tailLegs(key, today.minusDays(6), today, Prefs.adbKey), Instant.now(clock), "AeroDataBox",
            ) else TailReport(key, have?.info, FlightAware.tailLegs(key, Prefs.aeroKey), Instant.now(clock), "FlightAware")
            reports[key] = r
            errors.remove(key)
            save()
            r
        } catch (e: HttpError) {
            errors[key] = when (e.code) { 401, 403 -> "Key rejected (${e.code})"; 429 -> "Quota used up"; else -> e.message ?: "Error" }
            have
        } catch (e: Exception) {
            errors[key] = e.message ?: "No connection"
            have
        } finally {
            busy.remove(key)
        }
    }
}
