package com.aria.air

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.sync.*
import com.aria.air.util.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Lookup cadences and the network code against canned responses (no real requests). */
class ApiTest : AirTest() {
    /** URL fragment -> response body; anything else is a 404. */
    private val responses = linkedMapOf<String, String>()
    private val requested = mutableListOf<String>()
    private var headers = mapOf<String, String>()

    @Before
    fun fakeNetwork() {
        Http.transport = { url, h ->
            requested += url; headers = h
            responses.entries.firstOrNull { it.key in url }?.value ?: throw HttpError(404, "HTTP 404")
        }
    }

    @After
    fun restore() {
        Http.transport = { _, _ -> error("no network in tests") }
        Prefs.adbKey = ""; Prefs.aeroKey = ""
    }

    private val sfo get() = Db.airport("SFO")!!
    private val bos get() = Db.airport("BOS")!!

    // ---------- FlightAware inbound ----------

    @Test
    fun flightAwareInboundCoversAParkedPlane() {
        val ours = flight(hours(1), from = "PIT", to = "SFO") // the plane's next flight is ours, from PIT
        fun fa(vararg kv: Pair<String, Any>) = org.json.JSONObject().apply {
            put("ident_icao", "UAL1234")
            put("origin", org.json.JSONObject().put("code_iata", "ORD"))
            put("destination", org.json.JSONObject().put("code_iata", "PIT"))
            kv.forEach { (k, v) -> put(k, v) }
        }
        val pit = Db.airport("PIT")!!
        // Landed at PIT: on the ground here, with the landing time, so "at the gate".
        val landed = FlightAware.inboundFrom(ours, fa("actual_off" to "2026-10-02T09:00:00Z", "actual_on" to "2026-10-02T10:20:00Z", "actual_in" to "2026-10-02T10:28:00Z"), NOW)!!
        assertTrue(landed.onGround)
        assertEquals(pit.lat, landed.lat, 0.001)
        assertEquals(Instant.parse("2026-10-02T10:28:00Z"), landed.landedAt)
        assertTrue(ours.copy(inbound = landed).inboundHere())
        // In the air: halfway by FlightAware's progress, arriving at the estimated gate time.
        val flying = FlightAware.inboundFrom(ours, fa("actual_off" to "2026-10-02T11:00:00Z", "progress_percent" to 50, "estimated_in" to "2026-10-02T12:30:00Z"), NOW)!!
        assertFalse(flying.onGround)
        assertEquals(Instant.parse("2026-10-02T12:30:00Z"), flying.eta)
        assertEquals((Db.airport("ORD")!!.lat + pit.lat) / 2, flying.lat, 0.001)
        // Not left yet: still at ORD, no arrival estimate to judge by.
        val waiting = FlightAware.inboundFrom(ours, fa(), NOW)!!
        assertTrue(waiting.onGround)
        assertNull(waiting.eta)
        assertFalse(ours.copy(inbound = waiting).inboundHere())
    }

    @Test
    fun flightAwareIsOnlyASecondOpinion() {
        val soon = flight(hours(3), from = "PIT", to = "SFO")
        // Routine: only while AeroDataBox is missing the tail number or gate, in the last 12 h.
        assertTrue(FlightSync.secondOpinion(soon, NOW, auto = true))
        assertFalse(FlightSync.secondOpinion(soon.copy(reg = "N12345", depGate = "A8"), NOW, auto = true))
        assertFalse(FlightSync.secondOpinion(flight(hours(20)), NOW, auto = true))
        // The refresh button asks within two days either side, whatever is known.
        assertTrue(FlightSync.secondOpinion(soon.copy(reg = "N12345", depGate = "A8"), NOW, auto = false))
        assertFalse(FlightSync.secondOpinion(flight(hours(24 * 5)), NOW, auto = false))
        // Merging: FlightAware's gates and tail win, AeroDataBox's times stay.
        val adb = soon.copy(arrGate = "E4", baggage = "F5")
        val fa = soon.copy(depGate = "A8", arrGate = "E12", reg = "N12345", depSched = NOW)
        val m = FlightSync.merge(adb, fa)
        assertEquals("A8", m.depGate)
        assertEquals("E12", m.arrGate)
        assertEquals("N12345", m.reg)
        assertEquals("F5", m.baggage)
        assertEquals(adb.depSched, m.depSched)
        assertTrue(m.faAt != null)
    }

