package com.aria.air

import kotlinx.coroutines.runBlocking
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
import java.time.ZoneId

/** Radar tile math and rules, and weather along the route, against canned responses. */
class RadarTest : AirTest() {
    private val responses = linkedMapOf<String, String>()
    private val requested = mutableListOf<String>()

    @Before
    fun fakeNetwork() {
        Api.http = { url, _ -> requested += url; responses.entries.firstOrNull { it.key in url }?.value ?: throw Api.HttpError(404, "HTTP 404") }
    }

    @After
    fun restore() {
        Api.http = { _, _ -> error("no network in tests") }
        Prefs.setFlag(Layers.RADAR, true)
    }

    // ---------- Tiles ----------

    @Test
    fun tileMath() {
        assertEquals(0.0, Radar.tileX(-180.0, 3), 1e-9)
        assertEquals(4.0, Radar.tileX(0.0, 3), 1e-9)
        assertEquals(4.0, Radar.tileY(0.0, 3), 1e-9)
        // Denver at zoom 7: tile (26, 48), as on any slippy map.
        assertEquals(26, Radar.tileX(-104.99, 7).toInt()); assertEquals(48, Radar.tileY(39.74, 7).toInt())
        for (lat in listOf(-60.0, -10.0, 0.0, 33.3, 71.0)) assertEquals(lat, Radar.tileLat(Radar.tileY(lat, 5), 5), 1e-9)
    }

    @Test
    fun planCoversTheBoxWithFewTiles() {
        val dfw = Db.airport("DFW")!!; val bos = Db.airport("BOS")!!
        val b = Radar.box(greatCircle(dfw.lat, dfw.lon, bos.lat, bos.lon))
        assertTrue(b.lonMin < dfw.lon - 9 && b.lonMax > bos.lon + 9 && b.latMin < dfw.lat - 6 && b.latMax > bos.lat + 6)
        val p = Radar.plan(b)
        assertTrue(p.nx * p.ny <= 9)
        assertTrue(p.z in 1..7)
        assertTrue(p.x0 <= Radar.tileX(b.lonMin, p.z) && p.x0 + p.nx >= Radar.tileX(b.lonMax, p.z))
        assertTrue(p.y0 <= Radar.tileY(b.latMax, p.z) && p.y0 + p.ny >= Radar.tileY(b.latMin, p.z))
        // A hop gets a sharper zoom, capped at RainViewer's 7.
        val pit = Db.airport("PIT")!!; val cle = Db.airport("CLE")!!
        val hop = Radar.plan(Radar.box(greatCircle(pit.lat, pit.lon, cle.lat, cle.lon)))
        assertTrue(hop.z > p.z && hop.z <= 7)
    }

    @Test
    fun tileUrlWrapsAcrossTheDateLine() {
        assertEquals("H/v2/radar/x/512/3/7/2/2/1_1.png", Radar.tileUrl("H/v2/radar/x", 3, -1, 2))
        assertEquals("H/512/3/0/2/2/1_1.png", Radar.tileUrl("H", 3, 8, 2))
    }

    @Test
    fun reprojectPutsEachLatLonOnItsTilePixel() {
        // One zoom-2 tile x=0,y=1: lon -180..-90, lat 66.5..0. Each source pixel holds its own row number.
        val p = Radar.Plan(2, 0, 1, 1, 1)
        val T = Radar.TILE
        val src = arrayOf<IntArray?>(IntArray(T * T) { it / T })
        val b = GeoBox(-170.0, -100.0, 10.0, 60.0)
        val out = Radar.reproject(src, p, b, 70, 50)
        fun row(lat: Double) = ((Radar.tileY(lat, 2) - 1) * T).toInt()
        assertEquals(row(59.5), out[0])            // top row: 60°N edge (pixel centre at 59.5)
        assertEquals(row(10.5), out[49 * 70])      // bottom row
        assertEquals(row(35.5), out[24 * 70 + 35]) // middle
        // Rows are linear in latitude like the map, so mercator rows bunch up toward the pole.
        assertTrue(out[10 * 70] - out[0] > out[49 * 70] - out[39 * 70])
        // Outside the tiles: transparent.
        assertEquals(0, Radar.reproject(src, p, GeoBox(-80.0, -70.0, 10.0, 20.0), 4, 4).sum())
    }

