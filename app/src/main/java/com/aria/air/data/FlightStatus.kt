package com.aria.air.data

// Where a flight is in its trip (phase), whether it's late, and whether a live fix is really this flight.

import com.aria.air.util.*
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Whether "on time" / "delayed" means anything yet. Airlines only publish real estimates close to departure, so it
 * needs: under 24 h to go, a status fetched in the last 6 h, and an estimated time in it. Weeks out it's just a schedule.
 */
val Flight.hasLiveStatus: Boolean
    get() {
        val now = Instant.now(clock)
        val d = dep ?: return false
        return depEst != null && updated != null && Duration.between(updated, now) < Duration.ofHours(6) &&
            now > d.minus(Duration.ofHours(24))
    }

/** Late (+) or early (-) in minutes: actual times always count, estimates only once they're live. Null = unknown. */
val Flight.depDelay: Long?
    get() = depSched?.let { s -> (tookOff ?: depEst?.takeIf { hasLiveStatus })?.let { Duration.between(s, it).toMinutes() } }
val Flight.arrDelay: Long?
    get() = arrSched?.let { s ->
        (arrActual ?: arrEst?.takeIf { hasLiveStatus || tookOff != null })?.let { Duration.between(s, it).toMinutes() }
    }
/** The phases of a trip, as in the airline apps: before, at the gate, in the air, after landing. */
enum class Phase(val title: String) { PreTravel("Pre-travel"), Departure("Departure"), InFlight("In flight"), Arrival("Arrival"), Done("Done") }

private val FRESH = Duration.ofMinutes(10)

/** Re-checked every time, so a bad fix saved earlier (another plane on the same number) can't keep us "in the air". */
val Flight.isAirborne: Boolean
    get() = live != null && !live.onGround && live.speedKt >= 60 && Duration.between(live.at, Instant.now(clock)) < FRESH &&
        plausible(this, live)

/** A status fetched in the last 30 minutes, from a service that would report takeoff, shows none yet. */
fun Flight.airlineSaysNotDeparted(now: Instant = Instant.now(clock)): Boolean {
    val planned = depEst ?: depSched ?: return false
    return updated != null && Duration.between(updated, now) < Duration.ofMinutes(30) && depActual == null &&
        now < planned.plus(Duration.ofMinutes(30))
}

/** A takeoff time we believe: none more than an hour before the schedule (that would be another day's or leg's). */
val Flight.tookOff: Instant?
    get() = depActual?.takeIf { a -> depSched == null || a > depSched.minus(Duration.ofHours(1)) }

fun Flight.phase(now: Instant = Instant.now(clock)): Phase = when {
    cancelled -> if (now < depGuess.plus(Duration.ofHours(6))) Phase.Departure else Phase.Done
    arrActual != null -> if (now < arrActual.plus(Duration.ofHours(1))) Phase.Arrival else Phase.Done
    // The airline says it hasn't left yet (a fresh status with no takeoff time): believe it over any tracking data.
    airlineSaysNotDeparted(now) -> if (now < depGuess.minus(Duration.ofHours(3))) Phase.PreTravel else Phase.Departure
    isAirborne || tookOff != null && now < arrGuess -> Phase.InFlight
    now < depGuess.minus(Duration.ofHours(3)) -> Phase.PreTravel
    now < depGuess -> Phase.Departure
    now < arrGuess -> Phase.InFlight
    now < arrGuess.plus(Duration.ofHours(1)) -> Phase.Arrival
    else -> Phase.Done
}

val Flight.upcoming get() = phase() != Phase.Done

/** No schedule yet, but it's from yesterday or today and hasn't landed: worth asking where the plane is. */
val Flight.maybeLive: Boolean
    get() = !hasTimes && arrActual == null && !cancelled && date in localToday().minusDays(1)..localToday()

/** 0..1 through the flight: by distance when the plane is broadcasting, else by clock. */
fun Flight.progress(now: Instant = Instant.now(clock)): Double {
    val o = origin; val d = dest
    if (isAirborne && o != null && d != null) {
        val flown = distKm(o.lat, o.lon, live!!.lat, live.lon)
        val left = distKm(live.lat, live.lon, d.lat, d.lon)
        return (flown / (flown + left)).coerceIn(0.0, 1.0)
    }
    val total = Duration.between(depGuess, arrGuess).toMillis().coerceAtLeast(1)
    return (Duration.between(depGuess, now).toMillis().toDouble() / total).coerceIn(0.0, 1.0)
}

