package com.aria.air

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The only network traffic in the app. It sends flight numbers, never who you are or what's in your documents:
 *  - adsb.lol (free, open data): the route for a flight number, and the plane's live position/speed/altitude.
 *  - AeroDataBox via RapidAPI or FlightAware AeroAPI (optional, your own key): schedule, delays, gates, baggage belt,
 *    aircraft. AeroDataBox is used when both keys are set.
 */
object Api {
    class HttpError(val code: Int, msg: String) : Exception(msg)

    /** Every request goes through here; unit tests swap in canned responses. */
    internal var http: (url: String, headers: Map<String, String>) -> String = ::fetch

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val start = System.currentTimeMillis()
        val r = runCatching { http(url, headers) }
        Debug.request(url, System.currentTimeMillis() - start, r)
        return r.getOrThrow()
    }

    private fun fetch(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Air-Android/${BuildConfig.VERSION_NAME}")
            headers.forEach(c::setRequestProperty)
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code, "HTTP $code from ${URL(url).host}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** ICAO callsign as broadcast by the plane: UA1234 -> UAL1234. */
    fun callsign(airline: String, number: String) = (Db.airline(airline)?.icao ?: airline) + number.trimStart('0')

    /** Airports the flight number serves, in order ("PIT", "SFO"), or empty when unknown. Multi-stop flights give 3+. */
    suspend fun route(airline: String, number: String): List<String> = withContext(Dispatchers.IO) {
        val cs = callsign(airline, number)
        runCatching {
            JSONObject(get("https://vrs-standing-data.adsb.lol/routes/${cs.take(2)}/$cs.json"))
                .optString("_airport_codes_iata").split("-").filter { it.length == 3 }
        }.getOrDefault(emptyList())
    }

    /** Latest ADS-B fix for the flight's callsign, if the plane is broadcasting and plausibly on this route. */
    suspend fun live(f: Flight): Live? = withContext(Dispatchers.IO) {
        val body = runCatching { get("https://api.adsb.lol/v2/callsign/${callsign(f.airline, f.number)}") }.getOrNull()
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
                onGround = alt == "ground", aircraft = a.optString("t"), reg = a.optString("r"),
            )
        }.firstOrNull { plausible(f, it) }
    }

    /** Flight numbers get reused on other legs, so only trust a plane that's roughly between the two airports. */
    /**
     * Whether a plane broadcasting this flight's callsign really is this flight today. Airlines reuse numbers on other
     * legs and days, so besides being between the airports it has to fit the clock: before the scheduled departure it
     * can only still be at the origin, and after it can't have flown further than a jet could since.
     */
    fun plausible(f: Flight, l: Live): Boolean {
        if (!onRoute(f, l)) return false
        val o = f.origin ?: return true
        val dep = f.dep ?: return true // no schedule: the route check is all we have
        val flownKm = distKm(o.lat, o.lon, l.lat, l.lon)
        val since = Duration.between(dep.minus(Duration.ofMinutes(20)), l.at)
        // Before departure only a plane on the ground at the origin can be ours; one in the air nearby is arriving.
        if (since.isNegative) return l.onGround && flownKm < 15
        // Near the origin and descending toward it: that's a plane landing there (often our own inbound running
        // late on the same flight number), not ours taking off.
        if (!l.onGround && flownKm < 80 && l.vrateFpm < -300 && headingToward(l, o.lat, o.lon)) return false
        return flownKm <= since.toMinutes() / 60.0 * 1100 + 60 // 1100 km/h is faster than any airliner
    }

    /** Whether the plane's track points within 60 degrees of the direction to (lat, lon). */
    private fun headingToward(l: Live, lat: Double, lon: Double): Boolean {
        val p1 = Math.toRadians(l.lat); val p2 = Math.toRadians(lat); val dl = Math.toRadians(lon - l.lon)
        val bearing = Math.toDegrees(kotlin.math.atan2(kotlin.math.sin(dl) * kotlin.math.cos(p2),
            kotlin.math.cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dl)))
        val diff = kotlin.math.abs(((bearing - l.track) % 360 + 540) % 360 - 180)
        return diff < 60
    }

    fun onRoute(f: Flight, l: Live): Boolean {
        val o = f.origin ?: return true
        val d = f.dest ?: return true
        val total = distKm(o, d)
        return distKm(o.lat, o.lon, l.lat, l.lon) + distKm(l.lat, l.lon, d.lat, d.lon) < total * 1.25 + 150
    }

    /** Status from FlightAware AeroAPI. Only covers roughly 10 days back to 2 days ahead. */
    suspend fun aero(f: Flight, key: String): Flight? = withContext(Dispatchers.IO) {
        val start = f.date.minusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        val end = f.date.plusDays(2).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        val body = get(
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
                ?.let { id -> runCatching { aeroInbound(f, id, key) }.getOrNull() } ?: f.inbound,
        )
    }

    private fun soon(f: Flight) = Duration.between(Instant.now(clock), f.dep ?: f.depGuess) < Duration.ofHours(12)

    /**
     * The plane's previous flight from FlightAware ([id] is its inbound_fa_flight_id), as an [Inbound]. Unlike ADS-B,
     * this knows about a plane parked at the gate with its transponder off: whether it has left, when it gets in, and
     * when it landed. Its position is placed at an airport, or along the route by FlightAware's progress.
     */
    suspend fun aeroInbound(f: Flight, id: String, key: String): Inbound? = withContext(Dispatchers.IO) {
        countLookup()
        val j = JSONObject(get("https://aeroapi.flightaware.com/aeroapi/flights/$id", mapOf("x-apikey" to key)))
            .optJSONArray("flights")?.optJSONObject(0) ?: return@withContext null
        inboundFromAero(f, j, Instant.now(clock))
    }

    /** [j]: a FlightAware flight object for the plane's previous flight. Pure, for tests. */
    fun inboundFromAero(f: Flight, j: JSONObject, now: Instant): Inbound? {
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

    private val adbTime = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mmX")
    private fun adbHeaders(key: String) = mapOf("X-RapidAPI-Key" to key, "X-RapidAPI-Host" to "aerodatabox.p.rapidapi.com")

    /** Status from AeroDataBox (RapidAPI). Covers roughly a year back and a year ahead. */
    suspend fun adb(f: Flight, key: String): Flight? = withContext(Dispatchers.IO) {
        val body = get(
            "https://aerodatabox.p.rapidapi.com/flights/number/${f.airline}${f.number}/${f.date}" +
                "?withAircraftImage=false&withLocation=false&dateLocalRole=Departure",
            adbHeaders(key),
        )
        Debug.status(f, body)
        if (body.isBlank()) return@withContext null // 204: no such flight that day
        val legs = org.json.JSONArray(body).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun iata(side: JSONObject?) = side?.optJSONObject("airport")?.optString("iata").orEmpty()
        // Only this flight's day: another day's run of the same number says nothing about this one.
        fun localDate(side: JSONObject?) = side?.optJSONObject("scheduledTime")?.optString("local")?.take(10)
        val leg = legs.filter { localDate(it.optJSONObject("departure")) == f.date.toString() }
            .let { day -> day.firstOrNull { iata(it.optJSONObject("departure")) == f.from } ?: day.firstOrNull() }
            ?: return@withContext null
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
            from = iata(dep).ifEmpty { f.from }, to = iata(arr).ifEmpty { f.to },
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
            cancelled = status.startsWith("Cancel"), diverted = status == "Diverted",
            adbSaid = said, disputed = disputed,
        )
    }

    suspend fun testAdbKey(key: String): String? = withContext(Dispatchers.IO) {
        try {
            get("https://aerodatabox.p.rapidapi.com/flights/number/UA900/${localToday()}?withAircraftImage=false&withLocation=false", adbHeaders(key)); null
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

    /**
     * Weather at [a] at the hour of [at], from Open-Meteo (free, no key): the forecast API up to ~16 days ahead and
     * for the last two months, the ERA5 archive before that. Sends only the airport's coordinates and the date.
     */
    suspend fun weather(a: Airport, at: Instant): Wx? = withContext(Dispatchers.IO) {
        val utc = at.atZone(java.time.ZoneOffset.UTC)
        val day = utc.toLocalDate()
        val today = LocalDate.ofInstant(Instant.now(clock), java.time.ZoneOffset.UTC)
        val host = when {
            day < today.minusDays(60) -> "https://archive-api.open-meteo.com/v1/archive"
            day <= today.plusDays(15) -> "https://api.open-meteo.com/v1/forecast"
            else -> return@withContext null // too far out to forecast yet
        }
        val h = runCatching {
            JSONObject(get("$host?latitude=${a.lat}&longitude=${a.lon}&hourly=temperature_2m,weather_code,wind_speed_10m&start_date=$day&end_date=$day&timezone=GMT"))
                .getJSONObject("hourly")
        }.getOrNull() ?: return@withContext null
        val i = utc.hour
        val t = h.optJSONArray("temperature_2m")?.opt(i) as? Number ?: return@withContext null
        Wx(
            tempC = t.toDouble(), code = (h.optJSONArray("weather_code")?.opt(i) as? Number)?.toInt() ?: -1,
            windKmh = (h.optJSONArray("wind_speed_10m")?.opt(i) as? Number)?.toDouble() ?: 0.0, at = at, fetched = Instant.now(clock),
        )
    }

    /** Fills in takeoff/landing weather: once for past flights, every 3 h for upcoming ones until they happen. */
    suspend fun refreshWeather(f: Flight): Flight {
        val o = f.origin ?: return f
        val d = f.dest ?: return f
        val now = Instant.now(clock)
        // Forecasts sharpen as the day nears: daily when days away, every 6 h in the last 3 days, every 3 h on the day.
        fun every(at: Instant) = Duration.between(now, at).let {
            when {
                it < Duration.ofHours(12) -> Duration.ofHours(3)
                it < Duration.ofDays(3) -> Duration.ofHours(6)
                else -> Duration.ofHours(24)
            }
        }
        fun stale(w: Wx?, happened: Boolean) = w == null || (!happened && Duration.between(w.fetched, now) > every(w.at))
        var n = f
        val dep = f.dep ?: f.depGuess
        val arr = f.arr ?: f.arrGuess
        if (stale(f.depWx, dep < now.minus(Duration.ofHours(1)))) weather(o, dep)?.let { n = n.copy(depWx = it) }
        if (stale(f.arrWx, arr < now.minus(Duration.ofHours(1)))) weather(d, arr)?.let { n = n.copy(arrWx = it) }
        return n
    }

    /** One pass over every flight still missing weather (e.g. an imported history). */
    suspend fun backfillWeather() {
        for (f in Store.flights.filter { it.depWx == null || it.arrWx == null }.toList()) {
            val n = runCatching { refreshWeather(f) }.getOrDefault(f)
            if (n != f) Store.get(f.id)?.let { cur -> Store.upsert(cur.copy(depWx = n.depWx ?: cur.depWx, arrWx = n.arrWx ?: cur.arrWx)) }
            kotlinx.coroutines.delay(250) // be polite to the free API
        }
    }

    /**
     * How often the keyed status service (AeroDataBox / FlightAware, which cost quota) may be asked about a flight,
     * or null for never automatically. Sparse far out, where only cancellations and retimings can show up, and
     * frequent only around the gate and landing. Live position and weather come from free services and aren't
     * limited by this.
     */
    fun statusEvery(f: Flight, now: Instant): Duration? {
        if (f.arrActual != null || f.cancelled) return null // settled
        val toDep = Duration.between(now, f.dep ?: f.depGuess)
        val toArr = Duration.between(now, f.arr ?: f.arrGuess)
        return when {
            toArr < Duration.ofHours(-2) -> null                                   // long over: nothing more to learn
            toDep.isNegative -> if (toArr < Duration.ofMinutes(45)) Duration.ofMinutes(20) else Duration.ofMinutes(45)
            toDep < Duration.ofHours(4) -> Duration.ofMinutes(20)                  // gate, delays, boarding
            toDep < Duration.ofHours(24) -> Duration.ofHours(2)
            toDep < Duration.ofDays(3) -> Duration.ofHours(12)
            toDep < Duration.ofDays(14) -> Duration.ofDays(1)
            else -> Duration.ofDays(7)                                             // just watching for cancellations
        }
    }

    private val lastLiveTry = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val lastInboundTry = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    /** Follow the inbound plane only in the last 12 h before departure, once its tail number is known. */
    private fun inboundDue(f: Flight, now: Instant, auto: Boolean, foreground: Boolean): Boolean {
        if (f.reg.isEmpty() || f.tookOff != null || f.cancelled || f.isAirborne) return false
        val toDep = Duration.between(now, f.dep ?: f.depGuess)
        if (toDep.isNegative || toDep > Duration.ofHours(12)) return false
        if (!auto) return true
        val every = if (foreground) Duration.ofMinutes(2) else Duration.ofMinutes(10)
        return lastInboundTry[f.id]?.let { Duration.between(it, now) >= every } != false
    }

    /**
     * Where the plane assigned to [f] is now (adsb.lol, by tail number) and when it reaches our departure airport.
     * Null when it isn't broadcasting (parked with transponder off) or is already flying our flight.
     */
    suspend fun inbound(f: Flight): Inbound? = withContext(Dispatchers.IO) {
        val o = f.origin ?: return@withContext null
        val ac = JSONObject(get("https://api.adsb.lol/v2/reg/${f.reg}")).optJSONArray("ac") ?: return@withContext null
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
            JSONObject(get("https://vrs-standing-data.adsb.lol/routes/${cs.take(2)}/$cs.json"))
                .optString("_airport_codes_iata").split("-").filter { it.length == 3 }
        }.getOrDefault(emptyList()) else emptyList()
        val headedHere = sameNumber || route.isEmpty() || route.last() == f.from
        // Straight line at current speed plus ~10 min for approach and taxi.
        val eta = if (!ground && headedHere && gs > 100) {
            at.plusSeconds((distKm(lat, lon, o.lat, o.lon) / (gs * 1.852) * 3600).toLong() + 600)
        } else null
        Inbound(cs, route.firstOrNull().orEmpty(), if (sameNumber) f.from else route.lastOrNull().orEmpty(), lat, lon, (alt as? Number)?.toInt() ?: 0, gs.toInt(), ground, eta, at)
    }

    /**
     * How often to ask adsb.lol where the plane is (free, but still battery and data), or null when there's nothing
     * to see. At the gate it's only to catch takeoff; near landing more often; every minute only while you're
     * looking at the flight.
     */
    fun liveEvery(f: Flight, now: Instant, foreground: Boolean): Duration? {
        val phase = f.phase(now)
        if (!(phase in Phase.Departure..Phase.Arrival || f.maybeLive) || f.arrActual != null) return null
        if (foreground) return Duration.ofMinutes(1)
        val toArr = Duration.between(now, f.arr ?: f.arrGuess)
        return when {
            phase == Phase.InFlight && toArr < Duration.ofMinutes(45) -> Duration.ofMinutes(3)
            else -> Duration.ofMinutes(10)
        }
    }

    /** Lookups this calendar month, shown in Settings › Data so quota use is visible. */
    private fun countLookup() {
        val k = "lookups-" + java.time.YearMonth.now()
        Prefs[k] = ((Prefs[k].toIntOrNull() ?: 0) + 1).toString()
    }

    fun lookupsThisMonth() = Prefs["lookups-" + java.time.YearMonth.now()].toIntOrNull() ?: 0

    /** Checks an AeroAPI key with a cheap call. Returns null when it works, else the problem. */
    suspend fun testKey(key: String): String? = withContext(Dispatchers.IO) {
        try {
            get("https://aeroapi.flightaware.com/aeroapi/airports/KSFO", mapOf("x-apikey" to key)); null
        } catch (e: HttpError) {
            if (e.code == 401 || e.code == 403) "Key rejected (${e.code})" else e.message
        } catch (e: Exception) {
            e.message ?: "No connection"
        }
    }

    /**
     * Refreshes everything we can about a flight. [auto] = background poll, which spends status API calls sparingly:
     * every 15 min around the flight, every 6 h before it.
     */
    private fun key0(a: String, b: String) = a.isNotBlank() || b.isNotBlank()

    private val lastAeroTry = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    /**
     * How long FlightAware's gates, baggage and tail outrank AeroDataBox's after it gave them. A real change is caught
     * sooner than this: AeroDataBox changing its answer gets FlightAware asked again (see secondOpinion).
     */
    val FA_OWNS: Duration = Duration.ofHours(6)

    /**
     * With both keys, AeroDataBox does the routine lookups and FlightAware is a second opinion, asked only when it adds
     * something: the refresh button within two days of the flight, or in the last 12 h while AeroDataBox is missing the
     * tail number or departure gate (at most every 30 min, and only until both are known).
     */
    fun secondOpinion(f: Flight, now: Instant, auto: Boolean): Boolean {
        val toDep = Duration.between(now, f.dep ?: f.depGuess)
        if (!auto) return toDep.abs() < Duration.ofDays(2)
        val since = lastAeroTry[f.id]?.let { Duration.between(it, now) }
        // A gate change reported by AeroDataBox: confirm it within minutes, up to landing.
        if (f.disputed && now < (f.arr ?: f.arrGuess)) return since == null || since >= Duration.ofMinutes(5)
        if (toDep.isNegative || toDep > Duration.ofHours(12) || f.tookOff != null) return false
        // Once the tail number is known, adsb.lol follows the inbound plane for free.
        val missing = f.reg.isEmpty() || f.depGate.isEmpty()
        return missing && (since == null || since >= Duration.ofMinutes(30))
    }

    /**
     * AeroDataBox's view [a] with FlightAware's [b] on top where it knows more: gates (fresher at US airports), the tail
     * number and the inbound plane. Times stay AeroDataBox's. Disagreements go in the debug log.
     */
    fun merge(a: Flight, b: Flight): Flight {
        listOf(
            "departure gate" to (a.depGate to b.depGate), "arrival gate" to (a.arrGate to b.arrGate), "tail" to (a.reg to b.reg),
        ).filter { (_, v) -> v.first.isNotEmpty() && v.second.isNotEmpty() && v.first != v.second }
            .forEach { (k, v) -> Debug.note("${a.label} $k: AeroDataBox ${v.first}, FlightAware ${v.second}") }
        return a.copy(
            faAt = Instant.now(clock), disputed = false,
            depGate = b.depGate.ifEmpty { a.depGate }, depTerminal = b.depTerminal.ifEmpty { a.depTerminal },
            arrGate = b.arrGate.ifEmpty { a.arrGate }, arrTerminal = b.arrTerminal.ifEmpty { a.arrTerminal },
            baggage = b.baggage.ifEmpty { a.baggage }, reg = b.reg.ifEmpty { a.reg }, inbound = b.inbound ?: a.inbound,
        )
    }

    suspend fun refresh(f: Flight, auto: Boolean = false, foreground: Boolean = false): Flight {
        // A takeoff time that can't be ours (saved from another leg or day on the same number) is dropped first.
        var n = if (f.depActual != null && f.tookOff == null) f.copy(depActual = null) else f
        val now = Instant.now(clock)
        val adbKey = Prefs.adbKey
        val aeroKey = Prefs.aeroKey
        // Automatic lookups follow statusEvery(); only the refresh button (auto = false) goes around it.
        val every = statusEvery(f, now)
        val due = every != null && (f.updated == null || Duration.between(f.updated, now) > every)
        val today = localToday()
        Debug.note("refresh ${f.label} ${f.date}: auto=$auto status due=$due (every $every, last ${f.updated})")
        if (!auto || due) {
            fun failed(e: Throwable) = Debug.note("status lookup for ${f.label} failed: $e")
            val status = when {
                adbKey.isNotBlank() && f.date in today.minusDays(360)..today.plusDays(360) -> runCatching { adb(n, adbKey) }.onFailure(::failed).getOrNull()
                aeroKey.isNotBlank() && f.date in today.minusDays(9)..today.plusDays(2) -> runCatching { aero(n, aeroKey) }.onFailure(::failed).getOrNull()
                else -> null
            }
            if (status == null && key0(adbKey, aeroKey)) Debug.note("status lookup for ${f.label}: no matching flight that day")
            val filled = if (status != null && adbKey.isNotBlank() && aeroKey.isNotBlank() && secondOpinion(status, now, auto)) {
                lastAeroTry[f.id] = now
                runCatching { aero(status, aeroKey) }.onFailure(::failed).getOrNull()?.let { fa -> countLookup(); merge(status, fa) } ?: status
            } else status
            if (adbKey.isNotBlank() || aeroKey.isNotBlank()) countLookup()
            filled?.let { n = it.copy(updated = now) }
        }
        // Without a status provider, takeoff and landing are inferred from ADS-B below.
        val key = adbKey + aeroKey
        val liveEvery = liveEvery(n, now, foreground)
        val liveDue = liveEvery != null && (!auto || lastLiveTry[n.id]?.let { Duration.between(it, now) >= liveEvery } != false)
        if (liveDue && n.arrActual == null) {
            lastLiveTry[n.id] = now
            val l = live(n)
            if (l != null) {
                n = n.copy(live = l, aircraft = n.aircraft.ifEmpty { l.aircraft }, reg = n.reg.ifEmpty { l.reg })
                // Without a status API, estimate takeoff and landing from where the plane is and how fast it's going.
                // ponytail: straight line at current ground speed, so a few minutes off with wind and approach paths.
                val o = n.origin; val d = n.dest
                val kmh = l.speedKt * 1.852
                if (!l.onGround && key.isBlank() && o != null && d != null && kmh > 200) {
                    val flown = distKm(o.lat, o.lon, l.lat, l.lon)
                    val left = distKm(l.lat, l.lon, d.lat, d.lon)
                    if (n.depActual == null) n = n.copy(depActual = l.at.minusSeconds((flown / kmh * 3600).toLong() + 600))
                    n = n.copy(arrEst = l.at.plusSeconds((left / kmh * 3600).toLong() + 900))
                }
            } else if (key.isBlank() && n.live != null && !n.live!!.onGround && n.arrActual == null) {
                // Stopped broadcasting: landed if the last fix was low and near the destination.
                val d = n.dest
                val nearDest = d != null && distKm(n.live!!.lat, n.live!!.lon, d.lat, d.lon) < 60 && n.live!!.altFt < 6000
                if (nearDest) n = n.copy(arrActual = n.live!!.at)
            }
            if (l != null && l.onGround && key.isBlank() && n.depActual != null && n.dest?.let { distKm(l.lat, l.lon, it.lat, it.lon) < 10 } == true) {
                n = n.copy(arrActual = n.arrActual ?: l.at)
            }
        }
        if (inboundDue(n, now, auto, foreground)) {
            lastInboundTry[n.id] = now
            val prev = n.inbound
            val fresh = runCatching { inbound(n) }.getOrNull()?.let { i ->
                // Remember when it touched down here: airborne last time, on the ground now.
                val landed = prev?.landedAt?.takeIf { i.onGround } ?: if (prev != null && !prev.onGround && i.onGround) i.at else null
                i.copy(landedAt = landed, firstSeen = prev?.firstSeen ?: i.at)
            }
            val o = n.origin
            val quietAfterLanding = fresh == null && prev != null && !prev.onGround && prev.to == n.from && o != null &&
                prev.eta != null && now > prev.eta.minus(Duration.ofMinutes(10))
            n = n.copy(
                inbound = when {
                    fresh != null -> fresh
                    // Went quiet around its expected landing here: it landed and parked (transponders go off at the gate).
                    quietAfterLanding -> prev!!.copy(
                        onGround = true, lat = o!!.lat, lon = o.lon, altFt = 0, speedKt = 0, eta = null, landedAt = prev.eta,
                    )
                    else -> prev
                },
            )
        }
        return runCatching { refreshWeather(n) }.getOrDefault(n)
    }
}
