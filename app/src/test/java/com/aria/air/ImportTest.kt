package com.aria.air

import com.aria.air.data.*
import com.aria.air.importer.*
import com.aria.air.util.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** Reading flights, travelers and codes out of emails, PDF text and byAir exports. All data is made up. */
class ImportTest : AirTest() {
    private val today = LocalDate.of(2026, 9, 26)
    private fun parse(s: String) = Importer.parse(s, today)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun t(h: Int, m: Int) = LocalTime.of(h, m)

    @Test
    fun trackingLinksAndFooterNoise() {
        // Plain-text airline emails are full of tracking links and encoded runs that read like flight numbers.
        val email = """
            Your trip confirmation: TESTAB
            View trip: https://e.example.com/c?_ri_=X0Gzc2X%3DSH8PW3V2CI9BL0&_ei_=EolaGGF4SNMvxFF7KucKuWNhjeSKZKbO
            Thu, Nov 5, 2026
            DL 400  Detroit (DTW) to Portland (PWM)
            Departs 10:25 AM  Arrives 12:22 PM   Seat 14C
            Track at www.example.com/t/KP2JQ9GA1 or text TRIP to 12345
            Ref CI 9 · BL 0 · O7 4
        """.trimIndent()
        assertEquals(listOf("DL 400"), parse(email).map { it.label })
        // A connection on the same airline stays even when its airports didn't parse.
        val two = parse("Thu, Nov 5, 2026\nDL 400 DTW to PWM 10:25 AM 12:22 PM\nThu, Nov 5, 2026\nConnecting DL 1180 at 2:10 PM")
        assertEquals(listOf("DL 400", "DL 1180"), two.map { it.label })
    }

    @Test
    fun confirmationEmail() {
        val email = """
            Your trip confirmation: TESTAB
            Thu, Nov 5, 2026
            UA 455  San Francisco (SFO) to Chicago (ORD)
            Departs 7:15 AM  Arrives 1:32 PM   Seat 18D   Economy
            Thu, Nov 12, 2026
            UA456  ORD → SFO  6:05 PM – 8:40 PM  Seat: 9F
        """.trimIndent()
        val f = parse(email)
        assertEquals(2, f.size)
        assertEquals(Found("UA", "455", d(2026, 11, 5), "SFO", "ORD", t(7, 15), t(13, 32), "18D", "TESTAB"), f[0])
        assertEquals(Found("UA", "456", d(2026, 11, 12), "ORD", "SFO", t(18, 5), t(20, 40), "9F", "TESTAB"), f[1])
    }

    @Test
    fun boardingPassStyle() {
        val f = parse("WN500 14MAR OAK LAS 07:15 08:45 GATE 12")
        assertEquals(listOf(Found("WN", "500", d(2026, 3, 14), "OAK", "LAS", t(7, 15), t(8, 45))), f)
    }

    @Test
    fun ignoresTimesPricesAndUnknownAirlines() {
        assertTrue(parse("Total 7:00 AM USD 245.10 Terminal 3").isEmpty())
        assertTrue(parse("Flight ZZ 123 on Nov 5, 2026").isEmpty())  // not an airline
        assertTrue(parse("Departs 10:25 AM, gate B12").isEmpty())    // "AM" and gate numbers aren't flights
    }

    @Test
    fun deltaForwardedItinerary() {
        // Text as it comes out of the Gmail PDF: "ﬁ" ligature and narrow no-break spaces before AM/PM.
        val pdf = """
            Delta.com Trip Information For: Jane Q Doe
            Delta Air Lines <DeltaAirLines@t.delta.com> Sun, Aug 23, 2026 at 2:55 PM
            Jane Q's Upcoming Trip
            Conﬁrmation Number: QWERTY
            2026/11/05 DEPART ARRIVE
            Delta Air Lines, Inc
            DL 1105
            Operated by Delta Air
            Lines, Inc
            Atlanta, GA
            9:15 AM
            Boston, MA
            11:58 AM
            2026/11/12 DEPART ARRIVE
            Delta Air Lines, Inc
            DL 1105
            Operated by Delta Air
            Lines, Inc
            Boston, MA
            1:00 PM
            Atlanta, GA
            3:55 PM
            Delta is glad to forward this itinerary along on a one-time basis.
        """.trimIndent()
        val f = parse(pdf)
        assertEquals(2, f.size)
        assertEquals(Found("DL", "1105", d(2026, 11, 5), "ATL", "BOS", t(9, 15), t(11, 58), "", "QWERTY"), f[0])
        assertEquals(Found("DL", "1105", d(2026, 11, 12), "BOS", "ATL", t(13, 0), t(15, 55), "", "QWERTY"), f[1])
        assertEquals("Jane Q Doe", Importer.traveler(pdf))
    }

