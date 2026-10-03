package com.aria.air

import com.aria.air.data.*
import com.aria.air.util.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalTime

/** Phases, status, delays, the live fix and the inbound plane (Data.kt). */
class FlightTest : AirTest() {
    private val sfo get() = Db.airport("SFO")!!

    // ---------- Connections ----------

    @Test
    fun connectingFlightsBecomeOneJourney() {
        val a = flight(hours(2), block = hours(3), from = "SFO", to = "DEN")              // lands in 5 h
        val b = flight(hours(6), block = hours(2), from = "DEN", to = "PIT").copy(number = "200") // 1 h layover
        val c = flight(hours(10), block = hours(1), from = "PIT", to = "BOS").copy(number = "300") // 2 h layover
        assertTrue(connects(a, b))
        assertEquals(listOf(listOf(a, b, c)), connections(listOf(c, a, b))) // order doesn't matter
        // Same-day return trip: out in the morning, back 13 h later. Two journeys.
        val back = flight(hours(18), from = "DEN", to = "SFO").copy(number = "400")
        assertFalse(connects(a, back.copy(from = "DEN")))
        // Not from where the last one landed, or someone else's flight.
        assertFalse(connects(a, b.copy(from = "ORD")))
        assertFalse(connects(a, b.copy(friend = "Alex")))
        // Overnight (17 h): only when booked together.
        val next = flight(hours(22), from = "DEN", to = "PIT").copy(number = "500")
        assertFalse(connects(a, next))
        assertTrue(connects(a.copy(booking = "TESTAB"), next.copy(booking = "TESTAB")))
    }

    // ---------- Phases and status ----------

    @Test
    fun phasesByTheClock() {
        assertEquals(Phase.PreTravel, flight(hours(4)).phase(NOW))
        assertEquals(Phase.Departure, flight(hours(2)).phase(NOW))
        assertEquals(Phase.InFlight, flight(hours(-1)).phase(NOW))
        assertEquals(Phase.Arrival, flight(hours(-5).minus(min(30))).phase(NOW))
        assertEquals(Phase.Done, flight(hours(-7)).phase(NOW))
        assertTrue(flight(hours(-1)).upcoming)
        assertFalse(flight(hours(-7)).upcoming)
    }

    @Test
    fun statusLines() {
        assertEquals("Upcoming" to Tone.Neutral, flight(hours(4)).status(NOW))
        // Past departure with no takeoff seen: don't claim it's flying.
        assertEquals("Due to depart" to Tone.Info, flight(min(-10)).status(NOW))
        // Half an hour past with nothing newer (offline on board): most likely flying.
        assertEquals("Probably in the air" to Tone.Info, flight(hours(-1)).status(NOW))
        val off = flight(hours(-1)).let { it.copy(depActual = it.depSched) }
        assertEquals("In the air" to Tone.Info, off.status(NOW))
        assertEquals("Arrived" to Tone.Good, flight(hours(-5).minus(min(30))).status(NOW))
        val landed = flight(hours(-6)).let { it.copy(depActual = it.depSched, arrActual = NOW.minus(min(30))) }
        assertEquals(Phase.Arrival, landed.phase(NOW))
        assertEquals("Landed 30m late" to Tone.Good, landed.status(NOW))
        assertEquals("Landed" to Tone.Good, landed.copy(arrActual = landed.arrSched!!.plus(min(10))).status(NOW))
        assertEquals(Phase.Done, landed.copy(arrActual = NOW.minus(hours(2))).phase(NOW))
        assertEquals("Diverted" to Tone.Bad, off.copy(diverted = true).status(NOW))
    }

    @Test
    fun cancelledStaysVisibleForSixHours() {
        val f = flight(hours(-5)).copy(cancelled = true)
        assertEquals(Phase.Departure, f.phase(NOW))
        assertEquals("Cancelled" to Tone.Bad, f.status(NOW))
        assertEquals(Phase.Done, flight(hours(-7)).copy(cancelled = true).phase(NOW))
    }

