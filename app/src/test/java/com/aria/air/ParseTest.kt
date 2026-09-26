package com.aria.air

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

class ParseTest {
    companion object {
        @BeforeClass @JvmStatic
        fun load() = Db.load(File("src/main/assets/airports.tsv").reader(), File("src/main/assets/airlines.tsv").reader())
    }

    private val today = LocalDate.of(2026, 9, 26)

    @Test
    fun confirmationEmail() {
        val email = """
            Your trip confirmation: TESTAB
            Sat, Sep 26, 2026
            UA 1000  San Francisco (SFO) to Pittsburgh (PIT)
            Departs 8:10 AM  Arrives 4:04 PM   Seat 21C   Economy
            Fri, Oct 2, 2026
            UA2000  PIT → SFO  7:00 AM – 9:24 AM  Seat: 14A
        """.trimIndent()
        val f = Importer.parse(email, today)
        assertEquals(2, f.size)
        assertEquals(Found("UA", "1000", LocalDate.of(2026, 9, 26), "SFO", "PIT", LocalTime.of(8, 10), LocalTime.of(16, 4), "21C", "TESTAB"), f[0])
        assertEquals(Found("UA", "2000", LocalDate.of(2026, 10, 2), "PIT", "SFO", LocalTime.of(7, 0), LocalTime.of(9, 24), "14A", "TESTAB"), f[1])
    }

    @Test
    fun boardingPassStyle() {
        val f = Importer.parse("WN3489 09JUL SJC SAN 06:30 08:00 GATE 12", today)
        assertEquals(1, f.size)
        assertEquals(LocalDate.of(2026, 7, 9), f[0].date)
        assertEquals("SJC", f[0].from); assertEquals("SAN", f[0].to)
        assertEquals(LocalTime.of(6, 30), f[0].dep)
    }

    @Test
    fun ignoresTimesAndPrices() {
        assertTrue(Importer.parse("Total 7:00 AM USD 245.10 Terminal 3", today).isEmpty())
    }

    @Test
    fun byAirExport() {
        val csv = """
            Flight Date,Flight Code,Departure Airport Code,Arrival Airport Code,Departure Time,Arrival Time,Booking Code,Seat Number,Seat Type,Seat Class,Purpose,Notes,Ownership,byAir Flight ID,byAir Codeshare ID
            2026-06-20,UA948,SFO,LHR,16:45,11:10,TESTAB,21C,,,,"window, left",mine,1,0
            2026-07-20,WN1546,MDW,SFO,10:05,12:35,,,,,,,friend,2,0
        """.trimIndent()
        assertTrue(isByAirCsv(csv))
        val (ua, wn) = byAirFlights(csv)
        assertEquals("UA", ua.airline); assertEquals("948", ua.number); assertEquals("", ua.friend)
        assertEquals("21C", ua.seat); assertEquals("window, left", ua.notes)
        // 16:45 in SFO to 11:10 next day in London is 10h25m.
        assertEquals(java.time.Duration.ofMinutes(625), ua.duration)
        assertEquals("Friend", wn.friend)
    }

    @Test
    fun deltaForwardedItinerary() {
        // Text as it comes out of the Gmail PDF: "ﬁ" ligature and narrow no-break spaces before AM/PM.
        val pdf = """
            Delta.com Trip Information For: Jane Q Doe
            Delta Air Lines <DeltaAirLines@t.delta.com> Sun, Aug 23, 2026 at 2:55 PM
            Jane Q's Upcoming Trip
            Conﬁrmation Number: QWERTY
            2026/10/17 DEPART ARRIVE
            Delta Air Lines, Inc
            DL 400
            Operated by Delta Air
            Lines, Inc
            Detroit, MI
            10:25 AM
            Portland, ME
            12:22 PM
            2026/10/24 DEPART ARRIVE
            Delta Air Lines, Inc
            DL 400
            Operated by Delta Air
            Lines, Inc
            Portland, ME
            1:10 PM
            Detroit, MI
            3:19 PM
            Delta is glad to forward this itinerary along on a one-time basis.
        """.trimIndent()
        val f = Importer.parse(pdf, today)
        assertEquals(2, f.size)
        assertEquals(Found("DL", "400", LocalDate.of(2026, 10, 17), "DTW", "PWM", LocalTime.of(10, 25), LocalTime.of(12, 22), "", "QWERTY"), f[0])
        assertEquals(Found("DL", "400", LocalDate.of(2026, 10, 24), "PWM", "DTW", LocalTime.of(13, 10), LocalTime.of(15, 19), "", "QWERTY"), f[1])
        assertEquals("Jane Q Doe", Importer.traveler(pdf))
    }