    @Test
    fun unitedDetailsBeforeFlightNumber() {
        val pdf = """
            United confirmation number : ZXCVBN
            Flight to Boston
            Nov 5, 2026
            7:15 AM 3:40 PM
            SFO 5h 25m BOS
            San Francisco, CA, US Boston, MA, US
            FLIGHT INFO
            UA 455
            Boeing 737 MAX 8
            Flight to San Francisco
            Nov 12, 2026
            6:05 PM 9:30 PM
            BOS 6h 25m SFO
            UA 456
            Travelers
            John Doe
            Seats:
            SFO to BOS: 18D
            BOS to SFO: 9F
            November 5, 2026
        """.trimIndent()
        val f = parse(pdf)
        assertEquals(Found("UA", "455", d(2026, 11, 5), "SFO", "BOS", t(7, 15), t(15, 40), "18D", "ZXCVBN"), f[0])
        assertEquals(Found("UA", "456", d(2026, 11, 12), "BOS", "SFO", t(18, 5), t(21, 30), "9F", "ZXCVBN"), f[1])
        assertEquals("John Doe", Importer.traveler(pdf))
    }

    @Test
    fun receiptDateAfterNumberAndLastFirstName() {
        val pdf = """
            Mon, Oct 12, 2026 at 11:19 PM
            Confirmation Number:
            QAZWSX
            Flight 1 of 2 UA300 Class: United Economy (M)
            Tue, Nov 03, 2026 Tue, Nov 03, 2026
            06:00 AM 12:15 PM
            San Francisco, CA, US (SFO) Chicago, IL, US (ORD)
            Flight 2 of 2 UA301 Class: United Economy (T)
            Mon, Nov 09, 2026 Mon, Nov 09, 2026
            05:30 PM 08:10 PM
            Chicago, IL, US (ORD) San Francisco, CA, US (SFO)
            Traveler Details
            DOE/JOHNQ
            Seats: SFO-ORD 21C
            Date of purchase: Mon, Oct 12, 2026
        """.trimIndent()
        val f = parse(pdf)
        assertEquals(Found("UA", "300", d(2026, 11, 3), "SFO", "ORD", t(6, 0), t(12, 15), "21C", "QAZWSX"), f[0])
        assertEquals(Found("UA", "301", d(2026, 11, 9), "ORD", "SFO", t(17, 30), t(20, 10), "", "QAZWSX"), f[1])
        assertEquals("Johnq Doe", Importer.traveler(pdf))
    }

    @Test
    fun deltaReceiptRouteAboveDateAndNumber() {
        // Each flight: "BOS>SEA", then "Wed 10Mar2027 DL 1111". The next flight's route and date sit just after the
        // number and must not be taken for this one's.
        val pdf = """
            Date of Purchase: Jan 04, 2027
            Flight Receipt for Boston, MA to Seattle, WA
            PASSENGER INFORMATION
            JANE A DOE
            Confirmation Number: QWERTY
            Ticket Number: 0060000000000
            FLIGHT INFORMATION
            Date and Flight Status Class Seat/Cabin
            BOS>SEA
            Wed 10Mar2027 DL 1111
            OPEN V CMAIN
            SEA>BOS
            Sat 20Mar2027 DL 2222
            OPEN X CMAIN
            DETAILED CHARGES
            Air Transportation Charges
            Base Fare: ${'$'}100.00 USD
        """.trimIndent()
        val f = parse(pdf)
        assertEquals(listOf("DL 1111", "DL 2222"), f.map { it.label })
        assertEquals(Found("DL", "1111", d(2027, 3, 10), "BOS", "SEA", null, null, "", "QWERTY"), f[0])
        assertEquals(Found("DL", "2222", d(2027, 3, 20), "SEA", "BOS", null, null, "", "QWERTY"), f[1])
        assertEquals("Jane A Doe", Importer.traveler(pdf)) // "PASSENGER INFORMATION", name on the next line
        // The same receipt as another PDF reader spells it, with the hidden "to" a letter a line.
        assertEquals(f, parse(pdf.replace("BOS>SEA", "BOS>\nt\no\nSEA").replace("SEA>BOS", "SEA>\nt\no\nBOS")))
    }

