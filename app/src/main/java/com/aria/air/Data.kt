package com.aria.air

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Reader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------- Reference data (bundled, offline) ----------

data class Airport(
    val iata: String, val icao: String, val name: String, val city: String, val country: String,
    val lat: Double, val lon: Double, val tz: ZoneId,
    val region: String = "", // "US-ME"
    val rank: Int = 3,        // 0 large ... 3 small, for picking a city's main airport
)

data class Airline(val iata: String, val icao: String, val name: String)

/** Airports and airlines from assets (built by tools/make_data.py). */
object Db {
    var airports: Map<String, Airport> = emptyMap(); private set
    var airlines: Map<String, Airline> = emptyMap(); private set
    var airlinesByIcao: Map<String, Airline> = emptyMap(); private set

    fun load(ctx: Context) = load(ctx.assets.open("airports.tsv").reader(), ctx.assets.open("airlines.tsv").reader())

    fun load(airportsTsv: Reader, airlinesTsv: Reader) {
        airports = airportsTsv.useLines { lines ->
            lines.map { it.split('\t') }.filter { it.size >= 8 }.associate { r ->
                r[0] to Airport(
                    r[0], r[1], r[2], r[3], r[4], r[5].toDouble(), r[6].toDouble(),
                    runCatching { ZoneId.of(r[7]) }.getOrDefault(ZoneOffset.UTC),
                    r.getOrElse(8) { "" }, r.getOrNull(9)?.toIntOrNull() ?: 3,
                )
            }
        }
        airlines = airlinesTsv.useLines { lines ->
            lines.map { it.split('\t') }.filter { it.size >= 3 }.associate { r -> r[0] to Airline(r[0], r[1], r[2]) }
        }
        airlinesByIcao = airlines.values.associateBy { it.icao }
    }

    fun airport(code: String?): Airport? = code?.let { airports[it.uppercase()] }
    fun airline(code: String?): Airline? = code?.let { airlines[it.uppercase()] }

    /** Main airport of a city, as written in itineraries: ("Portland", "ME") -> PWM. [area] is a state or country code. */
    fun airportForCity(city: String, area: String): Airport? = airports.values
        .filter { it.city.equals(city, true) && (it.region.equals("US-$area", true) || it.region.endsWith("-$area", true) || it.country == area) }
        .minByOrNull { it.rank }
}

fun flag(iso: String): String =
    if (iso.length != 2) "" else iso.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

fun countryName(iso: String): String = Locale("", iso).getDisplayCountry(Locale.US).ifEmpty { iso }

// ---------- Geo ----------

const val EARTH_KM = 6371.0
const val KM_PER_MI = 1.609344

fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
    return 2 * EARTH_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

fun distKm(a: Airport, b: Airport) = distKm(a.lat, a.lon, b.lat, b.lon)

/** Points along the great circle from a to b as (lat, lon), longitudes unwrapped so the line is continuous. */
fun greatCircle(lat1: Double, lon1: Double, lat2: Double, lon2: Double, n: Int = 64): List<Pair<Double, Double>> {
    val p1 = Math.toRadians(lat1); val l1 = Math.toRadians(lon1)
    val p2 = Math.toRadians(lat2); val l2 = Math.toRadians(lon2)
    val d = distKm(lat1, lon1, lat2, lon2) / EARTH_KM
    if (d < 1e-6) return listOf(lat1 to lon1)
    val out = ArrayList<Pair<Double, Double>>(n + 1)
    var prevLon = lon1
    for (i in 0..n) {
        val f = i.toDouble() / n
        val a = sin((1 - f) * d) / sin(d); val b = sin(f * d) / sin(d)
        val x = a * cos(p1) * cos(l1) + b * cos(p2) * cos(l2)
        val y = a * cos(p1) * sin(l1) + b * cos(p2) * sin(l2)
        val z = a * sin(p1) + b * sin(p2)
        var lon = Math.toDegrees(atan2(y, x))
        while (lon - prevLon > 180) lon -= 360
        while (lon - prevLon < -180) lon += 360
        prevLon = lon
        out += Math.toDegrees(atan2(z, sqrt(x * x + y * y))) to lon
    }
    return out
}

