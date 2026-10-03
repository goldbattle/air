package com.aria.air.data

// The flight record we keep, and the times, places and aircraft facts derived from it.

import com.aria.air.util.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Last ADS-B fix for a flight (from adsb.lol). */
data class Live(
    val lat: Double, val lon: Double, val altFt: Int, val speedKt: Int, val track: Double, val vrateFpm: Int,
    val at: Instant, val onGround: Boolean, val aircraft: String = "", val reg: String = "", val hex: String = "",
)

/**
 * One flight, mine or a friend's: what we were told (schedule, seat, booking), what we've learned since (status, gates,
 * live position, weather, the inbound plane, the flown path), and bookkeeping (alerts sent, last refresh). Saved in
 * flights.json; see FlightJson.kt.
 */
data class Flight(
    val id: String = UUID.randomUUID().toString(),
    val airline: String,              // IATA, "UA"
    val number: String,               // "1234"
    val date: LocalDate,              // departure date, local to the origin
    val from: String, val to: String, // IATA airport codes
    val depSched: Instant? = null, val depEst: Instant? = null, val depActual: Instant? = null,
    val arrSched: Instant? = null, val arrEst: Instant? = null, val arrActual: Instant? = null,
    val depTerminal: String = "", val depGate: String = "",
    val arrTerminal: String = "", val arrGate: String = "", val baggage: String = "",
    val aircraft: String = "", val reg: String = "",
    val cancelled: Boolean = false, val diverted: Boolean = false,
    val seat: String = "", val booking: String = "", val notes: String = "",
    val friend: String = "",          // empty = my flight
    val source: String = "manual",    // manual, search, pdf, image, email, text
    val work: Boolean = false,        // business trip (my flights)
    val depWx: Wx? = null, val arrWx: Wx? = null, // weather at takeoff and landing
    val routeWx: RouteWx? = null,     // weather along the route (Weather.kt)
    val inbound: Inbound? = null,     // the plane's previous flight, before ours
    val faAt: Instant? = null,        // when FlightAware last gave gates, tail and inbound (they outrank AeroDataBox's)
    val adbSaid: String = "",         // AeroDataBox's last raw "departure gate|arrival gate", to notice it changing
    val disputed: Boolean = false,    // AeroDataBox just reported a new gate that FlightAware should confirm (not saved)
    val live: Live? = null,
    val updated: Instant? = null,     // last successful status refresh
    val alerted: String = "",         // events already notified (comma list), so alerts fire once
    // The flown path (Track.kt): recorded while live, completed from adsb.lol's trace of the plane ([hex]) after landing.
    val track: List<Pt> = emptyList(), val hex: String = "", val trackAt: Instant? = null,
)

/** "UA1234", as airlines and status services write it. */
val Flight.code get() = "$airline$number"

/** "UA 1234", as shown on screen. */
val Flight.label get() = "$airline $number"

/** Mine, as opposed to a friend's. */
val Flight.mine get() = friend.isEmpty()

/** The departure airport, or null when it isn't in the bundled list. */
val Flight.origin get() = Db.airport(from)

/** The arrival airport, or null when it isn't in the bundled list. */
val Flight.dest get() = Db.airport(to)

/** The departure airport's time zone (the phone's when unknown): local times at the start are shown in it. */
val Flight.fromTz: ZoneId get() = origin?.tz ?: ZoneId.systemDefault()

/** The arrival airport's time zone (the phone's when unknown). */
val Flight.toTz: ZoneId get() = dest?.tz ?: ZoneId.systemDefault()

/** Great-circle distance between the airports, 0 when one is unknown. */
val Flight.distanceKm: Double get() = origin?.let { o -> dest?.let { distKm(o, it) } } ?: 0.0

/** Best known departure/arrival: actual, then estimate, then schedule. */
val Flight.dep get() = tookOff ?: depEst ?: depSched
val Flight.arr get() = arrActual ?: arrEst ?: arrSched

// ponytail: crude estimate, good enough for sorting and stats; real times replace it once known.
/** Typical block time when no schedule is known: cruise at ~500 mph plus 30 min taxi/climb. */
fun blockTime(km: Double): Duration = Duration.ofMinutes((km / KM_PER_MI / 500 * 60 + 30).toLong())

/** Departure used for ordering and phases; noon local on the date when no time is known. */
val Flight.depGuess: Instant get() = dep ?: date.atTime(LocalTime.NOON).atZone(fromTz).toInstant()
val Flight.arrGuess: Instant get() = arr ?: depGuess.plus(blockTime(distanceKm))

/** Gate to gate, from the best times known (estimated when there are none). */
val Flight.duration: Duration get() = Duration.between(dep ?: depGuess, arr ?: arrGuess)

/** Whether a schedule is known (from a document, a status service, or typed in). */
val Flight.hasTimes get() = depSched != null && arrSched != null

/** ICAO callsign as broadcast by the plane: UA1234 -> UAL1234. */
fun callsign(airline: String, number: String) = (Db.airline(airline)?.icao ?: airline) + number.trimStart('0')

/**
 * How long FlightAware's gates, baggage and tail outrank AeroDataBox's after it gave them. A real change is caught
 * sooner than this: AeroDataBox changing its answer gets FlightAware asked again (see FlightSync.secondOpinion).
 */
val FA_OWNS: Duration = Duration.ofHours(6)

// ponytail: typical US-airline values by aircraft size; airlines don't publish boarding times in the status feeds.
/**
 * How long before departure boarding usually starts, by aircraft: about 50 min for twin-aisle jets, 30 for regional
 * jets and turboprops, 40 for everything else (and when the aircraft isn't known yet).
 */
val Flight.boardingLead: Duration
    get() {
        val a = aircraft.uppercase().replace(" ", "")
        val wide = listOf("B77", "B78", "B74", "B76", "A33", "A34", "A35", "A38", "777", "787", "747", "767", "A330", "A340", "A350", "A380")
        val regional = listOf("CRJ", "E17", "E75", "E19", "ERJ", "DH8", "AT7", "AT4", "DASH", "EMBRAER", "CANADAIR")
        return when {
            wide.any { it in a } -> Duration.ofMinutes(50)
            regional.any { it in a } -> Duration.ofMinutes(30)
            else -> Duration.ofMinutes(40)
        }
    }

/** Estimated start of boarding, or null without a departure time. */
val Flight.boarding: Instant? get() = dep?.minus(boardingLead)