    @Test
    fun onTimeOnlyCloseToDeparture() {
        fun f(depIn: Duration, lateMin: Long = 0) = flight(depIn).let { it.copy(depEst = it.depSched!!.plus(min(lateMin)), updated = NOW) }
        assertEquals("Upcoming", f(hours(24 * 21)).status(NOW).first)  // weeks out: no real status exists yet
        assertNull(f(hours(24 * 21), lateMin = 40).depDelay)            // and no "delay" either
        assertEquals("On time" to Tone.Good, f(hours(20)).status(NOW))
        assertEquals("Delayed 40m" to Tone.Warn, f(hours(20), lateMin = 40).status(NOW))
        assertEquals("Delayed 1h 30m" to Tone.Bad, f(hours(20), lateMin = 90).status(NOW))
        assertEquals("On time", f(hours(20), lateMin = 10).status(NOW).first) // under 15 min isn't a delay
        assertEquals("Upcoming", f(hours(20)).copy(updated = NOW.minus(hours(8))).status(NOW).first) // stale status
        assertEquals("Upcoming", f(hours(20)).copy(depEst = null).status(NOW).first)                 // no estimate
    }

    @Test
    fun delaysFromActualsAlwaysCount() {
        val f = flight(hours(-1))
        assertEquals(12L, f.copy(depActual = f.depSched!!.plus(min(12))).depDelay)
        // Arrival estimates count once it took off, even without a fresh status.
        val flying = f.copy(depActual = f.depSched, arrEst = f.arrSched!!.plus(min(25)))
        assertEquals(25L, flying.arrDelay)
        assertNull(f.copy(arrEst = f.arrSched!!.plus(min(25))).arrDelay)
        assertEquals(-5L, f.copy(arrActual = f.arrSched!!.minus(min(5))).arrDelay)
        assertNull(f.copy(depSched = null).depDelay)
    }

    @Test
    fun takeoffFromAnotherLegIsIgnored() {
        val f = flight(hours(1))
        assertNull(f.copy(depActual = f.depSched!!.minus(hours(3))).tookOff)
        assertEquals(f.depSched!!.minus(min(30)), f.copy(depActual = f.depSched!!.minus(min(30))).tookOff)
        assertEquals(NOW, f.copy(depSched = null, depActual = NOW).tookOff)
    }

    @Test
    fun bestTimesAndGuesses() {
        val f = flight(hours(2))
        assertEquals(f.depSched, f.dep)
        assertEquals(f.depSched!!.plus(min(20)), f.copy(depEst = f.depSched!!.plus(min(20))).dep)
        assertEquals(f.arrSched!!.plus(min(5)), f.copy(arrEst = f.arrSched!!.plus(min(5)), arrActual = f.arrSched!!.plus(min(5))).arr)
        // No times: noon at the origin on the date, then a block time from the distance.
        val bare = flight(null)
        assertEquals(TODAY.atTime(LocalTime.NOON).atZone(sfo.tz).toInstant(), bare.depGuess)
        assertEquals(bare.depGuess.plus(blockTime(bare.distanceKm)), bare.arrGuess)
        assertEquals(blockTime(bare.distanceKm), bare.duration)
        assertFalse(bare.hasTimes); assertTrue(f.hasTimes)
        assertEquals(min(30), blockTime(0.0))
        assertEquals(min(89), blockTime(800.0)) // 497 mi at 500 mph, plus 30 min
    }

    @Test
    fun airlineStatusBeatsTracking() {
        val f = flight(hours(1)).copy(updated = NOW.minus(min(5)))
        assertTrue(f.airlineSaysNotDeparted(NOW))
        assertFalse(f.copy(updated = NOW.minus(min(40))).airlineSaysNotDeparted(NOW))  // stale
        assertFalse(f.copy(depActual = f.depSched).airlineSaysNotDeparted(NOW))         // it did leave
        assertFalse(flight(hours(-1)).copy(updated = NOW).airlineSaysNotDeparted(NOW))   // 1 h past the plan
        assertFalse(flight(null).copy(updated = NOW).airlineSaysNotDeparted(NOW))        // no plan at all
        // A fix that looks like us climbing out, but the airline (checked 5 min ago) has no takeoff yet.
        val g = f.copy(depEst = f.depSched, live = Live(sfo.lat, sfo.lon, 0, 8, 0.0, 0, NOW, true))
        assertEquals(Phase.Departure, g.phase(NOW))
        assertTrue(g.status(NOW).first != "In the air")
    }

    @Test
    fun maybeLiveWithoutSchedule() {
        val f = flight(null).copy(date = localToday())
        assertTrue(f.maybeLive)
        assertTrue(f.copy(date = localToday().minusDays(1)).maybeLive)
        assertFalse(f.copy(date = localToday().minusDays(2)).maybeLive)
        assertFalse(f.copy(cancelled = true).maybeLive)
        assertFalse(f.copy(arrActual = NOW).maybeLive)
        assertFalse(flight(hours(1)).copy(date = localToday()).maybeLive)
    }

