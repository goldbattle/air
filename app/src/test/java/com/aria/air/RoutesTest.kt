package com.aria.air

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Recorded tracks, adsb.lol trace parsing and what the flight map draws (Routes.kt). Fake data only. */
class RoutesTest : AirTest() {
    private val requested = mutableListOf<String>()
    private val responses = linkedMapOf<String, String>()

    @Before
    fun fakeNetwork() {
        Api.http = { url, _ -> requested += url; responses.entries.firstOrNull { it.key in url }?.value ?: throw Api.HttpError(404, "HTTP 404") }
    }

    @After
    fun restore() {
        Api.http = { _, _ -> error("no network in tests") }
    }

    private val sfo get() = Db.airport("SFO")!!

    @Test
    fun yesterdaysPathFromAnotherPlane() = runBlocking {
        // UA 100 SFO-BOS today; yesterday AeroDataBox says plane abc123 flew it, and its daily history has the leg.
        val f = flight(hours(5))
        Prefs.adbKey = "key"
        val yesterday = f.date.minusDays(1)
        responses["aerodatabox.p.rapidapi.com/flights/number/UA100/$yesterday"] =
            """[{"departure":{"airport":{"iata":"SFO"}},"arrival":{"airport":{"iata":"BOS"}},"aircraft":{"modeS":"ABC123"}}]"""
        val t0 = NOW.epochSecond - 30 * 3600
        responses["globe_history/${yesterday.toString().replace('-', '/')}/traces/23/trace_full_abc123.json"] = JSONObject()
            .put("timestamp", t0).put("trace", JSONArray()
                .put(JSONArray("[0, 37.7, -122.3, 2000, 200, 60, 2, 1500, {\"flight\": \"UAL100\"}]"))
                .put(JSONArray("[9000, 41.0, -95.0, 37000, 480, 80, 0, 0, null]"))
                .put(JSONArray("[18000, 42.3, -71.1, 2500, 160, 90, 0, -800, null]"))).toString()
        val legs = Routes.fetch(f)
        assertEquals(1, legs.size)
        assertEquals(t0, legs[0].first().t)
        Prefs.adbKey = ""
        assertTrue(Routes.fetch(f).isEmpty()) // no key: no lookups for other days
    }

    @Test
    fun inboundFromThePlanesTrace() {
        // Our flight leaves PIT in an hour; the plane's trace: a red-eye from SFO, last seen low over PIT 50 min ago.
        val ours = flight(hours(1), from = "PIT", to = "SFO").copy(hex = "a1b213")
        val t0 = NOW.epochSecond - 6 * 3600
        val trace = JSONObject().put("timestamp", t0).put("trace", JSONArray()
            .put(JSONArray("[0, 37.62, -122.38, 3000, 180, 60, 2, 1500, {\"flight\": \"UAL1300 \"}]"))
            .put(JSONArray("[9000, 41.5, -100.0, 37000, 480, 80, 0, 0, null]"))
            .put(JSONArray("[18600, 40.47, -80.30, 2200, 150, 100, 0, -700, null]"))
            .put(JSONArray("[18900, 40.49, -80.23, \"ground\", 15, 100, 0, 0, null]")))
        val i = Routes.arrivedHere(trace.toString(), ours, NOW)!!
        assertEquals("UAL1300", i.callsign); assertEquals("SFO", i.from); assertEquals("PIT", i.to)
        assertTrue(i.onGround)
        assertEquals(Instant.ofEpochSecond(t0 + 18600 + 180), i.landedAt)
        assertTrue(ours.copy(inbound = i).inboundHere())
        // A previous leg that ended somewhere else isn't our inbound.
        assertNull(Routes.arrivedHere(trace.toString(), ours.copy(from = "ORD"), NOW))
    }
    private val bos get() = Db.airport("BOS")!!

    /** A readsb trace_full: ground rows, then [legs] of (callsign, takeoff epoch s, from, to) a minute apart, 5 h each. */
    private fun trace(vararg legs: Triple<String, Long, Pair<Airport, Airport>>): String {
        val t0 = legs.minOf { it.second } - 600
        val rows = JSONArray()
        for ((cs, start, ab) in legs) {
            rows.put(JSONArray(listOf(start - 300 - t0, ab.first.lat, ab.first.lon, "ground", 5, 0, 2, 0, JSONObject().put("flight", "$cs  "), "adsb_icao")))
            greatCircle(ab.first.lat, ab.first.lon, ab.second.lat, ab.second.lon, 300).forEachIndexed { i, (la, lo) ->
                rows.put(JSONArray(listOf(start + i * 60 - t0, la, lo, 30000, 450, 80, 0, 0, JSONObject.NULL, "adsb_icao")))
            }
            rows.put(JSONArray(listOf(start + 301 * 60 - t0, ab.second.lat, ab.second.lon, "ground", 5, 0, 0, 0, JSONObject.NULL, "adsb_icao")))
        }
        return JSONObject().put("icao", "abc123").put("timestamp", t0).put("trace", rows).toString()
    }

