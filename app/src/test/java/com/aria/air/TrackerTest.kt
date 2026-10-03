package com.aria.air

import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.util.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Worker cadence, the ongoing notification, alerts and reminders (Tracker.kt), without Android. */
class TrackerTest : AirTest() {
    @Test
    fun checkCadence() {
        fun next(vararg f: Flight) = Tracker.nextCheckMinutes(f.toList(), NOW)
        assertEquals(30L, next(flight(hours(48))))                  // days away
        assertEquals(10L, next(flight(hours(8))))                   // the countdown notification is up
        assertEquals(5L, next(flight(hours(3))))                    // leaving for the airport
        assertEquals(2L, next(flight(min(30))))                     // at the gate
        assertEquals(15L, next(flight(hours(-1))))                  // cruising
        assertEquals(5L, next(flight(min(-280))))                   // 20 min to landing
        assertEquals(5L, next(flight(min(-330))))                   // just landed
        assertEquals(5L, next(flight(null).copy(date = localToday()))) // no schedule, maybe flying today
        assertEquals(2L, next(flight(hours(-1)), flight(min(30))))  // most urgent wins
        assertEquals(30L, next())
    }

    @Test
    fun whichFlightsTheWorkerFollows() {
        assertTrue(Tracker.tracked(flight(hours(24 * 30)), NOW))                        // mine, far out
        assertFalse(Tracker.tracked(flight(hours(24)).copy(friend = "Sam"), NOW))       // a friend's, not yet
        assertTrue(Tracker.tracked(flight(hours(5)).copy(friend = "Sam"), NOW))
        assertFalse(Tracker.tracked(flight(hours(-24)), NOW))                           // done
        assertTrue(Tracker.tracked(flight(null).copy(date = localToday()), NOW))        // maybe flying today
    }

    @Test
    fun ongoingNotificationWindow() {
        assertFalse(Notify.wantsLive(flight(hours(13)), NOW))
        assertTrue(Notify.wantsLive(flight(hours(11)), NOW))                          // mine: 12 h ahead by default
        assertFalse(Notify.wantsLive(flight(hours(4)).copy(friend = "Sam"), NOW))
        assertTrue(Notify.wantsLive(flight(hours(2)).copy(friend = "Sam"), NOW))      // friends: 3 h
        assertTrue(Notify.wantsLive(flight(hours(-1)), NOW))                          // in the air
        assertTrue(Notify.wantsLive(flight(min(-330)), NOW))                          // just landed
        assertFalse(Notify.wantsLive(flight(hours(-8)), NOW))
        assertFalse(Notify.wantsLive(flight(hours(1)).copy(cancelled = true), NOW))
        assertFalse(Notify.wantsLive(flight(null), NOW))                             // no time yet
        Prefs.setFlag("liveMine", false)
        try {
            assertFalse(Notify.wantsLive(flight(hours(-1)), NOW))
        } finally {
            Prefs.setFlag("liveMine", true)
        }
    }

    private fun events(old: Flight, new: Flight): Pair<List<String>, String> {
        val sent = mutableListOf<String>()
        val alerted = Notify.events(old, new) { key, title, text -> sent += "$key|$title|$text" }
        return sent to alerted
    }

    @Test
    fun eachEventAlertsOnce() {
        val f = flight(hours(-1))
        val off = f.copy(depActual = f.depSched)
        val (sent, alerted) = events(f, off)
        assertEquals(listOf("departed|UA 100 took off|SFO → BOS, lands 12:00 PM local"), sent)
        assertEquals("departed", alerted)
        assertTrue(events(f.copy(alerted = alerted), off.copy(alerted = alerted)).first.isEmpty())
        // Friends' alerts name them.
        assertEquals("departed|Sam · UA 100 took off|SFO → BOS, lands 12:00 PM local", events(f, off.copy(friend = "Sam")).first.single())
    }

