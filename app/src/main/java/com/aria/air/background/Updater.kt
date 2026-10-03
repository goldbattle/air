package com.aria.air.background

// Self-update: the daily check, the download into a PackageInstaller session, and its results.

import com.aria.air.*
import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.util.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Updates from GitHub Releases: asks the latest-release API (nothing else is sent, not even the app version), downloads
 * the APK straight into a PackageInstaller session and installs it in place. Android asks the user to confirm the first
 * update (and whenever the app wasn't the one that installed itself); later ones can go through silently.
 */
object Updater {
    private const val CHANNEL = "updates"
    private const val NOTE = 7_000_001
    const val DOWNLOAD = "com.aria.air.UPDATE_DOWNLOAD"
    const val STATUS = "com.aria.air.UPDATE_STATUS"

    /** What Settings shows: null until checked, then "Up to date", "Version X available", progress or an error. */
    var status by mutableStateOf<String?>(null)

    /** The newer release found by the last check, or null. */
    var available by mutableStateOf<GitHub.Release?>(null)

    /** A check or download is running; the Settings row ignores taps meanwhile. */
    var busy by mutableStateOf(false)

    /** Checks now and updates [status]. */
    fun check() {
        if (busy) return
        busy = true
        status = "Checking…"
        App.scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { GitHub.latest() } }
            available = GitHub.newer(r.getOrNull())
            status = when {
                r.isFailure -> "Couldn't check"
                available != null -> "Version ${available!!.name} available"
                else -> "Up to date"
            }
            busy = false
        }
    }

    /** Downloads and installs the latest release in the background. */
    fun start(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "update-install", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateWorker>()
                .setInputData(workDataOf("install" to true))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build(),
        )
    }

    /** Daily check while the toggle is on: needs a network and a battery that isn't low, and only notifies. */
    fun schedule(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "New versions of Air and download progress"
            },
        )
        val wm = WorkManager.getInstance(ctx)
        if (!Prefs.flag("updateCheck")) {
            wm.cancelUniqueWork("update-check"); return
        }
        wm.enqueueUniquePeriodicWork(
            "update-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED, requiresBatteryNotLow = true))
                .build(),
        )
    }

    /**
     * Posts or updates the one update notification: title, optional text, a progress bar ([progress] below 0 =
     * indeterminate) and an optional button.
     */
    fun notify(ctx: Context, title: String, text: String? = null, progress: Int? = null, action: Pair<String, PendingIntent>? = null) {
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(if (progress != null) android.R.drawable.stat_sys_download else R.drawable.ic_plane)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (text != null) b.setContentText(text)
        if (progress != null) b.setProgress(100, progress, progress < 0).setOngoing(true)
        if (action != null) b.addAction(Notification.Action.Builder(null, action.first, action.second).build()).setAutoCancel(true)
        ctx.getSystemService(NotificationManager::class.java).notify(NOTE, b.build())
    }

    /** Removes the update notification. */
    fun cancel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTE)

    /**
     * A PendingIntent that sends [action] to [UpdateReceiver]: the notification's buttons and the installer's callback.
     */
    fun broadcast(ctx: Context, action: String, flags: Int = PendingIntent.FLAG_IMMUTABLE) =
        PendingIntent.getBroadcast(ctx, 0, Intent(ctx, UpdateReceiver::class.java).setAction(action), flags or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Streams the APK into an install session, then commits it; the result arrives in [UpdateReceiver]. */
    fun install(ctx: Context, r: GitHub.Release) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        val session = pi.openSession(id)
        try {
            val c = URL(r.apk).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "Air-Android")
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode} downloading the update")
                val total = c.contentLengthLong
                session.openWrite("air.apk", 0, total).use { out ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != shown && (pct < 0 || pct % 5 == 0)) {
                                shown = pct
                                status = if (pct >= 0) "Downloading $pct%" else "Downloading…"
                                notify(ctx, "Downloading Air ${r.name}", progress = pct)
                            }
                        }
                    }
                    session.fsync(out)
                }
            } finally {
                c.disconnect()
            }
            status = "Installing…"
            notify(ctx, "Installing Air ${r.name}", progress = -1)
            session.commit(broadcast(ctx, STATUS, PendingIntent.FLAG_MUTABLE).intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    /** The body of [UpdateWorker]: without [install] the daily check (notify once per version); with it, download and install. */
    suspend fun run(ctx: Context, install: Boolean): Unit = withContext(Dispatchers.IO) {
        try {
            val r = GitHub.newer(GitHub.latest())
            when {
                r == null -> Updater.busy = false
                install -> Updater.install(ctx, r)
                // Once per version, so a skipped update doesn't nag every day.
                Prefs["updateNotified"] != "${r.code}" -> {
                    Prefs["updateNotified"] = "${r.code}"
                    Updater.notify(ctx, "Air ${r.name} is available", "Tap Update to install it", action = "Update" to Updater.broadcast(ctx, Updater.DOWNLOAD))
                }
            }
        } catch (e: Exception) {
            // The daily check just tries again tomorrow; a download the user asked for says what went wrong.
            if (install) {
                Updater.busy = false
                Updater.status = "Update failed"
                Updater.notify(ctx, "Couldn't download the update", e.message, action = "Retry" to Updater.broadcast(ctx, Updater.DOWNLOAD))
            }
        }
    }

    /** The body of [UpdateReceiver]: the Update/Retry buttons, the installer's result, and the app having been replaced. */
    fun onBroadcast(ctx: Context, intent: Intent) {
        when (intent.action) {
            Updater.DOWNLOAD -> {
                Updater.cancel(ctx)
                Updater.start(ctx)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> Updater.cancel(ctx)
            Updater.STATUS -> when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Shown right away when the app is open; otherwise the notification's Install button opens it.
                    Updater.notify(
                        ctx, "Air update ready", "Tap Install to finish updating",
                        action = "Install" to PendingIntent.getActivity(ctx, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                    Updater.status = "Waiting for you to confirm"
                    runCatching { ctx.startActivity(confirm) }
                }
                PackageInstaller.STATUS_SUCCESS -> Updater.cancel(ctx)
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    Updater.busy = false
                    Updater.cancel(ctx)
                    Updater.status = "Update cancelled"
                }
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Updater.busy = false
                    Updater.status = "Update failed"
                    Updater.notify(ctx, "Couldn't install the update", msg)
                }
            }
        }
    }
}
