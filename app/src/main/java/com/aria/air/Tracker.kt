package com.aria.air

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Updates flights that are close to happening, then reschedules itself at a pace set by [nextCheckMinutes].
 * Works offline: the notification and widget are redrawn from the schedule (or the best estimate) every run, and the
 * network is only used when there is a connection. Stops when nothing is upcoming.
 */
// ponytail: WorkManager is deferred in deep Doze, so updates can lag while the phone sits idle. The notification's
// countdown ticks on its own regardless. Upgrade path: a foreground service while airborne.
class TrackWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val now = Instant.now()
        val due = withContext(Dispatchers.Main) { Store.flights.toList() }.filter {
            // All my upcoming flights (far-out ones only hit the network once a day, see Api.refresh); friends' near theirs.
            (it.upcoming || it.maybeLive) && (it.mine || it.depGuess < now.plus(Duration.ofHours(6)))
        }
        val online = isOnline(applicationContext)
        for (f in due) {
            // Offline (say, in the air without Wi-Fi): skip the network and just redraw from times and estimates.
            val n = if (online) runCatching { Api.refresh(f, auto = true) }.getOrDefault(f) else f
            val alerted = Notify.events(applicationContext, f, n)
            val saved = n.copy(alerted = alerted)
            withContext(Dispatchers.Main) {
                // Keep anything the user edited while we were fetching.
                Store.get(f.id)?.let { cur ->
                    Store.upsert(saved.copy(seat = cur.seat, booking = cur.booking, notes = cur.notes, friend = cur.friend, work = cur.work))
                }
            }
            Notify.live(applicationContext, saved)
        }
        withContext(Dispatchers.Main) {
            Notify.reminders(applicationContext)
            schedule(applicationContext, fromWorker = true)
        }
        FlightWidget.refresh(applicationContext) // countdowns move even when nothing was saved
        return Result.success()
    }

    companion object {
        /** Call after flights change, and from the worker itself. */
        fun schedule(ctx: Context, fromWorker: Boolean = false) {
            val wm = WorkManager.getInstance(ctx)
            val upcoming = Store.flights.filter { it.upcoming || it.maybeLive }
            // Clear notifications of flights that finished or were deleted.
            Notify.cleanup(ctx)
            if (upcoming.isEmpty()) {
                wm.cancelUniqueWork("track"); return
            }
            val delay = if (!fromWorker) 0L else nextCheckMinutes(upcoming, Instant.now())
            // No network constraint: offline runs still move the progress bar and widget along.
            val req = OneTimeWorkRequestBuilder<TrackWorker>()
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .build()
            // From inside the worker, APPEND so we don't cancel ourselves; from the app, REPLACE to run now.
            wm.enqueueUniqueWork("track", if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * How soon to check again, by the most urgent flight: often around the gate (delays, gate changes, boarding)
         * and around landing (gate, bags), rarely while cruising (progress just follows the clock), and every half
         * hour otherwise.
         */
        fun nextCheckMinutes(flights: List<Flight>, now: Instant): Long = flights.minOfOrNull { f ->
            val toDep = Duration.between(now, f.dep ?: f.depGuess)
            val toArr = Duration.between(now, f.arr ?: f.arrGuess)
            when (f.phase(now)) {
                Phase.InFlight -> if (toArr <= Duration.ofMinutes(45)) 5L else 15L
                Phase.Arrival -> 5L
                else -> when {
                    f.maybeLive -> 5L
                    toDep <= Duration.ofHours(1) -> 2L
                    toDep <= Duration.ofHours(4) -> 5L
                    Notify.wantsLive(f, now) -> 10L
                    else -> 30L
                }
            }
        } ?: 30L

        fun isOnline(ctx: Context): Boolean {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }
}

object Notify {
    private const val LIVE = "live"
    private const val ALERTS = "alerts"

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

    private fun open(ctx: Context, f: Flight) = PendingIntent.getActivity(
        ctx, f.id.hashCode(),
        Intent(ctx, MainActivity::class.java).putExtra("flight", f.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun who(f: Flight) = if (f.mine) "" else "${f.friend} · "

    private fun liveEnabled(f: Flight) = Prefs.flag(if (f.mine) "liveMine" else "liveFriends")
    private fun alertsEnabled(f: Flight) = Prefs.flag(if (f.mine) "alertsMine" else "alertsFriends")

    /**
     * Whether a flight gets the ongoing notification now: my flights from [Prefs.liveLeadHours] before departure (once
     * the time is known), friends' from 3 hours before, both through the air and until an hour after landing.
     */
    fun wantsLive(f: Flight, now: Instant = Instant.now()): Boolean {
        if (!liveEnabled(f) || f.cancelled) return false
        val phase = f.phase(now)
        if (phase == Phase.InFlight || phase == Phase.Arrival) return true
        if (phase == Phase.Done) return false
        val dep = f.dep ?: return false
        return Duration.between(now, dep) <= Duration.ofHours(if (f.mine) Prefs.liveLeadHours.toLong() else 3)
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
        val id = f.id.hashCode()
        val now = Instant.now()
        if (!wantsLive(f, now)) {
            nm.cancel(id); return
        }
        val phase = f.phase(now)
        val flying = phase == Phase.InFlight
        val landed = phase == Phase.Arrival
        val dep = f.dep ?: f.depGuess
        val arr = f.arr ?: f.arrGuess
        val boarding = dep.minus(BOARDING)
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
            .setGroup("live-${f.id}") // its own card, not bundled with alerts
            // Flight times aren't private: show the full card on the lock screen even when content is hidden.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
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
            b.setRequestPromotedOngoing(!landed).setShortCriticalText(chip)
        } catch (_: LinkageError) {
        }
        nm.notify(id, b.build())
    }

    fun cleanup(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val keep = Store.flights.filter { wantsLive(it) }.map { it.id.hashCode() }.toSet()
        nm.activeNotifications.filter { it.notification.channelId == LIVE && it.id !in keep }.forEach { nm.cancel(it.id) }
    }

    private fun alert(ctx: Context, f: Flight, key: String, title: String, text: String) {
        ctx.getSystemService(NotificationManager::class.java).notify(
            (f.id + key).hashCode(),
            Notification.Builder(ctx, ALERTS).setSmallIcon(R.drawable.ic_plane)
                .setContentTitle(title).setContentText(text).setContentIntent(open(ctx, f)).setAutoCancel(true).build(),
        )
    }

    /** Notifies what changed between [old] and [new], each event once. Returns the updated "already alerted" list. */
    fun events(ctx: Context, old: Flight, new: Flight): String {
        val done = old.alerted.split(',').filter { it.isNotEmpty() }.toMutableSet()
        fun once(key: String, title: String, text: String) {
            if (key in done) return
            done += key
            if (alertsEnabled(new)) alert(ctx, new, key, title, text)
        }
        val name = "${who(new)}${new.label}"
        if (new.cancelled) once("cancelled", "$name cancelled", "${new.from} → ${new.to} on ${dayLabel(new.date)}")
        // The airline retimed the flight (often weeks ahead). Keyed by the new time, so each change alerts once.
        val was = old.depSched
        val now = new.depSched
        if (was != null && now != null && new.depActual == null && Duration.between(was, now).abs() >= Duration.ofMinutes(5)) {
            once(
                "sched${now.epochSecond}", "$name schedule change",
                "Now departs ${dayLabel(now.atZone(new.fromTz).toLocalDate())} at ${time(now, new.fromTz)} (was ${time(was, new.fromTz)})",
            )
        }
        if (new.diverted) once("diverted", "$name diverted", "Was flying ${new.from} → ${new.to}")
        if (new.depActual != null || new.isAirborne) {
            once("departed", "$name took off", "${new.from} → ${new.to}, lands ${time(new.arr ?: new.arrGuess, new.toTz)} local")
        }
        if (new.arrActual != null) {
            once("landed", "$name landed in ${new.dest?.city ?: new.to}",
                "Arrived ${time(new.arrActual, new.toTz)}" + (if (new.arrGate.isNotEmpty()) " · Gate ${new.arrGate}" else "") +
                    (if (new.baggage.isNotEmpty()) " · Bags at ${new.baggage}" else ""))
        }
        // Delay and gate alerts re-fire when the value changes, keyed by the value.
        val delay = new.depDelay
        if (delay != null && delay >= 15 && new.depActual == null && delay != old.depDelay) {
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
        val now = Instant.now()
        val h = Prefs.reminderHours
        for (f in Store.flights.filter { it.mine && it.upcoming && !it.cancelled }) {
            val dep = f.dep ?: continue
            if (dep <= now) continue
            val left = Duration.between(now, dep)
            val sent = f.alerted.split(',').filter { it.isNotEmpty() }.toMutableSet()
            if (Prefs.flag("checkinReminder") && left <= Duration.ofHours(24) && "checkin" !in sent) {
                sent += "checkin"
                alert(ctx, f, "checkin", "Check in for ${f.label} to ${f.dest?.city ?: f.to}",
                    "Check-in is open. Departs ${dayLabel(f.date)} at ${time(dep, f.fromTz)} from ${f.from}" +
                        (if (f.booking.isNotEmpty()) " · Conf. ${f.booking}" else ""))
            }
            if (h > 0 && left <= Duration.ofHours(h.toLong()) && "reminder" !in sent) {
                sent += "reminder"
                alert(ctx, f, "reminder", "${f.label} to ${f.dest?.city ?: f.to} leaves in ${span(left)}",
                    "Boarding ${time(dep.minus(BOARDING), f.fromTz)} at ${f.from}" + (if (f.depGate.isNotEmpty()) ", gate ${f.depGate}" else ""))
            }
            val joined = sent.joinToString(",")
            if (joined != f.alerted) Store.upsert(f.copy(alerted = joined))
        }
    }
}

/** Typical boarding lead for narrow-body flights. */
// ponytail: fixed 40 min; airlines board wide-bodies ~50-60 min out. A per-airline value in the airline guide would fix it.
val BOARDING: Duration = Duration.ofMinutes(40)
