package com.aria.air.data

// Connecting flights chained into journeys.

import java.time.Duration

/** The most time between landing and the next takeoff for two flights to count as one journey. */
val CONNECTION_MAX: Duration = Duration.ofHours(6)

/**
 * Whether [b] continues [a]'s journey: same traveler, leaving the airport [a] landed at within [CONNECTION_MAX] of
 * landing. Booked together (same confirmation code) allows an overnight connection, up to a day.
 */
fun connects(a: Flight, b: Flight): Boolean {
    if (a.friend != b.friend || a.to != b.from || a.cancelled || b.cancelled) return false
    val together = a.booking.isNotEmpty() && a.booking == b.booking
    val landed = a.arr ?: return together && b.date in a.date..a.date.plusDays(1)
    val leaves = b.dep ?: return together && b.date in a.date..a.date.plusDays(1)
    val gap = Duration.between(landed, leaves)
    return !gap.isNegative && gap <= if (together) Duration.ofDays(1) else CONNECTION_MAX
}

/** [flights] in order, with connecting ones chained into journeys (most are a single flight). */
fun connections(flights: List<Flight>): List<List<Flight>> {
    val out = mutableListOf<MutableList<Flight>>()
    for (f in flights.sortedBy { it.depGuess }) {
        out.lastOrNull { connects(it.last(), f) }?.add(f) ?: out.add(mutableListOf(f))
    }
    return out
}

/** The journey this flight is a leg of (just itself when it doesn't connect). */
fun Flight.connection(): List<Flight> =
    connections(Store.flights.filter { it.friend == friend }).firstOrNull { c -> c.any { it.id == id } } ?: listOf(this)
