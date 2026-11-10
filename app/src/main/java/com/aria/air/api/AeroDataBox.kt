package com.aria.air.api

// AeroDataBox via RapidAPI (your own key): schedules, delays, gates, belts and the aircraft.

import com.aria.air.data.*
import com.aria.air.debug.*
import com.aria.air.util.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AeroDataBox through RapidAPI, with your own key (Settings › Data). Covers about a year back and a year ahead, so it
 * does the routine status lookups when its key is set. Requests send a flight number and a date.
 */
object AeroDataBox {
    private val adbTime = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mmX")

    /** RapidAPI's auth headers for AeroDataBox. */
    internal fun headers(key: String) = mapOf("X-RapidAPI-Key" to key, "X-RapidAPI-Host" to "aerodatabox.p.rapidapi.com")

    private fun iata(side: JSONObject?) = side?.optJSONObject("airport")?.optString("iata").orEmpty()

    /**
     * The leg that is [f]: departing on its date (another day's run of the same number says nothing about this one)
     * from its airport. A number flown elsewhere that day (DL 1111 LAX-DEN, when yours is BOS-SEA) isn't yours: null,
     * rather than taking over your route. Pure, for tests.
     */
    internal fun pickLeg(legs: org.json.JSONArray, f: Flight): JSONObject? {
        fun localDate(side: JSONObject?) = side?.optJSONObject("scheduledTime")?.optString("local")?.take(10)
        return (0 until legs.length()).map { legs.getJSONObject(it) }
            .filter { localDate(it.optJSONObject("departure")) == f.date.toString() }
            .firstOrNull { f.from.isEmpty() || iata(it.optJSONObject("departure")) == f.from }
    }

    /**
     * When [f]'s number flies that day but not from its airport: the routes it does fly ("LAX → DEN"), else null.
     * Kept on the flight and shown on its page, so a wrong number or date in an import gets noticed. Pure, for tests.
     */
    internal fun mismatch(legs: org.json.JSONArray, f: Flight): String? {
        fun localDate(side: JSONObject?) = side?.optJSONObject("scheduledTime")?.optString("local")?.take(10)
        return (0 until legs.length()).map { legs.getJSONObject(it) }
            .filter { localDate(it.optJSONObject("departure")) == f.date.toString() }
            .map { "${iata(it.optJSONObject("departure"))} → ${iata(it.optJSONObject("arrival"))}" }
            .distinct().joinToString(", ").ifEmpty { null }
            ?.also { Debug.note("${f.label} ${f.date}: flies $it, not ${f.from} → ${f.to}") }
    }

