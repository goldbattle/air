package com.aria.air.sync

// Refreshing a flight: which sources to ask, when, and how their answers are combined.

import com.aria.air.api.*
import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.debug.*
import com.aria.air.util.*
import java.time.Duration
import java.time.Instant

/**
 * The orchestration behind every refresh: decides which services to ask (AeroDataBox, FlightAware, adsb.lol, Open-Meteo)
 * and how often ([Cadence]), merges their answers, follows the inbound plane, and fills the flown path. The services
 * themselves (com.aria.air.api) are plain clients.
 */
object FlightSync {
    private val lastLiveTry = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val lastInboundTry = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    /** Follow the inbound plane only in the last 12 h before departure, once its tail number is known. */
    private fun inboundDue(f: Flight, now: Instant, auto: Boolean, foreground: Boolean): Boolean {
        if (f.reg.isEmpty() || f.tookOff != null || f.cancelled || f.isAirborne) return false
        val toDep = Duration.between(now, f.dep ?: f.depGuess)
        if (toDep.isNegative || toDep > Duration.ofHours(12)) return false
        if (!auto) return true
        val every = if (foreground) Duration.ofMinutes(2) else Duration.ofMinutes(10)
        return lastInboundTry[f.id]?.let { Duration.between(it, now) >= every } != false
    }

    /** Whether either status key is set. */
    private fun key0(a: String, b: String) = a.isNotBlank() || b.isNotBlank()

    private val lastAeroTry = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    /**
     * With both keys, AeroDataBox does the routine lookups and FlightAware is a second opinion, asked only when it adds
     * something: the refresh button within two days of the flight, or in the last 12 h while AeroDataBox is missing the
     * tail number or departure gate (at most every 30 min, and only until both are known).
     */
    fun secondOpinion(f: Flight, now: Instant, auto: Boolean): Boolean {
        val toDep = Duration.between(now, f.dep ?: f.depGuess)
        if (!auto) return toDep.abs() < Duration.ofDays(2)
        val since = lastAeroTry[f.id]?.let { Duration.between(it, now) }
        // A gate change reported by AeroDataBox: confirm it within minutes, up to landing.
        if (f.disputed && now < (f.arr ?: f.arrGuess)) return since == null || since >= Duration.ofMinutes(5)
        if (toDep.isNegative || toDep > Duration.ofHours(12) || f.tookOff != null) return false
        // Once the tail number is known, adsb.lol follows the inbound plane for free.
        val missing = f.reg.isEmpty() || f.depGate.isEmpty()
        return missing && (since == null || since >= Duration.ofMinutes(30))
    }

    /**
     * AeroDataBox's view [a] with FlightAware's [b] on top where it knows more: gates (fresher at US airports), the tail
     * number and the inbound plane. Times stay AeroDataBox's. Disagreements go in the debug log.
     */
    fun merge(a: Flight, b: Flight): Flight {
        listOf(
            "departure gate" to (a.depGate to b.depGate), "arrival gate" to (a.arrGate to b.arrGate), "tail" to (a.reg to b.reg),
        ).filter { (_, v) -> v.first.isNotEmpty() && v.second.isNotEmpty() && v.first != v.second }
            .forEach { (k, v) -> Debug.note("${a.label} $k: AeroDataBox ${v.first}, FlightAware ${v.second}") }
        return a.copy(
            faAt = Instant.now(clock), disputed = false,
            depGate = b.depGate.ifEmpty { a.depGate }, depTerminal = b.depTerminal.ifEmpty { a.depTerminal },
            arrGate = b.arrGate.ifEmpty { a.arrGate }, arrTerminal = b.arrTerminal.ifEmpty { a.arrTerminal },
            baggage = b.baggage.ifEmpty { a.baggage }, reg = b.reg.ifEmpty { a.reg }, inbound = b.inbound ?: a.inbound,
        )
    }

