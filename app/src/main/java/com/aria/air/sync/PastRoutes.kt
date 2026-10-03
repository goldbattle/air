package com.aria.air.sync

// Recent paths flown under a flight number, for the Past routes map layer (routes.json).

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.util.*
import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recent paths flown under the same flight number, drawn faintly on the flight map (Layers › Past routes, off by
 * default). Gathered from adsb.lol's traces of the planes flying the number, earlier days' planes named by AeroDataBox,
 * and FlightAware as the fallback; kept in a small cache file (routes.json), not in backups.
 */
object PastRoutes {
    /** Past paths per [routeKey], as Compose state so the map redraws when they arrive. */
    val cache = mutableStateMapOf<String, Typical>()
    internal var file: File? = null
    private val lastTry = ConcurrentHashMap<String, Instant>()

    /** Planes seen flying the number, to read their trace once they've landed (an unfinished leg isn't kept). */
    private val hexes = ConcurrentHashMap<String, List<String>>()

    /** Loads the cache from files/routes.json, leaving out entries over 30 days old; call once at startup. */
    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "routes.json").also { file = it }
        if (!f.exists()) return
        runCatching {
            val j = JSONObject(f.readText())
            val old = Instant.now(clock).minus(Duration.ofDays(30))
            j.keys().forEach { k ->
                val e = j.getJSONObject(k)
                val legs = e.getJSONArray("legs").let { a -> (0 until a.length()).map { ptsFrom(a.getJSONArray(it)) } }
                val oldest = e.optString("oldest").takeIf { it.isNotEmpty() }?.let(java.time.LocalDate::parse)
                Instant.ofEpochMilli(e.getLong("at")).takeIf { it > old }?.let { cache[k] = Typical(it, legs, oldest) }
            }
        }
    }

    /** Writes the cache back (a temp file, then renamed over the old one). */
    private fun save() {
        val f = file ?: return
        val j = JSONObject()
        cache.forEach { (k, v) ->
            j.put(k, JSONObject().put("at", v.at.toEpochMilli()).put("legs", JSONArray(v.legs.map { it.toJson() })).put("oldest", v.oldest?.toString() ?: ""))
        }
        File(f.path + ".tmp").apply { writeText(j.toString()) }.renameTo(f)
    }

    /** Once a day per flight number (finished flights too), and after a miss not again for an hour. */
    fun due(f: Flight, now: Instant): Boolean = !f.cancelled &&
        cache[f.routeKey]?.let { Duration.between(it.at, now) >= Duration.ofDays(1) } != false &&
        lastTry[f.routeKey]?.let { Duration.between(it, now) >= Duration.ofHours(1) } != false

    /** Recent complete legs of this flight number (not this very flight), newest first. */
    suspend fun fetch(f: Flight): List<List<Pt>> = withContext(Dispatchers.IO) {
        val cs = callsign(f.airline, f.number)
        val seen = AdsbLol.hexesFlying(cs)
        val hex = (seen + hexes[f.routeKey].orEmpty()).distinct().take(2).also { hexes[f.routeKey] = it }
        val ours = (f.dep ?: f.depGuess).epochSecond
        val recent = hex.flatMap { h ->
            runCatching { AdsbLol.legsFrom(AdsbLol.trace(h), cs, f) }.getOrDefault(emptyList())
        }
        val found = (recent + earlierDays(f, cs)).filter { Math.abs(it.first().t - ours) > 3 * 3600 }
        // FlightAware (with a key) as the fallback: it keeps every flight's track for about ten days.
        (found.ifEmpty { FlightAware.pastTracks(f, ours) }).sortedByDescending { it.first().t }
    }

    /**
     * The same flight number on earlier days, which is usually another plane: AeroDataBox (with a key) names the plane
     * that flew it that day (its modeS hex), and adsb.lol's daily history has that plane's path. One lookup a day,
     * three with "All recent routes"; nothing without a key.
     */
    private suspend fun earlierDays(f: Flight, cs: String): List<List<Pt>> {
        val key = Prefs.adbKey.takeIf { it.isNotBlank() } ?: return emptyList()
        // A queue worked through a little at a time: a week back the first time; then each day the latest day plus
        // a few older ones, until there are MAX_PAST paths or two months are covered.
        val c = cache[f.routeKey]
        val days = (if (c?.oldest == null) (1..FIRST_DAYS).map { f.date.minusDays(it) }
            else listOf(f.date.minusDays(1)) +
                (if (c.legs.size < MAX_PAST) (1..3L).map { c.oldest.minusDays(it) }.filter { it >= f.date.minusDays(60) } else emptyList())).distinct()
        listOfNotNull(days.minOrNull(), c?.oldest).minOrNull()?.let { scanned[f.routeKey] = it }
        return days.flatMapIndexed { i, day ->
            if (i > 0) kotlinx.coroutines.delay(1100) // AeroDataBox's free plan allows about one request a second
            runCatching {
                Lookups.count()
                val legs = JSONArray(AeroDataBox.flightsOn(f, day, key).ifBlank { "[]" })
                val leg = (0 until legs.length()).map { legs.getJSONObject(it) }
                    .firstOrNull { it.optJSONObject("departure")?.optJSONObject("airport")?.optString("iata") == f.from }
                val hex = leg?.optJSONObject("aircraft")?.optString("modeS")?.lowercase()?.takeIf { it.length == 6 } ?: return@runCatching emptyList()
                // History files are per UTC day; a flight can run into the next one.
                listOf(day, day.plusDays(1)).flatMap { d ->
                    runCatching {
                        AdsbLol.legsFrom(AdsbLol.history(d, hex), cs, f)
                    }.getOrDefault(emptyList())
                }.take(1)
            }.getOrDefault(emptyList())
        }
    }
    private val updating = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val scanned = ConcurrentHashMap<String, java.time.LocalDate>()

    /**
     * For the flight on screen only, with the Past routes layer on: fetches recent paths when [due] ([force] = pull to
     * refresh) and adds them to the cache (newest [MAX_PAST] kept). One fetch per route at a time.
     */
    suspend fun update(f: Flight, force: Boolean = false) {
        val now = Instant.now(clock)
        if (!Layers.routes || !(if (force) !f.cancelled else due(f, now))) return
        if (!updating.add(f.routeKey)) return // already fetching (opening the page and a pull at once)
        try {
            lastTry[f.routeKey] = now
            val legs = runCatching { Layers.loading(Layers.ROUTES) { fetch(f) } }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                val old = cache[f.routeKey]?.legs.orEmpty()
                // Remembered even when nothing new turned up, so the next look only covers the latest day.
                cache[f.routeKey] = Typical(
                    now, (legs + old).distinctBy { it.first().t / 600 }.sortedByDescending { it.first().t }.take(MAX_PAST),
                    scanned.remove(f.routeKey) ?: cache[f.routeKey]?.oldest,
                )
                save()
            }
        } finally { updating.remove(f.routeKey) }
    }
}