// ---------- Flights ----------

/** Weather at an airport around takeoff or landing (Open-Meteo), with when it was fetched. */
data class Wx(val tempC: Double, val code: Int, val windKmh: Double, val at: Instant, val fetched: Instant)

/** WMO weather code as an emoji and a word. */
fun Wx.icon() = when (code) {
    0 -> "☀️"; 1 -> "🌤️"; 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"
    in 51..57 -> "🌦️"; in 61..67, in 80..82 -> "🌧️"; in 71..77, 85, 86 -> "🌨️"; in 95..99 -> "⛈️"
    else -> "🌡️"
}

fun Wx.desc() = when (code) {
    0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"
    in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 80..82 -> "Showers"; in 71..77 -> "Snow"; 85, 86 -> "Snow showers"
    in 95..99 -> "Thunderstorms"; else -> "—"
}

/** "64°F" (or °C with Settings › Data › Celsius). */
fun Wx.temp() = if (Prefs.flag("celsius", false)) "${Math.round(tempC)}°C" else "${Math.round(tempC * 9 / 5 + 32)}°F"
fun Wx.wind() = if (Prefs.flag("celsius", false)) "${Math.round(windKmh)} km/h" else "${Math.round(windKmh / KM_PER_MI)} mph"
fun Wx.short() = "${icon()} ${temp()}"

/** Last ADS-B fix for a flight (from adsb.lol). */
data class Live(
    val lat: Double, val lon: Double, val altFt: Int, val speedKt: Int, val track: Double, val vrateFpm: Int,
    val at: Instant, val onGround: Boolean, val aircraft: String = "", val reg: String = "",
)

data class Flight(
    val id: String = UUID.randomUUID().toString(),
    val airline: String,              // IATA, "UA"
    val number: String,               // "2000"
    val date: LocalDate,              // departure date, local to the origin
    val from: String, val to: String, // IATA airport codes
    val depSched: Instant? = null, val depEst: Instant? = null, val depActual: Instant? = null,
    val arrSched: Instant? = null, val arrEst: Instant? = null, val arrActual: Instant? = null,
    val depTerminal: String = "", val depGate: String = "",
    val arrTerminal: String = "", val arrGate: String = "", val baggage: String = "",
    val aircraft: String = "", val reg: String = "",
    val cancelled: Boolean = false, val diverted: Boolean = false,
    val seat: String = "", val booking: String = "", val notes: String = "",
    val friend: String = "",          // empty = my flight
    val source: String = "manual",    // manual, search, pdf, image, email, text
    val work: Boolean = false,        // business trip (my flights)
    val depWx: Wx? = null, val arrWx: Wx? = null, // weather at takeoff and landing
    val live: Live? = null,
    val updated: Instant? = null,     // last successful status refresh
    val alerted: String = "",         // events already notified (comma list), so alerts fire once
)

val Flight.code get() = "$airline$number"
val Flight.label get() = "$airline $number"
val Flight.mine get() = friend.isEmpty()
val Flight.origin get() = Db.airport(from)
val Flight.dest get() = Db.airport(to)
val Flight.fromTz: ZoneId get() = origin?.tz ?: ZoneId.systemDefault()
val Flight.toTz: ZoneId get() = dest?.tz ?: ZoneId.systemDefault()
val Flight.distanceKm: Double get() = origin?.let { o -> dest?.let { distKm(o, it) } } ?: 0.0

/** Best known departure/arrival: actual, then estimate, then schedule. */
val Flight.dep get() = depActual ?: depEst ?: depSched
val Flight.arr get() = arrActual ?: arrEst ?: arrSched

/** Typical block time when no schedule is known: cruise at ~500 mph plus 30 min taxi/climb. */
// ponytail: crude estimate, good enough for sorting and stats; real times replace it once known.
fun blockTime(km: Double): Duration = Duration.ofMinutes((km / KM_PER_MI / 500 * 60 + 30).toLong())