    // ---------- Cadences ----------

    @Test
    fun statusLookupsRampWithDistance() {
        assertEquals(Duration.ofDays(7), Cadence.statusEvery(flight(hours(24 * 30)), NOW))
        assertEquals(Duration.ofDays(1), Cadence.statusEvery(flight(hours(24 * 7)), NOW))
        assertEquals(hours(12), Cadence.statusEvery(flight(hours(48)), NOW))
        assertEquals(hours(2), Cadence.statusEvery(flight(hours(10)), NOW))
        assertEquals(min(20), Cadence.statusEvery(flight(hours(2)), NOW))
        assertEquals(min(45), Cadence.statusEvery(flight(hours(-1)), NOW))      // cruising
        assertEquals(min(20), Cadence.statusEvery(flight(min(-270)), NOW))      // landing within 45 min
        assertNull(Cadence.statusEvery(flight(hours(-24 * 30)), NOW))           // past: never automatically
        assertNull(Cadence.statusEvery(flight(hours(2)).copy(cancelled = true), NOW))
        assertNull(Cadence.statusEvery(flight(hours(-6)).copy(arrActual = NOW), NOW))
    }

    @Test
    fun livePositionPollingIsSparing() {
        assertNull(Cadence.liveEvery(flight(hours(24 * 5)), NOW, foreground = false))  // days away: never
        assertEquals(min(10), Cadence.liveEvery(flight(hours(1)), NOW, foreground = false))   // at the gate: catch takeoff
        assertEquals(min(10), Cadence.liveEvery(flight(hours(-1)), NOW, foreground = false))  // cruising
        assertEquals(min(3), Cadence.liveEvery(flight(min(-280)), NOW, foreground = false))   // near landing
        assertEquals(min(1), Cadence.liveEvery(flight(hours(-1)), NOW, foreground = true))    // watching the flight page
        assertNull(Cadence.liveEvery(flight(hours(-6)).copy(arrActual = NOW), NOW, foreground = true))
        assertEquals(min(10), Cadence.liveEvery(flight(null).copy(date = localToday()), NOW, foreground = false)) // maybe flying
    }

    @Test
    fun callsigns() {
        assertEquals("UAL1234", callsign("UA", "1234"))
        assertEquals("SWA12", callsign("WN", "0012"))
        assertEquals("ZZ12", callsign("ZZ", "12"))
    }

    // ---------- adsb.lol ----------

    @Test
    fun route() = runBlocking {
        responses["routes/UA/UAL100.json"] = """{"_airport_codes_iata":"SFO-DEN-BOS"}"""
        assertEquals(listOf("SFO", "DEN", "BOS"), AdsbLol.route("UA", "100"))
        assertEquals(listOf<String>(), AdsbLol.route("UA", "101"))  // 404: unknown
    }

    private fun ac(lat: Double, lon: Double, alt: String = "35000", extra: String = "") =
        """{"lat":$lat,"lon":$lon,"alt_baro":$alt,"gs":480.5,"track":70,"baro_rate":-64,"seen_pos":2.0,"t":"B38M","r":"N12345"$extra}"""

