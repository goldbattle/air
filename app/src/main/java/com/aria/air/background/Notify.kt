package com.aria.air.background

// Notifications: the live progress card, alerts for changes, and reminders.

import com.aria.air.*
import com.aria.air.data.*
import com.aria.air.sync.IndoorMaps
import com.aria.air.util.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Every notification about flights: the ongoing Live Update per flight, alerts when something changes, and reminders.
 * Each alert fires once, remembered in the flight's [Flight.alerted] list.
 */
object Notify {
    private const val LIVE = "live"
    private const val ALERTS = "alerts"

    /**
     * Each of my live flights has its own card, always there. Friends' share one card ("2 of 3"): they take turns on
     * it every [TURN], or on its Next flight button.
     */
    private const val FRIENDS_ID = 1
    val TURN: Duration = Duration.ofMinutes(10)

    /** Friends' flights wanting a live card now, in turn order, and the one whose turn it is. */
    fun friendsLive(now: Instant) = turnOrder(Store.flights.filter { !it.mine && wantsLive(it, now) }, now)
    private fun friendStar(now: Instant) = Turns.showing("live", friendsLive(now), now, TURN)

    /** Redraws the friends' card with whoever's turn it is (after Next flight). */
    fun liveNow(ctx: Context) {
        val star = friendStar(Instant.now(clock))
        if (star == null) ctx.getSystemService(NotificationManager::class.java).cancel(FRIENDS_ID) else live(ctx, star)
    }

