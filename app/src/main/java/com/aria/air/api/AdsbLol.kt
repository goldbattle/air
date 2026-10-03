package com.aria.air.api

// adsb.lol: free, open ADS-B data. Routes by flight number, live positions, and the planes' traces.

import com.aria.air.data.*
import com.aria.air.util.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * adsb.lol (free, open data, no key). It sees a plane only while its transponder is on, so a plane parked at the gate
 * with it off is invisible here; its trace files fill that gap after the fact. Requests send only a callsign, a tail
 * number or an aircraft's ICAO hex.
 */
object AdsbLol {
    /** Airports the flight number serves, in order ("PIT", "SFO"), or empty when unknown. Multi-stop flights give 3+. */
    suspend fun route(airline: String, number: String): List<String> = withContext(Dispatchers.IO) {
        val cs = callsign(airline, number)
        runCatching {
            JSONObject(Http.get("https://vrs-standing-data.adsb.lol/routes/${cs.take(2)}/$cs.json"))
                .optString("_airport_codes_iata").split("-").filter { it.length == 3 }
        }.getOrDefault(emptyList())
    }

    /** Latest ADS-B fix for the flight's callsign, if the plane is broadcasting and plausibly on this route. */
    suspend fun live(f: Flight): Live? = withContext(Dispatchers.IO) {
        val body = runCatching { Http.get("https://api.adsb.lol/v2/callsign/${callsign(f.airline, f.number)}") }.getOrNull()
            ?: return@withContext null
        val ac = JSONObject(body).optJSONArray("ac") ?: return@withContext null
        val now = Instant.now(clock)
        (0 until ac.length()).map { ac.getJSONObject(it) }.filter { it.has("lat") }.map { a ->
            val alt = a.opt("alt_baro")
            Live(
                lat = a.getDouble("lat"), lon = a.getDouble("lon"),
                altFt = (alt as? Number)?.toInt() ?: 0, speedKt = a.optDouble("gs", 0.0).toInt(),
                track = a.optDouble("track", 0.0), vrateFpm = a.optInt("baro_rate"),
                at = now.minusMillis((a.optDouble("seen_pos", 0.0) * 1000).toLong()),
                onGround = alt == "ground", aircraft = a.optString("t"), reg = a.optString("r"), hex = a.optString("hex"),
            )
        }.firstOrNull { plausible(f, it) }
    }

    /**
     * Where the plane assigned to [f] is now (adsb.lol, by tail number) and when it reaches our departure airport.
     * Null when it isn't broadcasting (parked with transponder off) or is already flying our flight.
     */
    suspend fun inbound(f: Flight): Inbound? = withContext(Dispatchers.IO) {
        val o = f.origin ?: return@withContext null
        val ac = JSONObject(Http.get("https://api.adsb.lol/v2/reg/${f.reg}")).optJSONArray("ac") ?: return@withContext null
        val a = (0 until ac.length()).map { ac.getJSONObject(it) }.firstOrNull { it.has("lat") } ?: return@withContext null
        val cs = a.optString("flight").trim()
        val alt = a.opt("alt_baro")
        val ground = alt == "ground"
        val lat = a.getDouble("lat"); val lon = a.getDouble("lon")
        val gs = a.optDouble("gs", 0.0)
        val at = Instant.now(clock).minusMillis((a.optDouble("seen_pos", 0.0) * 1000).toLong())
        // Same flight number: either it's already flying our flight, or (numbers flown as several legs a day) it's the
        // leg before ours, coming into our airport. The clock tells which.
        val sameNumber = cs == callsign(f.airline, f.number)
        // Airborne and fitting our schedule: it's already flying our flight (the live view covers that). On the
        // ground at our gate before we leave, it's simply our plane waiting, which is what this section shows.
        if (sameNumber && !ground && plausible(f, Live(lat, lon, 0, gs.toInt(), 0.0, 0, at, ground))) return@withContext null
        val route = if (cs.isNotEmpty() && !sameNumber) runCatching {
            JSONObject(Http.get("https://vrs-standing-data.adsb.lol/routes/${cs.take(2)}/$cs.json"))
                .optString("_airport_codes_iata").split("-").filter { it.length == 3 }
        }.getOrDefault(emptyList()) else emptyList()
        val headedHere = sameNumber || route.isEmpty() || route.last() == f.from
        // Straight line at current speed plus ~10 min for approach and taxi.
        val eta = if (!ground && headedHere && gs > 100) {
            at.plusSeconds((distKm(lat, lon, o.lat, o.lon) / (gs * 1.852) * 3600).toLong() + 600)
        } else null
        Inbound(cs, route.firstOrNull().orEmpty(), if (sameNumber) f.from else route.lastOrNull().orEmpty(), lat, lon, (alt as? Number)?.toInt() ?: 0, gs.toInt(), ground, eta, at)
    }