/** Departure used for ordering and phases; noon local on the date when no time is known. */
val Flight.depGuess: Instant get() = dep ?: date.atTime(LocalTime.NOON).atZone(fromTz).toInstant()
val Flight.arrGuess: Instant get() = arr ?: depGuess.plus(blockTime(distanceKm))
val Flight.duration: Duration get() = Duration.between(dep ?: depGuess, arr ?: arrGuess)
val Flight.hasTimes get() = depSched != null && arrSched != null

/** Minutes late (positive) or early (negative) against the schedule, or null when unknown. */
/**
 * Whether "on time" / "delayed" means anything yet. Airlines only publish real estimates close to departure, so it
 * needs: under 24 h to go, a status fetched in the last 6 h, and an estimated time in it. Weeks out it's just a schedule.
 */
val Flight.hasLiveStatus: Boolean
    get() {
        val now = Instant.now()
        val d = dep ?: return false
        return depEst != null && updated != null && Duration.between(updated, now) < Duration.ofHours(6) &&
            now > d.minus(Duration.ofHours(24))
    }

/** Late (+) or early (-) in minutes: actual times always count, estimates only once they're live. Null = unknown. */
val Flight.depDelay: Long?
    get() = depSched?.let { s -> (depActual ?: depEst?.takeIf { hasLiveStatus })?.let { Duration.between(s, it).toMinutes() } }
val Flight.arrDelay: Long?
    get() = arrSched?.let { s ->
        (arrActual ?: arrEst?.takeIf { hasLiveStatus || depActual != null })?.let { Duration.between(s, it).toMinutes() }
    }

/** The phases of a trip, as in the airline apps: before, at the gate, in the air, after landing. */
enum class Phase(val title: String) { PreTravel("Pre-travel"), Departure("Departure"), InFlight("In flight"), Arrival("Arrival"), Done("Done") }

private val FRESH = Duration.ofMinutes(10)

val Flight.isAirborne: Boolean
    get() = live != null && !live.onGround && Duration.between(live.at, Instant.now()) < FRESH

fun Flight.phase(now: Instant = Instant.now()): Phase = when {
    cancelled -> if (now < depGuess.plus(Duration.ofHours(6))) Phase.Departure else Phase.Done
    arrActual != null -> if (now < arrActual.plus(Duration.ofHours(1))) Phase.Arrival else Phase.Done
    isAirborne || depActual != null && now < arrGuess -> Phase.InFlight
    now < depGuess.minus(Duration.ofHours(3)) -> Phase.PreTravel
    now < depGuess -> Phase.Departure
    now < arrGuess -> Phase.InFlight
    now < arrGuess.plus(Duration.ofHours(1)) -> Phase.Arrival
    else -> Phase.Done
}

val Flight.upcoming get() = phase() != Phase.Done

/** No schedule yet, but it's from yesterday or today and hasn't landed: worth asking where the plane is. */
val Flight.maybeLive: Boolean
    get() = !hasTimes && arrActual == null && !cancelled && date in LocalDate.now().minusDays(1)..LocalDate.now()

/** 0..1 through the flight: by distance when the plane is broadcasting, else by clock. */
fun Flight.progress(now: Instant = Instant.now()): Double {
    val o = origin; val d = dest
    if (isAirborne && o != null && d != null) {
        val flown = distKm(o.lat, o.lon, live!!.lat, live.lon)
        val left = distKm(live.lat, live.lon, d.lat, d.lon)
        return (flown / (flown + left)).coerceIn(0.0, 1.0)
    }
    val total = Duration.between(depGuess, arrGuess).toMillis().coerceAtLeast(1)
    return (Duration.between(depGuess, now).toMillis().toDouble() / total).coerceIn(0.0, 1.0)
}

/** Remaining distance in km: from the live position if there is one, else by progress. */
fun Flight.remainingKm(): Double {
    val d = dest ?: return 0.0
    return if (isAirborne) distKm(live!!.lat, live.lon, d.lat, d.lon) else distanceKm * (1 - progress())
}

