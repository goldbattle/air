package com.aria.air.data

// The plane's previous flight (the inbound), and what it means for our departure.

import com.aria.air.util.*
import java.time.Duration
import java.time.Instant

/**
 * The aircraft assigned to a flight, seen on its previous flight (by tail number, from adsb.lol): where it is and
 * when it gets to our departure airport. [from]/[to] are that flight's route when known.
 */
data class Inbound(
    val callsign: String, val from: String, val to: String,
    val lat: Double, val lon: Double, val altFt: Int, val speedKt: Int, val onGround: Boolean,
    val eta: Instant?, val at: Instant,
    /** When we saw it touch down at our airport (null if it was already parked when we started watching). */
    val landedAt: Instant? = null,
    /** First time we saw it at all, to tell "landed an hour ago" from "was already there, probably overnight". */
    val firstSeen: Instant = at,
) {
    /** "UA 523" from the callsign "UAL523". */
    val label: String get() = Db.airlinesByIcao[callsign.take(3)]?.let { "${it.iata} ${callsign.drop(3)}" } ?: callsign
}
/** Minutes between the inbound plane reaching our airport and our departure, or null when unknown. */
fun Flight.inboundSpare(): Long? = inbound?.eta?.let { Duration.between(it, dep ?: depGuess).toMinutes() }

/** Whether the inbound plane is already parked at our departure airport. */
fun Flight.inboundHere(): Boolean = inbound?.let { i ->
    i.onGround && origin?.let { o -> distKm(o.lat, o.lon, i.lat, i.lon) < 8 } == true
} == true

/**
 * Parked at our airport since before we started watching, for a morning departure: it most likely spent the night
 * there, which is the best case (no inbound flight to be late).
 */
fun Flight.inboundOvernight(): Boolean {
    val i = inbound ?: return false
    return inboundHere() && i.landedAt == null && ((dep ?: depGuess).atZone(fromTz).hour < 11)
}

/** How worrying the inbound is: a plane needs roughly 30-45 min on the ground between flights. */
fun Flight.inboundTone(): Tone = when {
    inbound == null -> Tone.Neutral
    inboundHere() -> Tone.Good
    else -> inboundSpare()?.let { if (it >= 45) Tone.Good else if (it >= 25) Tone.Warn else Tone.Bad } ?: Tone.Neutral
}

/** One line for the card and the flight page: "UA 523 from DEN lands 7:32 AM · 38 min to spare". */
fun Flight.inboundLine(): String? {
    val i = inbound ?: return null
    val spare = inboundSpare()
    return when {
        inboundHere() -> "Your plane is at the gate at $from" +
            (i.landedAt?.let { " · landed ${time(it, fromTz)}" } ?: if (inboundOvernight()) " · likely overnight" else "")
        i.onGround -> "Your plane is on the ground" + (if (i.to.isNotEmpty() && i.to != from) " before its flight to ${i.to}" else "")
        i.eta != null -> "${i.label}${if (i.from.isNotEmpty()) " from ${i.from}" else ""} lands ${time(i.eta, fromTz)}" +
            (spare?.let { if (it >= 0) " · ${span(Duration.ofMinutes(it))} to spare" else " · ${span(Duration.ofMinutes(-it))} after your departure" } ?: "")
        else -> "${i.label} ${i.from} → ${i.to}"
    }
}