    @Test
    fun recolorsToGreenYellowRed() {
        fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
        assertEquals(0, Radar.recolor(argb(150, 206, 192, 135)))   // faint beige: dropped
        assertEquals(0, Radar.recolor(0))
        val light = Radar.recolor(argb(255, 136, 221, 238)); val heavy = Radar.recolor(argb(255, 0, 71, 104))
        for (c in listOf(light, heavy)) assertTrue((c shr 8 and 0xFF) > (c shr 16 and 0xFF) && (c shr 8 and 0xFF) > (c and 0xFF)) // green
        assertTrue((light ushr 24) < (heavy ushr 24))               // more rain, more solid
        assertEquals(argb(255, 255, 210, 0), Radar.recolor(argb(255, 255, 210, 0))) // yellow to red kept
    }

    @Test
    fun fadesAtTheEdges() {
        val px = Radar.fade(IntArray(100) { -1 }, 10, 10) // opaque white
        assertEquals(0, px[0] ushr 24)
        assertEquals(255, px[5 * 10 + 5] ushr 24)
        assertEquals(0xFFFFFF, px[0] and 0xFFFFFF)
    }

    @Test
    fun latestFrame() = runBlocking {
        responses["weather-maps.json"] = """{"host":"https://tilecache.rainviewer.com","radar":{"past":[
            {"time":1790929200,"path":"/v2/radar/a"},{"time":1790936400,"path":"/v2/radar/b"}],"nowcast":[]}}"""
        assertEquals("https://tilecache.rainviewer.com/v2/radar/b" to Instant.ofEpochSecond(1790936400), Radar.frame())
        responses.clear()
        assertNull(Radar.frame())
    }

    @Test
    fun radarOnlyAroundTheFlight() {
        assertFalse(Radar.shown(flight(hours(7)), NOW))     // too early: radar says nothing about then
        assertTrue(Radar.shown(flight(hours(5)), NOW))
        assertTrue(Radar.shown(flight(hours(-2)), NOW))     // in the air
        assertFalse(Radar.shown(flight(hours(-6)), NOW))    // landed
        assertFalse(Radar.shown(flight(hours(-2)).copy(arrActual = NOW.minusSeconds(60)), NOW))
        assertFalse(Radar.shown(flight(hours(1)).copy(cancelled = true), NOW))
        Prefs.setFlag(Layers.RADAR, false)
        assertFalse(Radar.shown(flight(hours(1)), NOW))
        assertTrue(Radar.due(null, NOW))
        assertFalse(Radar.due(NOW.minus(min(9)), NOW))
        assertTrue(Radar.due(NOW.minus(min(10)), NOW))
    }

    // ---------- Weather along the route ----------

    @Test
    fun samplesAlongTheArcAtTheTimeOverhead() {
        val f = flight(hours(2), block = hours(9))
        val s = RouteWeather.samples(f)
        assertEquals(RouteWeather.N, s.size)
        assertEquals(f.depSched!!.plus(hours(1)), s.first().third)
        assertEquals(f.depSched!!.plus(hours(8)), s.last().third)
        val sfo = f.origin!!; val bos = f.dest!!
        assertTrue(s.zipWithNext().all { (a, b) -> distKm(a.first, a.second, bos.lat, bos.lon) > distKm(b.first, b.second, bos.lat, bos.lon) })
        assertEquals(f.distanceKm / 9, distKm(sfo.lat, sfo.lon, s[0].first, s[0].second), 5.0)
        // Longitudes come back in -180..180 even across the date line.
        assertTrue(RouteWeather.samples(flight(from = "SFO", to = "HND")).all { it.second in -180.0..180.0 })
    }

    private fun hourly(code: (Int) -> Int, cape: String = "[${(0..47).joinToString(",") { "0" }}]") =
        """{"hourly":{"weather_code":[${(0..47).joinToString(",") { "${code(it)}" }}],"precipitation":[${(0..47).joinToString(",") { if (code(it) >= 61) "2.5" else "0" }}],
        "cloud_cover":[${(0..47).joinToString(",") { "50" }}],"cape":$cape}}"""

