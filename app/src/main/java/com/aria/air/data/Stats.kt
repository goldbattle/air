package com.aria.air.data

// Travel totals for the passport.

import java.time.Duration
import java.time.Month

/**
 * Totals over a list of flights: how many, how far, how long in the air, how often each airport, airline, country,
 * route, aircraft type and month comes up, the longest flight, estimated CO₂, and how punctual the flights were.
 */
data class Stats(
    val flights: Int, val km: Double, val air: Duration,
    val airports: Map<String, Int>, val airlines: Map<String, Int>, val countries: Set<String>,
    val routes: Map<String, Int>, val longest: Flight?,
    val aircraft: Map<String, Int> = emptyMap(), val months: Map<Month, Int> = emptyMap(), val co2Kg: Double = 0.0,
    val timed: Int = 0, val onTime: Int = 0, val lateMinutes: Long = 0,
) {
    /** Average arrival delay over the flights with known times (early counts as zero); null when there are none. */
    val averageDelay: Long? get() = if (timed == 0) null else lateMinutes / timed
}

// ponytail: rough average-passenger factors (DEFRA-style, without contrail effects), ±30%; per aircraft and seat class
// would need fuel-burn tables.
/** Estimated CO₂ for one passenger's flight of [km]: shorter flights burn more per km on takeoff and climb. */
fun co2Kg(km: Double): Double = km * when {
    km < 800 -> 0.15
    km < 3700 -> 0.13
    else -> 0.11
}

/** Adds up [list] for the passport. Distances are great-circle; time in the air uses the best times known. */
fun stats(list: List<Flight>): Stats {
    val airports = mutableMapOf<String, Int>()
    list.forEach { airports.merge(it.from, 1, Int::plus); airports.merge(it.to, 1, Int::plus) }
    val delays = list.mapNotNull { it.arrDelay }
    return Stats(
        flights = list.size,
        km = list.sumOf { it.distanceKm },
        air = list.fold(Duration.ZERO) { acc, f -> acc + f.duration.let { if (it.isNegative) Duration.ZERO else it } },
        airports = airports,
        airlines = list.groupingBy { it.airline }.eachCount(),
        countries = airports.keys.mapNotNull { Db.airport(it)?.country }.toSet(),
        routes = list.groupingBy { listOf(it.from, it.to).sorted().joinToString(" – ") }.eachCount(),
        longest = list.maxByOrNull { it.distanceKm },
        aircraft = list.map { it.aircraft.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount(),
        months = list.groupingBy { it.date.month }.eachCount(),
        co2Kg = list.sumOf { co2Kg(it.distanceKm) },
        timed = delays.size, onTime = delays.count { it < 15 }, lateMinutes = delays.sumOf { maxOf(0L, it) },
    )
}

/** Airports [list] reaches that [before] (earlier flights) never did, in the order first visited. */
fun firstVisits(list: List<Flight>, before: List<Flight>): List<String> {
    val seen = before.flatMap { listOf(it.from, it.to) }.toSet()
    return list.sortedBy { it.depGuess }.flatMap { listOf(it.from, it.to) }.distinct().filter { it !in seen }
}