    @Test
    fun liveTakesThePlausiblePlane() = runBlocking {
        val f = flight(hours(-1))
        val p = greatCircle(sfo.lat, sfo.lon, bos.lat, bos.lon, 100)
        // No position, then another leg far down the route, then ours.
        responses["v2/callsign/UAL100"] = """{"ac":[{"flight":"UAL100"},${ac(p[90].first, p[90].second)},${ac(p[20].first, p[20].second)}]}"""
        val l = AdsbLol.live(f)!!
        assertEquals(p[20].first, l.lat, 1e-9)
        assertEquals(35000, l.altFt); assertEquals(480, l.speedKt); assertEquals(-64, l.vrateFpm)
        assertEquals(NOW.minusSeconds(2), l.at); assertFalse(l.onGround)
        assertEquals("B38M", l.aircraft); assertEquals("N12345", l.reg)
        // At the gate, the altitude is the word "ground".
        responses["v2/callsign/UAL100"] = """{"ac":[${ac(sfo.lat, sfo.lon, "\"ground\"")}]}"""
        val g = AdsbLol.live(flight(hours(1)))!!
        assertTrue(g.onGround); assertEquals(0, g.altFt)
        responses["v2/callsign/UAL100"] = """{"ac":[]}"""
        assertNull(AdsbLol.live(f))
        responses.clear()
        assertNull(AdsbLol.live(f))  // offline
    }

    // ---------- AeroDataBox ----------

    private fun adbLeg(day: String, from: String, status: String, extra: String = "") = """
        {"departure":{"airport":{"iata":"$from"},"scheduledTime":{"utc":"${day} 15:00Z","local":"${day} 08:00-07:00"},
          "revisedTime":{"utc":"${day} 15:20Z"},"runwayTime":{"utc":"${day} 15:32Z"},"terminal":"3","gate":"F12"},
         "arrival":{"airport":{"iata":"BOS"},"scheduledTime":{"utc":"${day} 20:30Z"},"predictedTime":{"utc":"${day} 20:45Z"},
          "terminal":"B","gate":"B5","baggageBelt":"4"$extra},
         "status":"$status","aircraft":{"model":"Boeing 737 MAX 8","reg":"N12345"}}
    """

    @Test
    fun aeroDataBox() = runBlocking {
        val f = flight(null)
        val t = { s: String -> Instant.parse("2026-10-02T${s}:00Z") }
        responses["flights/number/UA100/2026-10-02"] =
            "[${adbLeg("2026-10-01", "SFO", "Arrived")},${adbLeg("2026-10-02", "DEN", "Expected")},${adbLeg("2026-10-02", "SFO", "EnRoute")}]"
        val n = AeroDataBox.status(f, "key")!!
        assertEquals("SFO", n.from); assertEquals("BOS", n.to)
        assertEquals(t("15:00"), n.depSched); assertEquals(t("15:20"), n.depEst); assertEquals(t("15:32"), n.depActual)
        assertEquals(t("20:30"), n.arrSched); assertEquals(t("20:45"), n.arrEst); assertNull(n.arrActual)
        assertEquals("3", n.depTerminal); assertEquals("F12", n.depGate)
        assertEquals("B", n.arrTerminal); assertEquals("B5", n.arrGate); assertEquals("4", n.baggage)
        assertEquals("Boeing 737 MAX 8", n.aircraft); assertEquals("N12345", n.reg)
        assertFalse(n.cancelled); assertFalse(n.diverted)
        assertEquals("key", headers["X-RapidAPI-Key"])
        // The same number flown elsewhere that day isn't this flight: no status, and the route stays ours.
        responses["flights/number/UA100/2026-10-02"] = "[${adbLeg("2026-10-02", "DEN", "Expected")}]"
        // Reported, though: the number flies DEN → BOS that day.
        assertEquals("DEN → BOS", AeroDataBox.status(f, "key")!!.mismatch)
        // A saved route is never replaced by the lookup's (here it says BOS); only a blank one is filled.
        responses["flights/number/UA100/2026-10-02"] = "[${adbLeg("2026-10-02", "SFO", "Expected")}]"
        AeroDataBox.status(f.copy(to = "EWR"), "key")!!.let { assertEquals("EWR", it.to); assertEquals("SFO → BOS", it.mismatch) }
        assertEquals("", AeroDataBox.status(f, "key")!!.mismatch)
        assertEquals("BOS", AeroDataBox.status(f.copy(to = ""), "key")!!.to)
    }

