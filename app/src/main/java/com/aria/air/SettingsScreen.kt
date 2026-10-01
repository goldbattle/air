package com.aria.air

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TABS = listOf("About", "Notifications", "Data", "Duplicates", "Backup")

object SettingsNav { var tab by mutableIntStateOf(0) }

@Composable
fun SettingsScreen() = Page("Settings") {
    item("tabs") {
        val dupes = Dupes.groups().size
        Pills(TABS.map { if (it == "Duplicates" && dupes > 0) "$it $dupes" else it }, SettingsNav.tab) { SettingsNav.tab = it }
    }
    item("tab-${SettingsNav.tab}") {
        when (SettingsNav.tab) {
            0 -> AboutTab()
            1 -> NotificationsTab()
            2 -> DataTab()
            3 -> DuplicatesTab()
            else -> BackupTab()
        }
    }
}

// ---------- About ----------

@Composable
private fun AboutTab() {
    val built = Instant.ofEpochMilli(BuildConfig.BUILD_TIME).atZone(ZoneId.systemDefault())
    val mine = Store.flights.count { it.mine }
    Spacer(Modifier.height(16.dp))
    Group {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.ic_launcher_foreground), null,
                Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(C.blue),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Air", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Your flights, live, and your travel passport", fontSize = 14.sp, color = C.secondary)
            }
        }
    }
    SectionHeader("You")
    Group { FieldCell("Name", Prefs.name, "For your passport") { Prefs.name = it } }
    SectionHeader("Privacy")
    Group {
        Cell("Stored on this phone", "Everything", icon = Icons.Filled.PhoneAndroid)
        Hairline(50.dp)
        Cell("Documents & emails", "Read on-device", icon = Icons.Filled.Description)
        Hairline(50.dp)
        Cell("Sent over the network", "Flight numbers, airports", icon = Icons.Filled.Public)
    }
    Footnote(
        "Flights, notes, seats and imported documents never leave the phone, and cloud backup is off. " +
            "To get routes and live status the app asks adsb.lol (and AeroDataBox or FlightAware, if you add a key) about a flight " +
            "number, and Open-Meteo about the weather at an airport on a date. Nothing else.",
    )
    SectionHeader("Stats")
    Group {
        Cell("My flights", "$mine")
        Hairline()
        Cell("Friends' flights", "${Store.flights.size - mine}")
        Hairline()
        Cell("Airports in database", fmtInt(Db.airports.size))
        Hairline()
        Cell("Airlines in database", fmtInt(Db.airlines.size))
    }
    SectionHeader("Build")
    Group {
        Cell("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Hairline()
        UpdateRows()
        Hairline()
        Cell("Built", built.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")))
        Hairline()
        Cell("Commit", BuildConfig.GIT_COMMIT)
    }
}

// ---------- Notifications ----------

@Composable
private fun NotificationsTab() {
    val ctx = LocalContext.current
    fun toggle(k: String, v: Boolean) { Prefs.setFlag(k, v); TrackWorker.schedule(ctx) }
    SectionHeader("My flights")
    Group {
        SwitchCell("Live progress", Prefs.flag("liveMine"), "Countdown with boarding time and gate, then flight progress, speed and altitude") { toggle("liveMine", it) }
        Hairline()
        SwitchCell("Alerts", Prefs.flag("alertsMine"), "Delays, gate changes, takeoff and landing") { toggle("alertsMine", it) }
        Hairline()
        SwitchCell("Check-in reminder", Prefs.flag("checkinReminder"), "24 hours before departure, when check-in opens") { toggle("checkinReminder", it) }
    }
    SectionHeader("Show the countdown from")
    Group {
        Column(Modifier.padding(12.dp)) {
            val opts = listOf(4, 12, 24)
            Segmented(listOf("4 h before", "12 h before", "24 h before"), opts.indexOf(Prefs.liveLeadHours).coerceAtLeast(1)) {
                Prefs.liveLeadHours = opts[it]; TrackWorker.schedule(ctx)
            }
        }
    }
    Footnote("In the last 4 hours a bar fills up toward boarding, so you know when to head out.")
    SectionHeader("Reminder before departure")
    Group {
        Column(Modifier.padding(12.dp)) {
            val opts = listOf(0, 1, 3, 24)
            Segmented(listOf("Off", "1 h", "3 h", "24 h"), opts.indexOf(Prefs.reminderHours).coerceAtLeast(0)) { Prefs.reminderHours = opts[it] }
        }
    }
    SectionHeader("Friends' flights")
    Group {
        SwitchCell("Live progress", Prefs.flag("liveFriends"), "Follow their flight like a live activity") { toggle("liveFriends", it) }
        Hairline()
        SwitchCell("Alerts", Prefs.flag("alertsFriends"), "When they take off, land, or get delayed") { toggle("alertsFriends", it) }
    }
    SectionHeader("System")
    Group {
        Cell("Notification settings", icon = Icons.Filled.Notifications, chevron = true) {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
    }
    Footnote(
        "Flights are checked every 2 minutes from 4 hours before departure until landing, and every 30 minutes otherwise. " +
            "Android may delay checks while the phone is idle; the countdown keeps running.",
    )
}

// ---------- Data sources ----------

@Composable
private fun DataTab() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.aeroKey) }
    var test by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    SectionHeader("Live tracking")
    Group {
        Cell("adsb.lol", "Always on", subtitle = "Routes, live position, speed, altitude · free, open data", icon = Icons.Filled.Radar)
    }
    var adb by remember { mutableStateOf(Prefs.adbKey) }
    var adbTest by remember { mutableStateOf<String?>(null) }
    SectionHeader("Weather")
    Group {
        Cell("Open-Meteo", "Always on", subtitle = "Takeoff and landing weather, forecast and history · free", icon = Icons.Filled.WbSunny)
        Hairline()
        SwitchCell("Celsius", Prefs.flag("celsius", false)) { Prefs.setFlag("celsius", it) }
    }
    SectionHeader("Schedules, delays & gates")
    Group {
        FieldCell("AeroDataBox key", adb, "RapidAPI key", secret = true) { adb = it.trim(); Prefs.adbKey = adb; adbTest = null }
        Hairline()
        Cell(if (testing) "Testing…" else "Test key", adbTest, color = C.blue) {
            if (adb.isBlank() || testing) return@Cell
            testing = true
            App.scope.launch {
                adbTest = Api.testAdbKey(adb) ?: "Works"
                testing = false
            }
        }
        Hairline()
        Cell("Get a free key on RapidAPI", icon = Icons.Filled.OpenInNew, chevron = true) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://rapidapi.com/aedbx-aedbx/api/aerodatabox".toUri())) }
        }
    }
    Footnote(
        "AeroDataBox (the Basic plan is free) covers flights about a year back and ahead, so it also fills in " +
            "actual times for past flights when you open them.",
    )
    Group {
        Cell("Status lookups this month", fmtInt(Api.lookupsThisMonth()), icon = Icons.Filled.DataUsage)
    }
    Footnote(
        "Lookups are spaced out by how close a flight is: weekly when it's weeks away, daily in the last two weeks, " +
            "every 2 hours the day before, every 20 minutes around the gate and landing. Past flights are never " +
            "looked up automatically; tap refresh on a flight to look it up once.",
    )
    SectionHeader("Or FlightAware")
    Group {
        FieldCell("AeroAPI key", key, "Paste key", secret = true) { key = it.trim(); Prefs.aeroKey = key; test = null }
        Hairline()
        Cell(if (testing) "Testing…" else "Test key", test, color = C.blue) {
            if (key.isBlank() || testing) return@Cell
            testing = true
            App.scope.launch {
                test = Api.testKey(key) ?: "Works"
                testing = false
            }
        }
        Hairline()
        Cell("FlightAware key: sign in, then API Keys", icon = Icons.Filled.OpenInNew, chevron = true) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://www.flightaware.com/aeroapi/portal/".toUri())) }
        }
    }
    Footnote(
        "Used only when no AeroDataBox key is set. FlightAware AeroAPI adds times, delays, terminals, gates and baggage belts. " +
            "Its Personal tier is pay-per-call with a small free monthly allowance; the app calls it about every " +
            "15 minutes around a flight and every 6 hours before it. Without a key you enter times yourself " +
            "(or they're read from your confirmation), and takeoff/landing are detected from live ADS-B.",
    )
}

