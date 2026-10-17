package com.aria.air.data

// Flown paths: the breadcrumb track recorded while live (kept on the Flight, so it's in backups), and what the flight
// map draws.

import com.aria.air.sync.*
import com.aria.air.util.*
import java.time.Instant

const val TRACK_GAP = 120L // seconds between kept points
const val TRACK_MAX = 300

/** Past routes kept and drawn per flight number and route. */
const val MAX_PAST = 20

/** Days looked back the first time past routes are fetched; after that one more day each day. */
const val FIRST_DAYS = 7L

/** [p] appended if it's [TRACK_GAP] after the last point; past [TRACK_MAX] points every other one is dropped. */
fun List<Pt>.add(p: Pt): List<Pt> {
    if (lastOrNull()?.let { p.t - it.t < TRACK_GAP } == true) return this
    val out = this + p
    return if (out.size > TRACK_MAX) out.filterIndexed { i, _ -> i % 2 == 0 || i == out.lastIndex } else out
}

/** [pts] thinned the way recording thins: one point per [TRACK_GAP] seconds, at most [TRACK_MAX]. */
fun thin(pts: List<Pt>): List<Pt> = pts.fold(emptyList()) { acc, p -> acc.add(p) }

/** Adds an airborne fix to the recorded track. */
fun Flight.record(l: Live): Flight = copy(
    hex = l.hex.ifEmpty { hex }, track = if (l.onGround) track else track.add(Pt(l.lat, l.lon, l.altFt, l.at.epochSecond)),
)

/** Both copies' points, for saves racing each other (the worker and the open flight page). */
fun mergeTracks(a: List<Pt>, b: List<Pt>): List<Pt> = if (a == b || a.isEmpty()) b else if (b.isEmpty()) a else
    thin((a + b).distinctBy { it.t }.sortedBy { it.t })
// ---------- What the map draws ----------

/**
 * Faint past paths, the planned arc (or not), the flown path solid, [guess] (signal lost: from the last fix to where the
 * plane most likely is now, [plane]) dotted, and the rest of the way from the plane, dashed.
 */
data class MapPaths(val typical: List<List<Pt>>, val arc: Boolean, val flown: List<Pt>, val rest: List<Pt>, val guess: List<Pt> = emptyList())

/** Past routes are kept per flight number and route: "UA100 SFO-BOS". */
val Flight.routeKey get() = "$code $from-$to"

/** The path actually flown: from the origin through the track, to the plane or (landed) the destination. */
fun Flight.flownPath(landed: Boolean): List<Pt> {
    val o = origin ?: return emptyList(); val d = dest ?: return emptyList()
    if (track.isEmpty()) return emptyList()
    val tip = if (landed) Pt(d.lat, d.lon) else live?.takeIf { isAirborne }?.let { Pt(it.lat, it.lon) }
    return listOf(Pt(o.lat, o.lon)) + track + listOfNotNull(tip)
}

/**
 * Completed: the recorded path, or the arc without one. Upcoming or flying: up to [max] recent paths of this flight
 * number ([fetched] from adsb.lol, then my own recorded past flights of it, or of the route), the planned arc, and once
 * there's a track, the flown part solid with the rest of the arc ahead of the plane.
 */
fun Flight.paths(fetched: List<List<Pt>>, others: List<Flight>, max: Int, now: Instant = Instant.now(clock)): MapPaths {
    val landed = arrActual != null || phase(now) >= Phase.Arrival
    val flown = flownPath(landed)
    val mine = others.filter { it.id != id && it.track.isNotEmpty() && it.phase(now) == Phase.Done && (it.code == code || it.from == from && it.to == to) }
        .sortedWith(compareByDescending<Flight> { it.code == code }.thenByDescending { it.track.last().t }).map { it.flownPath(true) }
    val typical = (fetched + mine).take(max)
    if (landed) return MapPaths(typical, flown.isEmpty(), flown, emptyList())
    val o = origin; val d = dest
    fun arc(a: Pt, b: Pt, n: Int) = greatCircle(a.lat, a.lon, b.lat, b.lon, n).map { Pt(it.first, it.second) }
    val guess = plane(now)?.takeIf { it.estimated && o != null }?.let { arc(flown.lastOrNull() ?: Pt(o!!.lat, o.lon), Pt(it.lat, it.lon), 16) }.orEmpty()
    val rest = (guess.lastOrNull() ?: flown.lastOrNull())?.takeIf { d != null }?.let { arc(it, Pt(d!!.lat, d.lon), 32) }.orEmpty()
    return MapPaths(typical, flown.isEmpty() && guess.isEmpty(), flown, rest, guess)
}

/** [paths] for the map: past paths per the Layers toggles (one by default, up to 3 with "all"). */
fun Flight.mapPaths(): MapPaths = paths(
    PastRoutes.cache[routeKey]?.legs.orEmpty(), Store.flights,
    if (Layers.routes) MAX_PAST else 0,
)

/** Recent paths flown under a flight number, as fetched [at]; days back to [oldest] have been looked at. */
data class Typical(val at: Instant, val legs: List<List<Pt>>, val oldest: java.time.LocalDate? = null)