    /** ICAO hexes of the planes broadcasting [cs] right now (adsb.lol's live view); empty when none or offline. */
    fun hexesFlying(cs: String): List<String> {
        val ac = runCatching { JSONObject(Http.get("https://api.adsb.lol/v2/callsign/$cs")).optJSONArray("ac") }.getOrNull()
        return (0 until (ac?.length() ?: 0)).map { ac!!.getJSONObject(it).optString("hex").lowercase() }
            .filter { it.length == 6 && !it.startsWith("~") }
    }

    /** The plane's trace file for about the last day (readsb trace_full JSON); throws when there's none. */
    fun trace(hex: String): String = Http.get("https://adsb.lol/data/traces/${hex.takeLast(2)}/trace_full_$hex.json")

    /** The plane's trace for one UTC [day] from adsb.lol's daily history (kept about a month). */
    fun history(day: java.time.LocalDate, hex: String): String =
        Http.get("https://adsb.lol/globe_history/${day.toString().replace('-', '/')}/traces/${hex.takeLast(2)}/trace_full_$hex.json")

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

    /**
     * The plane's previous flight from its trace when it ended here: the last leg under another callsign before ours,
     * whose last airborne point is low and close to our origin (it landed; parked planes stop broadcasting). Free,
     * and it covers what live tracking can't: a plane already at the gate with its transponder off.
     */
    fun arrivedHere(json: String, f: Flight, now: Instant): Inbound? {
        val o = f.origin ?: return null
        val ours = callsign(f.airline, f.number)
        val dep = (f.dep ?: f.depGuess).epochSecond
        val legs = mutableListOf<Pair<String, MutableList<Pt>>>()
        for ((c, newLeg, p) in rows(json)) {
            if (legs.isEmpty() || newLeg) legs += c to mutableListOf()
            if (p != null) legs.last().second += p
        }
        val (call, pts) = legs.lastOrNull { (c, pts) -> c.isNotEmpty() && c != ours && pts.isNotEmpty() && pts.last().t < dep }
            ?: return null
        val last = pts.last()
        if (distKm(last.lat, last.lon, o.lat, o.lon) > 40 || last.altFt > 6000) return null
        val start = pts.first()
        val from = Db.airports.values.minByOrNull { distKm(it.lat, it.lon, start.lat, start.lon) }
            ?.takeIf { distKm(it.lat, it.lon, start.lat, start.lon) < 40 }?.iata.orEmpty()
        return Inbound(
            callsign = call, from = from, to = f.from, lat = o.lat, lon = o.lon, altFt = 0, speedKt = 0, onGround = true,
            eta = null, at = now, landedAt = Instant.ofEpochSecond(last.t + 180), // a few minutes from final approach
        )
    }

    /** [arrivedHere] for [f] from adsb.lol, by the plane's ICAO hex. */
    suspend fun inboundFromTrace(f: Flight): Inbound? = withContext(Dispatchers.IO) {
        val hex = f.hex.lowercase().takeIf { it.length == 6 } ?: return@withContext null
        arrivedHere(trace(hex), f, Instant.now(clock))
    }

    /** The plane's ICAO hex from adsb.lol: by tail number, or by callsign while it still broadcasts it. */
    fun lookupHex(f: Flight): String {
        val url = if (f.reg.isNotEmpty()) "https://api.adsb.lol/v2/reg/${f.reg}" else "https://api.adsb.lol/v2/callsign/${callsign(f.airline, f.number)}"
        return runCatching { JSONObject(Http.get(url)).optJSONArray("ac")?.optJSONObject(0)?.optString("hex") }.getOrNull().orEmpty()
    }
}