enum class Tone { Neutral, Good, Warn, Bad, Info }

/** Short status like the airline apps: "On time", "Delayed 45m", "In the air", "Landed". */
fun Flight.status(now: Instant = Instant.now()): Pair<String, Tone> {
    val delay = if (phase(now) <= Phase.Departure) depDelay else arrDelay
    return when {
        cancelled -> "Cancelled" to Tone.Bad
        diverted -> "Diverted" to Tone.Bad
        arrActual != null -> (if ((arrDelay ?: 0) >= 15) "Landed ${span(Duration.ofMinutes(arrDelay!!))} late" else "Landed") to Tone.Good
        phase(now) == Phase.InFlight -> (if (isAirborne || depActual != null) "In the air" else "Departed") to Tone.Info
        phase(now) == Phase.Arrival || phase(now) == Phase.Done -> "Arrived" to Tone.Good
        delay != null && delay >= 15 -> "Delayed ${span(Duration.ofMinutes(delay))}" to (if (delay >= 60) Tone.Bad else Tone.Warn)
        hasLiveStatus -> "On time" to Tone.Good
        else -> "Upcoming" to Tone.Neutral // no real status yet (too far out, or no status service)
    }
}

// ---------- JSON ----------

private fun JSONObject.inst(k: String): Instant? = if (has(k)) Instant.ofEpochMilli(getLong(k)) else null
private fun JSONObject.putInst(k: String, v: Instant?) = apply { if (v != null) put(k, v.toEpochMilli()) }
private fun JSONObject.putStr(k: String, v: String) = apply { if (v.isNotEmpty()) put(k, v) }

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
    .putStr("friend", friend).put("source", source).put("work", work).putInst("updated", updated).putStr("alerted", alerted)
    .apply {
        fun wx(w: Wx) = JSONObject().put("t", w.tempC).put("code", w.code).put("wind", w.windKmh)
            .put("at", w.at.toEpochMilli()).put("fetched", w.fetched.toEpochMilli())
        depWx?.let { put("depWx", wx(it)) }
        arrWx?.let { put("arrWx", wx(it)) }
        live?.let {
            put("live", JSONObject().put("lat", it.lat).put("lon", it.lon).put("alt", it.altFt).put("gs", it.speedKt)
                .put("track", it.track).put("vrate", it.vrateFpm).put("at", it.at.toEpochMilli()).put("ground", it.onGround)
                .put("aircraft", it.aircraft).put("reg", it.reg))
        }
    }

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
    friend = j.optString("friend"), source = j.optString("source", "manual"), work = j.optBoolean("work"), updated = j.inst("updated"),
    alerted = j.optString("alerted"),
    depWx = j.optJSONObject("depWx")?.let(::wxFrom), arrWx = j.optJSONObject("arrWx")?.let(::wxFrom),
    live = j.optJSONObject("live")?.let {
        Live(it.getDouble("lat"), it.getDouble("lon"), it.getInt("alt"), it.getInt("gs"), it.getDouble("track"),
            it.optInt("vrate"), Instant.ofEpochMilli(it.getLong("at")), it.optBoolean("ground"),
            it.optString("aircraft"), it.optString("reg"))
    },
)

private fun wxFrom(j: JSONObject) = Wx(
    j.getDouble("t"), j.getInt("code"), j.optDouble("wind", 0.0), Instant.ofEpochMilli(j.getLong("at")), Instant.ofEpochMilli(j.getLong("fetched")),
)

// ---------- Storage ----------