    @Test
    fun landedCancelledDiverted() {
        val f = flight(hours(-6)).let { it.copy(depActual = it.depSched) }
        val landed = f.copy(arrActual = NOW.minus(min(30)), arrGate = "B5", baggage = "4")
        assertTrue(events(f, landed).first.contains("landed|UA 100 landed in Boston|Arrived 7:30 AM · Gate B5 · Bags at 4"))
        val c = flight(hours(2))
        assertEquals(listOf("cancelled|UA 100 cancelled|SFO → BOS on Today"), events(c, c.copy(cancelled = true)).first)
        assertTrue(events(f, f.copy(diverted = true)).first.contains("diverted|UA 100 diverted|Was flying SFO → BOS"))
    }

    @Test
    fun delaysAndGateChangesReAlertWhenTheyChange() {
        val f = flight(hours(5)).copy(updated = NOW, depEst = NOW.plus(hours(5)), depGate = "F12")
        val late = f.copy(depEst = f.depSched!!.plus(min(30)))
        assertEquals(listOf("delay30|UA 100 delayed 30m|Now departs 10:30 AM from SFO"), events(f, late).first)
        val (_, alerted) = events(f, late)
        val later = late.copy(depEst = f.depSched!!.plus(min(50)))
        assertEquals("delay50", events(late.copy(alerted = alerted), later).first.single().substringBefore('|'))
        assertTrue(events(f, f.copy(depEst = f.depSched!!.plus(min(10)))).first.isEmpty()) // under 15 min
        assertEquals(
            listOf("gateF14|UA 100 gate change|Now departing from gate F14 (was F12)"),
            events(f, f.copy(depGate = "F14")).first,
        )
        assertTrue(events(f.copy(depGate = ""), f).first.isEmpty()) // first gate isn't a change
    }

    @Test
    fun scheduleChange() {
        val f = flight(hours(24 * 10))
        val moved = f.copy(depSched = f.depSched!!.plus(hours(2)))
        assertEquals(
            listOf("sched${moved.depSched!!.epochSecond}|UA 100 schedule change|Now departs Mon, Oct 12 at 7:00 AM (was 5:00 AM)"),
            events(f, moved).first,
        )
        assertTrue(events(f, f.copy(depSched = f.depSched!!.plus(min(3)))).first.isEmpty())
    }

    @Test
    fun lateInboundPlane() {
        val f = flight(hours(3))
        val late = f.copy(inbound = Inbound("UAL523", "DEN", "SFO", 39.0, -110.0, 30000, 450, false, f.depSched!!.minus(min(10)), NOW))
        val (sent, alerted) = events(f, late)
        assertEquals(1, sent.size)
        assertTrue(sent[0], sent[0].startsWith("inbound") && sent[0].contains("your plane is running late"))
        assertTrue(events(f.copy(alerted = alerted), late.copy(alerted = alerted)).first.isEmpty())
    }

    @Test
    fun reminders() {
        fun due(f: Flight, hours: Int = 3, checkin: Boolean = true) = Notify.dueReminders(f, NOW, hours, checkin).map { it.first }
        assertEquals(listOf<String>(), due(flight(hours(30))))
        assertEquals(listOf("checkin"), due(flight(hours(20))))
        assertEquals(listOf("checkin", "reminder"), due(flight(hours(2))))
        assertEquals(listOf("reminder"), due(flight(hours(2)).copy(alerted = "checkin")))
        assertEquals(listOf<String>(), due(flight(hours(2)).copy(alerted = "checkin,reminder")))
        assertEquals(listOf("checkin"), due(flight(hours(2)), hours = 0))  // reminder off
        assertEquals(listOf("reminder"), due(flight(hours(2)), checkin = false))
        assertEquals(listOf<String>(), due(flight(hours(-1))))  // already left
        assertEquals(listOf<String>(), due(flight(null)))       // no time
        val f = flight(hours(2)).copy(booking = "TESTAB", depGate = "F12")
        val (checkin, reminder) = Notify.dueReminders(f, NOW, 3, true)
        assertEquals("Check in for UA 100 to Boston", checkin.second)
        assertEquals("Check-in is open. Departs Today at 7:00 AM from SFO · Conf. TESTAB", checkin.third)
        assertEquals("UA 100 to Boston leaves in 2h 0m", reminder.second)
        assertEquals("Boarding 6:20 AM at SFO, gate F12", reminder.third)
    }
}
