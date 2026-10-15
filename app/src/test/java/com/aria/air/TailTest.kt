package com.aria.air

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Aircraft pages: the airframe, its recent flights and their punctuality, from AeroDataBox or FlightAware. */
class TailTest : AirTest() {
    @After
    fun restore() {
        Http.transport = { _, _ -> error("no network in tests") }
        Prefs.adbKey = ""; Prefs.aeroKey = ""
        Tails.reports.clear(); Tails.errors.clear()
    }

    private val aircraft = """
        {"id":1,"reg":"N12345","active":true,"serial":"41234","hexIcao":"A1B213","airlineName":"Example Air","model":"B739",
         "modelCode":"737-924ER","numSeats":179,"firstFlightDate":"2013-02-20","deliveryDate":"2013-03-26T00:00:00",
         "typeName":"Boeing 737-900","numEngines":2,"engineType":"Jet","ageYears":13.5}
    """.trimIndent()

    private fun adbLeg(number: String, status: String, depS: String, depR: String?, arrS: String, arrR: String?) = """
        {"number":"$number","status":"$status",
         "departure":{"airport":{"iata":"SFO"},"scheduledTime":{"utc":"$depS"}${depR?.let { ""","runwayTime":{"utc":"$it"}""" } ?: ""}},
         "arrival":{"airport":{"iata":"PIT"},"scheduledTime":{"utc":"$arrS"}${arrR?.let { ""","revisedTime":{"utc":"$it"}""" } ?: ""}}}
    """.trimIndent()

    private val adbLegs = "[" + listOf(
        adbLeg("UA 2000", "Arrived", "2026-09-30 15:00Z", "2026-09-30 15:40Z", "2026-09-30 20:00Z", "2026-09-30 20:35Z"),
        adbLeg("UA 2001", "Arrived", "2026-10-01 15:00Z", "2026-10-01 15:05Z", "2026-10-01 20:00Z", "2026-10-01 19:55Z"),
        adbLeg("UA 2002", "Expected", "2026-10-02 15:00Z", null, "2026-10-02 20:00Z", "2026-10-02 20:10Z"),
        adbLeg("UA 2003", "Canceled", "2026-09-29 15:00Z", null, "2026-09-29 20:00Z", null),
    ).joinToString(",") + "]"

    @Test
    fun readsAeroDataBox() {
        val i = AeroDataBox.parseAircraft(JSONObject(aircraft))
        assertEquals("Boeing 737-900", i.model); assertEquals("737-924ER", i.type); assertEquals("Example Air", i.airline)
        assertEquals(13.5, i.ageYears!!, 1e-9); assertEquals(LocalDate.of(2013, 3, 26), i.delivered)
        assertEquals(179, i.seats); assertEquals("2 × Jet", i.engines); assertEquals("a1b213", i.hex)
        assertNull(AeroDataBox.parseAircraft(JSONObject("{}")).ageYears)

        val legs = AeroDataBox.parseLegs(adbLegs)
        assertEquals(listOf("UA2002", "UA2001", "UA2000", "UA2003"), legs.map { it.flight }) // newest first
        assertEquals(35L, legs[2].arrDelay); assertEquals(40L, legs[2].depDelay)
        assertNull(legs[0].arrDelay) // an estimate isn't a landing
        assertTrue(legs[3].cancelled)
        assertEquals(emptyList<TailLeg>(), AeroDataBox.parseLegs(""))
    }

    @Test
    fun punctuality() {
        val r = TailReport("N12345", null, AeroDataBox.parseLegs(adbLegs), NOW, "AeroDataBox")
        assertEquals(2, r.landed.size)
        assertEquals(1, r.onTime)
        assertEquals(17L, r.averageDelay) // 35 late and 5 early (counted as 0), over two
        assertNull(r.copy(legs = emptyList()).averageDelay)
        val back = TailReport.fromJson(JSONObject(r.copy(info = AeroDataBox.parseAircraft(JSONObject(aircraft))).toJson().toString()))!!
        assertEquals(r.legs, back.legs); assertEquals(13.5, back.info!!.ageYears!!, 1e-9); assertEquals(179, back.info!!.seats)
    }

    @Test
    fun readsFlightAware() {
        val legs = FlightAware.parseLegs("""{"flights":[
            {"ident":"UAL2000","ident_iata":"UA2000","origin":{"code_iata":"SFO"},"destination":{"code_iata":"PIT"},
             "scheduled_out":"2026-10-01T15:00:00Z","actual_out":"2026-10-01T15:10:00Z","scheduled_in":"2026-10-01T20:00:00Z",
             "actual_in":"2026-10-01T20:20:00Z","cancelled":false},
            {"ident":"UAL2001","ident_iata":null,"origin":{"code_iata":"PIT"},"destination":{"code_iata":"SFO"},
             "scheduled_out":"2026-10-02T15:00:00Z","cancelled":true}]}""")
        assertEquals(listOf("UAL2001", "UA2000"), legs.map { it.flight })
        assertEquals(20L, legs[1].arrDelay); assertTrue(legs[0].cancelled)
    }

    @Test
    fun looksUpOncePerFewHours() = runBlocking {
        val asked = mutableListOf<String>()
        Http.transport = { url, _ -> asked += url; if ("/aircrafts/reg/N12345" in url) aircraft else adbLegs }
        assertNull(Tails.get("N12345")) // no key: nothing asked
        assertEquals(0, asked.size)
        Prefs.adbKey = "k"
        val r = Tails.get("N-12345")!!
        assertEquals(2, asked.size)
        assertTrue(asked[1].contains("/flights/reg/N12345/2026-09-26/2026-10-02"))
        assertEquals("Boeing 737-900", r.info?.model); assertEquals(4, r.legs.size)
        Tails.get("n12345"); assertEquals(2, asked.size) // saved
        Tails.get("N12345", force = true); assertEquals(4, asked.size)
    }

    @Test
    fun flightAwareOnlyAndErrors() = runBlocking {
        Prefs.aeroKey = "fa"
        Http.transport = { url, _ ->
            assertTrue("ident_type=registration" in url)
            """{"flights":[]}"""
        }
        assertEquals("FlightAware", Tails.get("N12345")!!.source)
        Http.transport = { _, _ -> throw HttpError(429, "HTTP 429") }
        val kept = Tails.get("N12345", force = true)
        assertEquals("FlightAware", kept?.source) // the saved report stays
        assertEquals("Quota used up", Tails.errors["N12345"])
    }

    @Test
    fun yearInReview() {
        val f = flight(hours(-48)).copy(arrActual = NOW.minus(hours(48)).plus(hours(5)).plus(min(30)), aircraft = "Boeing 737-900")
        val g = flight(hours(-24), from = "BOS", to = "SFO").copy(arrActual = NOW.minus(hours(19)), aircraft = "Boeing 737-900")
        val s = stats(listOf(f, g))
        assertEquals(2, s.timed); assertEquals(1, s.onTime); assertEquals(15L, s.averageDelay)
        assertEquals(mapOf("Boeing 737-900" to 2), s.aircraft)
        assertEquals(f.distanceKm * 2 * 0.11, s.co2Kg, 1e-6) // SFO–BOS (4,300 km) counts as long haul
        assertEquals(800 * 0.13, co2Kg(800.0), 1e-9)
        assertEquals(listOf("SFO", "BOS"), firstVisits(listOf(f, g), emptyList()))
        assertEquals(listOf("BOS"), firstVisits(listOf(f, g), listOf(flight(hours(-500), to = "LAX"))))
        assertNull(stats(emptyList()).averageDelay)
    }
}