    private val yesterday = NOW.minus(hours(24)).epochSecond

    @Test
    fun thinningKeepsOnePointEveryTwoMinutesAndCaps() {
        val pts = (0 until 240).map { Pt(37.0, -122.0 + it * 0.01, 30000, 1_000_000L + it * 30) } // 2 h, every 30 s
        val t = thin(pts)
        assertTrue(t.zipWithNext().all { (a, b) -> b.t - a.t >= TRACK_GAP })
        assertEquals(60, t.size)
        val long = thin((0 until 2000).map { Pt(37.0, -122.0, 0, it * TRACK_GAP) })
        assertTrue(long.size <= TRACK_MAX)
        assertEquals(1999 * TRACK_GAP, long.last().t) // the newest point always stays
        // Recording: only airborne fixes, and the plane's hex is remembered.
        val f = flight(hours(-1))
        val air = Live(40.0, -100.0, 35000, 450, 80.0, 0, NOW, false, hex = "abc123")
        val r = f.record(air).record(air.copy(at = NOW.plusSeconds(60))).record(air.copy(onGround = true, at = NOW.plusSeconds(600)))
        assertEquals(1, r.track.size)
        assertEquals("abc123", r.hex)
    }

    @Test
    fun jsonRoundTripWithAndWithoutTrack() {
        val f = flight(hours(-6)).copy(track = listOf(Pt(37.6188, -122.375, 1200, 1_790_000_000), Pt(40.1234, -100.5, 35000, 1_790_003_600)),
            hex = "abc123", trackAt = NOW)
        assertEquals(f, flightFrom(JSONObject(f.toJson().toString())))
        val plain = flight(hours(-6))
        val j = plain.toJson()
        assertFalse(j.has("track") || j.has("hex") || j.has("trackAt"))
        assertEquals(plain, flightFrom(JSONObject(j.toString()))) // an old backup without a track still loads
        // Saves racing each other keep both copies' points.
        val a = listOf(Pt(1.0, 1.0, 0, 0), Pt(2.0, 2.0, 0, 600))
        assertEquals(listOf(0L, 300L, 600L), mergeTracks(a, listOf(Pt(1.5, 1.5, 0, 300))).map { it.t })
    }

    @Test
    fun legsFromTraceKeepsCompleteLegsOfTheCallsign() {
        val f = flight(hours(24))
        val json = trace(
            Triple("UAL100", yesterday, sfo to bos),
            Triple("UAL200", yesterday + 6 * 3600, bos to sfo),
        )
        val legs = Routes.legsFrom(json, "UAL100", f)
        assertEquals(1, legs.size)
        assertEquals(yesterday, legs[0].first().t)
        assertTrue(distKm(legs[0].last().lat, legs[0].last().lon, bos.lat, bos.lon) < 50)
        assertTrue(legs[0].zipWithNext().all { (a, b) -> b.t - a.t >= TRACK_GAP })
        // Another flight's route under the same number: not a past path of ours.
        assertTrue(Routes.legsFrom(json, "UAL100", flight(hours(24), from = "SFO", to = "ORD")).isEmpty())
    }

    @Test
    fun fetchFindsThePlaneByCallsignAndSkipsThisVeryFlight() = runBlocking {
        val f = flight(hours(2)) // today's flight; the plane flew the number yesterday and is broadcasting it now
        responses["/v2/callsign/UAL100"] = """{"ac":[{"hex":"abc123","flight":"UAL100  ","lat":37.6,"lon":-122.4}]}"""
        responses["/data/traces/23/trace_full_abc123.json"] = trace(Triple("UAL100", yesterday, sfo to bos), Triple("UAL100", NOW.epochSecond + 7200, sfo to bos))
        val legs = Routes.fetch(f)
        assertEquals(1, legs.size)
        assertEquals(yesterday, legs[0].first().t)
        assertTrue(requested.any { it == "https://adsb.lol/data/traces/23/trace_full_abc123.json" })
    }