    @Test
    fun corporateItineraryYearlessDatesAndWork() {
        val pdf = """
            <travel@example.com> Thu, Nov 20, 2025 at 6:38 PM
            Travel Itinerary For
            JOHN Q DOE
            Trip Confirmation
            Booked within 14 days of departure
            Travel Begins
            Tuesday, December 2
            Charged to department
            Flight WN1200
            OAK-LAS
            6:35 PM - Tuesday, December 2
            8:05 PM - Tuesday, December 2
            Reference #
            PLMOKN
            Check-Out
            Thursday, December 4
            Confirmation #
            12345XY678901
            Flight WN1201
            LAS-OAK
            8:20 PM - Thursday, December 4
            9:50 PM - Thursday, December 4
            Reference #
            PLMOKN
        """.trimIndent()
        val f = parse(pdf)
        assertEquals(Found("WN", "1200", d(2025, 12, 2), "OAK", "LAS", t(18, 35), t(20, 5), "", "PLMOKN"), f[0])
        assertEquals(Found("WN", "1201", d(2025, 12, 4), "LAS", "OAK", t(20, 20), t(21, 50), "", "PLMOKN"), f[1])
        assertEquals("John Q Doe", Importer.traveler(pdf))
        assertTrue(Importer.isWork(pdf))
    }

    @Test
    fun yearlessDatesRollIntoNextYear() {
        // Sent late December about a January flight: January is next year.
        val f = parse("Sent Mon, Dec 28, 2026\nFlight UA 455 on Saturday, January 9\nSFO-BOS 7:15 AM 3:40 PM")
        assertEquals(d(2027, 1, 9), f.single().date)
        // No full date anywhere: the year is today's.
        assertEquals(d(2026, 3, 14), parse("WN500 14 MAR OAK LAS").single().date)
    }

    @Test
    fun dateFormats() {
        fun date(s: String) = parse("UA 455 $s SFO BOS").single().date
        assertEquals(d(2026, 11, 5), date("2026-11-05"))
        assertEquals(d(2026, 11, 5), date("5 Nov 2026"))
        assertEquals(d(2026, 11, 5), date("05NOV26"))
        assertEquals(d(2026, 11, 5), date("November 5th, 2026"))
        assertEquals(d(2026, 11, 5), date("11/05/2026"))
        assertEquals(d(2026, 11, 5), date("11/5/26"))
        assertEquals(d(2026, 11, 5), date("2026/11/05"))
        assertNull(parse("UA 455 SFO BOS").single().date)
    }

    @Test
    fun twelveOClockAndTwentyFourHourTimes() {
        val f = parse("UA 455 Nov 5, 2026 SFO BOS 12:05 AM 12:40 PM").single()
        assertEquals(t(0, 5), f.dep); assertEquals(t(12, 40), f.arr)
        val g = parse("UA 455 Nov 5, 2026 SFO BOS 23:10 7:05").single()
        assertEquals(t(23, 10), g.dep); assertEquals(t(7, 5), g.arr)
        assertEquals(t(19, 0), parse("UA 455 Nov 5, 2026 SFO BOS 7:00 p.m. 9:00 p.m.").single().dep)
    }

    @Test
    fun weekdaysAndMonthsAreNotAirports() {
        // SAT (San Antonio) and MAR are real codes, but here they're words.
        val f = parse("UA 455 Sat, Nov 7, 2026 SFO to BOS").single()
        assertEquals("SFO", f.from); assertEquals("BOS", f.to)
    }

