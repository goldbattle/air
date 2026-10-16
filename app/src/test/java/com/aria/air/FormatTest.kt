package com.aria.air

import com.aria.air.data.*
import com.aria.air.ui.screens.*
import com.aria.air.ui.widget.*
import com.aria.air.util.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** Formatters (util/Format.kt), weather and flags, geography, and the text the widget and cards show. */
class FormatTest : AirTest() {
    @Test
    fun spans() {
        assertEquals("0m", span(Duration.ZERO))
        assertEquals("45m", span(min(45)))
        assertEquals("5h 24m", span(min(324)))
        assertEquals("47h 59m", span(min(48 * 60 - 1)))
        assertEquals("2d 0h", span(hours(48)))
        assertEquals("3d 4h", span(hours(76)))
        assertEquals("45m", span(min(-45)))  // sign is the caller's business
    }

    @Test
    fun timesAndDays() {
        val la = ZoneId.of("America/Los_Angeles")
        assertEquals("5:00 AM", time(NOW, la))
        assertEquals("1:00 PM", time(NOW, ZoneId.of("Europe/London")))
        assertEquals("—", time(null, la))
        val today = localToday()
        assertEquals("Today", dayLabel(today))
        assertEquals("Tomorrow", dayLabel(today.plusDays(1)))
        assertEquals("Yesterday", dayLabel(today.minusDays(1)))
        assertEquals("Mon, Oct 12", dayLabel(LocalDate.of(2026, 10, 12)))
        assertEquals("Sat, Jan 2, 2027", dayLabel(LocalDate.of(2027, 1, 2)))
        assertEquals("Friday, October 2, 2026", longDay(TODAY))
    }

    @Test
    fun ago() {
        assertEquals("just now", ago(NOW.minusSeconds(30)))
        assertEquals("5 min ago", ago(NOW.minus(min(5))))
        assertEquals("3 h ago", ago(NOW.minus(hours(3))))
        assertEquals("2 d ago", ago(NOW.minus(hours(49))))
    }

    @Test
    fun numbers() {
        assertEquals("1,234,567", fmtInt(1234567))
        assertEquals("35,000", fmtInt(35000.7))
    }

    @Test
    fun weather() {
        val w = Wx(20.0, 0, 16.09344, NOW, NOW)
        assertEquals("☀️" to "Clear", w.icon() to w.desc())
        assertEquals("🌧️" to "Rain", w.copy(code = 63).icon() to w.copy(code = 63).desc())
        assertEquals("🌧️" to "Showers", w.copy(code = 81).icon() to w.copy(code = 81).desc())
        assertEquals("🌨️" to "Snow showers", w.copy(code = 85).icon() to w.copy(code = 85).desc())
        assertEquals("⛈️" to "Thunderstorms", w.copy(code = 95).icon() to w.copy(code = 95).desc())
        assertEquals("🌡️" to "—", w.copy(code = -1).icon() to w.copy(code = -1).desc())
        assertEquals("68°F", w.temp()); assertEquals("10 mph", w.wind()); assertEquals("☀️ 68°F", w.short())
        Prefs.setFlag("celsius", true)
        try {
            assertEquals("20°C", w.temp()); assertEquals("16 km/h", w.wind())
        } finally {
            Prefs.setFlag("celsius", false)
        }
    }

    @Test
    fun flagsAndCountries() {
        assertEquals("🇺🇸", flag("us"))
        assertEquals("🇬🇧", flag("GB"))
        assertEquals("", flag("USA"))
        assertEquals("Germany", countryName("DE"))
        assertEquals("", countryName(""))
    }

    @Test
    fun referenceData() {
        val pwm = Db.airport("pwm")!!
        assertEquals("PWM", pwm.iata); assertEquals("US-ME", pwm.region)
        assertEquals("PWM", Db.airportForCity("Portland", "ME")!!.iata)
        assertEquals("PDX", Db.airportForCity("Portland", "OR")!!.iata)
        assertEquals("YXU", Db.airportForCity("London", "ON")!!.iata)  // Ontario, not England or Kentucky
        assertEquals("United Airlines", Db.airline("ua")!!.name)
        assertEquals("UA", Db.airlinesByIcao["UAL"]!!.iata)
        assertEquals(null, Db.airport(null))
    }

