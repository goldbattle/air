package com.aria.air

// Android entry points whose class names the system or WorkManager stores; their logic lives elsewhere.

import com.aria.air.api.*
import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.ui.widget.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

// These classes stay in com.aria.air on purpose: WorkManager keeps each worker's class name in its database, the
// launcher keeps the widget receiver's, and the update notification's PendingIntent names UpdateReceiver. Moving them
// would orphan scheduled work and placed widgets on phones that update. Each one just hands off to its package.

/** The background flight check; see [Tracker]. */
class TrackWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Tracker.run(applicationContext)
        return Result.success()
    }
}

/** Scheduled and on-demand email scans; see [MailJobs]. */
class MailWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        MailJobs.run(applicationContext, notify = inputData.getBoolean("notify", true))
        return Result.success()
    }
}

/** Without "install": the daily update check. With it: download and install the latest release. See [Updater]. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Updater.run(applicationContext, install = inputData.getBoolean("install", false))
        return Result.success()
    }
}

/** The update notification's buttons and the installer's result; see [Updater.onBroadcast]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Updater.onBroadcast(ctx, intent)
}

/** The friends' live card's Next flight button: the card moves on to the next friend's flight. */
class NextLiveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val now = java.time.Instant.now(com.aria.air.util.clock)
        Turns.next("live", Notify.friendsLive(now), now, Notify.TURN)
        Notify.liveNow(ctx)
    }
}

/** Home screen widget: the next (or current) flight, in the style of the iOS system widgets. */
class FlightWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Radar layer (RainViewer.kt): fetched here when due, then drawn from memory.
        if (Layers.radar) widgetFlights(stacked = true).forEach { f -> kotlinx.coroutines.withTimeoutOrNull(20_000) { RainViewer.get(f) } }
        provideContent { WidgetContent(context) }
    }

    /** The widget's size breakpoints, and a way to redraw every placed copy. */
    companion object {
        val SMALL = DpSize(150.dp, 150.dp)
        val WIDE = DpSize(300.dp, 150.dp)
        val LARGE = DpSize(300.dp, 250.dp) // wide and about three rows: two flights on one map

        /**
         * Redraws every placed copy of the widget: after each save, and on each background check since countdowns move.
         * Failures are ignored; having no widget placed is normal.
         */
        suspend fun refresh(ctx: Context) = runCatching { FlightWidget().updateAll(ctx) }
    }
}

/** The receiver the launcher knows the widget by (named in the manifest). It only hands out [FlightWidget]. */
class FlightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FlightWidget()
}