// ---------- Duplicates ----------

@Composable
private fun DuplicatesTab() {
    val groups = Dupes.groups()
    if (groups.isEmpty()) {
        Spacer(Modifier.height(16.dp))
        Group { Cell("No duplicates", icon = Icons.Filled.CheckCircle, iconTint = C.green) }
        Footnote("Flights with the same number and date, or the same route within 30 minutes, show up here.")
        return
    }
    Footnote(
        "Merge keeps the most complete copy and fills its blanks (times, seat, confirmation, notes, work) from the " +
            "others. \"Not duplicates\" hides the group for good, for example when you and a friend were on the same flight.",
    )
    groups.forEach { g ->
        SectionHeader("${dayLabel(g.first().date)} · ${Dupes.reason(g[0], g[1]) ?: ""}")
        Group {
            g.forEachIndexed { i, f ->
                if (i > 0) Hairline()
                Cell(
                    "${f.label}  ${f.from} → ${f.to}",
                    subtitle = listOfNotNull(
                        if (f.mine) "Me" else f.friend,
                        f.dep?.let { time(it, f.fromTz) },
                        f.seat.takeIf { it.isNotEmpty() }?.let { "Seat $it" },
                        f.booking.takeIf { it.isNotEmpty() },
                        if (f.work) "Work" else null,
                        "via ${f.source}",
                    ).joinToString(" · "),
                    value = if (i == 0) "Keep" else null, chevron = true,
                ) { Nav.push(Screen.FlightDetail(f.id)) }
            }
            Hairline(0.dp)
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Merge", Modifier.weight(1f)) { Dupes.merge(g) }
                Button("Not duplicates", Modifier.weight(1f), filled = false, color = C.secondary) { Dupes.ignore(g) }
            }
        }
    }
}

