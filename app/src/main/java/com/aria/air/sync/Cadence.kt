package com.aria.air.sync

// How often each source is asked about a flight: the quota-saving schedule.

import com.aria.air.data.*
import java.time.Duration
import java.time.Instant

/** How often each source may be asked about a flight. Paid lookups are sparse far out and frequent near the gate. */
object Cadence {
    /**
     * How often the keyed status service (AeroDataBox / FlightAware, which cost quota) may be asked about a flight,
     * or null for never automatically. Sparse far out, where only cancellations and retimings can show up, and
     * frequent only around the gate and landing. Live position and weather come from free services and aren't
     * limited by this.
     */
    fun statusEvery(f: Flight, now: Instant): Duration? {
        if (f.arrActual != null || f.cancelled) return null // settled
        val toDep = Duration.between(now, f.dep ?: f.depGuess)
        val toArr = Duration.between(now, f.arr ?: f.arrGuess)
        return when {
            toArr < Duration.ofHours(-2) -> null                                   // long over: nothing more to learn
            toDep.isNegative -> if (toArr < Duration.ofMinutes(45)) Duration.ofMinutes(20) else Duration.ofMinutes(45)
            toDep < Duration.ofHours(4) -> Duration.ofMinutes(20)                  // gate, delays, boarding
            toDep < Duration.ofHours(24) -> Duration.ofHours(2)
            toDep < Duration.ofDays(3) -> Duration.ofHours(12)
            toDep < Duration.ofDays(14) -> Duration.ofDays(1)
            else -> Duration.ofDays(7)                                             // just watching for cancellations
        }
    }

    /**
     * How often to ask adsb.lol where the plane is (free, but still battery and data), or null when there's nothing
     * to see. At the gate it's only to catch takeoff; near landing more often; every minute only while you're
     * looking at the flight.
     */
    fun liveEvery(f: Flight, now: Instant, foreground: Boolean): Duration? {
        val phase = f.phase(now)
        if (!(phase in Phase.Departure..Phase.Arrival || f.maybeLive) || f.arrActual != null) return null
        if (foreground) return Duration.ofMinutes(1)
        val toArr = Duration.between(now, f.arr ?: f.arrGuess)
        return when {
            phase == Phase.InFlight && toArr < Duration.ofMinutes(45) -> Duration.ofMinutes(3)
            else -> Duration.ofMinutes(10)
        }
    }

    /** Forecasts sharpen as the day nears: daily when days away, every 6 h in the last 3 days, every 3 h on the day. */
    fun weatherEvery(now: Instant, at: Instant): Duration = Duration.between(now, at).let {
        when {
            it < Duration.ofHours(12) -> Duration.ofHours(3)
            it < Duration.ofDays(3) -> Duration.ofHours(6)
            else -> Duration.ofHours(24)
        }
    }
}
