package com.aria.air.importer

// From what a document says to saved flights: flight numbers, travelers, matches and merges.

import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.sync.*
import android.content.Context
import java.time.Duration

/** "UA1234", "ua 1234" or the ICAO form "UAL1234" -> ("UA", "1234"). */
fun parseFlightNumber(s: String): Pair<String, String>? {
    val t = s.uppercase().replace(" ", "")
    Regex("^([A-Z]{3})(\\d{1,4})$").find(t)?.let { m -> Db.airlinesByIcao[m.groupValues[1]]?.let { return it.iata to m.groupValues[2].trimStart('0') } }
    val m = Regex("^([A-Z][A-Z0-9]|[0-9][A-Z])(\\d{1,4})$").find(t) ?: return null
    return m.groupValues[1] to m.groupValues[2].trimStart('0')
}

/**
 * "John Q Doe", "Johnq Doe" (airlines glue the middle initial on) and "John Doe" are the same
 * person; "Jane Doe" isn't.
 */
fun sameName(a: String, b: String): Boolean {
    val x = a.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
    val y = b.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
    if (x.isEmpty() || y.isEmpty()) return false
    val (p, q) = x.first() to y.first()
    val first = p == q || (kotlin.math.abs(p.length - q.length) == 1 && (p.startsWith(q) || q.startsWith(p)))
    return first && (x.size == 1 || y.size == 1 || x.last() == y.last())
}

/** People to pick as the traveler: you (""), friends in saved flights, and the booking's name if it's someone new. */
fun travelerChoices(me: String, friends: List<String>, booking: String?): List<String> =
    listOf("") + friends + listOfNotNull(booking?.takeIf { b -> !(me.isNotBlank() && sameName(b, me)) && friends.none { sameName(it, b) } })

/**
 * Who an import is for until you say otherwise: you if the booking has your name, the friend it matches, or the name
 * on it. No name: you, or nobody yet (null) when adding from Friends.
 */
fun defaultTraveler(me: String, friends: List<String>, booking: String?, friendTab: Boolean): String? = when {
    booking == null -> if (friendTab) null else ""
    me.isNotBlank() && sameName(booking, me) -> ""
    else -> friends.firstOrNull { sameName(it, booking) } ?: booking
}

/** A saved flight this imported one probably is: same number and date, or same route and date. */
fun existingFor(f: Found): Flight? {
    val d = f.date ?: return null
    return Store.flights.firstOrNull { it.date == d && it.airline == f.airline && it.number == f.number }
        ?: Store.flights.firstOrNull { it.date == d && f.from != null && it.from == f.from && it.to == f.to }
}

/**
 * Saves a flight, fills in what the APIs know, and starts tracking. Returns its id. If it's already saved, the saved
 * one gets this one's missing details instead, unless [asCopy], which saves it separately (it then shows up in
 * Settings › Duplicates).
 */
suspend fun addFlight(ctx: Context, f: Flight, asCopy: Boolean = false): String {
    if (asCopy) {
        Store.upsert(f)
        runCatching { FlightSync.refresh(f) }.getOrNull()?.let { n -> if (Store.get(f.id) != null) Store.upsert(n) }
        Tracker.schedule(ctx)
        return f.id
    }
    // Already there (say from a byAir import): fill in what this document adds instead of duplicating it.
    Store.findSame(f)?.let { old ->
        Store.upsert(old.fillFrom(f))
        return old.id
    }
    Store.upsert(f)
    // One lookup per added flight, past ones included, to backfill actual times, gates and aircraft.
    val n = runCatching { FlightSync.refresh(f) }.getOrDefault(f)
    if (Store.get(f.id) != null) Store.upsert(n)
    Tracker.schedule(ctx)
    return f.id
}

/** [this] with the details it's missing taken from [f], the same flight read from another document. */
fun Flight.fillFrom(f: Flight) = copy(
    seat = seat.ifEmpty { f.seat }, booking = booking.ifEmpty { f.booking },
    depSched = depSched ?: f.depSched, arrSched = arrSched ?: f.arrSched, work = work || f.work,
)

/** The details merging [f] would add to [old]: "seat", "confirmation", "times". */
fun mergeAdds(old: Flight, f: Found): List<String> = listOfNotNull(
    "seat".takeIf { old.seat.isEmpty() && f.seat.isNotEmpty() },
    "confirmation".takeIf { old.booking.isEmpty() && f.booking.isNotEmpty() },
    "times".takeIf { old.depSched == null && f.dep != null || old.arrSched == null && f.arr != null },
)

/**
 * This document's flight as a [Flight] for [friend] ("" = me), its times placed in each airport's time zone (an arrival
 * before departure is the next day). Missing airports come from [route]. Null when it still has no date or airports,
 * since then it can't be saved.
 */
fun Found.toFlight(route: List<String>, friend: String, work: Boolean): Flight? {
    val d = date ?: return null
    val from = from ?: route.firstOrNull() ?: return null
    val to = to ?: route.lastOrNull()?.takeIf { it != from } ?: return null
    val fromTz = Db.airport(from)?.tz ?: return null
    val toTz = Db.airport(to)?.tz ?: return null
    val depAt = dep?.let { d.atTime(it).atZone(fromTz).toInstant() }
    var arrAt = arr?.let { d.atTime(it).atZone(toTz).toInstant() }
    if (depAt != null && arrAt != null) while (arrAt!! < depAt) arrAt = arrAt.plus(Duration.ofDays(1))
    return Flight(
        airline = airline, number = number, date = d, from = from, to = to, depSched = depAt, arrSched = arrAt,
        seat = seat, booking = booking, friend = friend, source = "import", work = work && friend.isEmpty(),
    )
}