    @Test
    fun unitedDetailsBeforeFlightNumber() {
        val pdf = """
            United confirmation number : ZXCVBN
            Flight to Portland
            Oct 17, 2026
            8:30 AM 5:17 PM
            SFO 5h 47m PWM
            San Francisco, CA, US Portland, ME, US
            FLIGHT INFO
            UA 700
            Boeing 737 MAX 8
            Flight to San Francisco
            Oct 25, 2026
            7:50 AM 11:51 AM
            PWM 7h 1m SFO
            UA 2100
            Travelers
            John Doe
            Seats:
            SFO to PWM: 22C
            PWM to SFO: 14A
            October 17, 2026
        """.trimIndent()
        val f = Importer.parse(pdf, today)
        assertEquals(Found("UA", "700", LocalDate.of(2026, 10, 17), "SFO", "PWM", LocalTime.of(8, 30), LocalTime.of(17, 17), "22C", "ZXCVBN"), f[0])
        assertEquals(Found("UA", "2100", LocalDate.of(2026, 10, 25), "PWM", "SFO", LocalTime.of(7, 50), LocalTime.of(11, 51), "14A", "ZXCVBN"), f[1])
        assertEquals("John Doe", Importer.traveler(pdf))
    }

    @Test
    fun receiptDateAfterNumberAndLastFirstName() {
        val pdf = """
            Fri, Sep 25, 2026 at 11:19 PM
            Confirmation Number:
            QAZWSX
            Flight 1 of 2 UA1000 Class: United Economy (M)
            Sat, Sep 26, 2026 Sat, Sep 26, 2026
            08:10 AM 04:04 PM
            San Francisco, CA, US (SFO) Pittsburgh, PA, US (PIT)
            Flight 2 of 2 UA2000 Class: United Economy (T)
            Fri, Oct 02, 2026 Fri, Oct 02, 2026
            07:00 AM 09:24 AM
            Pittsburgh, PA, US (PIT) San Francisco, CA, US (SFO)
            Traveler Details
            DOE/JOHNQ
            Seats: SFO-PIT 21C
            Date of purchase: Tue, Sep 01, 2026
        """.trimIndent()
        val f = Importer.parse(pdf, today)
        assertEquals(Found("UA", "1000", LocalDate.of(2026, 9, 26), "SFO", "PIT", LocalTime.of(8, 10), LocalTime.of(16, 4), "21C", "QAZWSX"), f[0])
        assertEquals(Found("UA", "2000", LocalDate.of(2026, 10, 2), "PIT", "SFO", LocalTime.of(7, 0), LocalTime.of(9, 24), "", "QAZWSX"), f[1])
        assertEquals("Johnq Doe", Importer.traveler(pdf))
        assertTrue(sameName("Johnq Doe", "John Doe"))
        assertTrue(!sameName("Jane Doe", "John Doe"))
    }

    @Test
    fun corporateItineraryYearlessDatesAndWork() {
        val pdf = """
            <travel@example.com> Thu, Nov 21, 2024 at 6:38 PM
            Travel Itinerary For
            JOHN Q DOE
            Trip Confirmation
            Booked within 14 days of departure
            Travel Begins
            Tuesday, December 3
            Charged to department
            Flight WN2300
            SJC-BUR
            6:35 PM - Tuesday, December 3
            7:45 PM - Tuesday, December 3
            Reference #
            PLMOKN
            Check-Out
            Thursday, December 5
            Confirmation #
            68711SE451730
            Flight WN1700
            BUR-SJC
            8:20 PM - Thursday, December 5
            9:35 PM - Thursday, December 5
            Reference #
            PLMOKN
        """.trimIndent()
        val f = Importer.parse(pdf, today)
        assertEquals(Found("WN", "2300", LocalDate.of(2024, 12, 3), "SJC", "BUR", LocalTime.of(18, 35), LocalTime.of(19, 45), "", "PLMOKN"), f[0])
        assertEquals(Found("WN", "1700", LocalDate.of(2024, 12, 5), "BUR", "SJC", LocalTime.of(20, 20), LocalTime.of(21, 35), "", "PLMOKN"), f[1])
        assertEquals("John Q Doe", Importer.traveler(pdf))
        assertTrue(Importer.isWork(pdf))
        assertTrue(!Importer.isWork("Thanks for choosing United. Seat 21C"))
    }

    @Test
    fun duplicatesMerge() {
        val d = LocalDate.of(2026, 1, 2)
        val a = Flight(airline = "UA", number = "1", date = d, from = "SFO", to = "PIT", source = "byAir")
        val b = a.copy(id = "other", seat = "21C", booking = "ABCDEF", work = true, source = "import")
        val c = a.copy(id = "friend", friend = "Alex")
        assertEquals("Same flight", Dupes.reason(a, b))
        assertEquals("Same flight, different traveler", Dupes.reason(a, c))
        assertEquals(null, Dupes.reason(a, a.copy(id = "x", date = d.plusDays(1))))
        val m = a.mergedWith(b)
        assertEquals(a.id, m.id); assertEquals("21C", m.seat); assertEquals("ABCDEF", m.booking); assertTrue(m.work)
    }

