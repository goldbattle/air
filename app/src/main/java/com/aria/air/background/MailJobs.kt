package com.aria.air.background

// Email scanning in the background: the schedule, scan now, and the "found flights" notification.

import com.aria.air.*
import com.aria.air.data.*
import com.aria.air.importer.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Background email scans in WorkManager ([MailWorker]), so a long first scan finishes even if you leave the app. */
object MailJobs {
    /** Auto-scan every [Prefs] "mailWeeks" weeks (0 = manual only), over any network, while there are accounts. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val weeks = Prefs["mailWeeks"].toIntOrNull() ?: 0
        if (weeks == 0 || Mail.accounts.isEmpty()) { wm.cancelUniqueWork("mail"); return }
        val req = PeriodicWorkRequestBuilder<MailWorker>(weeks * 7L, TimeUnit.DAYS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED)).build()
        wm.enqueueUniquePeriodicWork("mail", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Runs in WorkManager so a long first scan finishes even if you leave the app. */
    fun scanNow(ctx: Context) {
        Mail.progress = "Starting…"
        val req = OneTimeWorkRequestBuilder<MailWorker>().setInputData(workDataOf("notify" to false))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("mailNow", ExistingWorkPolicy.KEEP, req)
    }

    /** Posts "N flights found in your email"; tapping it opens the found-emails page. */
    fun notify(ctx: Context, flights: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mail", "Flights found in email", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java).putExtra("mail", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            "mail".hashCode(),
            Notification.Builder(ctx, "mail").setSmallIcon(R.drawable.ic_plane)
                .setContentTitle("$flights flight${if (flights == 1) "" else "s"} found in your email")
                .setContentText("Tap to choose which to import").setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    /** One scan of every account; notifies about new flights when [notify] (the scheduled scans). */
    suspend fun run(ctx: Context, notify: Boolean) {
        val n = Mail.scanAll(ctx)
        if (n > 0 && notify) notify(ctx, n)
    }
}