    @Test
    fun greatCircles() {
        val sfo = Db.airport("SFO")!!; val bos = Db.airport("BOS")!!
        val mi = distKm(sfo, bos) / KM_PER_MI
        assertTrue("SFO-BOS is ~2,704 mi, got $mi", mi in 2690.0..2720.0)
        assertEquals(distKm(sfo, bos), distKm(bos, sfo), 1e-9)
        assertEquals(0.0, distKm(sfo, sfo), 1e-9)
        val pts = greatCircle(sfo.lat, sfo.lon, bos.lat, bos.lon)
        assertEquals(65, pts.size)
        assertEquals(sfo.lat, pts.first().first, 1e-9); assertEquals(bos.lon, pts.last().second, 1e-9)
        assertTrue(pts.maxOf { it.first } > maxOf(sfo.lat, bos.lat)) // bows north
        assertEquals(1, greatCircle(1.0, 2.0, 1.0, 2.0).size)
        // Across the date line the longitudes keep going instead of jumping by 360.
        val nrt = Db.airport("NRT")!!
        val pacific = greatCircle(sfo.lat, sfo.lon, nrt.lat, nrt.lon)
        assertTrue(pacific.zipWithNext().all { (a, b) -> kotlin.math.abs(a.second - b.second) < 10 })
        assertEquals(nrt.lon - 360, pacific.last().second, 1e-6)
    }

    @Test
    fun compassPoints() {
        assertEquals(listOf("N", "N", "NE", "E", "S", "W", "NW", "N", "W"), listOf(0.0, 22.0, 23.0, 90.0, 180.0, 270.0, 315.0, 359.0, -90.0).map(::compass))
    }

    @Test
    fun widgetHeadline() {
        assertEquals("Departs in" to "24h 0m", headline(flight(hours(24)), NOW))
        assertEquals("Boarding in" to "1h 20m", headline(flight(hours(2)), NOW))
        assertEquals("Departs" to "Today", headline(flight(null).copy(date = localToday()), NOW))
        assertEquals("Lands in" to "4h 0m", headline(flight(hours(-1)), NOW))
        val landed = flight(hours(-6)).let { it.copy(depActual = it.depSched, arrActual = NOW.minus(min(30))) }
        assertEquals("Landed" to "7:30 AM", headline(landed, NOW))
    }

    @Test
    fun widgetCyclesMineAndFriends() {
        val slots = (0L until 8).map { NOW.plus(min(30 * it)) }
        fun shown() = slots.map { widgetFlight(it)?.id }
        Store.flights.clear()
        try {
            assertEquals(List(8) { null }, shown())
            // No flight of mine: friends' upcoming flights take turns, even weeks out.
            val ann = flight(hours(24 * 20)).copy(id = "ann", friend = "Ann Lee")
            val bob = flight(hours(24 * 30)).copy(id = "bob", friend = "Bob")
            Store.flights += listOf(ann, bob)
            assertEquals(setOf("ann", "bob"), shown().toSet())
            // Mine two weeks out: mine and friends' alternate.
            Store.flights += flight(hours(24 * 14)).copy(id = "me")
            assertEquals(4, shown().count { it == "me" })
            // Mine tomorrow: mine three turns in four.
            Store.flights.removeAll { it.id == "me" }
            Store.flights += flight(hours(24)).copy(id = "me")
            assertEquals(6, shown().count { it == "me" })
        } finally {
            Store.flights.clear()
        }
    }

    @Test
    fun cardAndWidgetDetails() {
        val f = flight(hours(2)).copy(depGate = "F12", seat = "21C", arrGate = "B5", baggage = "4")
        assertEquals("Gate F12 · Seat 21C", detailLine(f))
        assertEquals("", detailLine(flight(hours(2))))
        val landed = f.copy(depActual = f.depSched!!.minus(hours(8)), depSched = NOW.minus(hours(6)), arrSched = NOW.minus(hours(1)), arrActual = NOW.minus(min(30)))
        assertEquals("Gate B5 · Bags 4", detailLine(landed))
        val g = flight(hours(-1))
        val sfo = g.origin!!; val bos = g.dest!!
        val p = greatCircle(sfo.lat, sfo.lon, bos.lat, bos.lon, 100)[20]
        assertEquals("552 mph · 35,000 ft", detailLine(g.copy(live = Live(p.first, p.second, 35000, 480, 70.0, 0, NOW, false))))
        assertEquals("in 2h 0m", middleLabel(f, Phase.Departure, NOW))
        assertEquals("Boarding", middleLabel(flight(min(-5)), Phase.Departure, NOW))
        assertEquals("5h 0m", middleLabel(f, Phase.InFlight, NOW))
    }
}