/** All flights, in one JSON file in app storage. Mutate on the main thread only. */
object Store {
    val flights = mutableStateListOf<Flight>()
    private lateinit var file: File
    /** Called after every save (the app refreshes the home screen widget). */
    var onChange: (() -> Unit)? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "flights.json")
        if (file.exists()) flights.addAll(parse(file.readText()))
    }

    fun parse(text: String): List<Flight> {
        val arr = JSONObject(text).getJSONArray("flights")
        return (0 until arr.length()).mapNotNull { runCatching { flightFrom(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun json(list: List<Flight> = flights): String =
        JSONObject().put("app", "air").put("version", 1).put("flights", JSONArray(list.map { it.toJson() })).toString()

    /** Write to a temp file and rename, so a crash mid-write never loses the file. */
    private fun save() = synchronized(this) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json())
        tmp.renameTo(file)
        onChange?.invoke()
    }

    fun get(id: String?) = flights.firstOrNull { it.id == id }

    fun upsert(f: Flight) {
        val i = flights.indexOfFirst { it.id == f.id }
        if (i >= 0) flights[i] = f else flights.add(f)
        save()
    }

    fun remove(id: String) {
        flights.removeAll { it.id == id }
        save()
    }

    /** Same flight number, date and traveler = same flight (so re-imports don't duplicate). */
    fun findSame(f: Flight) = flights.firstOrNull { it.code == f.code && it.date == f.date && it.friend.equals(f.friend, true) }

    /** Merges a backup: new flights are added, existing ones (same id) are left alone. Returns how many were added. */
    fun import(text: String): Int {
        val known = flights.map { it.id }.toSet()
        val add = parse(text).filter { it.id !in known && findSame(it) == null }
        flights.addAll(add)
        save()
        return add.size
    }

    /** Adds flights that aren't already stored (same number, date and traveler). Returns how many were added. */
    fun addAll(list: List<Flight>): Int {
        val add = list.filter { findSame(it) == null }.distinctBy { Triple(it.code, it.date, it.friend) }
        flights.addAll(add)
        save()
        return add.size
    }

    val friends: List<String> get() = flights.filter { !it.mine }.map { it.friend }.distinct().sorted()
}

/** Small settings in SharedPreferences, mirrored in Compose state so screens update when they change. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val values = mutableStateMapOf<String, String>()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        sp.all.forEach { (k, v) -> values[k] = v.toString() }
    }

    operator fun get(k: String) = values[k] ?: ""
    operator fun set(k: String, v: String) {
        values[k] = v
        sp.edit().putString(k, v).apply()
    }

    fun flag(k: String, default: Boolean = true) = values[k]?.let { it == "1" } ?: default
    fun setFlag(k: String, v: Boolean) = set(k, if (v) "1" else "0")

    var name: String get() = this["name"]; set(v) { this["name"] = v }
    var aeroKey: String get() = this["aeroKey"]; set(v) { this["aeroKey"] = v }
    var adbKey: String get() = this["adbKey"]; set(v) { this["adbKey"] = v }
    val hasStatusKey get() = aeroKey.isNotBlank() || adbKey.isNotBlank()
    /** Hours before departure for the reminder on my flights, 0 = off. */
    var reminderHours: Int get() = this["reminderHours"].toIntOrNull() ?: 3; set(v) { this["reminderHours"] = v.toString() }
    /** Hours before my departures that the ongoing countdown notification appears. */
    var liveLeadHours: Int get() = this["liveLeadHours"].toIntOrNull() ?: 12; set(v) { this["liveLeadHours"] = v.toString() }
}

// ---------- Stats (the passport) ----------

data class Stats(
    val flights: Int, val km: Double, val air: Duration,
    val airports: Map<String, Int>, val airlines: Map<String, Int>, val countries: Set<String>,
    val routes: Map<String, Int>, val longest: Flight?,
)

fun stats(list: List<Flight>): Stats {
    val airports = mutableMapOf<String, Int>()
    list.forEach { airports.merge(it.from, 1, Int::plus); airports.merge(it.to, 1, Int::plus) }
    return Stats(
        flights = list.size,
        km = list.sumOf { it.distanceKm },
        air = list.fold(Duration.ZERO) { acc, f -> acc + f.duration.let { if (it.isNegative) Duration.ZERO else it } },
        airports = airports,
        airlines = list.groupingBy { it.airline }.eachCount(),
        countries = airports.keys.mapNotNull { Db.airport(it)?.country }.toSet(),
        routes = list.groupingBy { listOf(it.from, it.to).sorted().joinToString(" – ") }.eachCount(),
        longest = list.maxByOrNull { it.distanceKm },
    )
}