    /** Status from AeroDataBox (RapidAPI). Covers roughly a year back and a year ahead. */
    suspend fun status(f: Flight, key: String): Flight? = withContext(Dispatchers.IO) {
        val body = Http.get(
            "https://aerodatabox.p.rapidapi.com/flights/number/${f.airline}${f.number}/${f.date}" +
                "?withAircraftImage=false&withLocation=false&dateLocalRole=Departure",
            headers(key),
        )
        Debug.status(f, body)
        if (body.isBlank()) return@withContext null // 204: no such flight that day
        val legs = org.json.JSONArray(body)
        val leg = pickLeg(legs, f) ?: return@withContext mismatch(legs, f)?.let { f.copy(mismatch = it) }
        val dep = leg.optJSONObject("departure") ?: JSONObject()
        val arr = leg.optJSONObject("arrival") ?: JSONObject()
        fun t(side: JSONObject, k: String) = side.optJSONObject(k)?.optString("utc")?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { java.time.OffsetDateTime.parse(it, adbTime).toInstant() }.getOrNull() }
        fun s(side: JSONObject, k: String) = side.optString(k).takeIf { it != "null" }.orEmpty()
        val status = leg.optString("status")
        val departed = status in setOf("Departed", "EnRoute", "Approaching", "Arrived", "Diverted")
        val arrived = status == "Arrived"
        val arrEst = t(arr, "revisedTime") ?: t(arr, "predictedTime")
        val aircraft = leg.optJSONObject("aircraft")
        // AeroDataBox can hand back data an hour old near departure (missing a fresh delay); then its estimate isn't
        // good enough to call the flight on time.
        val asOf = leg.optString("lastUpdatedUtc").takeIf { it.isNotEmpty() }
            ?.let { runCatching { java.time.OffsetDateTime.parse(it, adbTime).toInstant() }.getOrNull() }
        val nowAdb = Instant.now(clock)
        val stale = asOf != null && Duration.between(asOf, nowAdb) > Duration.ofMinutes(30) &&
            Duration.between(nowAdb, t(dep, "scheduledTime") ?: f.depGuess) < Duration.ofHours(6)
        if (stale) Debug.note("${f.label}: AeroDataBox data is from $asOf, too old to trust its estimate")
        // Gates, baggage and the tail are FlightAware's once it gave them; an empty value never clears one.
        val faOwns = f.faAt != null && Duration.between(f.faAt, Instant.now(clock)) < FA_OWNS
        fun keep(old: String, new: String) = if (faOwns && old.isNotEmpty()) old else new.ifEmpty { old }
        // AeroDataBox changing its own answer is the sign of a real gate change; repeating an answer FlightAware
        // already overruled is just stale. A change gets FlightAware asked again straight away (secondOpinion).
        val said = "${s(dep, "gate")}|${s(arr, "gate")}"
        val disputed = faOwns && f.adbSaid.isNotEmpty() && said != f.adbSaid &&
            (s(dep, "gate").let { it.isNotEmpty() && it != f.depGate } || s(arr, "gate").let { it.isNotEmpty() && it != f.arrGate })
        if (disputed) Debug.note("${f.label}: AeroDataBox now says gates $said (was ${f.adbSaid}), asking FlightAware")
        f.copy(
            // The route is what you saved; a lookup only fills it in when it's missing.
            from = f.from.ifEmpty { iata(dep) }, to = f.to.ifEmpty { iata(arr) },
            depSched = t(dep, "scheduledTime") ?: f.depSched,
            // Stale data still counts when it shows a delay; it just can't vouch for "on time".
            depEst = t(dep, "revisedTime")?.takeIf { e -> !stale || t(dep, "scheduledTime")?.let { e > it } == true },
            depActual = if (departed) t(dep, "runwayTime") ?: t(dep, "revisedTime") ?: f.depActual else null,
            arrSched = t(arr, "scheduledTime") ?: f.arrSched, arrEst = arrEst,
            arrActual = if (arrived) t(arr, "revisedTime") ?: t(arr, "runwayTime") ?: arrEst ?: f.arrSched else null,
            depTerminal = keep(f.depTerminal, s(dep, "terminal")), depGate = keep(f.depGate, s(dep, "gate")),
            arrTerminal = keep(f.arrTerminal, s(arr, "terminal")), arrGate = keep(f.arrGate, s(arr, "gate")),
            baggage = keep(f.baggage, s(arr, "baggageBelt")),
            aircraft = aircraft?.optString("model")?.takeIf { it.isNotEmpty() } ?: f.aircraft,
            reg = keep(f.reg, aircraft?.optString("reg").orEmpty()),
            hex = aircraft?.optString("modeS")?.lowercase()?.takeIf { it.length == 6 } ?: f.hex,
            cancelled = status.startsWith("Cancel"), diverted = status == "Diverted",
            // Ours leaves from the right airport but lands somewhere else: still ours, but say so.
            mismatch = iata(arr).takeIf { f.to.isNotEmpty() && it.isNotEmpty() && it != f.to && status != "Diverted" }
                ?.let { "${f.from} → $it" }.orEmpty(),
            adbSaid = said, disputed = disputed,
        )
    }

    /**
     * Checks an AeroDataBox key with one cheap lookup. Returns null when it works, else what's wrong in words (rejected
     * key, quota used up, no connection).
     */
    suspend fun testKey(key: String): String? = withContext(Dispatchers.IO) {
        try {
            Http.get("https://aerodatabox.p.rapidapi.com/flights/number/UA900/${localToday()}?withAircraftImage=false&withLocation=false", headers(key)); null
        } catch (e: HttpError) {
            when (e.code) {
                401, 403 -> "Key rejected (${e.code}): subscribe to AeroDataBox on RapidAPI"
                429 -> "Monthly quota used up"
                else -> e.message
            }
        } catch (e: Exception) {
            e.message ?: "No connection"
        }
    }

    /** The raw list of [f]'s flight number on [day] (empty body when it didn't fly), to learn which plane flew it. */
    fun flightsOn(f: Flight, day: java.time.LocalDate, key: String): String = Http.get(
        "https://aerodatabox.p.rapidapi.com/flights/number/${f.airline}${f.number}/$day?withAircraftImage=false&withLocation=false&dateLocalRole=Departure",
        headers(key),
    )

    /** The airframe behind tail number [reg]: model, age, seats, engines (one TIER 1 call). Null when unknown. */
    fun aircraft(reg: String, key: String): TailInfo? = try {
        Http.get("https://aerodatabox.p.rapidapi.com/aircrafts/reg/${tailKey(reg)}", headers(key))
            .takeIf { it.isNotBlank() }?.let { parseAircraft(JSONObject(it)) }
    } catch (e: HttpError) {
        if (e.code == 404) null else throw e
    }

    /** [reg] as AeroDataBox looks it up: no spaces or dashes ("N-12345" -> "N12345"). */
    internal fun tailKey(reg: String) = reg.uppercase().replace(Regex("[^A-Z0-9]"), "")

    /** An aircraft record (AircraftContract) as [TailInfo]. */
    internal fun parseAircraft(j: JSONObject): TailInfo {
        fun s(k: String) = j.optString(k).takeIf { it != "null" }.orEmpty()
        fun d(k: String) = s(k).take(10).takeIf { it.length == 10 }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val engines = listOf(j.optInt("numEngines").takeIf { it > 0 }?.toString().orEmpty(), s("engineType"))
            .filter { it.isNotEmpty() }.joinToString(" × ")
        return TailInfo(
            model = s("typeName").ifEmpty { s("model") }, type = s("modelCode").ifEmpty { s("model") }, airline = s("airlineName"),
            ageYears = j.optDouble("ageYears").takeIf { !it.isNaN() }, delivered = d("deliveryDate"), firstFlight = d("firstFlightDate"),
            seats = j.optInt("numSeats").takeIf { it > 0 }, engines = engines, hex = s("hexIcao").lowercase(), serial = s("serial"),
        )
    }

    /** The aircraft's flights between [from] and [to] (local dates, one TIER 3 call), newest first. */
    fun tailLegs(reg: String, from: LocalDate, to: LocalDate, key: String): List<TailLeg> = parseLegs(
        Http.get(
            "https://aerodatabox.p.rapidapi.com/flights/reg/${tailKey(reg)}/$from/$to?withAircraftImage=false&withLocation=false&dateLocalRole=Departure",
            headers(key),
        ),
    )

    /** AeroDataBox's flight list as [TailLeg]s: actual times only once it has left or landed, newest first. */
    internal fun parseLegs(body: String): List<TailLeg> {
        if (body.isBlank()) return emptyList()
        val a = org.json.JSONArray(body)
        return (0 until a.length()).map { a.getJSONObject(it) }.map { leg ->
            val dep = leg.optJSONObject("departure") ?: JSONObject()
            val arr = leg.optJSONObject("arrival") ?: JSONObject()
            fun t(side: JSONObject, k: String) = side.optJSONObject(k)?.optString("utc")?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { java.time.OffsetDateTime.parse(it, adbTime).toInstant() }.getOrNull() }
            val status = leg.optString("status")
            val departed = status in setOf("Departed", "EnRoute", "Approaching", "Arrived", "Diverted")
            TailLeg(
                flight = leg.optString("number").replace(" ", ""),
                from = dep.optJSONObject("airport")?.optString("iata").orEmpty(), to = arr.optJSONObject("airport")?.optString("iata").orEmpty(),
                depSched = t(dep, "scheduledTime"), depActual = if (departed) t(dep, "runwayTime") ?: t(dep, "revisedTime") else null,
                arrSched = t(arr, "scheduledTime"), arrActual = if (status == "Arrived") t(arr, "revisedTime") ?: t(arr, "runwayTime") else null,
                cancelled = status.startsWith("Cancel"),
            )
        }.sortedByDescending { it.depSched }
    }
}