// ---------- Backup ----------

@Composable
private fun BackupTab() {
    val ctx = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Store.json().toByteArray()) } }
            .onSuccess { Toast.makeText(ctx, "Exported ${Store.flights.size} flights", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { Store.import(ctx.contentResolver.openInputStream(uri)!!.use { it.reader().readText() }) }
            .onSuccess { Toast.makeText(ctx, "Added $it flights", Toast.LENGTH_SHORT).show(); TrackWorker.schedule(ctx) }
            .onFailure { Toast.makeText(ctx, "Not an Air backup: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val byAir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importInto(ctx, uri, null, friend = false)
    }
    SectionHeader("Backup")
    Group {
        Cell("Export all flights", icon = Icons.Filled.Upload, chevron = true) { export.launch("air-backup-${LocalDate.now()}.json") }
        Hairline(50.dp)
        Cell("Restore from backup", icon = Icons.Filled.Download, chevron = true) { restore.launch(arrayOf("application/json", "*/*")) }
        Hairline(50.dp)
        Cell("Import byAir export", subtitle = "The .csv from byAir › Export flights", icon = Icons.Filled.FileOpen, chevron = true) {
            byAir.launch(arrayOf("text/*", "*/*"))
        }
    }
    Footnote(
        "Cloud backup is off so your history stays private. Export now and then and keep the file somewhere safe. " +
            "Restoring adds flights you don't have yet; it never overwrites.",
    )
}
