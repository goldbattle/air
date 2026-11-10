package com.aria.air.data

// The JSON format of flights.json and backups (field names must never change).

import com.aria.air.util.*
import java.time.Instant
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** The instant stored under [k] as epoch millis, or null when absent. */
private fun JSONObject.inst(k: String): Instant? = if (has(k)) Instant.ofEpochMilli(getLong(k)) else null

/** Stores [v] as epoch millis, leaving the key out when null (keeps the file small). */
private fun JSONObject.putInst(k: String, v: Instant?) = apply { if (v != null) put(k, v.toEpochMilli()) }

/** Stores [v], leaving the key out when empty. */
private fun JSONObject.putStr(k: String, v: String) = apply { if (v.isNotEmpty()) put(k, v) }

/**
 * This flight as stored in flights.json and backups. Empty and null fields are left out. Field names must never change,
 * or older files and backups stop loading.
 */
fun Flight.toJson(): JSONObject = JSONObject()
    .put("id", id).put("airline", airline).put("number", number).put("date", date.toString())
    .put("from", from).put("to", to)
    .putInst("depSched", depSched).putInst("depEst", depEst).putInst("depActual", depActual)
    .putInst("arrSched", arrSched).putInst("arrEst", arrEst).putInst("arrActual", arrActual)
    .putStr("depTerminal", depTerminal).putStr("depGate", depGate)
    .putStr("arrTerminal", arrTerminal).putStr("arrGate", arrGate).putStr("baggage", baggage)
    .putStr("aircraft", aircraft).putStr("reg", reg)
    .put("cancelled", cancelled).put("diverted", diverted)
    .putStr("seat", seat).putStr("booking", booking).putStr("notes", notes)
    .putStr("friend", friend).put("source", source).put("work", work).putInst("updated", updated).putInst("faAt", faAt).putStr("adbSaid", adbSaid).putStr("mismatch", mismatch).putStr("alerted", alerted)
    .apply {
        fun wx(w: Wx) = JSONObject().put("t", w.tempC).put("code", w.code).put("wind", w.windKmh)
            .put("at", w.at.toEpochMilli()).put("fetched", w.fetched.toEpochMilli())
        depWx?.let { put("depWx", wx(it)) }
        inbound?.let {
            put("inbound", JSONObject().put("callsign", it.callsign).put("from", it.from).put("to", it.to)
                .put("lat", it.lat).put("lon", it.lon).put("alt", it.altFt).put("gs", it.speedKt).put("ground", it.onGround)
                .put("at", it.at.toEpochMilli()).put("firstSeen", it.firstSeen.toEpochMilli())
                .apply { it.eta?.let { e -> put("eta", e.toEpochMilli()) }; it.landedAt?.let { l -> put("landedAt", l.toEpochMilli()) } })
        }
        arrWx?.let { put("arrWx", wx(it)) }
        routeWx?.let { put("routeWx", it.toJson()) }
        live?.let {
            put("live", JSONObject().put("lat", it.lat).put("lon", it.lon).put("alt", it.altFt).put("gs", it.speedKt)
                .put("track", it.track).put("vrate", it.vrateFpm).put("at", it.at.toEpochMilli()).put("ground", it.onGround)
                .put("aircraft", it.aircraft).put("reg", it.reg))
        }
    }
    .putTrack(this)

/**
 * Reads a flight written by [toJson], by this or any older version (missing fields get their defaults). Throws on a
 * record without the basics: id, number, date, airports.
 */