    @Test
    fun aeroDataBoxLeavesFlightAwaresGatesAlone() = runBlocking {
        responses["flights/number/UA100/2026-10-02"] = "[${adbLeg("2026-10-02", "SFO", "Expected")}]"
        // FlightAware gave the gates and tail an hour ago: they stay, AeroDataBox's times still come through.
        val fa = flight(null).copy(depGate = "A8", arrGate = "E12", reg = "N99999", faAt = NOW.minus(hours(1)))
        val n = AeroDataBox.status(fa, "key")!!
        assertEquals("A8", n.depGate); assertEquals("E12", n.arrGate); assertEquals("N99999", n.reg)
        assertEquals(Instant.parse("2026-10-02T15:20:00Z"), n.depEst)
        // Six hours on, AeroDataBox's own values are used again.
        val old = AeroDataBox.status(fa.copy(faAt = NOW.minus(hours(7))), "key")!!
        assertEquals("F12", old.depGate); assertEquals("N12345", old.reg)
        // AeroDataBox repeating the gate FlightAware overruled is stale: no dispute. Changing its answer is a dispute.
        assertFalse(AeroDataBox.status(fa.copy(adbSaid = "F12|B5"), "key")!!.disputed)
        val changed = AeroDataBox.status(fa.copy(adbSaid = "F10|B5"), "key")!!
        assertTrue(changed.disputed)
        assertEquals("A8", changed.depGate) // kept until FlightAware confirms
        assertTrue(FlightSync.secondOpinion(changed.copy(depSched = NOW.minus(hours(1)), arrSched = NOW.plus(hours(3))), NOW, auto = true))
        // An empty value from AeroDataBox never clears a known one.
        responses["flights/number/UA100/2026-10-02"] = "[${adbLeg("2026-10-02", "SFO", "Expected").replace("\"gate\":\"F12\"", "\"gate\":null")}]"
        assertEquals("A8", AeroDataBox.status(flight(null).copy(depGate = "A8"), "key")!!.depGate)
    }

    @Test
    fun staleAeroDataBoxCantSayOnTime() = runBlocking {
        val onTime = adbLeg("2026-10-02", "SFO", "Expected").replace("15:20Z", "15:00Z")
        fun asOf(t: String) = onTime.replace("\"status\"", "\"lastUpdatedUtc\":\"2026-10-02 $t\",\"status\"")
        // Data from 11:50, 10 min before NOW: its "on time" estimate stands.
        responses["flights/number/UA100/2026-10-02"] = "[${asOf("11:50Z")}]"
        assertTrue(AeroDataBox.status(flight(null), "key")!!.depEst != null)
        // From 11:00, an hour old with departure 3 h away: no estimate, so no "On time".
        responses["flights/number/UA100/2026-10-02"] = "[${asOf("11:00Z")}]"
        assertNull(AeroDataBox.status(flight(null), "key")!!.depEst)
        // But an old delay is still a delay.
        responses["flights/number/UA100/2026-10-02"] =
            "[${adbLeg("2026-10-02", "SFO", "Expected").replace("\"status\"", "\"lastUpdatedUtc\":\"2026-10-02 11:00Z\",\"status\"")}]"
        assertEquals(Instant.parse("2026-10-02T15:20:00Z"), AeroDataBox.status(flight(null), "key")!!.depEst)
    }

    @Test
    fun aeroDataBoxStates() = runBlocking {
        val f = flight(null)
        responses["flights/number/UA100"] = "[${adbLeg("2026-10-02", "SFO", "Expected")}]"
        assertNull(AeroDataBox.status(f, "key")!!.depActual)  // not departed: no takeoff time
        responses["flights/number/UA100"] = "[${adbLeg("2026-10-02", "SFO", "Arrived", ""","revisedTime":{"utc":"2026-10-02 20:41Z"}""")}]"
        assertEquals(Instant.parse("2026-10-02T20:41:00Z"), AeroDataBox.status(f, "key")!!.arrActual)
        responses["flights/number/UA100"] = "[${adbLeg("2026-10-02", "SFO", "CanceledUncertain")}]"
        assertTrue(AeroDataBox.status(f, "key")!!.cancelled)
        responses["flights/number/UA100"] = "[${adbLeg("2026-10-02", "SFO", "Diverted")}]"
        assertTrue(AeroDataBox.status(f, "key")!!.diverted)
        responses["flights/number/UA100"] = "[${adbLeg("2026-10-03", "SFO", "Expected")}]"
        assertNull(AeroDataBox.status(f, "key"))  // only another day's run
        responses["flights/number/UA100"] = ""
        assertNull(AeroDataBox.status(f, "key"))  // 204
    }

    // ---------- FlightAware ----------

    @Test
    fun flightAware() = runBlocking {
        val f = flight(null)
        responses["aeroapi/flights/UAL100"] = """{"flights":[
            {"origin":{"code_iata":"SFO"},"destination":{"code_iata":"BOS"},"scheduled_out":"2026-10-03T15:00:00Z"},
            {"origin":{"code_iata":"SFO"},"destination":{"code_iata":"BOS"},"scheduled_out":"2026-10-02T15:00:00Z",
             "estimated_out":"2026-10-02T15:10:00Z","actual_out":null,"scheduled_in":"2026-10-02T20:30:00Z",
             "gate_origin":"F12","gate_destination":null,"baggage_claim":"4","aircraft_type":"B38M","registration":"N12345",
             "cancelled":false,"diverted":false}]}"""
        val n = FlightAware.status(f, "key")!!
        assertEquals("key", headers["x-apikey"])
        assertEquals(Instant.parse("2026-10-02T15:00:00Z"), n.depSched)
        assertEquals(Instant.parse("2026-10-02T15:10:00Z"), n.depEst)
        assertNull(n.depActual)
        assertEquals(Instant.parse("2026-10-02T20:30:00Z"), n.arrSched)
        assertEquals("F12", n.depGate); assertEquals("", n.arrGate); assertEquals("4", n.baggage)
        assertEquals("B38M", n.aircraft); assertEquals("N12345", n.reg)
        assertNull(FlightAware.status(f.copy(from = "LAX"), "key"))  // no leg from our airport
    }

    // ---------- Weather ----------

    private val hourly = """{"hourly":{"temperature_2m":[${(0..23).joinToString(",")}],"weather_code":[${(0..23).joinToString(",") { if (it == 15) "61" else "0" }}],
        "wind_speed_10m":[${(0..23).joinToString(",") { "${it * 2}" }}]}}"""

    @Test
    fun weather() = runBlocking {
        responses["open-meteo.com"] = hourly
        val at = Instant.parse("2026-10-03T15:20:00Z")
        assertEquals(Wx(15.0, 61, 30.0, at, NOW), OpenMeteo.weather(sfo, at))
        assertTrue(requested.last().startsWith("https://api.open-meteo.com/v1/forecast?latitude="))
        OpenMeteo.weather(sfo, NOW.minus(Duration.ofDays(90)))
        assertTrue(requested.last().startsWith("https://archive-api.open-meteo.com/v1/archive"))
        val before = requested.size
        assertNull(OpenMeteo.weather(sfo, NOW.plus(Duration.ofDays(30))))  // too far out: not even asked
        assertEquals(before, requested.size)
        responses.clear()
        assertNull(OpenMeteo.weather(sfo, at))
    }

    @Test
    fun weatherRefreshesLessOftenFarOut() = runBlocking {
        responses["open-meteo.com"] = hourly
        val f = flight(hours(24 * 5))
        val fresh = Wx(10.0, 0, 5.0, f.depSched!!, NOW.minus(hours(5)))
        val n = WeatherSync.refresh(f.copy(depWx = fresh, arrWx = fresh))
        assertEquals(fresh, n.depWx)  // 5 days out: daily is enough
        val near = flight(hours(6))
        val sameDay = fresh.copy(at = near.depSched!!)
        val m = WeatherSync.refresh(near.copy(depWx = sameDay, arrWx = sameDay))
        assertEquals(NOW, m.depWx!!.fetched)  // same day: every 3 h
        val past = flight(hours(-24))
        val old = Wx(10.0, 0, 5.0, past.depSched!!, NOW.minus(Duration.ofDays(1)))
        assertEquals(old, WeatherSync.refresh(past.copy(depWx = old, arrWx = old)).depWx) // happened: kept
    }

    // ---------- Inbound plane ----------

    @Test
    fun inboundPlaneHeadingHere() = runBlocking {
        val f = flight(hours(3)).copy(reg = "N12345")
        responses["v2/reg/N12345"] = """{"ac":[{"flight":"UAL523  ","lat":39.0,"lon":-110.0,"alt_baro":30000,"gs":450,"seen_pos":0}]}"""
        responses["routes/UA/UAL523.json"] = """{"_airport_codes_iata":"DEN-SFO"}"""
        val i = AdsbLol.inbound(f)!!
        assertEquals("UAL523", i.callsign); assertEquals("DEN", i.from); assertEquals("SFO", i.to)
        assertFalse(i.onGround)
        val km = distKm(39.0, -110.0, sfo.lat, sfo.lon)
        assertEquals(NOW.plusSeconds((km / (450 * 1.852) * 3600).toLong() + 600), i.eta)
        // Going somewhere else first: no arrival time here.
        responses["routes/UA/UAL523.json"] = """{"_airport_codes_iata":"DEN-LAX"}"""
        assertNull(AdsbLol.inbound(f)!!.eta)
    }

    @Test
    fun inboundAlreadyFlyingOurFlightIsNotInbound() = runBlocking {
        val f = flight(min(-30)).copy(reg = "N12345")
        val p = greatCircle(sfo.lat, sfo.lon, bos.lat, bos.lon, 100)
        responses["v2/reg/N12345"] = """{"ac":[{"flight":"UAL100","lat":${p[5].first},"lon":${p[5].second},"alt_baro":30000,"gs":450}]}"""
        assertNull(AdsbLol.inbound(f))
        // The earlier leg of our number, parked at our gate: that's our plane waiting.
        responses["v2/reg/N12345"] = """{"ac":[{"flight":"UAL100","lat":${sfo.lat},"lon":${sfo.lon},"alt_baro":"ground","gs":0}]}"""
        val i = AdsbLol.inbound(flight(hours(2)).copy(reg = "N12345"))!!
        assertTrue(i.onGround); assertEquals("SFO", i.to)
    }

    // ---------- Refresh ----------

    @Test
    fun refreshUsesTheStatusKeyOnlyWhenDue() = runBlocking {
        Prefs.adbKey = "key"
        val before = Lookups.thisMonth()
        responses["flights/number/UA100"] = "[${adbLeg("2026-10-03", "SFO", "Expected")}]"
        val f = flight(hours(27)).copy(date = TODAY.plusDays(1))
        val n = FlightSync.refresh(f, auto = true)
        assertEquals(NOW, n.updated); assertEquals("F12", n.depGate)
        assertEquals(before + 1, Lookups.thisMonth())
        // Checked a minute ago, 27 h out (every 12 h): not again, unless asked.
        requested.clear()
        FlightSync.refresh(n.copy(updated = NOW.minus(min(1))), auto = true)
        assertFalse(requested.any { "aerodatabox" in it })
        FlightSync.refresh(n.copy(updated = NOW.minus(min(1))), auto = false)
        assertTrue(requested.any { "aerodatabox" in it })
    }

    @Test
    fun refreshWithoutKeyEstimatesFromThePlane() = runBlocking {
        val f = flight(hours(-1))
        val p = greatCircle(sfo.lat, sfo.lon, bos.lat, bos.lon, 100)
        responses["v2/callsign/UAL100"] = """{"ac":[${ac(p[20].first, p[20].second)}]}"""
        val n = FlightSync.refresh(f)
        assertEquals("B38M", n.aircraft); assertEquals("N12345", n.reg)
        val kmh = 480 * 1.852
        val flown = distKm(sfo.lat, sfo.lon, p[20].first, p[20].second)
        assertEquals(n.live!!.at.minusSeconds((flown / kmh * 3600).toLong() + 600), n.depActual)
        assertTrue(n.arrEst!! > NOW)
        assertNull(n.updated)
    }

    @Test
    fun refreshInfersLandingWhenThePlaneGoesQuietNearby() = runBlocking {
        val f = flight(hours(-5)).let { it.copy(depActual = it.depSched) }
        val near = Live(bos.lat + 0.1, bos.lon, 3000, 160, 40.0, -700, NOW.minus(min(2)), false)
        val n = FlightSync.refresh(f.copy(live = near))
        assertEquals(near.at, n.arrActual)
        // Last seen high and far away: no landing assumed.
        assertNull(FlightSync.refresh(f.copy(live = near.copy(lat = 41.0, lon = -90.0, altFt = 35000))).arrActual)
    }

    @Test
    fun refreshDropsAnotherLegsTakeoff() = runBlocking {
        val f = flight(hours(2)).let { it.copy(depActual = it.depSched!!.minus(hours(4))) }
        assertNull(FlightSync.refresh(f).depActual)
    }

    @Test
    fun refreshRemembersTheInboundLanding() = runBlocking {
        val f = flight(hours(2)).copy(reg = "N12345")
        val flying = Inbound("UAL523", "DEN", "SFO", 38.0, -121.0, 8000, 250, false, NOW.plus(min(5)), NOW.minus(min(10)), firstSeen = NOW.minus(hours(1)))
        responses["v2/reg/N12345"] = """{"ac":[{"flight":"UAL523","lat":${sfo.lat},"lon":${sfo.lon},"alt_baro":"ground","gs":3}]}"""
        responses["routes/UA/UAL523.json"] = """{"_airport_codes_iata":"DEN-SFO"}"""
        val n = FlightSync.refresh(f.copy(inbound = flying))
        assertTrue(n.inbound!!.onGround)
        assertEquals(NOW, n.inbound!!.landedAt)
        assertEquals(NOW.minus(hours(1)), n.inbound!!.firstSeen)
        // Went quiet around its landing time: assume it landed and parked at the gate.
        responses.clear()
        val m = FlightSync.refresh(f.copy(id = "quiet", inbound = flying))
        assertTrue(m.inboundHere())
        assertEquals(flying.eta, m.inbound!!.landedAt)
    }

    @Test
    fun keyChecks() = runBlocking {
        Http.transport = { _, _ -> throw HttpError(403, "HTTP 403") }
        assertEquals("Key rejected (403): subscribe to AeroDataBox on RapidAPI", AeroDataBox.testKey("k"))
        assertEquals("Key rejected (403)", FlightAware.testKey("k"))
        Http.transport = { _, _ -> throw HttpError(429, "HTTP 429") }
        assertEquals("Monthly quota used up", AeroDataBox.testKey("k"))
        Http.transport = { _, _ -> throw java.io.IOException("No route to host") }
        assertEquals("No route to host", FlightAware.testKey("k"))
        Http.transport = { _, _ -> "[]" }
        assertNull(AeroDataBox.testKey("k")); assertNull(FlightAware.testKey("k"))
    }
}
