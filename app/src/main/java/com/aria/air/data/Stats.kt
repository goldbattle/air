package com.aria.air.data

// Travel totals for the passport.

import java.time.Duration

/**
 * Totals over a list of flights: how many, how far, how long in the air, how often each airport, airline, country and
 * route comes up, and the longest flight.
 */
data class Stats(
    val flights: Int, val km: Double, val air: Duration,
    val airports: Map<String, Int>, val airlines: Map<String, Int>, val countries: Set<String>,
    val routes: Map<String, Int>, val longest: Flight?,
)

/** Adds up [list] for the passport. Distances are great-circle; time in the air uses the best times known. */
fun stats(list: List<Flight>): Stats {
    val airports = mutableMapOf<String, Int>()
    list.forEach { airports.merge(it.from, 1, Int::plus); airports.merge(it.to, 1, Int::plus) }
    return Stats(
        flights = list.size,
        km = list.sumOf { it.distanceKm },
        air = list.fold(Duration.ZERO) { acc, f -> acc + f.duration.let { if (it.isNegative) Duration.ZERO else it } },
        airports = airports,
        airlines = list.groupingBy { it.airline }.eachCount(),
        countries = airports.keys.mapNotNull { Db.airport(it)?.country }.toSet(),
        routes = list.groupingBy { listOf(it.from, it.to).sorted().joinToString(" – ") }.eachCount(),
        longest = list.maxByOrNull { it.distanceKm },
    )
}
