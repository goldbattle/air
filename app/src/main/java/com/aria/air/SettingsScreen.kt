package com.aria.air

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// Frequent on the left, rare on the right, so the common ones never need a sideways scroll.
private val TABS get() = listOf("About", "Data", "Import", "Notifications", "Duplicates") + if (Debug.on) listOf("Debug") else emptyList()

object SettingsNav { var tab by mutableIntStateOf(0) }

@Composable
fun SettingsScreen() = Page("Settings") {
    item("tabs") {
        val dupes = Dupes.groups().size
        Pills(TABS.map { if (it == "Duplicates" && dupes > 0) "$it $dupes" else it }, SettingsNav.tab) { SettingsNav.tab = it }
    }
    item("tab-${SettingsNav.tab}") {
        when (TABS[SettingsNav.tab]) {
            "About" -> AboutTab()
            "Data" -> DataTab()
            "Import" -> ImportTab()
            "Debug" -> DebugTab()
            "Notifications" -> NotificationsTab()
            else -> DuplicatesTab()
        }
    }
}

// ---------- About ----------

@Composable
private fun AboutTab() {
    val built = Instant.ofEpochMilli(BuildConfig.BUILD_TIME).atZone(ZoneId.systemDefault())
    val mine = Store.flights.filter { it.mine }
    val miles = mine.filter { !it.cancelled && it.phase() == Phase.Done }.sumOf { it.distanceKm } / KM_PER_MI
    SectionHeader("You")
    Group { FieldCell("Name", Prefs.name, "For your passport") { Prefs.name = it } }
    SectionHeader("Stats")
    Group {
        Cell("My flights", "${mine.size}")
        Hairline()
        Cell("Miles flown", fmtInt(miles.roundToInt()))
        Hairline()
        Cell("Friends' flights", "${Store.flights.size - mine.size}")
        Hairline()
        Cell("Airports in database", fmtInt(Db.airports.size))
        Hairline()
        Cell("Airlines in database", fmtInt(Db.airlines.size))
    }
    SectionHeader("Build")
    Group {
        // Seven taps turn on developer options, as on Android itself.
        var taps by remember { mutableIntStateOf(0) }
        val ctx = LocalContext.current
        Cell("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") {
            if (Debug.on) { Toast.makeText(ctx, "Developer options are already on", Toast.LENGTH_SHORT).show(); return@Cell }
            taps++
            if (taps >= 7) {
                Prefs.setFlag("dev", true)
                Debug.note("developer options on")
                Toast.makeText(ctx, "Developer options on: see the Debug tab", Toast.LENGTH_SHORT).show()
            } else if (taps >= 4) Toast.makeText(ctx, "${7 - taps} more taps to turn on developer options", Toast.LENGTH_SHORT).show()
        }
        Hairline()
        UpdateRows()
        Hairline()
        Cell("Commit", BuildConfig.GIT_COMMIT)
        Hairline()
        Cell("Built", built.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")))
    }
    SectionHeader("Privacy")
    Group {
        Text(
            "Flight numbers, airport coordinates and dates go to adsb.lol and Open-Meteo, map tile coordinates to " +
                "RainViewer for radar (and flight numbers to AeroDataBox or FlightAware " +
                "if you add a key). Air asks GitHub for updates and, if you link email, reads booking emails straight from " +
                "your mail server. Nothing else leaves this phone.",
            fontSize = 14.sp, color = C.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
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
        Cell("Notification settings", chevron = true) {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
    }
    Footnote("Checked every 2 minutes from 4 hours before departure until landing, every 30 minutes otherwise.")
}

// ---------- Data sources ----------

@Composable
private fun DataTab() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.aeroKey) }
    var test by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var adb by remember { mutableStateOf(Prefs.adbKey) }
    var adbTest by remember { mutableStateOf<String?>(null) }
    SectionHeader("Always on, free")
    Group {
        Cell("adsb.lol", subtitle = "Routes, live position, speed, altitude")
        Hairline()
        Cell("Open-Meteo", subtitle = "Weather at takeoff, landing and along the route")
        Hairline()
        Cell("RainViewer", subtitle = "Radar on the map near departure · sends only map tile coordinates")
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
        Cell("Get a free key on RapidAPI", chevron = true) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://rapidapi.com/aedbx-aedbx/api/aerodatabox".toUri())) }
        }
        Hairline()
        Cell("Lookups this month", fmtInt(Api.lookupsThisMonth()))
    }
    Footnote(
        "The free Basic plan covers flights about a year back and ahead. Lookups get more frequent as a flight " +
            "gets closer; past flights are only looked up when you tap refresh.",
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
        Cell("Get a key: sign in, then API Keys", chevron = true) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://www.flightaware.com/aeroapi/portal/".toUri())) }
        }
    }
    Footnote(
        "Used only when no AeroDataBox key is set. Pay-per-call with a small free monthly allowance. " +
            "Without either key, times come from your confirmation and takeoff/landing from live ADS-B.",
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
private fun ImportTab() {
    val ctx = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Store.json().toByteArray()) } }
            .onSuccess { Toast.makeText(ctx, "Exported ${Store.flights.size} flights", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    // One picker for everything: importInto tells a backup, a byAir CSV and a confirmation apart.
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importInto(ctx, uri, null, friend = false)
    }
    val accounts = Mail.accounts.size
    SectionHeader("Import")
    Group {
        Cell(
            "Gmail", if (accounts > 0) "$accounts linked" else null, chevron = true,
            subtitle = if (Mail.suggestions.isNotEmpty()) "${Mail.suggestions.size} flights found to review" else "Find bookings in your mailbox",
        ) { Nav.push(Screen.MailAccounts) }
        Hairline()
        Cell("From a file", subtitle = "PDF, screenshot, email, byAir .csv or Air backup", chevron = true) {
            file.launch(arrayOf("*/*"))
        }
        Hairline()
        Cell("Print to Air", subtitle = "Turn on once, then print any email to \"Save to Air\"", chevron = true) {
            ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        }
    }
    Footnote("You can also share a confirmation to Air from any app. Everything is read on this phone.")
    SectionHeader("Backup")
    Group {
        Cell("Export all flights", chevron = true) { export.launch("air-backup-${LocalDate.now()}.json") }
    }
    Footnote(
        "Cloud backup is off, so export now and then and keep the file somewhere safe. To restore, import the file " +
            "above; it only adds flights you don't have and never overwrites.",
    )
}

