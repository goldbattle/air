package com.aria.air.background

// The background check of upcoming flights: what it watches and how soon it runs again.

import com.aria.air.*
import com.aria.air.data.*
import com.aria.air.sync.*
import com.aria.air.util.*
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ponytail: WorkManager is deferred in deep Doze, so updates can lag while the phone sits idle. The notification's
// countdown ticks on its own regardless. Upgrade path: a foreground service while airborne.
/**
 * Updates flights that are close to happening, then reschedules itself at a pace set by [nextCheckMinutes].
 * Works offline: the notification and widget are redrawn from the schedule (or the best estimate) every run, and the
 * network is only used when there is a connection. Stops when nothing is upcoming. Runs inside [TrackWorker].
 */
object Tracker {
    /** One run of the check: refresh what's due, notify what changed, redraw the widget, schedule the next run. */
    suspend fun run(ctx: Context) {
        val now = Instant.now(clock)
        val due = withContext(Dispatchers.Main) { Store.flights.toList() }.filter { tracked(it, now) }
        val online = isOnline(ctx)
        for (f in due) {
            // Offline (say, in the air without Wi-Fi): skip the network and just redraw from times and estimates.
            val n = if (online) runCatching { FlightSync.refresh(f, auto = true) }.getOrDefault(f) else f
            val alerted = Notify.events(ctx, f, n)
            val saved = n.copy(alerted = alerted)
            withContext(Dispatchers.Main) {
                // Keep anything the user edited while we were fetching.
                Store.get(f.id)?.let { cur ->
                    Store.upsert(saved.copy(seat = cur.seat, booking = cur.booking, notes = cur.notes, friend = cur.friend, work = cur.work))
                }
            }
            Notify.live(ctx, saved)
        }
        withContext(Dispatchers.Main) {
            Notify.reminders(ctx)
            schedule(ctx, fromWorker = true)
        }
        FlightWidget.refresh(ctx) // countdowns move even when nothing was saved
    }

    /** All my upcoming flights (far-out ones only hit the network once a day, see Cadence.statusEvery); friends' near theirs. */
    fun tracked(f: Flight, now: Instant) = (f.upcoming || f.maybeLive) && (f.mine || f.depGuess < now.plus(Duration.ofHours(6)))

    /** Call after flights change, and from the worker itself. */
    fun schedule(ctx: Context, fromWorker: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        val upcoming = Store.flights.filter { it.upcoming || it.maybeLive }
        // Clear notifications of flights that finished or were deleted.
        Notify.cleanup(ctx)
        if (upcoming.isEmpty()) {
            wm.cancelUniqueWork("track"); return
        }
        val delay = if (!fromWorker) 0L else nextCheckMinutes(upcoming, Instant.now(clock))
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

    /** Whether the phone has internet right now. Offline runs skip the network and just redraw. */
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