// ---------- Duplicates ----------

/** How much a flight record knows, to pick which copy to keep when merging. */
fun Flight.completeness(): Int =
    listOf(depSched, arrSched, depActual, arrActual, updated).count { it != null } +
        listOf(seat, booking, notes, aircraft, depGate, arrGate).count { it.isNotEmpty() } + (if (mine) 3 else 0)

/** This flight with any blanks filled from [o]. Keeps this one's id and traveler. */
fun Flight.mergedWith(o: Flight) = copy(
    depSched = depSched ?: o.depSched, depEst = depEst ?: o.depEst, depActual = depActual ?: o.depActual,
    arrSched = arrSched ?: o.arrSched, arrEst = arrEst ?: o.arrEst, arrActual = arrActual ?: o.arrActual,
    depTerminal = depTerminal.ifEmpty { o.depTerminal }, depGate = depGate.ifEmpty { o.depGate },
    arrTerminal = arrTerminal.ifEmpty { o.arrTerminal }, arrGate = arrGate.ifEmpty { o.arrGate }, baggage = baggage.ifEmpty { o.baggage },
    aircraft = aircraft.ifEmpty { o.aircraft }, reg = reg.ifEmpty { o.reg },
    seat = seat.ifEmpty { o.seat }, booking = booking.ifEmpty { o.booking },
    notes = listOf(notes, o.notes).filter { it.isNotEmpty() }.distinct().joinToString("\n"),
    work = work || o.work, live = live ?: o.live, updated = updated ?: o.updated,
    depWx = depWx ?: o.depWx, arrWx = arrWx ?: o.arrWx,
)

object Dupes {
    private fun key(a: Flight, b: Flight) = listOf(a.id, b.id).sorted().joinToString("+")
    private val ignored get() = Prefs["dupIgnore"].split(',').filter { it.isNotEmpty() }.toSet()

    /** Why two flights look like the same trip, or null if they don't. */
    fun reason(a: Flight, b: Flight): String? {
        if (a.date != b.date) return null
        if (a.code == b.code) return if (a.friend.equals(b.friend, true)) "Same flight" else "Same flight, different traveler"
        val close = a.depSched == null || b.depSched == null || Duration.between(a.depSched, b.depSched).abs() <= Duration.ofMinutes(30)
        return if (a.from == b.from && a.to == b.to && close) "Same route and time (codeshare?)" else null
    }

    /** Groups of flights that look like duplicates, minus pairs marked "not duplicates". */
    fun groups(list: List<Flight> = Store.flights): List<List<Flight>> {
        val skip = ignored
        val parent = HashMap<String, String>()
        fun find(x: String): String = parent[x]?.let { if (it == x) x else find(it).also { r -> parent[x] = r } } ?: x
        list.groupBy { it.date }.values.forEach { day ->
            for (i in day.indices) for (j in i + 1 until day.size) {
                val a = day[i]; val b = day[j]
                if (reason(a, b) != null && key(a, b) !in skip) parent[find(a.id)] = find(b.id).also { parent.putIfAbsent(it, it) }
            }
        }
        val byId = list.associateBy { it.id }
        return parent.keys.groupBy { find(it) }.values.map { ids -> ids.mapNotNull { byId[it] }.sortedByDescending { it.completeness() } }
            .filter { it.size > 1 }.sortedByDescending { it.first().date }
    }

    /** Keeps the most complete flight, fills its blanks from the others, deletes the others. */
    fun merge(group: List<Flight>) {
        val keep = group.maxBy { it.completeness() }
        Store.upsert(group.filter { it.id != keep.id }.fold(keep) { acc, o -> acc.mergedWith(o) })
        group.filter { it.id != keep.id }.forEach { Store.remove(it.id) }
    }

    fun ignore(group: List<Flight>) {
        val pairs = group.flatMap { a -> group.filter { it.id != a.id }.map { b -> key(a, b) } }
        Prefs["dupIgnore"] = (ignored + pairs).joinToString(",")
    }
}