    /**
     * Refreshes everything we can about a flight and returns the new copy (the caller saves it). In order: the status
     * service (AeroDataBox, with FlightAware as a second opinion), the live position from adsb.lol (which also infers
     * takeoff and landing when there's no key), the inbound plane, the flown path, then weather. [auto] = a background
     * poll, which asks each source only when [Cadence] says it's due; the refresh button asks the status service anyway.
     * [foreground] = the flight's page is open, so the position is worth asking for every minute.
     */
    suspend fun refresh(f: Flight, auto: Boolean = false, foreground: Boolean = false): Flight {
        // A takeoff time that can't be ours (saved from another leg or day on the same number) is dropped first.
        var n = if (f.depActual != null && f.tookOff == null) f.copy(depActual = null) else f
        val now = Instant.now(clock)
        val adbKey = Prefs.adbKey
        val aeroKey = Prefs.aeroKey
        // Automatic lookups follow statusEvery(); only the refresh button (auto = false) goes around it.
        val every = Cadence.statusEvery(f, now)
        val due = every != null && (f.updated == null || Duration.between(f.updated, now) > every)
        val today = localToday()
        Debug.note("refresh ${f.label} ${f.date}: auto=$auto status due=$due (every $every, last ${f.updated})")
        if (!auto || due) {
            fun failed(e: Throwable) = Debug.note("status lookup for ${f.label} failed: $e")
            val status = when {
                adbKey.isNotBlank() && f.date in today.minusDays(360)..today.plusDays(360) -> runCatching { AeroDataBox.status(n, adbKey) }.onFailure(::failed).getOrNull()
                aeroKey.isNotBlank() && f.date in today.minusDays(9)..today.plusDays(2) -> runCatching { FlightAware.status(n, aeroKey) }.onFailure(::failed).getOrNull()
                else -> null
            }
            if (status == null && key0(adbKey, aeroKey)) Debug.note("status lookup for ${f.label}: no matching flight that day")
            val filled = if (status != null && adbKey.isNotBlank() && aeroKey.isNotBlank() && secondOpinion(status, now, auto)) {
                lastAeroTry[f.id] = now
                runCatching { FlightAware.status(status, aeroKey) }.onFailure(::failed).getOrNull()?.let { fa -> Lookups.count(); merge(status, fa) } ?: status
            } else status
            if (adbKey.isNotBlank() || aeroKey.isNotBlank()) Lookups.count()
            filled?.let { n = it.copy(updated = now) }
        }
        // Without a status provider, takeoff and landing are inferred from ADS-B below.
        val key = adbKey + aeroKey
        val liveEvery = Cadence.liveEvery(n, now, foreground)
        val liveDue = liveEvery != null && (!auto || lastLiveTry[n.id]?.let { Duration.between(it, now) >= liveEvery } != false)
        if (liveDue && n.arrActual == null) {
            lastLiveTry[n.id] = now
            val l = AdsbLol.live(n)
            if (l != null) {
                n = n.copy(live = l, aircraft = n.aircraft.ifEmpty { l.aircraft }, reg = n.reg.ifEmpty { l.reg }).record(l)
                // Without a status API, estimate takeoff and landing from where the plane is and how fast it's going.
                // ponytail: straight line at current ground speed, so a few minutes off with wind and approach paths.
                val o = n.origin; val d = n.dest
                val kmh = l.speedKt * 1.852
                if (!l.onGround && key.isBlank() && o != null && d != null && kmh > 200) {
                    val flown = distKm(o.lat, o.lon, l.lat, l.lon)
                    val left = distKm(l.lat, l.lon, d.lat, d.lon)
                    if (n.depActual == null) n = n.copy(depActual = l.at.minusSeconds((flown / kmh * 3600).toLong() + 600))
                    n = n.copy(arrEst = l.at.plusSeconds((left / kmh * 3600).toLong() + 900))
                }
            } else if (key.isBlank() && n.live != null && !n.live!!.onGround && n.arrActual == null) {
                // Stopped broadcasting: landed if the last fix was low and near the destination.
                val d = n.dest
                val nearDest = d != null && distKm(n.live!!.lat, n.live!!.lon, d.lat, d.lon) < 60 && n.live!!.altFt < 6000
                if (nearDest) n = n.copy(arrActual = n.live!!.at)
            }
            if (l != null && l.onGround && key.isBlank() && n.depActual != null && n.dest?.let { distKm(l.lat, l.lon, it.lat, it.lon) < 10 } == true) {
                n = n.copy(arrActual = n.arrActual ?: l.at)
            }
        }
        if (inboundDue(n, now, auto, foreground)) {
            lastInboundTry[n.id] = now
            val prev = n.inbound
            val fresh = runCatching { AdsbLol.inbound(n) }.getOrNull()?.let { i ->
                // Remember when it touched down here: airborne last time, on the ground now.
                val landed = prev?.landedAt?.takeIf { i.onGround } ?: if (prev != null && !prev.onGround && i.onGround) i.at else null
                i.copy(landedAt = landed, firstSeen = prev?.firstSeen ?: i.at)
            }
            // Not broadcasting: maybe already parked here. Its trace says which flight brought it and when it landed.
            val traced = if (fresh == null && prev?.landedAt == null) runCatching { AdsbLol.inboundFromTrace(n) }.getOrNull() else null
            val o = n.origin
            val quietAfterLanding = fresh == null && prev != null && !prev.onGround && prev.to == n.from && o != null &&
                prev.eta != null && now > prev.eta.minus(Duration.ofMinutes(10))
            n = n.copy(
                inbound = when {
                    fresh != null -> fresh
                    traced != null -> traced.copy(firstSeen = prev?.firstSeen ?: traced.at)
                    // Went quiet around its expected landing here: it landed and parked (transponders go off at the gate).
                    quietAfterLanding -> prev!!.copy(
                        onGround = true, lat = o!!.lat, lon = o.lon, altFt = 0, speedKt = 0, eta = null, landedAt = prev.eta,
                    )
                    else -> prev
                },
            )
        }
        n = FlownTrack.fill(n, auto)
        return runCatching { WeatherSync.refresh(n) }.getOrDefault(n)
    }
}

/**
 * Refreshes one flight and saves it, with the same alerts as the background check (so a cancellation or retiming
 * seen here first still notifies once). [force] = pull-to-refresh or the refresh button: ask even if asked recently.
 */
suspend fun refreshAndSave(ctx: android.content.Context, f: Flight, force: Boolean, foreground: Boolean = true) {
    val fetched = runCatching { FlightSync.refresh(f, auto = !force, foreground = foreground) }.getOrDefault(f)
    val n = fetched.copy(alerted = Notify.events(ctx, f, fetched))
    Store.get(f.id)?.let { cur -> Store.upsert(n.copy(seat = cur.seat, booking = cur.booking, notes = cur.notes, friend = cur.friend, work = cur.work)) }
    Store.get(f.id)?.let { Notify.live(ctx, it) }
}
