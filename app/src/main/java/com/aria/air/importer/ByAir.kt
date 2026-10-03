package com.aria.air.importer

// Import of a byAir CSV export.

import com.aria.air.data.*
import com.aria.air.util.*
import java.time.LocalDate
import java.time.LocalTime

/** Splits one CSV line, honoring quoted fields ("a, b" and "" escapes). */
fun csvLine(line: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
            else -> cur.append(c)
        }
        i++
    }
    out += cur.toString()
    return out
}

/** Whether [text] is a byAir CSV export, by its header row. */
fun isByAirCsv(text: String) = text.trimStart('﻿').startsWith("Flight Date,Flight Code")

/**
 * Flights from a byAir export. Times there are local to each airport; arrivals earlier than departure are next day.
 * byAir doesn't export friends' names, so their flights come in as [friendName]. Rows with unknown airports are skipped.
 */
fun byAirFlights(text: String, friendName: String = "Friend"): List<Flight> {
    val lines = text.trimStart('﻿').lines().filter { it.isNotBlank() }
    val head = csvLine(lines.first())
    fun col(r: List<String>, name: String) = head.indexOf(name).let { if (it >= 0) r.getOrNull(it).orEmpty().trim() else "" }
    return lines.drop(1).mapNotNull { line ->
        val r = csvLine(line)
        val (airline, number) = parseFlightNumber(col(r, "Flight Code")) ?: return@mapNotNull null
        val date = runCatching { LocalDate.parse(col(r, "Flight Date")) }.getOrNull() ?: return@mapNotNull null
        val from = Db.airport(col(r, "Departure Airport Code")) ?: return@mapNotNull null
        val to = Db.airport(col(r, "Arrival Airport Code")) ?: return@mapNotNull null
        val dep = runCatching { date.atTime(LocalTime.parse(col(r, "Departure Time"))).atZone(from.tz).toInstant() }.getOrNull()
        var arr = runCatching { date.atTime(LocalTime.parse(col(r, "Arrival Time"))).atZone(to.tz).toInstant() }.getOrNull()
        if (dep != null && arr != null) while (arr!! < dep) arr = arr.plus(java.time.Duration.ofDays(1))
        Flight(
            airline = airline, number = number, date = date, from = from.iata, to = to.iata, depSched = dep, arrSched = arr,
            booking = col(r, "Booking Code"), seat = col(r, "Seat Number"), notes = col(r, "Notes"),
            friend = if (col(r, "Ownership") == "friend") friendName else "", source = "byAir",
            work = col(r, "Purpose").contains(Regex("(?i)business|work")),
        )
    }
}
