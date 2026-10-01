package com.aria.air

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Updates from GitHub Releases: asks the latest-release API (nothing else is sent, not even the app version), downloads
 * the APK straight into a PackageInstaller session and installs it in place. Android asks the user to confirm the first
 * update (and whenever the app wasn't the one that installed itself); later ones can go through silently.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/goldbattle/air/releases/latest"
    private const val CHANNEL = "updates"
    private const val NOTE = 7_000_001
    const val DOWNLOAD = "com.aria.air.UPDATE_DOWNLOAD"
    const val STATUS = "com.aria.air.UPDATE_STATUS"

    data class Release(val code: Int, val name: String, val apk: String)

    /** Tags are "v" + versionName, and versionName is "1.0.<versionCode>", so the build number is the last part. */
    fun codeOf(tag: String): Int? = tag.substringAfterLast('.').toIntOrNull()

    /** [r] if it is a newer build than [installed]. */
    fun newer(r: Release?, installed: Int = BuildConfig.VERSION_CODE) = r?.takeIf { it.code > installed }

    /** What Settings shows: null until checked, then "Up to date", "Version X available", progress or an error. */
    var status by mutableStateOf<String?>(null)
    var available by mutableStateOf<Release?>(null)
    var busy by mutableStateOf(false)

    /** The latest release on GitHub, or null if it has no APK. Blocking. */
    fun latest(): Release? {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        val json = try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Air-Android") // GitHub requires one
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode == 404) return null // no releases yet
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode} from GitHub")
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
        val tag = json.getString("tag_name")
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
            ?: return null
        return Release(codeOf(tag) ?: return null, tag.removePrefix("v"), apk.getString("browser_download_url"))
    }

    /** Checks now and updates [status]. */
    fun check() {
        if (busy) return
        busy = true
        status = "Checking…"
        App.scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { latest() } }
            available = newer(r.getOrNull())
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

    fun cancel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTE)

    fun broadcast(ctx: Context, action: String, flags: Int = PendingIntent.FLAG_IMMUTABLE) =
        PendingIntent.getBroadcast(ctx, 0, Intent(ctx, UpdateReceiver::class.java).setAction(action), flags or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Streams the APK into an install session, then commits it; the result arrives in [UpdateReceiver]. */
    fun install(ctx: Context, r: Release) {
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
}

/** Without "install": the daily check. With it: download and install the latest release. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val install = inputData.getBoolean("install", false)
        try {
            val r = Updater.newer(Updater.latest())
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
        Result.success()
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
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

/** Settings > About rows: check now, the result with an Update button, and the daily-check toggle. */
@Composable
fun UpdateRows() {
    val ctx = LocalContext.current
    val r = Updater.available
    Cell(
        if (r != null) "Update" else "Check for updates", Updater.status,
        color = C.blue,
    ) {
        when {
            Updater.busy -> Unit
            r == null -> Updater.check()
            // Android sends the user to "Install unknown apps" for Air once; after that updates go straight through.
            !ctx.packageManager.canRequestPackageInstalls() -> runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${ctx.packageName}".toUri()))
            }
            else -> {
                Updater.busy = true
                Updater.status = "Downloading…"
                Updater.start(ctx)
            }
        }
    }
    Hairline()
    SwitchCell("Check daily", Prefs.flag("updateCheck"), "Notify when a new version is out") {
        Prefs.setFlag("updateCheck", it); Updater.schedule(ctx)
    }
}