    /** Creates the two channels, quiet live progress and high-priority alerts. Safe to call on every start. */
    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(LIVE, "Live flight progress", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Ongoing progress for flights at the gate or in the air"
            setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Flight alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Departures, landings, delays, gate changes and reminders"
        })
    }

    /** Tapping a flight's notification opens its page. */
    private fun open(ctx: Context, f: Flight) = PendingIntent.getActivity(
        ctx, f.id.hashCode(),
        Intent(ctx, MainActivity::class.java).putExtra("flight", f.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens [code]'s terminal map on [gate], over the flight. */
    private fun openMap(ctx: Context, f: Flight, code: String, gate: String) = PendingIntent.getActivity(
        ctx, (f.id + "map").hashCode(),
        Intent(ctx, MainActivity::class.java).putExtra("flight", f.id).putExtra("map", code).putExtra("gate", gate)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "Sam · " before a friend's flight, nothing before mine. */
    private fun who(f: Flight) = if (f.mine) "" else "${f.friend} · "

    /** Whether live progress is on for this kind of flight (mine or friends', Settings › Notifications). */
    private fun liveEnabled(f: Flight) = Prefs.flag(if (f.mine) "liveMine" else "liveFriends")

    /** Whether alerts are on for this kind of flight. */
    private fun alertsEnabled(f: Flight) = Prefs.flag(if (f.mine) "alertsMine" else "alertsFriends")

    /**
     * Whether a flight gets the ongoing notification now: from [Prefs.liveLeadHours] before departure (once the time is
     * known), mine and friends' alike (friends share one card), through the air and until an hour after landing.
     */
    fun wantsLive(f: Flight, now: Instant = Instant.now(clock)): Boolean {
        if (!liveEnabled(f) || f.cancelled) return false
        val phase = f.phase(now)
        if (phase == Phase.InFlight || phase == Phase.Arrival) return true
        if (phase == Phase.Done) return false
        val dep = f.dep ?: return false
        return Duration.between(now, dep) <= Duration.ofHours(Prefs.liveLeadHours.toLong())
    }

    /**
     * The ongoing Live Update, one per flight, changing with the trip:
     *  - hours before: countdown to departure, airport, boarding time, gate and seat
     *  - last 4 hours: plus a bar filling up toward boarding, i.e. time to head to the airport
     *  - in the air: progress bar with the plane, countdown to landing, speed and altitude
     *  - landed: arrival gate and baggage belt
     */
    fun live(ctx: Context, f: Flight) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val now = Instant.now(clock)
        val id = if (f.mine) f.id.hashCode() else FRIENDS_ID
        val star = if (f.mine) f else friendStar(now)
        if (!wantsLive(f, now) || star == null) {
            if (f.mine || star == null) nm.cancel(id)
            return
        }
        if (star.id != f.id) return // another friend's turn on the shared card
        val phase = f.phase(now)
        val flying = phase == Phase.InFlight
        val landed = phase == Phase.Arrival
        val dep = f.dep ?: f.depGuess
        val arr = f.arr ?: f.arrGuess
        val boarding = dep.minus(f.boardingLead)
        val l = f.live
        val text = when {
            landed -> buildString {
                append("Landed ${time(f.arrActual ?: arr, f.toTz)} in ${f.dest?.city ?: f.to}")
                if (f.arrGate.isNotEmpty()) append(" · Gate ${f.arrGate}")
                if (f.baggage.isNotEmpty()) append(" · Bags at ${f.baggage}")
            }
            flying && f.isAirborne && l != null ->
                "Lands ${time(arr, f.toTz)} · ${(l.speedKt * 1.15078).roundToInt()} mph · ${fmtInt(l.altFt)} ft"
            flying -> "Lands ${time(arr, f.toTz)} · ${fmtInt((f.remainingKm() / KM_PER_MI).roundToInt())} mi to go"
            else -> buildString {
                append("${f.from} ${time(dep, f.fromTz)} · Boarding ${time(boarding, f.fromTz)}")
                if (f.depGate.isNotEmpty()) append(" · Gate ${f.depGate}")
                if (f.seat.isNotEmpty()) append(" · Seat ${f.seat}")
                f.depDelay?.takeIf { it >= 15 }?.let { append(" · ${span(Duration.ofMinutes(it))} late") }
            }
        }
        val title = when {
            landed -> "${who(f)}${f.label} landed"
            flying -> "${who(f)}${f.label} · ${f.from} → ${f.to}"
            else -> "${who(f)}${f.label} to ${f.dest?.city ?: f.to} · departs in ${span(Duration.between(now, dep).let { if (it.isNegative) Duration.ZERO else it })}"
        }
        val target = if (flying || landed) arr else dep
        val chip = if (landed) "Landed" else span(Duration.between(now, target).let { if (it.isNegative) Duration.ZERO else it })
        // Before departure the bar covers the last 4 hours up to boarding; in the air, the flight itself.
        val lead = Duration.ofHours(4)
        val progress: Int? = when {
            landed -> 1000
            flying -> (f.progress(now) * 1000).roundToInt()
            Duration.between(now, boarding) <= lead ->
                (1000 - Duration.between(now, boarding).toMillis() * 1000 / lead.toMillis()).toInt().coerceIn(0, 1000)
            else -> null
        }
        val b = Notification.Builder(ctx, LIVE)
            .setSmallIcon(R.drawable.ic_plane)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open(ctx, f))
            .setOngoing(!landed)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setGroup(if (f.mine) "live-${f.id}" else "live-friends") // its own card, not bundled with alerts
            // Flight times aren't private: show the full card on the lock screen even when content is hidden.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        // At the airport (before takeoff, or after landing) the terminal map is a tap away, when it's on the phone.
        if (f.mine && !flying) {
            val (code, gate) = if (landed) f.to to f.arrGate else f.from to f.depGate
            if (IndoorMaps.saved(code) != null) b.addAction(
                Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_walk), "Terminal map", openMap(ctx, f, code, gate)).build(),
            )
        }
        if (!landed) b.setWhen(target.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        if (progress != null) {
            val color = if (flying || landed) 0xFF0A84FF.toInt() else 0xFFFF9F0A.toInt()
            val icon = Icon.createWithResource(ctx, if (flying) R.drawable.ic_plane else R.drawable.ic_walk)
            // Live Update APIs arrived in Android 16 builds after 36.0; older builds get a plain progress bar.
            try {
                b.setStyle(
                    Notification.ProgressStyle()
                        .setStyledByProgress(false)
                        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000).setColor(color)))
                        .setProgress(progress)
                        .setProgressTrackerIcon(icon),
                )
            } catch (_: LinkageError) {
                b.setProgress(1000, progress, false)
            }
        } else {
            b.setStyle(Notification.BigTextStyle().bigText(text))
        }
        try {
            // The status bar chip is mine whenever I have a live flight.
            b.setRequestPromotedOngoing(!landed && (f.mine || Store.flights.none { it.mine && wantsLive(it, now) })).setShortCriticalText(chip)
        } catch (_: LinkageError) {
        }
        // Several friends live: which one this is, and a button to the next.
        val friends = if (f.mine) emptyList() else friendsLive(now)
        if (friends.size > 1) {
            b.setSubText("${friends.indexOf(f) + 1} of ${friends.size} friends' flights")
            val next = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, NextLiveReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_plane), "Next flight", next).build())
        }
        nm.notify(id, b.build())
    }

    /** Removes live cards of flights that no longer want one (finished, deleted, or switched off). */
    fun cleanup(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val now = Instant.now(clock)
        val keep = Store.flights.filter { it.mine && wantsLive(it, now) }.map { it.id.hashCode() }.toSet() +
            (if (friendStar(now) != null) setOf(FRIENDS_ID) else emptySet())
        nm.activeNotifications.filter { it.notification.channelId == LIVE && it.id !in keep }.forEach { nm.cancel(it.id) }
    }

    /** Posts one alert for [f]. Each [key] gets its own notification, so a gate change doesn't replace a delay. */
    private fun alert(ctx: Context, f: Flight, key: String, title: String, text: String) {
        ctx.getSystemService(NotificationManager::class.java).notify(
            (f.id + key).hashCode(),
            Notification.Builder(ctx, ALERTS).setSmallIcon(R.drawable.ic_plane)
                .setContentTitle(title).setContentText(text).setContentIntent(open(ctx, f)).setAutoCancel(true).build(),
        )
    }

    /** Notifies what changed between [old] and [new], each event once. Returns the updated "already alerted" list. */
    fun events(ctx: Context, old: Flight, new: Flight): String =
        events(old, new) { key, title, text -> if (alertsEnabled(new)) alert(ctx, new, key, title, text) }

    /** [events] without Android: [send] gets each new alert's key, title and text. */
    fun events(old: Flight, new: Flight, send: (key: String, title: String, text: String) -> Unit): String {
        val done = old.alerted.split(',').filter { it.isNotEmpty() }.toMutableSet()
        fun once(key: String, title: String, text: String) {
            if (key in done) return
            done += key
            send(key, title, text)
        }
        val name = "${who(new)}${new.label}"
        if (new.cancelled) once("cancelled", "$name cancelled", "${new.from} → ${new.to} on ${dayLabel(new.date)}")
        // The plane we're waiting for is running late: alert once per 10-minute step of its arrival estimate.
        if (new.inboundTone() == Tone.Bad && new.tookOff == null) {
            val i = new.inbound!!
            once(
                "inbound${(i.eta?.epochSecond ?: 0) / 600}", "$name: your plane is running late",
                "${new.inboundLine()}. A departure delay is likely.",
            )
        }
        // The airline retimed the flight (often weeks ahead). Keyed by the new time, so each change alerts once.
        val was = old.depSched
        val now = new.depSched
        if (was != null && now != null && new.tookOff == null && Duration.between(was, now).abs() >= Duration.ofMinutes(5)) {
            once(
                "sched${now.epochSecond}", "$name schedule change",
                "Now departs ${dayLabel(now.atZone(new.fromTz).toLocalDate())} at ${time(now, new.fromTz)} (was ${time(was, new.fromTz)})",
            )
        }
        if (new.diverted) once("diverted", "$name diverted", "Was flying ${new.from} → ${new.to}")
        if (new.tookOff != null || new.isAirborne) {
            once("departed", "$name took off", "${new.from} → ${new.to}, lands ${time(new.arr ?: new.arrGuess, new.toTz)} local")
        }
        if (new.arrActual != null) {
            once("landed", "$name landed in ${new.dest?.city ?: new.to}",
                "Arrived ${time(new.arrActual, new.toTz)}" + (if (new.arrGate.isNotEmpty()) " · Gate ${new.arrGate}" else "") +
                    (if (new.baggage.isNotEmpty()) " · Bags at ${new.baggage}" else ""))
        }
        // Delay and gate alerts re-fire when the value changes, keyed by the value.
        val delay = new.depDelay
        if (delay != null && delay >= 15 && new.tookOff == null && delay != old.depDelay) {
            once("delay$delay", "$name delayed ${span(Duration.ofMinutes(delay))}", "Now departs ${time(new.dep, new.fromTz)} from ${new.from}")
        }
        if (new.depGate.isNotEmpty() && old.depGate.isNotEmpty() && new.depGate != old.depGate) {
            once("gate${new.depGate}", "$name gate change", "Now departing from gate ${new.depGate} (was ${old.depGate})")
        }
        return done.joinToString(",")
    }

    /** For my own flights, once each: "check in now" 24 h before, and "leaves in 3 hours" (Settings). */
    fun reminders(ctx: Context) {
        if (!Prefs.flag("alertsMine")) return
        val now = Instant.now(clock)
        val h = Prefs.reminderHours
        for (f in Store.flights.filter { it.mine && it.upcoming && !it.cancelled }) {
            val due = dueReminders(f, now, h, Prefs.flag("checkinReminder"))
            due.forEach { (key, title, text) -> alert(ctx, f, key, title, text) }
            if (due.isNotEmpty()) Store.upsert(f.copy(alerted = (f.alerted.split(',').filter { it.isNotEmpty() } + due.map { it.first }).joinToString(",")))
        }
    }

    /** The reminders [f] is due and hasn't had yet, as (key, title, text): check-in 24 h out, and [hours] before. */
    fun dueReminders(f: Flight, now: Instant, hours: Int, checkin: Boolean): List<Triple<String, String, String>> {
        val dep = f.dep ?: return emptyList()
        if (dep <= now) return emptyList()
        val left = Duration.between(now, dep)
        val sent = f.alerted.split(',').filter { it.isNotEmpty() }.toSet()
        return listOfNotNull(
            if (checkin && left <= Duration.ofHours(24) && "checkin" !in sent) Triple(
                "checkin", "Check in for ${f.label} to ${f.dest?.city ?: f.to}",
                "Check-in is open. Departs ${dayLabel(f.date)} at ${time(dep, f.fromTz)} from ${f.from}" +
                    (if (f.booking.isNotEmpty()) " · Conf. ${f.booking}" else ""),
            ) else null,
            if (hours > 0 && left <= Duration.ofHours(hours.toLong()) && "reminder" !in sent) Triple(
                "reminder", "${f.label} to ${f.dest?.city ?: f.to} leaves in ${span(left)}",
                "Boarding ${time(dep.minus(f.boardingLead), f.fromTz)} at ${f.from}" + (if (f.depGate.isNotEmpty()) ", gate ${f.depGate}" else ""),
            ) else null,
        )
    }
}
