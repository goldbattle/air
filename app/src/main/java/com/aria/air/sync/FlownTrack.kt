package com.aria.air.sync

// This flight's own full path, read from its plane's trace after the fact.

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.util.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Completes a flight's flown path from its plane's adsb.lol trace: Air only records points while it's watching, so a
 * flight first seen mid-air, or one the phone slept through, gets the rest from the trace after landing.
 */
object FlownTrack {
    private val trackTries = ConcurrentHashMap<String, Pair<Instant, Int>>()

    private fun landedAt(f: Flight, now: Instant): Instant? = f.arrActual ?: (f.arr ?: f.arrGuess).takeIf { f.phase(now) >= Phase.Arrival }

    /**
     * Whether to read the flight's path from its plane's trace: on the refresh button ([auto] false) while flying or
     * after landing (within adsb.lol's ~30 days of history), until a complete one was read. Automatically: once it's
     * seen in the air without its start (Air began watching mid-flight), and 15 min to a day after landing; at most 3
     * tries, 20 min apart.
     */
    fun due(f: Flight, now: Instant, auto: Boolean): Boolean {
        if (f.trackAt != null || f.cancelled) return false
        val o = f.origin ?: return false
        val landed = landedAt(f, now)
        if (landed == null && f.phase(now) != Phase.InFlight) return false
        if (landed != null && Duration.between(landed, now) > Duration.ofDays(30)) return false
        if (!auto) return true
        if (landed == null && (f.track.isEmpty() || distKm(f.track[0].lat, f.track[0].lon, o.lat, o.lon) < 100)) return false
        if (landed != null && Duration.between(landed, now).let { it < Duration.ofMinutes(15) || it > Duration.ofDays(1) }) return false
        val (at, n) = trackTries[f.id] ?: return true
        return n < 3 && Duration.between(at, now) >= Duration.ofMinutes(20)
    }

    /** [f] with its path filled from the plane's adsb.lol trace when [trackDue]; [f] unchanged when that fails. */
    suspend fun fill(f: Flight, auto: Boolean): Flight {
        val now = Instant.now(clock)
        if (!due(f, now, auto)) return f
        trackTries[f.id] = now to ((trackTries[f.id]?.second ?: 0) + 1)
        return runCatching { withContext(Dispatchers.IO) { readTrack(f, now) } }.getOrNull() ?: f
    }

    /**
     * The plane's airborne points between takeoff and landing (or now), on the route: today's trace_full while it's
     * recent, else the daily history files (UTC days). Complete (near both airports) marks the path done.
     */
    private fun readTrack(f: Flight, now: Instant): Flight? {
        val o = f.origin ?: return null; val d = f.dest ?: return null
        val hex = f.hex.ifEmpty { AdsbLol.lookupHex(f) }.lowercase().takeIf { it.length == 6 } ?: return null
        val from = (f.tookOff ?: f.dep ?: f.depGuess).minus(Duration.ofMinutes(20))
        val landed = landedAt(f, now)
        val to = (landed ?: now).plus(Duration.ofMinutes(20))
        // The last day's trace while it's recent, else the daily history file of every UTC day the flight touched.
        val files: List<() -> String> = if (Duration.between(from, now) < Duration.ofHours(20)) listOf { AdsbLol.trace(hex) }
            else generateSequence(from.atZone(ZoneOffset.UTC).toLocalDate()) { it.plusDays(1) }
                .takeWhile { !it.isAfter(to.atZone(ZoneOffset.UTC).toLocalDate()) }
                .map { day -> { AdsbLol.history(day, hex) } }.toList()
        val total = distKm(o, d)
        val pts = files.flatMap { runCatching { AdsbLol.points(it()) }.getOrDefault(emptyList()) }
            .filter { it.t in from.epochSecond..to.epochSecond && distKm(o.lat, o.lon, it.lat, it.lon) + distKm(it.lat, it.lon, d.lat, d.lon) < total * 1.25 + 150 }
            .sortedBy { it.t }
        if (pts.size < 2) return null
        val done = landed != null && distKm(pts.first().lat, pts.first().lon, o.lat, o.lon) < 150 &&
            distKm(pts.last().lat, pts.last().lon, d.lat, d.lon) < 150
        return f.copy(hex = hex, track = mergeTracks(f.track, thin(pts)), trackAt = if (done) now else null)
    }
}
