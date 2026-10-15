package com.aria.air.api

// FlightAware AeroAPI (your own key): status, gates, the tail number, the inbound plane and past tracks.

import com.aria.air.data.*
import com.aria.air.debug.*
import com.aria.air.util.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * FlightAware AeroAPI, with your own key (pay per call, small free allowance). Covers about ten days back to two days
 * ahead. Its gates are fresher at US airports and it knows the plane's previous flight even while that plane sits at a
 * gate with its transponder off. Requests send a flight number or FlightAware's own flight id.
 */
object FlightAware {
    /** Status from FlightAware AeroAPI. Only covers roughly 10 days back to 2 days ahead. */
    suspend fun status(f: Flight, key: String): Flight? = withContext(Dispatchers.IO) {
        val start = f.date.minusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        val end = f.date.plusDays(2).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        val body = Http.get(
            "https://aeroapi.flightaware.com/aeroapi/flights/${callsign(f.airline, f.number)}?start=$start&end=$end",
            mapOf("x-apikey" to key),
        )
        Debug.status(f, body)
        val arr = JSONObject(body).optJSONArray("flights") ?: return@withContext null
        val legs = (0 until arr.length()).map { arr.getJSONObject(it) }
        fun iata(j: JSONObject, k: String) = j.optJSONObject(k)?.optString("code_iata").orEmpty()
        fun t(j: JSONObject, vararg keys: String) = keys.firstNotNullOfOrNull { k ->
            j.optString(k).takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        }
        // Same origin, departing on the flight's local date.
        val leg = legs.firstOrNull { j ->
            val out = t(j, "scheduled_out", "scheduled_off") ?: return@firstOrNull false
            (f.from.isEmpty() || iata(j, "origin") == f.from) && out.atZone(f.fromTz).toLocalDate() == f.date
        } ?: return@withContext null
        fun s(k: String) = leg.optString(k).takeIf { it != "null" }.orEmpty()
        f.copy(
            from = iata(leg, "origin").ifEmpty { f.from }, to = iata(leg, "destination").ifEmpty { f.to },
            depSched = t(leg, "scheduled_out", "scheduled_off") ?: f.depSched,
            depEst = t(leg, "estimated_out", "estimated_off"), depActual = t(leg, "actual_out", "actual_off"),
            arrSched = t(leg, "scheduled_in", "scheduled_on") ?: f.arrSched,
            arrEst = t(leg, "estimated_in", "estimated_on"), arrActual = t(leg, "actual_in", "actual_on"),
            depTerminal = s("terminal_origin"), depGate = s("gate_origin"),
            arrTerminal = s("terminal_destination"), arrGate = s("gate_destination"), baggage = s("baggage_claim"),
            aircraft = s("aircraft_type").ifEmpty { f.aircraft }, reg = s("registration").ifEmpty { f.reg },
            cancelled = leg.optBoolean("cancelled"), diverted = leg.optBoolean("diverted"),
            inbound = s("inbound_fa_flight_id").takeIf { it.isNotEmpty() && soon(f) }
                ?.let { id -> runCatching { inbound(f, id, key) }.getOrNull() } ?: f.inbound,
        )
    }

    /** Within 12 h of departure: only then is the inbound plane worth an extra paid lookup. */
    private fun soon(f: Flight) = Duration.between(Instant.now(clock), f.dep ?: f.depGuess) < Duration.ofHours(12)

    /**
     * The plane's previous flight from FlightAware ([id] is its inbound_fa_flight_id), as an [Inbound]. Unlike ADS-B,
     * this knows about a plane parked at the gate with its transponder off: whether it has left, when it gets in, and
     * when it landed. Its position is placed at an airport, or along the route by FlightAware's progress.
     */
    suspend fun inbound(f: Flight, id: String, key: String): Inbound? = withContext(Dispatchers.IO) {
        Lookups.count()
        val j = JSONObject(Http.get("https://aeroapi.flightaware.com/aeroapi/flights/$id", mapOf("x-apikey" to key)))
            .optJSONArray("flights")?.optJSONObject(0) ?: return@withContext null
        inboundFrom(f, j, Instant.now(clock))
    }