    @Test
    fun fetchesAllPointsInOneRequest() = runBlocking {
        val f = flight(hours(2), block = hours(9)) // 14:00Z to 23:00Z
        // Rain over the third point only (15:00Z... 17:00Z at 9 h / 9).
        val atThird = RouteWeather.samples(f)[2].third
        val hour = atThird.atZone(java.time.ZoneOffset.UTC).hour
        responses["api.open-meteo.com"] = "[" + (0 until RouteWeather.N).joinToString(",") { i -> hourly({ h -> if (i == 2 && h == hour) 95 else 1 }) } + "]"
        val w = RouteWeather.fetch(f)!!
        assertEquals(1, requested.size)
        assertTrue(requested[0].contains("hourly=weather_code,precipitation,cloud_cover,cape"))
        assertEquals(RouteWeather.N, w.pts.size)
        assertEquals(Sky.Storms, w.pts[2].sky())
        assertEquals(listOf(Sky.Clear), w.pts.filterIndexed { i, _ -> i != 2 }.map { it.sky() }.distinct())
        assertEquals(NOW, w.fetched)
        // Long ago: the ERA5 archive, which has no CAPE (nulls).
        val old = flight(hours(-24 * 400))
        responses.clear()
        responses["archive-api"] = "[" + (0 until RouteWeather.N).joinToString(",") { hourly({ 3 }, "[" + (0..47).joinToString(",") { "null" } + "]") } + "]"
        val o = RouteWeather.fetch(old)!!
        assertTrue(requested.last().startsWith("https://archive-api.open-meteo.com/v1/archive"))
        assertNull(o.pts[0].cape)
        assertNull(RouteWeather.fetch(flight(hours(24 * 30)))) // too far out to forecast
    }

    @Test
    fun refreshedWhileUpcomingThenOnceAfterLanding() = runBlocking {
        responses["open-meteo.com"] = "[" + (0 until RouteWeather.N).joinToString(",") { hourly({ 0 }) } + "]"
        val fresh = RouteWx(emptyList(), NOW.minus(hours(1)))
        val f = flight(hours(24 * 5))
        assertEquals(fresh, RouteWeather.refresh(f.copy(routeWx = fresh)).routeWx) // daily is enough days out
        assertEquals(NOW, RouteWeather.refresh(f).routeWx!!.fetched)                // missing: fetched
        val landed = flight(hours(-10))
        assertEquals(NOW, RouteWeather.refresh(landed.copy(routeWx = RouteWx(emptyList(), NOW.minus(hours(12))))).routeWx!!.fetched)
        val record = RouteWx(emptyList(), NOW.minus(hours(2)))                      // fetched after landing: final
        assertEquals(record, RouteWeather.refresh(landed.copy(routeWx = record)).routeWx)
    }

    @Test
    fun jsonRoundTrip() {
        val w = RouteWx(listOf(
            RoutePt(39.86, -104.67, Instant.parse("2026-10-02T15:40:00Z"), 95, 3.2, 90, 1800.0),
            RoutePt(41.0, -95.5, Instant.parse("2026-10-02T16:20:00Z"), 1, 0.0, 10, null),
        ), NOW)
        val f = flight().copy(routeWx = w)
        assertEquals(f, flightFrom(JSONObject(f.toJson().toString())))
        val plain = flight()
        assertFalse(plain.toJson().has("routeWx"))
        assertEquals(plain, flightFrom(JSONObject(plain.toJson().toString()))) // old backups load as before
        val backup = Store.json(listOf(f, plain))
        assertEquals(listOf(f, plain), Store.parse(backup))
    }

    @Test
    fun summaryOnlyWhenWorthSaying() {
        val utc = ZoneId.of("UTC")
        val den = Db.airport("DEN")!!
        fun pt(code: Int, mm: Double = 0.0, cape: Double? = null, lat: Double = den.lat, lon: Double = den.lon) =
            RoutePt(lat, lon, Instant.parse("2026-10-02T15:40:00Z"), code, mm, 50, cape)
        assertNull(RouteWx(listOf(pt(0), pt(3), pt(51)), NOW).summary(utc)) // clear, cloud, drizzle: nothing to say
        assertEquals("Storms near Denver around 9:40 AM", RouteWx(listOf(pt(61, 2.0), pt(95)), NOW).summary(utc))
        assertEquals("Storms near Denver around 9:40 AM", RouteWx(listOf(pt(63, 2.0, cape = 1500.0)), NOW).summary(utc))
        assertEquals("Rain near Denver around 9:40 AM", RouteWx(listOf(pt(63, 2.0, cape = 200.0)), NOW).summary(utc))
        assertEquals("Snow en route around 3:40 PM", RouteWx(listOf(pt(73, lat = 45.0, lon = -40.0)), NOW).summary(utc))
    }
}