    @Test
    fun confirmationCodes() {
        fun code(s: String) = parse("$s\nUA 455 Nov 5, 2026 SFO BOS").single().booking
        assertEquals("ABC123", code("Record locator: ABC123"))
        assertEquals("ABC123", code("Booking reference ABC123"))
        assertEquals("ABC123", code("PNR: ABC123"))
        assertEquals("ABC123", code("Reservation code - ABC123"))
        assertEquals("", code("Trip Confirmation Booked by you"))  // keywords aren't codes
        assertEquals("", code("Confirmation abc123"))              // codes are uppercase
        assertEquals("", code("Confirmation ABC1234"))             // exactly six characters
    }

    @Test
    fun theCodeNextToTheFlightBeatsTheFirstOne() {
        val f = parse("Hotel confirmation: HOTEL1\n\nUA 455 Nov 5, 2026 SFO BOS 7:15 AM 3:40 PM Confirmation: FLY123")
        assertEquals("FLY123", f.single().booking)
    }

    @Test
    fun repeatedMentionsKeepTheMostComplete() {
        val f = parse("Your flight UA 455 on Nov 5, 2026.\n\n\nUA 455 Nov 5, 2026 SFO BOS 7:15 AM 3:40 PM Seat 18D")
        assertEquals(1, f.size)
        assertEquals("18D", f[0].seat); assertEquals(t(7, 15), f[0].dep)
    }

    @Test
    fun flightNumbersDropLeadingZeros() {
        assertEquals("12", parse("WN 0012 Nov 5, 2026 OAK LAS").single().number)
        assertEquals("WN 12", parse("WN 0012 Nov 5, 2026 OAK LAS").single().label)
    }

    @Test
    fun travelerNames() {
        assertEquals("John Doe", Importer.traveler("Passenger: JOHN DOE\nSeat 21C"))
        assertEquals("John Doe", Importer.traveler("Passenger name: MR JOHN DOE"))
        assertEquals("Jane Q. Doe", Importer.traveler("Passenger: Jane Q. Doe. Thu, Oct 22, 2026. UA 455")) // stops at the sentence
        assertEquals("Jane Doe", Importer.traveler("Traveller: Jane Doe"))
        assertEquals("Jane", Importer.traveler("Jane's Trip to Boston"))
        assertEquals("John McDoe", Importer.traveler("Passengers\nJohn McDoe"))
        assertNull(Importer.traveler("Thanks for flying with us"))
        assertNull(Importer.traveler("Traveler: the details below"))
    }

    @Test
    fun travelerFromKnownPeople() {
        val known = listOf("Sam Lee", "Jordan Park", "Alex Kim")
        // No label, but a friend is named in the itinerary: them, in any case or LAST/FIRST.
        assertEquals("Jordan Park", Importer.traveler("Confirmation ABC123\nJORDAN T PARK  Seat 12A\nDL 1111 BOS>SEA", known))
        assertEquals("Sam Lee", Importer.traveler("LEE/SAM\nUA 100", known))
        // A forward: headers, the greeting and sign-off name other people; only the itinerary counts.
        val fwd = """
            From: Alex Kim <alex@example.com>
            To: Jordan Park <jordan@example.com>
            Subject: Fwd: my flights
            Hi Jordan Park, here's the trip.
            ---------- Forwarded message ---------
            Booking ABC123 for SAM LEE
            DL 1111 BOS>SEA
            Thanks, Alex Kim
        """.trimIndent()
        assertEquals("Sam Lee", Importer.traveler(fwd, known))
        // Nobody known in the itinerary, or two of them: no guess.
        assertNull(Importer.traveler(fwd.replace("SAM LEE", "someone"), known))
        assertNull(Importer.traveler("SAM LEE and JORDAN PARK\nDL 1", known))
        assertNull(Importer.traveler("Sam went to the park", known)) // first and last name both needed
    }

    @Test
    fun workTrips() {
        assertTrue(Importer.isWork("Booked via Concur. Cost center 4411"))
        assertTrue(Importer.isWork("Purpose of travel: client visit"))
        assertFalse(Importer.isWork("Thanks for choosing United. Seat 21C"))
    }