    // ---------- Live position ----------

    private fun path(f: Flight) = f.origin!!.let { o -> f.dest!!.let { d -> greatCircle(o.lat, o.lon, d.lat, d.lon, 100) } }
    private fun fix(f: Flight, i: Int) = path(f)[i].let { Live(it.first, it.second, 35000, 480, 70.0, 0, NOW, false) }

    @Test
    fun liveFixMustFitTheClock() {
        val f = flight(hours(2))
        assertFalse(plausible(f, fix(f, 30)))  // not due to leave for 2 h: a plane mid-route is another flight
        assertTrue(plausible(f, fix(f, 0).copy(onGround = true, altFt = 0, speedKt = 5)))  // at the gate
        assertFalse(plausible(f, fix(f, 0)))   // in the air over the airport before departure: arriving, not us
        val g = flight(hours(-1))
        assertTrue(plausible(g, fix(g, 25)))   // an hour after departure, ~1,100 km out: fits
        assertFalse(plausible(g, fix(g, 80)))  // an hour after departure, ~3,500 km out: impossible
        assertTrue(plausible(flight(null), fix(g, 80)))  // no schedule: only the route is checked
    }

    @Test
    fun offRouteIsNotUs() {
        val f = flight(hours(-1))
        val mia = Db.airport("MIA")!!
        assertFalse(onRoute(f, Live(mia.lat, mia.lon, 35000, 480, 90.0, 0, NOW, false)))
        assertTrue(onRoute(f, fix(f, 50)))
        assertTrue(onRoute(f.copy(to = "???"), Live(0.0, 0.0, 0, 0, 0.0, 0, NOW, false))) // unknown airport: can't tell
    }

    @Test
    fun airborneNeedsAFreshMovingPlausibleFix() {
        val f = flight(hours(-1))
        val l = fix(f, 25)
        assertTrue(f.copy(live = l).isAirborne)
        assertFalse(f.copy(live = l.copy(at = NOW.minus(min(15)))).isAirborne) // stale
        assertFalse(f.copy(live = l.copy(onGround = true)).isAirborne)
        assertFalse(f.copy(live = l.copy(speedKt = 40)).isAirborne)
        assertFalse(f.isAirborne)
    }

    @Test
    fun staleBadDataDoesNotSayInTheAir() {
        // 75 min before takeoff, the earlier leg of the same number was saved as ours: its takeoff and its fix.
        val f = flight(min(75)).let {
            it.copy(
                depActual = it.depSched!!.minus(hours(3)),
                live = Live(sfo.lat + 0.5, sfo.lon - 0.3, 19000, 370, 150.0, -1500, NOW, false), // 60 km out, descending
            )
        }
        assertFalse(f.isAirborne)
        assertNull(f.tookOff)
        assertEquals(Phase.Departure, f.phase(NOW))
        assertEquals("Upcoming", f.status(NOW).first)
    }

    @Test
    fun inboundOnFinalApproachIsNotUs() {
        // The earlier leg of our number is 10 km out on final, descending into SFO.
        val approach = Live(sfo.lat + 0.07, sfo.lon - 0.05, 2500, 150, 150.0, -800, NOW, false) // NW of SFO, heading SE
        assertFalse(flight(min(75)).copy(live = approach).isAirborne)
        assertEquals("Upcoming", flight(min(75)).copy(live = approach).status(NOW).first)
        // Same approach but our departure time has come: still a landing, not our takeoff.
        assertFalse(flight(min(-5)).copy(live = approach).isAirborne)
        // 5 min after our departure, 10 km out, climbing away east: that's us.
        val climbOut = Live(sfo.lat + 0.02, sfo.lon + 0.1, 6000, 220, 75.0, 2500, NOW, false)
        assertTrue(flight(min(-5)).copy(live = climbOut).isAirborne)
        assertEquals("In the air", flight(min(-5)).copy(live = climbOut).status(NOW).first)
    }