    /** [j]: a FlightAware flight object for the plane's previous flight. Pure, for tests. */
    fun inboundFrom(f: Flight, j: JSONObject, now: Instant): Inbound? {
        fun t(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
            j.optString(k).takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        }
        fun iata(k: String) = j.optJSONObject(k)?.optString("code_iata").orEmpty()
        val from = iata("origin"); val to = iata("destination")
        val a = Db.airport(from); val b = Db.airport(to) ?: return null
        val left = t("actual_off", "actual_out")
        val landed = t("actual_in", "actual_on")
        val progress = j.optInt("progress_percent", 0).coerceIn(0, 100) / 100.0
        val (lat, lon) = when {
            landed != null || a == null -> b.lat to b.lon
            left == null -> a.lat to a.lon
            else -> a.lat + (b.lat - a.lat) * progress to a.lon + (b.lon - a.lon) * progress
        }
        return Inbound(
            callsign = j.optString("ident_icao").ifEmpty { j.optString("ident") }, from = from, to = to,
            lat = lat, lon = lon, altFt = 0, speedKt = 0, onGround = landed != null || left == null,
            eta = if (landed == null && left != null) t("estimated_in", "estimated_on", "scheduled_in") else null,
            at = now, landedAt = landed?.takeIf { to == f.from },
        )
    }

    /** Checks an AeroAPI key with a cheap call. Returns null when it works, else the problem. */
    suspend fun testKey(key: String): String? = withContext(Dispatchers.IO) {
        try {
            Http.get("https://aeroapi.flightaware.com/aeroapi/airports/KSFO", mapOf("x-apikey" to key)); null
        } catch (e: HttpError) {
            if (e.code == 401 || e.code == 403) "Key rejected (${e.code})" else e.message
        } catch (e: Exception) {
            e.message ?: "No connection"
        }
    }

    /**
     * Recent runs of the flight number on this route from FlightAware: its list of the last days' flights (one request),
     * then the track of up to one (three with "All recent routes") finished one that isn't ours.
     */
    fun pastTracks(f: Flight, ours: Long): List<List<Pt>> {
        val key = Prefs.aeroKey.takeIf { it.isNotBlank() } ?: return emptyList()
        val head = mapOf("x-apikey" to key)
        return runCatching {
            Lookups.count()
            val list = JSONObject(Http.get("https://aeroapi.flightaware.com/aeroapi/flights/${callsign(f.airline, f.number)}", head))
                .optJSONArray("flights") ?: return@runCatching emptyList()
            val done = (0 until list.length()).map { list.getJSONObject(it) }.filter { j ->
                j.optJSONObject("origin")?.optString("code_iata") == f.from && j.optJSONObject("destination")?.optString("code_iata") == f.to &&
                    j.optString("actual_on").let { it.isNotEmpty() && it != "null" } &&
                    runCatching { Math.abs(Instant.parse(j.getString("scheduled_out")).epochSecond - ours) > 3 * 3600 }.getOrDefault(false)
            }
            done.take(MAX_PAST).mapNotNull { j ->
                runCatching {
                    Lookups.count()
                    val pos = JSONObject(Http.get("https://aeroapi.flightaware.com/aeroapi/flights/${j.getString("fa_flight_id")}/track", head))
                        .getJSONArray("positions")
                    thin((0 until pos.length()).map { pos.getJSONObject(it) }.map { p ->
                        Pt(p.getDouble("latitude"), p.getDouble("longitude"), p.optInt("altitude") * 100, Instant.parse(p.getString("timestamp")).epochSecond)
                    })
                }.getOrNull()?.takeIf { it.size >= 2 }
            }
        }.getOrDefault(emptyList())
    }

    /** The aircraft [reg]'s flights over the last week, newest first (one paid call; FlightAware keeps about ten days). */
    fun tailLegs(reg: String, key: String): List<TailLeg> {
        val now = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        return parseLegs(Http.get(
            "https://aeroapi.flightaware.com/aeroapi/flights/${AeroDataBox.tailKey(reg)}?ident_type=registration" +
                "&start=${now.minus(Duration.ofDays(6))}&end=${now.plus(Duration.ofDays(1))}",
            mapOf("x-apikey" to key),
        ))
    }

    /** FlightAware's flight list as [TailLeg]s, newest first. */
    internal fun parseLegs(body: String): List<TailLeg> {
        val arr = JSONObject(body).optJSONArray("flights") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { j ->
            fun t(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
                j.optString(k).takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            }
            fun iata(k: String) = j.optJSONObject(k)?.optString("code_iata")?.takeIf { it != "null" }.orEmpty()
            TailLeg(
                flight = j.optString("ident_iata").takeIf { it.isNotEmpty() && it != "null" } ?: j.optString("ident"),
                from = iata("origin"), to = iata("destination"),
                depSched = t("scheduled_out", "scheduled_off"), depActual = t("actual_out", "actual_off"),
                arrSched = t("scheduled_in", "scheduled_on"), arrActual = t("actual_in", "actual_on"),
                cancelled = j.optBoolean("cancelled"),
            )
        }.sortedByDescending { it.depSched }
    }
}
