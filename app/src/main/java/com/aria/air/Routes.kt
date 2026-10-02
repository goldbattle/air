package com.aria.air

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Flown paths: the breadcrumb track Air records while a flight is live (kept on the Flight, so it's in backups), and
 * recent paths flown under the same flight number from adsb.lol's public trace files (a small cache file). Requests send
 * only the callsign and the aircraft's ICAO hex.
 */

/** One breadcrumb: position, altitude and time (epoch seconds). */
data class Pt(val lat: Double, val lon: Double, val altFt: Int = 0, val t: Long = 0)

const val TRACK_GAP = 120L // seconds between kept points
const val TRACK_MAX = 300

/** [p] appended if it's [TRACK_GAP] after the last point; past [TRACK_MAX] points every other one is dropped. */
fun List<Pt>.add(p: Pt): List<Pt> {
    if (lastOrNull()?.let { p.t - it.t < TRACK_GAP } == true) return this
    val out = this + p
    return if (out.size > TRACK_MAX) out.filterIndexed { i, _ -> i % 2 == 0 || i == out.lastIndex } else out
}

fun thin(pts: List<Pt>): List<Pt> = pts.fold(emptyList()) { acc, p -> acc.add(p) }

/** Adds an airborne fix to the recorded track. */
fun Flight.record(l: Live): Flight = copy(
    hex = l.hex.ifEmpty { hex }, track = if (l.onGround) track else track.add(Pt(l.lat, l.lon, l.altFt, l.at.epochSecond)),
)

/** Both copies' points, for saves racing each other (the worker and the open flight page). */
fun mergeTracks(a: List<Pt>, b: List<Pt>): List<Pt> = if (a == b || a.isEmpty()) b else if (b.isEmpty()) a else
    thin((a + b).distinctBy { it.t }.sortedBy { it.t })

// ---------- JSON ----------

private fun r4(x: Double) = Math.round(x * 1e4) / 1e4

/** Flat [lat, lon, alt, t, lat, lon, ...], about 25 bytes a point. */
fun List<Pt>.toJson() = JSONArray().also { a -> forEach { a.put(r4(it.lat)).put(r4(it.lon)).put(it.altFt).put(it.t) } }

fun ptsFrom(a: JSONArray?): List<Pt> = if (a == null) emptyList() else
    (0 until a.length() / 4).map { i -> Pt(a.getDouble(4 * i), a.getDouble(4 * i + 1), a.getInt(4 * i + 2), a.getLong(4 * i + 3)) }

fun JSONObject.putTrack(f: Flight): JSONObject = apply {
    if (f.track.isNotEmpty()) put("track", f.track.toJson())
    if (f.hex.isNotEmpty()) put("hex", f.hex)
    f.trackAt?.let { put("trackAt", it.toEpochMilli()) }
}

fun Flight.withTrack(j: JSONObject) = copy(
    track = ptsFrom(j.optJSONArray("track")), hex = j.optString("hex"),
    trackAt = if (j.has("trackAt")) Instant.ofEpochMilli(j.getLong("trackAt")) else null,
)

// ---------- What the map draws ----------

/** Faint past paths, the planned arc (or not), the flown path solid, and the rest of the way from the plane, dashed. */
data class MapPaths(val typical: List<List<Pt>>, val arc: Boolean, val flown: List<Pt>, val rest: List<Pt>)

val Flight.routeKey get() = "$code $from-$to"

/** The path actually flown: from the origin through the track, to the plane or (landed) the destination. */
fun Flight.flownPath(landed: Boolean): List<Pt> {
    val o = origin ?: return emptyList(); val d = dest ?: return emptyList()
    if (track.isEmpty()) return emptyList()
    val tip = if (landed) Pt(d.lat, d.lon) else live?.takeIf { isAirborne }?.let { Pt(it.lat, it.lon) }
    return listOf(Pt(o.lat, o.lon)) + track + listOfNotNull(tip)
}

/**
 * Completed: the recorded path, or the arc without one. Upcoming or flying: up to [max] recent paths of this flight
 * number ([fetched] from adsb.lol, then my own recorded past flights of it, or of the route), the planned arc, and once
 * there's a track, the flown part solid with the rest of the arc ahead of the plane.
 */
fun Flight.paths(fetched: List<List<Pt>>, others: List<Flight>, max: Int, now: Instant = Instant.now(clock)): MapPaths {
    val landed = arrActual != null || phase(now) >= Phase.Arrival
    val flown = flownPath(landed)
    if (landed) return MapPaths(emptyList(), flown.isEmpty(), flown, emptyList())
    val mine = others.filter { it.id != id && it.track.isNotEmpty() && it.phase(now) == Phase.Done && (it.code == code || it.from == from && it.to == to) }
        .sortedWith(compareByDescending<Flight> { it.code == code }.thenByDescending { it.track.last().t }).map { it.flownPath(true) }
    val typical = (fetched + mine).take(max)
    val d = dest
    val rest = if (flown.isEmpty() || d == null) emptyList() else
        flown.last().let { p -> greatCircle(p.lat, p.lon, d.lat, d.lon, 32).map { Pt(it.first, it.second) } }
    return MapPaths(typical, flown.isEmpty(), flown, rest)
}

/** [paths] for the map: past paths per the Layers toggles (one by default, up to 3 with "all"). */
fun Flight.mapPaths(): MapPaths = paths(
    Routes.cache[routeKey]?.legs.orEmpty(), Store.flights,
    if (!Prefs.flag("layerRoutes")) 0 else if (Prefs.flag("layerRoutesAll", false)) 3 else 1,
)

/** [pts] as (lat, lon) with longitudes unwrapped to stay continuous, starting near [ref]. */
fun unwrap(pts: List<Pt>, ref: Double): List<Pair<Double, Double>> {
    var prev = ref
    return pts.map { p ->
        var lon = p.lon
        while (lon - prev > 180) lon -= 360
        while (lon - prev < -180) lon += 360
        prev = lon
        p.lat to lon
    }
}

// ---------- Recent paths from adsb.lol ----------

/** Recent paths flown under a flight number, as fetched [at]. */
data class Typical(val at: Instant, val legs: List<List<Pt>>)

object Routes {
    val cache = mutableStateMapOf<String, Typical>()
    internal var file: File? = null
    private val lastTry = ConcurrentHashMap<String, Instant>()
    /** Planes seen flying the number, to read their trace once they've landed (an unfinished leg isn't kept). */
    private val hexes = ConcurrentHashMap<String, List<String>>()

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "routes.json").also { file = it }
        if (!f.exists()) return
        runCatching {
            val j = JSONObject(f.readText())
            val old = Instant.now(clock).minus(Duration.ofDays(30))
            j.keys().forEach { k ->
                val e = j.getJSONObject(k)
                val legs = e.getJSONArray("legs").let { a -> (0 until a.length()).map { ptsFrom(a.getJSONArray(it)) } }
                Instant.ofEpochMilli(e.getLong("at")).takeIf { it > old }?.let { cache[k] = Typical(it, legs) }
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val j = JSONObject()
        cache.forEach { (k, v) -> j.put(k, JSONObject().put("at", v.at.toEpochMilli()).put("legs", JSONArray(v.legs.map { it.toJson() }))) }
        File(f.path + ".tmp").apply { writeText(j.toString()) }.renameTo(f)
    }

    /** Only for flights still to fly, once a day per flight number, and after a miss not again for an hour. */
    fun due(f: Flight, now: Instant): Boolean = !f.cancelled && f.phase(now) < Phase.Arrival &&
        cache[f.routeKey]?.let { Duration.between(it.at, now) >= Duration.ofDays(1) } != false &&
        lastTry[f.routeKey]?.let { Duration.between(it, now) >= Duration.ofHours(1) } != false

    /**
     * A readsb trace file (adsb.lol): rows of [secs after "timestamp", lat, lon, alt ft or "ground", gs, track, flags
     * (2 = new leg), vrate, {flight: callsign} now and then, ...]. As (callsign so far, new leg, airborne point or null).
     */
    private fun rows(json: String): List<Triple<String, Boolean, Pt?>> {
        val j = JSONObject(json)
        val t0 = j.getDouble("timestamp")
        val tr = j.getJSONArray("trace")
        var call = ""
        return (0 until tr.length()).map { i ->
            val p = tr.getJSONArray(i)
            val c = p.optJSONObject(8)?.optString("flight")?.trim().orEmpty()
            val changed = c.isNotEmpty() && c != call
            if (changed) call = c
            val pt = (p.opt(3) as? Number)?.let { Pt(p.getDouble(1), p.getDouble(2), it.toInt(), (t0 + p.getDouble(0)).toLong()) }
            Triple(call, changed || p.optInt(6) and 2 != 0, pt)
        }
    }

    /** Airborne points in a trace file. */
    fun points(json: String): List<Pt> = rows(json).mapNotNull { it.third }

    /**
     * Legs flown under [cs] from [f]'s origin to its destination in a trace_full file (about the last day of the plane),
     * each thinned like a recorded track. Only complete ones: airborne near both airports.
     */
    fun legsFrom(json: String, cs: String, f: Flight): List<List<Pt>> {
        val o = f.origin ?: return emptyList(); val d = f.dest ?: return emptyList()
        val legs = mutableListOf<Pair<String, MutableList<Pt>>>()
        for ((c, newLeg, p) in rows(json)) {
            if (legs.isEmpty() || newLeg) legs += c to mutableListOf()
            if (p != null) legs.last().second += p
        }
        return legs.filter { (c, pts) ->
            c == cs && pts.size >= 2 && distKm(pts.first().lat, pts.first().lon, o.lat, o.lon) < 150 &&
                distKm(pts.last().lat, pts.last().lon, d.lat, d.lon) < 150
        }.map { thin(it.second) }
    }

    /** Recent complete legs of this flight number (not this very flight), newest first. */
    suspend fun fetch(f: Flight): List<List<Pt>> = withContext(Dispatchers.IO) {
        val cs = Api.callsign(f.airline, f.number)
        val ac = runCatching { JSONObject(Api.get("https://api.adsb.lol/v2/callsign/$cs")).optJSONArray("ac") }.getOrNull()
        val seen = (0 until (ac?.length() ?: 0)).map { ac!!.getJSONObject(it).optString("hex").lowercase() }
            .filter { it.length == 6 && !it.startsWith("~") }
        val hex = (seen + hexes[f.routeKey].orEmpty()).distinct().take(2).also { hexes[f.routeKey] = it }
        val ours = (f.dep ?: f.depGuess).epochSecond
        hex.flatMap { h ->
            runCatching { legsFrom(Api.get("https://adsb.lol/data/traces/${h.takeLast(2)}/trace_full_$h.json"), cs, f) }.getOrDefault(emptyList())
        }.filter { Math.abs(it.first().t - ours) > 3 * 3600 }.sortedByDescending { it.first().t }
    }

    /** For the flight on screen only: fetches recent paths when [due] ([force] = the refresh button) and caches them. */
    suspend fun update(f: Flight, force: Boolean = false) {
        val now = Instant.now(clock)
        if (!(if (force) !f.cancelled && f.phase(now) < Phase.Arrival else due(f, now))) return
        lastTry[f.routeKey] = now
        val legs = runCatching { fetch(f) }.getOrDefault(emptyList())
        if (legs.isEmpty()) return
        withContext(Dispatchers.Main) {
            val old = cache[f.routeKey]?.legs.orEmpty()
            cache[f.routeKey] = Typical(now, (legs + old).distinctBy { it.first().t / 600 }.sortedByDescending { it.first().t }.take(3))
            save()
        }
    }

    // ---------- This flight's own full path ----------

    private val trackTries = ConcurrentHashMap<String, Pair<Instant, Int>>()

    private fun landedAt(f: Flight, now: Instant): Instant? = f.arrActual ?: (f.arr ?: f.arrGuess).takeIf { f.phase(now) >= Phase.Arrival }

    /**
     * Whether to read the flight's path from its plane's trace: on the refresh button ([auto] false) while flying or
     * after landing (within adsb.lol's ~30 days of history), until a complete one was read. Automatically: once it's
     * seen in the air without its start (Air began watching mid-flight), and 15 min to a day after landing; at most 3
     * tries, 20 min apart.
     */
    fun trackDue(f: Flight, now: Instant, auto: Boolean): Boolean {
        if (f.trackAt != null || f.cancelled) return false
        val o = f.origin ?: return false
        val landed = landedAt(f, now)
        if (landed == null && f.phase(now) != Phase.InFlight) return false
        if (landed != null && Duration.between(landed, now) > Duration.ofDays(30)) return false
        if (!auto) return true
        if (landed == null && (f.track.isEmpty() || distKm(f.track[0].lat, f.track[0].lon, o.lat, o.lon) < 100)) return false
        if (landed != null && Duration.between(landed, now).let { it < Duration.ofMinutes(15) || it > Duration.ofDays(1) }) return false
        val (at, n) = trackTries[f.id] ?: return true
        return n < 3 && Duration.between(at, now) >= Duration.ofMinutes(20)
    }

    /** [f] with its path filled from the plane's adsb.lol trace when [trackDue]; [f] unchanged when that fails. */
    suspend fun fillTrack(f: Flight, auto: Boolean): Flight {
        val now = Instant.now(clock)
        if (!trackDue(f, now, auto)) return f
        trackTries[f.id] = now to ((trackTries[f.id]?.second ?: 0) + 1)
        return runCatching { withContext(Dispatchers.IO) { readTrack(f, now) } }.getOrNull() ?: f
    }

    /**
     * The plane's airborne points between takeoff and landing (or now), on the route: today's trace_full while it's
     * recent, else the daily history files (UTC days). Complete (near both airports) marks the path done.
     */
    private fun readTrack(f: Flight, now: Instant): Flight? {
        val o = f.origin ?: return null; val d = f.dest ?: return null
        val hex = f.hex.ifEmpty { lookupHex(f) }.lowercase().takeIf { it.length == 6 } ?: return null
        val from = (f.tookOff ?: f.dep ?: f.depGuess).minus(Duration.ofMinutes(20))
        val landed = landedAt(f, now)
        val to = (landed ?: now).plus(Duration.ofMinutes(20))
        val file = "${hex.takeLast(2)}/trace_full_$hex.json"
        val urls = if (Duration.between(from, now) < Duration.ofHours(20)) listOf("https://adsb.lol/data/traces/$file")
            else generateSequence(from.atZone(ZoneOffset.UTC).toLocalDate()) { it.plusDays(1) }
                .takeWhile { !it.isAfter(to.atZone(ZoneOffset.UTC).toLocalDate()) }
                .map { "https://adsb.lol/globe_history/${it.toString().replace('-', '/')}/traces/$file" }.toList()
        val total = distKm(o, d)
        val pts = urls.flatMap { runCatching { points(Api.get(it)) }.getOrDefault(emptyList()) }
            .filter { it.t in from.epochSecond..to.epochSecond && distKm(o.lat, o.lon, it.lat, it.lon) + distKm(it.lat, it.lon, d.lat, d.lon) < total * 1.25 + 150 }
            .sortedBy { it.t }
        if (pts.size < 2) return null
        val done = landed != null && distKm(pts.first().lat, pts.first().lon, o.lat, o.lon) < 150 &&
            distKm(pts.last().lat, pts.last().lon, d.lat, d.lon) < 150
        return f.copy(hex = hex, track = mergeTracks(f.track, thin(pts)), trackAt = if (done) now else null)
    }

    /** The plane's ICAO hex from adsb.lol: by tail number, or by callsign while it still broadcasts it. */
    private fun lookupHex(f: Flight): String {
        val url = if (f.reg.isNotEmpty()) "https://api.adsb.lol/v2/reg/${f.reg}" else "https://api.adsb.lol/v2/callsign/${Api.callsign(f.airline, f.number)}"
        return runCatching { JSONObject(Api.get(url)).optJSONArray("ac")?.optJSONObject(0)?.optString("hex") }.getOrNull().orEmpty()
    }
}