    @Test
    fun progressByDistanceOrClock() {
        val f = flight(hours(-1))
        assertEquals(0.2, f.progress(NOW), 1e-9)                 // 1 h into 5
        assertEquals(0.0, flight(hours(1)).progress(NOW), 1e-9)
        assertEquals(1.0, flight(hours(-9)).progress(NOW), 1e-9)
        val flying = f.copy(live = fix(f, 25))
        assertEquals(0.25, flying.progress(NOW), 0.01)
        assertEquals(flying.distanceKm * 0.75, flying.remainingKm(), 15.0)
        assertEquals(f.distanceKm * 0.8, f.remainingKm(), 1.0)
    }

    // ---------- Inbound plane ----------

    private fun inbound(f: Flight, minBefore: Long) =
        f.copy(inbound = Inbound("UAL523", "DEN", "SFO", 39.0, -110.0, 30000, 450, false, f.depSched!!.minus(min(minBefore)), NOW))

    @Test
    fun inboundRisk() {
        val f = flight(hours(3))
        assertEquals(Tone.Neutral, f.inboundTone())
        assertEquals(Tone.Good, inbound(f, 60).inboundTone())
        assertEquals(Tone.Warn, inbound(f, 30).inboundTone())
        assertEquals(Tone.Bad, inbound(f, 10).inboundTone())
        assertEquals(60L, inbound(f, 60).inboundSpare())
        val here = f.copy(inbound = Inbound("UAL523", "DEN", "SFO", sfo.lat, sfo.lon, 0, 0, true, null, NOW))
        assertTrue(here.inboundHere()); assertEquals(Tone.Good, here.inboundTone())
        assertEquals("UA 523", here.inbound!!.label)
        assertEquals("N12345", here.inbound!!.copy(callsign = "N12345").label) // not an airline callsign
    }

    @Test
    fun inboundLines() {
        val f = flight(hours(3))
        assertNull(f.inboundLine())
        assertEquals("UA 523 from DEN lands 7:00 AM · 1h 0m to spare", inbound(f, 60).inboundLine())
        assertEquals("UA 523 from DEN lands 8:20 AM · 20m after your departure", inbound(f, -20).inboundLine())
        val elsewhere = f.copy(inbound = Inbound("UAL523", "DEN", "LAX", 39.8, -104.7, 0, 0, true, null, NOW))
        assertEquals("Your plane is on the ground before its flight to LAX", elsewhere.inboundLine())
        val noEta = f.copy(inbound = Inbound("UAL523", "DEN", "SFO", 39.0, -110.0, 30000, 450, false, null, NOW))
        assertEquals("UA 523 DEN → SFO", noEta.inboundLine())
    }

    @Test
    fun planeParkedOvernight() {
        val morning = TODAY.plusDays(1).atTime(7, 0).atZone(sfo.tz).toInstant()
        val seen = morning.minus(hours(3))
        val parked = Inbound("UAL100", "", "SFO", sfo.lat, sfo.lon, 0, 0, true, null, seen)
        val f = flight(null).copy(date = TODAY.plusDays(1), depSched = morning, arrSched = morning.plus(hours(5)), inbound = parked)
        assertTrue(f.inboundOvernight())
        assertEquals(Tone.Good, f.inboundTone())
        assertEquals("Your plane is at the gate at SFO · likely overnight", f.inboundLine())
        // Seen landing: not overnight, and the line says when it landed.
        val landed = f.copy(inbound = parked.copy(landedAt = seen))
        assertFalse(landed.inboundOvernight())
        assertEquals("Your plane is at the gate at SFO · landed 4:00 AM", landed.inboundLine())
        // An afternoon departure: parked, but not necessarily overnight.
        val afternoon = morning.plus(hours(8))
        assertFalse(f.copy(depSched = afternoon, arrSched = afternoon.plus(hours(5))).inboundOvernight())
    }

    // ---------- Boarding ----------

    @Test
    fun boardingDependsOnAircraft() {
        val f = flight(hours(2))
        assertEquals(min(40), f.copy(aircraft = "B38M").boardingLead)            // 737 MAX 8
        assertEquals(min(40), f.boardingLead)                                     // unknown yet
        assertEquals(min(50), f.copy(aircraft = "Boeing 787-9").boardingLead)
        assertEquals(min(50), f.copy(aircraft = "B77W").boardingLead)
        assertEquals(min(30), f.copy(aircraft = "Embraer E175").boardingLead)
        assertEquals(min(30), f.copy(aircraft = "CRJ7").boardingLead)
        assertEquals(f.depSched!!.minus(min(40)), f.boarding)
        assertNull(flight(null).boarding)
    }
}
