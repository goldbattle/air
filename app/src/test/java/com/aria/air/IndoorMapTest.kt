package com.aria.air

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.sync.*
import com.aria.air.util.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/** Airport indoor maps: reading OpenStreetMap data, floors, gates, saving, and the download. */
class IndoorMapTest : AirTest() {
    @After
    fun restore() {
        Http.transport = { _, _ -> error("no network in tests") }
        IndoorMaps.maps.clear(); IndoorMaps.errors.clear(); IndoorMaps.bundled = { null }
    }

    private fun way(vararg p: Pair<Double, Double>) = p.joinToString(",", "[", "]") { """{"lat":${it.first},"lon":${it.second}}""" }

    /** A tiny Overpass answer: a gate, restrooms, a shop room, a terminal, stairs, a two-way multipolygon, and noise. */
    private val overpass = """
        {"elements":[
          {"type":"node","id":1,"lat":37.6150,"lon":-122.3900,"tags":{"aeroway":"gate","ref":"A08"}},
          {"type":"node","id":2,"lat":37.6151,"lon":-122.3901,"tags":{"amenity":"toilets","level":"1"}},
          {"type":"way","id":3,"tags":{"indoor":"room","shop":"books","name":"News","level":"1;2","opening_hours":"05:00-22:00"},
           "geometry":${way(37.0 to -122.0, 37.0 to -122.001, 37.001 to -122.001, 37.001 to -122.0, 37.0 to -122.0)}},
          {"type":"way","id":4,"tags":{"building":"terminal","name":"International"},
           "geometry":${way(37.61 to -122.39, 37.61 to -122.38, 37.62 to -122.38, 37.62 to -122.39, 37.61 to -122.39)}},
          {"type":"way","id":5,"tags":{"highway":"steps","indoor":"yes","level":"0-2"},
           "geometry":${way(37.0 to -122.0, 37.0005 to -122.0, 37.001 to -122.0)}},
          {"type":"relation","id":6,"tags":{"type":"multipolygon","indoor":"area","level":"1"},"members":[
            {"type":"way","role":"outer","geometry":${way(37.0 to -122.0, 37.0 to -122.01, 37.01 to -122.01)}},
            {"type":"way","role":"outer","geometry":${way(37.0 to -122.0, 37.01 to -122.0, 37.01 to -122.01)}}]},
          {"type":"node","id":7,"lat":37.0,"lon":-122.0,"tags":{"amenity":"parking"}},
          {"type":"node","id":8,"lat":37.0,"lon":-122.0}
        ]}
    """.trimIndent()

    @Test
    fun readsOverpass() {
        val m = IndoorMap.fromOverpass("SFO", overpass, NOW)
        assertEquals(listOf(Place.GATE, Place.TOILETS, Place.SHOP, Place.TERMINAL, Place.STAIRS, Place.AREA), m.features.map { it.kind })
        val shop = m.features[2]
        assertEquals("News", shop.name); assertEquals(listOf(1.0, 2.0), shop.levels); assertEquals("05:00-22:00", shop.hours)
        assertEquals(5, shop.rings.single().size)
        // Stairs drawn as a line become an icon at their middle, on floors 0 to 2.
        assertEquals(listOf(Pt(37.0005, -122.0)), m.features[4].rings.single())
        assertEquals(listOf(0.0, 1.0, 2.0), m.features[4].levels)
        // The relation's two halves are joined into one closed ring.
        val ring = m.features[5].rings.single()
        assertEquals(ring.first(), ring.last()); assertEquals(5, ring.size)
    }

    @Test
    fun gatesAndFloors() {
        val m = IndoorMap.fromOverpass("SFO", overpass, NOW)
        assertEquals("A08", m.gate("A8")?.ref)
        assertEquals("A08", m.gate("Gate a 8")?.ref)
        assertNull(m.gate("A80")); assertNull(m.gate(""))
        // An unmapped gate falls back to the closest number in its concourse; another concourse doesn't count.
        val g = IndoorMap("X", NOW, listOf("F5", "F9", "G11-G12", "A1").map { Feature(Place.GATE, ref = it, rings = listOf(listOf(Pt(0.0, 0.0)))) })
        assertEquals("F5", g.nearGate("F3")?.ref)
        assertEquals("F9", g.nearGate("F8")?.ref)
        assertNull(g.nearGate("D3"))
        assertEquals("G11-G12", g.gate("G12")?.ref)
        assertEquals(listOf("G11", "G12"), IndoorMap.gateKeys("G11-12"))
        assertEquals("B12", IndoorMap.gateKey("Gate B012"))
        assertEquals("102", IndoorMap.gateKey("102")) // zeros inside a number stay
        assertEquals(listOf(-1.0, 0.0, 1.5), IndoorMap.levels("1.5;-1;0;x"))
        // A floor needs a few things on it to be offered; the busiest one opens first.
        val floors = IndoorMap("X", NOW, List(3) { Feature(Place.SHOP, levels = listOf(1.0), rings = listOf(listOf(Pt(0.0, 0.0)))) } +
            List(4) { Feature(Place.FOOD, levels = listOf(2.0), rings = listOf(listOf(Pt(0.0, 0.0)))) } +
            Feature(Place.ROOM, levels = listOf(7.0), rings = listOf(listOf(Pt(0.0, 0.0)))) +
            List(5) { Feature(Place.ELEVATOR, levels = listOf(1.0, 2.0, 3.0, 4.0), rings = listOf(listOf(Pt(0.0, 0.0)))) })
        assertEquals(listOf(1.0, 2.0), floors.levels) // elevators alone don't add floors 3 and 4
        assertEquals(2.0, floors.mainLevel)
        val room7 = floors.features.first { it.kind == Place.ROOM }
        assertTrue(room7.on(7.0)); assertTrue(!room7.on(1.0))
        assertTrue(Feature(Place.GATE, rings = listOf(listOf(Pt(0.0, 0.0)))).on(3.0)) // no floor = every floor
    }