fun flightFrom(j: JSONObject) = Flight(
    id = j.getString("id"), airline = j.getString("airline"), number = j.getString("number"),
    date = LocalDate.parse(j.getString("date")), from = j.getString("from"), to = j.getString("to"),
    depSched = j.inst("depSched"), depEst = j.inst("depEst"), depActual = j.inst("depActual"),
    arrSched = j.inst("arrSched"), arrEst = j.inst("arrEst"), arrActual = j.inst("arrActual"),
    depTerminal = j.optString("depTerminal"), depGate = j.optString("depGate"),
    arrTerminal = j.optString("arrTerminal"), arrGate = j.optString("arrGate"), baggage = j.optString("baggage"),
    aircraft = j.optString("aircraft"), reg = j.optString("reg"),
    cancelled = j.optBoolean("cancelled"), diverted = j.optBoolean("diverted"),
    seat = j.optString("seat"), booking = j.optString("booking"), notes = j.optString("notes"),
    friend = j.optString("friend"), source = j.optString("source", "manual"), work = j.optBoolean("work"), updated = j.inst("updated"), faAt = j.inst("faAt"), adbSaid = j.optString("adbSaid"), mismatch = j.optString("mismatch"),
    alerted = j.optString("alerted"),
    depWx = j.optJSONObject("depWx")?.let(::wxFrom), arrWx = j.optJSONObject("arrWx")?.let(::wxFrom),
    routeWx = j.optJSONObject("routeWx")?.let { runCatching { routeWxFrom(it) }.getOrNull() },
    inbound = j.optJSONObject("inbound")?.let {
        Inbound(
            it.getString("callsign"), it.optString("from"), it.optString("to"), it.getDouble("lat"), it.getDouble("lon"),
            it.optInt("alt"), it.optInt("gs"), it.optBoolean("ground"),
            if (it.has("eta")) Instant.ofEpochMilli(it.getLong("eta")) else null, Instant.ofEpochMilli(it.getLong("at")),
            if (it.has("landedAt")) Instant.ofEpochMilli(it.getLong("landedAt")) else null,
            Instant.ofEpochMilli(it.optLong("firstSeen", it.getLong("at"))),
        )
    },
    live = j.optJSONObject("live")?.let {
        Live(it.getDouble("lat"), it.getDouble("lon"), it.getInt("alt"), it.getInt("gs"), it.getDouble("track"),
            it.optInt("vrate"), Instant.ofEpochMilli(it.getLong("at")), it.optBoolean("ground"),
            it.optString("aircraft"), it.optString("reg"))
    },
).withTrack(j)

/** Reads takeoff or landing weather written by [toJson]. */
private fun wxFrom(j: JSONObject) = Wx(
    j.getDouble("t"), j.getInt("code"), j.optDouble("wind", 0.0), Instant.ofEpochMilli(j.getLong("at")), Instant.ofEpochMilli(j.getLong("fetched")),
)

/** Rounded to 4 decimals (about 10 m): plenty for a map, and much smaller on disk. */
private fun r4(x: Double) = Math.round(x * 1e4) / 1e4

/** Flat [lat, lon, alt, t, lat, lon, ...], about 25 bytes a point. */
fun List<Pt>.toJson() = JSONArray().also { a -> forEach { a.put(r4(it.lat)).put(r4(it.lon)).put(it.altFt).put(it.t) } }

/** Reads a flat track array written by [List.toJson]; empty for null. */
fun ptsFrom(a: JSONArray?): List<Pt> = if (a == null) emptyList() else
    (0 until a.length() / 4).map { i -> Pt(a.getDouble(4 * i), a.getDouble(4 * i + 1), a.getInt(4 * i + 2), a.getLong(4 * i + 3)) }

/** Adds [f]'s flown path, its plane's hex, and when the path was completed, when there are any. */
fun JSONObject.putTrack(f: Flight): JSONObject = apply {
    if (f.track.isNotEmpty()) put("track", f.track.toJson())
    if (f.hex.isNotEmpty()) put("hex", f.hex)
    f.trackAt?.let { put("trackAt", it.toEpochMilli()) }
}

/** This flight with the path fields read back from [j]. */
fun Flight.withTrack(j: JSONObject) = copy(
    track = ptsFrom(j.optJSONArray("track")), hex = j.optString("hex"),
    trackAt = if (j.has("trackAt")) Instant.ofEpochMilli(j.getLong("trackAt")) else null,
)