// ---------- Debug (developer options) ----------

@Composable
private fun DebugTab() {
    val ctx = LocalContext.current
    var log by remember { mutableStateOf(Debug.log()) }
    SectionHeader("Report")
    Group {
        Cell("Copy debug report", subtitle = "Upcoming flights, their status data and the request log", color = C.blue) {
            Debug.copy(ctx, Debug.report(ctx))
        }
        Hairline()
        Cell("Share debug report", color = C.blue) { Debug.share(ctx, Debug.report(ctx)) }
    }
    Footnote("Flight pages get a bug button that copies just that flight. Reports include names and confirmation codes, never API keys.")
    SectionHeader("Phone")
    Group {
        Cell("Model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        Hairline()
        Cell("Android", "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        Hairline()
        Cell("Status lookups this month", fmtInt(Api.lookupsThisMonth()))
        Hairline()
        Cell("Status key", if (Prefs.adbKey.isNotBlank()) "AeroDataBox" else if (Prefs.aeroKey.isNotBlank()) "FlightAware" else "None")
    }
    SectionHeader("Request log")
    Group {
        Text(
            log.lines().takeLast(40).reversed().joinToString("\n").ifBlank { "Nothing yet. Requests show here as the app makes them." },
            fontSize = 11.sp, lineHeight = 15.sp, color = C.secondary,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(12.dp),
        )
        Hairline(0.dp)
        Cell("Refresh", color = C.blue) { log = Debug.log() }
        Hairline()
        Cell("Clear log", color = C.blue) { Debug.clear(); log = "" }
    }
    SectionHeader("Developer options")
    Group {
        Cell("Turn off developer options", color = C.red) {
            Debug.clear()
            Prefs.setFlag("dev", false)
            SettingsNav.tab = 0
        }
    }
}