/** Remaining distance in km: from the live position if there is one, else by progress. */
fun Flight.remainingKm(): Double {
    val d = dest ?: return 0.0
    return if (isAirborne) distKm(live!!.lat, live.lon, d.lat, d.lon) else distanceKm * (1 - progress())
}

enum class Tone { Neutral, Good, Warn, Bad, Info }

/** Short status like the airline apps: "On time", "Delayed 45m", "In the air", "Landed". */
fun Flight.status(now: Instant = Instant.now(clock)): Pair<String, Tone> {
    val delay = if (phase(now) <= Phase.Departure) depDelay else arrDelay
    return when {
        cancelled -> "Cancelled" to Tone.Bad
        diverted -> "Diverted" to Tone.Bad
        arrActual != null -> (if ((arrDelay ?: 0) >= 15) "Landed ${span(Duration.ofMinutes(arrDelay!!))} late" else "Landed") to Tone.Good
        // Past the scheduled time with no takeoff seen: don't claim it's flying at first. Half an hour on with nothing
        // newer (offline on board, no signal), it most likely is; progress keeps running on the clock either way.
        phase(now) == Phase.InFlight -> when {
            isAirborne || tookOff != null -> "In the air"
            now > depGuess.plus(Duration.ofMinutes(30)) && !airlineSaysNotDeparted(now) -> "Probably in the air"
            else -> "Due to depart"
        } to Tone.Info
        phase(now) == Phase.Arrival || phase(now) == Phase.Done -> "Arrived" to Tone.Good
        delay != null && delay >= 15 -> "Delayed ${span(Duration.ofMinutes(delay))}" to (if (delay >= 60) Tone.Bad else Tone.Warn)
        hasLiveStatus -> "On time" to Tone.Good
        else -> "Upcoming" to Tone.Neutral // no real status yet (too far out, or no status service)
    }
}
/**
 * Whether a plane broadcasting this flight's callsign really is this flight today. Airlines reuse numbers on other
 * legs and days, so besides being between the airports it has to fit the clock: before the scheduled departure it
 * can only still be at the origin, and after it can't have flown further than a jet could since.
 */
fun plausible(f: Flight, l: Live): Boolean {
    if (!onRoute(f, l)) return false
    val o = f.origin ?: return true
    val dep = f.dep ?: return true // no schedule: the route check is all we have
    val flownKm = distKm(o.lat, o.lon, l.lat, l.lon)
    val since = Duration.between(dep.minus(Duration.ofMinutes(20)), l.at)
    // Before departure only a plane on the ground at the origin can be ours; one in the air nearby is arriving.
    if (since.isNegative) return l.onGround && flownKm < 15
    // Near the origin and descending toward it: that's a plane landing there (often our own inbound running
    // late on the same flight number), not ours taking off.
    if (!l.onGround && flownKm < 80 && l.vrateFpm < -300 && headingToward(l, o.lat, o.lon)) return false
    return flownKm <= since.toMinutes() / 60.0 * 1100 + 60 // 1100 km/h is faster than any airliner
}

/** Whether the plane's track points within 60 degrees of the direction to (lat, lon). */
private fun headingToward(l: Live, lat: Double, lon: Double): Boolean {
    val p1 = Math.toRadians(l.lat); val p2 = Math.toRadians(lat); val dl = Math.toRadians(lon - l.lon)
    val bearing = Math.toDegrees(kotlin.math.atan2(kotlin.math.sin(dl) * kotlin.math.cos(p2),
        kotlin.math.cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dl)))
    val diff = kotlin.math.abs(((bearing - l.track) % 360 + 540) % 360 - 180)
    return diff < 60
}

fun onRoute(f: Flight, l: Live): Boolean {
    val o = f.origin ?: return true
    val d = f.dest ?: return true
    val total = distKm(o, d)
    return distKm(o.lat, o.lon, l.lat, l.lon) + distKm(l.lat, l.lon, d.lat, d.lon) < total * 1.25 + 150
}