    @Test
    fun checkCadence() {
        val now = java.time.Instant.parse("2026-10-02T12:00:00Z")
        fun f(depInMin: Long, durMin: Long = 300) = Flight(
            airline = "UA", number = "1", date = LocalDate.of(2026, 10, 2), from = "PIT", to = "SFO",
            depSched = now.plusSeconds(depInMin * 60), arrSched = now.plusSeconds((depInMin + durMin) * 60),
        )
        assertEquals(30L, TrackWorker.nextCheckMinutes(listOf(f(60 * 48)), now))   // days away
        assertEquals(5L, TrackWorker.nextCheckMinutes(listOf(f(3 * 60)), now))     // leaving for the airport
        assertEquals(2L, TrackWorker.nextCheckMinutes(listOf(f(30)), now))         // at the gate
        assertEquals(15L, TrackWorker.nextCheckMinutes(listOf(f(-60)), now))       // cruising
        assertEquals(5L, TrackWorker.nextCheckMinutes(listOf(f(-280)), now))       // 20 min to landing
        assertEquals(2L, TrackWorker.nextCheckMinutes(listOf(f(-60), f(30)), now)) // most urgent wins
    }

    @Test
    fun onTimeOnlyCloseToDeparture() {
        val now = java.time.Instant.now()
        fun f(depInHours: Long, lateMin: Long = 0): Flight {
            val dep = now.plusSeconds(depInHours * 3600)
            return Flight(
                airline = "UA", number = "1", date = LocalDate.now(), from = "PIT", to = "SFO",
                depSched = dep, depEst = dep.plusSeconds(lateMin * 60), arrSched = dep.plusSeconds(5 * 3600), updated = now,
            )
        }
        assertEquals("Upcoming", f(24 * 21).status().first)          // weeks out: no real status exists yet
        assertEquals(null, f(24 * 21, lateMin = 40).depDelay)         // and no "delay" either
        assertEquals("On time", f(20).status().first)
        assertEquals("Delayed 40m", f(20, lateMin = 40).status().first)
        assertEquals("Upcoming", f(20).copy(updated = now.minusSeconds(8 * 3600)).status().first) // stale status
        assertEquals("Upcoming", f(20).copy(depEst = null).status().first)                         // no estimate
    }

    @Test
    fun statusLookupsRampWithDistance() {
        val now = java.time.Instant.parse("2026-10-02T12:00:00Z")
        fun f(depInHours: Long) = Flight(
            airline = "UA", number = "1", date = LocalDate.of(2026, 10, 2), from = "PIT", to = "SFO",
            depSched = now.plusSeconds(depInHours * 3600), arrSched = now.plusSeconds((depInHours + 5) * 3600),
        )
        val h = java.time.Duration::ofHours
        assertEquals(java.time.Duration.ofDays(7), Api.statusEvery(f(24 * 30), now))
        assertEquals(java.time.Duration.ofDays(1), Api.statusEvery(f(24 * 7), now))
        assertEquals(h(12), Api.statusEvery(f(48), now))
        assertEquals(h(2), Api.statusEvery(f(10), now))
        assertEquals(java.time.Duration.ofMinutes(20), Api.statusEvery(f(2), now))
        assertEquals(java.time.Duration.ofMinutes(45), Api.statusEvery(f(-1), now))   // cruising
        assertEquals(null, Api.statusEvery(f(-24 * 30), now))                          // past: never automatically
        assertEquals(null, Api.statusEvery(f(2).copy(cancelled = true), now))
    }

    @Test
    fun livePositionPollingIsSparing() {
        val now = java.time.Instant.now()
        fun f(depInMin: Long) = Flight(
            airline = "UA", number = "1", date = LocalDate.now(), from = "PIT", to = "SFO",
            depSched = now.plusSeconds(depInMin * 60), arrSched = now.plusSeconds((depInMin + 300) * 60),
        )
        val m = java.time.Duration::ofMinutes
        assertEquals(null, Api.liveEvery(f(60 * 24 * 5), now, foreground = false))  // days away: never
        assertEquals(m(10), Api.liveEvery(f(60), now, foreground = false))          // at the gate: catch takeoff
        assertEquals(m(10), Api.liveEvery(f(-60), now, foreground = false))         // cruising
        assertEquals(m(3), Api.liveEvery(f(-280), now, foreground = false))         // near landing
        assertEquals(m(1), Api.liveEvery(f(-60), now, foreground = true))           // watching the flight page
    }

    @Test
    fun travelerNames() {
        assertEquals("Jane Doe", Importer.traveler("Passenger: JANE DOE\nSeat 21C"))
        assertEquals(null, Importer.traveler("Thanks for flying with us"))
    }

    @Test
    fun flightNumbers() {
        assertEquals("UA" to "2000", parseFlightNumber("ua 2000"))
        assertEquals("UA" to "2000", parseFlightNumber("UAL2000"))
        assertEquals("WN" to "12", parseFlightNumber("WN0012"))
    }

    @Test
    fun greatCircleSfoPit() {
        val sfo = Db.airport("SFO")!!; val pit = Db.airport("PIT")!!
        val mi = distKm(sfo, pit) / KM_PER_MI
        assertTrue("SFO-PIT is ~2,254 mi, got $mi", mi in 2240.0..2270.0)
        val pts = greatCircle(sfo.lat, sfo.lon, pit.lat, pit.lon)
        assertTrue(pts.maxOf { it.first } > maxOf(sfo.lat, pit.lat)) // bows north
    }
}