    @Test
    fun fillTrackReadsTheWholeFlightAfterLanding() = runBlocking {
        // Air only caught the middle of the flight; it landed an hour ago.
        val dep = NOW.minus(hours(6)).epochSecond
        val f = flight(hours(-6)).copy(depActual = NOW.minus(hours(6)), arrActual = NOW.minus(hours(1)), hex = "abc123",
            track = listOf(Pt(41.0, -95.0, 35000, dep + 2 * 3600)))
        responses["/data/traces/23/trace_full_abc123.json"] = trace(Triple("UAL100", dep, sfo to bos))
        val n = Routes.fillTrack(f, auto = true)
        assertNotNull(n.trackAt)
        assertTrue(n.track.size > 100)
        assertTrue(distKm(n.track.first().lat, n.track.first().lon, sfo.lat, sfo.lon) < 50)
        assertTrue(n.track.any { it.t == dep + 2 * 3600 }) // our own breadcrumb kept
        assertFalse(Routes.trackDue(n, NOW, auto = false)) // complete: never again
        // Two weeks ago, on the refresh button: the daily history files of its UTC days.
        requested.clear()
        val twoWeeks = java.time.Duration.ofDays(14)
        val old = flight(twoWeeks.negated()).copy(id = "old", date = TODAY.minusDays(14), depActual = NOW.minus(twoWeeks),
            arrActual = NOW.minus(twoWeeks).plus(hours(5)), hex = "abc123")
        Routes.fillTrack(old, auto = false)
        assertEquals(listOf("https://adsb.lol/globe_history/2026/09/18/traces/23/trace_full_abc123.json"), requested)
        // Not automatically for long-gone flights, nor ever past adsb.lol's history.
        assertFalse(Routes.trackDue(old, NOW, auto = true))
        assertFalse(Routes.trackDue(old.copy(arrActual = NOW.minus(java.time.Duration.ofDays(40))), NOW, auto = false))
    }

    @Test
    fun selection() {
        val past = listOf(Pt(sfo.lat, sfo.lon), Pt(45.0, -95.0), Pt(bos.lat, bos.lon))
        // Completed with a recorded path: that path, no arc, no past paths.
        val done = flight(hours(-8)).copy(arrActual = NOW.minus(hours(3)), track = listOf(Pt(42.0, -100.0, 35000, 1)))
        done.paths(listOf(past), emptyList(), 3).let {
            assertFalse(it.arc); assertEquals(3, it.flown.size); assertTrue(it.typical.isEmpty()); assertTrue(it.rest.isEmpty())
        }
        // Completed without one: the arc.
        done.copy(track = emptyList()).paths(listOf(past), emptyList(), 3).let { assertTrue(it.arc); assertTrue(it.flown.isEmpty()) }
        // Upcoming: fetched past paths, then my own recorded flights of the number, up to the limit; and the arc.
        val up = flight(hours(24))
        val mine = done.copy(id = "mine")
        up.paths(listOf(past), listOf(mine), 1).let { assertTrue(it.arc); assertEquals(listOf(past), it.typical) }
        up.paths(listOf(past), listOf(mine), 3).let { assertEquals(2, it.typical.size); assertEquals(mine.flownPath(true), it.typical[1]) }
        up.paths(emptyList(), emptyList(), 3).let { assertTrue(it.arc); assertTrue(it.typical.isEmpty()); assertTrue(it.flown.isEmpty()) }
        up.paths(listOf(past), listOf(mine), 0).let { assertTrue(it.typical.isEmpty()) } // Layers › Past routes off
        // Flying with a track: flown solid to the plane, then the rest of the arc from it.
        val l = Live(41.0, -95.0, 35000, 450, 80.0, 0, NOW, false)
        val flying = flight(hours(-2)).copy(depActual = NOW.minus(hours(2)), live = l, track = listOf(Pt(40.0, -105.0, 35000, 1)))
        flying.paths(emptyList(), emptyList(), 1).let {
            assertFalse(it.arc)
            assertEquals(Pt(41.0, -95.0), it.flown.last())
            assertEquals(it.flown.last().lat, it.rest.first().lat, 1e-6)
            assertEquals(bos.lat, it.rest.last().lat, 1e-6)
        }
        assertNull(flight(hours(24)).flownPath(false).firstOrNull())
    }
}