    @Test
    fun cleanAndStripHtml() {
        assertEquals("Confirmation 7:00 AM", Importer.clean("Conﬁrmation 7:00 AM"))
        val eml = "<html><style>p{color:red}</style><p>Seat=\n 21C &amp; more</p><a href=3D\"x\">UA&nbsp;455</a></html>"
        val s = Importer.stripHtml(eml).trim()
        assertFalse(s.contains("<") || s.contains("color"))
        assertTrue(s, s.contains("Seat 21C & more"))
        assertTrue(s, s.contains("UA 455"))
    }

    @Test
    fun flightNumbers() {
        assertEquals("UA" to "1234", parseFlightNumber("ua 1234"))
        assertEquals("UA" to "1234", parseFlightNumber("UAL1234"))
        assertEquals("WN" to "12", parseFlightNumber("WN0012"))
        assertEquals("B6" to "5", parseFlightNumber("B6 5"))
        assertEquals("9W" to "123", parseFlightNumber("9W 123"))
        assertNull(parseFlightNumber("1234"))  // digits alone aren't "12" + "34"
        assertNull(parseFlightNumber("hello"))
        assertNull(parseFlightNumber("UA12345"))
        assertNull(parseFlightNumber("XYZ123")) // not an ICAO code we know, and too long for IATA
    }

    @Test
    fun sameTraveler() {
        assertTrue(sameName("Johnq Doe", "John Doe"))
        assertTrue(sameName("John Q Doe", "john doe"))
        assertTrue(sameName("John", "John Doe"))
        assertFalse(sameName("Jane Doe", "John Doe"))
        assertFalse(sameName("John Smith", "John Doe"))
        assertFalse(sameName("", "John Doe"))
    }

    // ---------- byAir ----------

    private val byAirHead = "Flight Date,Flight Code,Departure Airport Code,Arrival Airport Code,Departure Time,Arrival Time,Booking Code," +
        "Seat Number,Seat Type,Seat Class,Purpose,Notes,Ownership,byAir Flight ID,byAir Codeshare ID"

    @Test
    fun byAirExport() {
        val csv = """
            $byAirHead
            2026-06-20,UA950,SFO,LHR,17:00,11:30,TESTAB,21C,,,,"window, left",mine,1,0
            2026-07-20,WN2200,OAK,LAS,10:05,11:35,,,,,Business,,friend,2,0
        """.trimIndent()
        assertTrue(isByAirCsv(csv))
        val (ua, wn) = byAirFlights(csv)
        assertEquals("UA", ua.airline); assertEquals("950", ua.number); assertEquals("", ua.friend)
        assertEquals("21C", ua.seat); assertEquals("window, left", ua.notes); assertEquals("TESTAB", ua.booking)
        assertEquals("byAir", ua.source); assertFalse(ua.work)
        // 17:00 in SFO to 11:30 next day in London is 10h30m.
        assertEquals(Duration.ofMinutes(630), ua.duration)
        assertEquals("Friend", wn.friend); assertTrue(wn.work)
        assertEquals("Sam", byAirFlights(csv, friendName = "Sam")[1].friend)
    }

    @Test
    fun byAirSkipsBadRowsAndKeepsMissingTimes() {
        val csv = "﻿" + """
            $byAirHead
            2026-06-20,UA950,SFO,XXX,17:00,11:30,,,,,,,mine,1,0
            not-a-date,UA950,SFO,LHR,17:00,11:30,,,,,,,mine,2,0
            2026-06-20,???,SFO,LHR,17:00,11:30,,,,,,,mine,3,0
            2026-06-21,UA951,LHR,SFO,,,,,,,,,mine,4,0
        """.trimIndent()
        assertTrue(isByAirCsv(csv))
        val f = byAirFlights(csv).single()
        assertEquals("951", f.number); assertNull(f.depSched); assertNull(f.arrSched)
        assertFalse(isByAirCsv("Date,Flight\n2026-01-01,UA1"))
    }

    @Test
    fun csvQuoting() {
        assertEquals(listOf("a", "b, c", "say \"hi\"", ""), csvLine("a,\"b, c\",\"say \"\"hi\"\"\","))
        assertEquals(listOf(""), csvLine(""))
    }
}