    @Test
    fun classifies() {
        fun kind(vararg t: Pair<String, String>) = IndoorMap.classify { k -> t.toMap()[k].orEmpty() }
        assertEquals(Place.LOUNGE, kind("indoor" to "room", "name" to "United Club"))
        assertEquals(Place.SHOP, kind("shop" to "clothes", "name" to "Club Monaco"))
        assertEquals(Place.SECURITY, kind("indoor" to "area", "name" to "TSA Checkpoint 3"))
        assertEquals(Place.MEDICAL, kind("indoor" to "yes", "emergency" to "defibrillator"))
        assertEquals(Place.COFFEE, kind("amenity" to "cafe"))
        assertNull(kind("shop" to "vacant"))
        assertNull(kind("indoor" to "door"))
    }

    @Test
    fun savesAndReloads() {
        val m = IndoorMap.fromOverpass("SFO", overpass, NOW)
        val back = IndoorMap.fromJson(JSONObject(m.toJson().toString()))!!
        assertEquals(m.features.map { it.kind to it.levels }, back.features.map { it.kind to it.levels })
        assertEquals(m.features[2].rings, back.features[2].rings)
        assertEquals(NOW, back.at)
        assertNull(IndoorMap.fromJson(JSONObject("""{"code":"SFO"}""")))
    }

    @Test
    fun downloadsByCodeThenNearby() = runBlocking {
        val asked = mutableListOf<String>()
        // The boundary query finds nothing, so the second, around-the-airport query is used.
        Http.transport = { url, _ ->
            asked += java.net.URLDecoder.decode(url, "UTF-8")
            if ("map_to_area" in asked.last()) """{"elements":[]}""" else overpass
        }
        val m = IndoorMaps.get("SFO")!!
        assertEquals(2, asked.size)
        assertTrue(asked[0].contains("\"icao\"=\"KSFO\""))
        assertTrue(asked[1].contains("around:2500"))
        assertEquals(6, m.features.size)
        // Saved: asking again within a month doesn't download.
        assertEquals(m, IndoorMaps.get("SFO")); assertEquals(2, asked.size)
    }

    @Test
    fun busyServersAreSkipped() = runBlocking {
        val hosts = mutableListOf<String>()
        // A 504, then a 200 that's really an error, then the answer.
        Http.transport = { url, _ ->
            hosts += java.net.URI(url).host
            when (hosts.size) {
                1 -> throw HttpError(504, "HTTP 504")
                2 -> """{"remark":"runtime error: Query timed out","elements":[]}"""
                else -> overpass
            }
        }
        assertNotNull(IndoorMaps.get("SFO"))
        assertEquals(listOf("overpass-api.de", "overpass.private.coffee", "overpass.kumi.systems"), hosts)
        // All down: the error is kept to show, and nothing breaks.
        Http.transport = { _, _ -> throw HttpError(500, "HTTP 500") }
        assertNull(IndoorMaps.get("BWI"))
        assertEquals("OpenStreetMap's servers are busy", IndoorMaps.errors["BWI"])
        // No answer at all is being offline, not busy.
        IndoorMaps.errors.clear()
        Http.transport = { _, _ -> throw java.io.IOException("Unable to resolve host") }
        assertNull(IndoorMaps.get("BWI"))
        assertEquals("No connection", IndoorMaps.errors["BWI"])
        // An HTML error page with a 200 counts as busy too.
        IndoorMaps.errors.clear()
        Http.transport = { _, _ -> "<html>too busy</html>" }
        assertNull(IndoorMaps.get("BWI"))
        assertEquals("OpenStreetMap's servers are busy", IndoorMaps.errors["BWI"])
    }

    @Test
    fun bundledMapsNeedNoDownload() = runBlocking {
        val generated = NOW.minus(java.time.Duration.ofDays(10))
        IndoorMaps.bundled = { code -> if (code == "SFO") overpass.replaceFirst("{", """{"generated":${generated.epochSecond},""") else null }
        Http.transport = { _, _ -> error("a bundled map shouldn't be downloaded") }
        val m = IndoorMaps.get("SFO")!!
        assertEquals(generated, m.at); assertEquals(6, m.features.size)
        // Over a month old: refreshed once; a failure keeps the bundled map, quietly, and isn't retried right away.
        clock = java.time.Clock.offset(clock, java.time.Duration.ofDays(30))
        IndoorMaps.maps.clear()
        var asked = 0
        Http.transport = { _, _ -> asked++; throw HttpError(503, "HTTP 503") }
        assertEquals(generated, IndoorMaps.get("SFO")?.at)
        val first = asked
        assertTrue(first > 0)
        IndoorMaps.get("SFO"); assertEquals(first, asked)
    }

    /** tools/airport_maps.py makes the bundled maps with the app's own query; keep them in step. */
    @Test
    fun bundlingScriptAsksTheSameQuery() {
        val script = java.io.File("../tools/airport_maps.py").readText()
        Overpass.wanted("{a}").lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            assertTrue("airport_maps.py is missing: $it", it in script)
        }
        Overpass.servers.forEach { assertTrue(it in script) }
    }
}
