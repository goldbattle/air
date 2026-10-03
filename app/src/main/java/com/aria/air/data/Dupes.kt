package com.aria.air.data

// Flights saved twice: finding, merging and ignoring them.

import java.time.Duration

/** How much a flight record knows, to pick which copy to keep when merging. */
fun Flight.completeness(): Int =
    listOf(depSched, arrSched, depActual, arrActual, updated).count { it != null } +
        listOf(seat, booking, notes, aircraft, depGate, arrGate).count { it.isNotEmpty() } + (if (mine) 3 else 0)

/** This flight with any blanks filled from [o]. Keeps this one's id and traveler. */
fun Flight.mergedWith(o: Flight) = copy(
    depSched = depSched ?: o.depSched, depEst = depEst ?: o.depEst, depActual = depActual ?: o.depActual,
    arrSched = arrSched ?: o.arrSched, arrEst = arrEst ?: o.arrEst, arrActual = arrActual ?: o.arrActual,
    depTerminal = depTerminal.ifEmpty { o.depTerminal }, depGate = depGate.ifEmpty { o.depGate },
    arrTerminal = arrTerminal.ifEmpty { o.arrTerminal }, arrGate = arrGate.ifEmpty { o.arrGate }, baggage = baggage.ifEmpty { o.baggage },
    aircraft = aircraft.ifEmpty { o.aircraft }, reg = reg.ifEmpty { o.reg },
    seat = seat.ifEmpty { o.seat }, booking = booking.ifEmpty { o.booking },
    notes = listOf(notes, o.notes).filter { it.isNotEmpty() }.distinct().joinToString("\n"),
    work = work || o.work, live = live ?: o.live, updated = updated ?: o.updated,
    depWx = depWx ?: o.depWx, arrWx = arrWx ?: o.arrWx, routeWx = routeWx ?: o.routeWx, track = track.ifEmpty { o.track },
)

/**
 * Flights saved twice (a re-import, a codeshare, a friend on the same plane): finding them, merging them, or marking
 * them as not duplicates for good (Prefs "dupIgnore").
 */
object Dupes {
    /** A pair's id, the same whichever order the two come in. */
    private fun key(a: Flight, b: Flight) = listOf(a.id, b.id).sorted().joinToString("+")
    private val ignored get() = Prefs["dupIgnore"].split(',').filter { it.isNotEmpty() }.toSet()

    /** Why two flights look like the same trip, or null if they don't. */
    fun reason(a: Flight, b: Flight): String? {
        if (a.date != b.date) return null
        if (a.code == b.code) return if (a.friend.equals(b.friend, true)) "Same flight" else "Same flight, different traveler"
        val close = a.depSched == null || b.depSched == null || Duration.between(a.depSched, b.depSched).abs() <= Duration.ofMinutes(30)
        return if (a.from == b.from && a.to == b.to && close) "Same route and time (codeshare?)" else null
    }

    /** Groups of flights that look like duplicates, minus pairs marked "not duplicates". */
    fun groups(list: List<Flight> = Store.flights): List<List<Flight>> {
        val skip = ignored
        val parent = HashMap<String, String>()
        fun find(x: String): String = parent[x]?.let { if (it == x) x else find(it).also { r -> parent[x] = r } } ?: x
        list.groupBy { it.date }.values.forEach { day ->
            for (i in day.indices) for (j in i + 1 until day.size) {
                val a = day[i]; val b = day[j]
                if (reason(a, b) != null && key(a, b) !in skip) parent[find(a.id)] = find(b.id).also { parent.putIfAbsent(it, it) }
            }
        }
        val byId = list.associateBy { it.id }
        return parent.keys.groupBy { find(it) }.values.map { ids -> ids.mapNotNull { byId[it] }.sortedByDescending { it.completeness() } }
            .filter { it.size > 1 }.sortedByDescending { it.first().date }
    }

    /** Keeps the most complete flight, fills its blanks from the others, deletes the others. */
    fun merge(group: List<Flight>) {
        val keep = group.maxBy { it.completeness() }
        Store.upsert(group.filter { it.id != keep.id }.fold(keep) { acc, o -> acc.mergedWith(o) })
        group.filter { it.id != keep.id }.forEach { Store.remove(it.id) }
    }

    /** Marks every pair in [group] as "not duplicates", so the group never shows again. */
    fun ignore(group: List<Flight>) {
        val pairs = group.flatMap { a -> group.filter { it.id != a.id }.map { b -> key(a, b) } }
        Prefs["dupIgnore"] = (ignored + pairs).joinToString(",")
    }
}
