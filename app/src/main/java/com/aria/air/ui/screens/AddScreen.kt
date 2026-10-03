package com.aria.air.ui.screens

// Adding a flight: search by number, or import a document (which then opens the review).

import com.aria.air.*
import com.aria.air.api.*
import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.importer.*
import com.aria.air.ui.components.*
import com.aria.air.util.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.launch

/** The document being imported, reviewed on the Add screen before anything is saved. */
object Imports {
    var busy by mutableStateOf(false)
    var found by mutableStateOf<List<Found>?>(null)
    var traveler by mutableStateOf<String?>(null) // name on the booking, if the document says
    var work by mutableStateOf(false)             // booked through a company, or sent to an address you marked work
    var to by mutableStateOf<String?>(null)       // the address the email was sent to, if it says
    var error by mutableStateOf<String?>(null)

    /** Called when the review ends, with whether flights were added (instead of opening them); see [reviewMail]. */
    var then: ((Boolean) -> Unit)? = null
    val active get() = busy || found != null || error != null

    /** Forgets the document under review. */
    fun clear() { busy = false; found = null; traveler = null; work = false; to = null; error = null; then = null }
}

/** Reads flights out of a shared/picked file or text, entirely on the phone, then opens the Add screen to review. */
fun importInto(ctx: Context, uri: Uri?, text: String?, friend: Boolean) {
    if (uri == null && text.isNullOrBlank()) return
    Imports.clear(); Imports.busy = true
    if ((Nav.stack.lastOrNull() as? Screen.Add) == null) Nav.push(Screen.Add(friend))
    App.scope.launch {
        val raw = runCatching { if (uri != null) Importer.text(ctx, uri) else Importer.stripHtml(text!!) }
        val t = raw.getOrNull()
        // A byAir export is a whole history: add it straight away instead of reviewing flight by flight.
        // An Air backup (shared from Files, another phone, or the screenshot script): restore it.
        if (t != null && Store.isBackup(t)) {
            val added = Store.import(t)
            Tracker.schedule(ctx)
            Imports.clear()
            Nav.pop()
            Toast.makeText(ctx, "Restored $added flights from backup", Toast.LENGTH_LONG).show()
            return@launch
        }
        if (t != null && isByAirCsv(t)) {
            val flights = byAirFlights(t)
            val added = Store.addAll(flights)
            Tracker.schedule(ctx)
            Imports.clear()
            Nav.pop()
            Toast.makeText(ctx, "Imported $added flights from byAir (${flights.size - added} already here)", Toast.LENGTH_LONG).show()
            return@launch
        }
        if (t == null) {
            Imports.error = "Couldn't read it: ${raw.exceptionOrNull()?.message}"
        } else {
            val found = Importer.parse(t)
            Imports.traveler = Importer.traveler(t)
            Imports.to = Importer.recipient(t) { Prefs.workFor(it) != null }
            Imports.work = Importer.isWork(t, Imports.to, Prefs::workFor)
            Imports.found = found
            if (found.isEmpty()) Imports.error = "No flight numbers found in that document."
        }
        Imports.busy = false
    }
}

/**
 * The Add page: the import review while a document is being read or reviewed, else the search form. Also hosts the
 * paste-text dialog.
 */
@Composable
fun AddScreen(s: Screen.Add) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importInto(ctx, uri, null, s.friend)
    }
    var paste by remember { mutableStateOf<String?>(null) }
    val openPicker = { picker.launch(arrayOf("application/pdf", "image/*", "message/rfc822", "text/*")) }

    if (Imports.active) ImportReview(s, openPicker) else SearchForm(s, openPicker) { paste = "" }

    paste?.let { text ->
        var t by remember { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { paste = null },
            title = { Text("Paste email") },
            text = { Group { TextArea(t, "Paste the confirmation email or itinerary text", 160.dp) { t = it } } },
            confirmButton = { TextButton({ paste = null; importInto(ctx, null, t, s.friend) }) { Text("Find flights") } },
            dismissButton = { TextButton({ paste = null }) { Text("Cancel") } },
        )
    }
}

/** Flight number + date search, plus the ways to import. */
@Composable
private fun SearchForm(s: Screen.Add, openPicker: () -> Unit, openPaste: () -> Unit) {
    val ctx = LocalContext.current
    var friend by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var searching by remember { mutableStateOf(false) }
    var legs by remember { mutableStateOf<List<String>?>(null) }
    var manualFrom by remember { mutableStateOf("") }
    var manualTo by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val parsed = parseFlightNumber(number)
    val who = if (s.friend) friend.trim() else ""
    val needsName = s.friend && who.isEmpty()

    fun add(from: String, to: String) {
        val p = parsed ?: return
        App.scope.launch {
            val id = addFlight(ctx, Flight(airline = p.first, number = p.second, date = date, from = from, to = to, friend = who, source = "search"))
            Nav.pop(); Nav.push(Screen.FlightDetail(id))
        }
    }

    fun search() {
        val p = parsed ?: run { message = "Enter a flight number like UA 1234"; return }
        message = if (Db.airline(p.first) == null) "Unknown airline code ${p.first}, searching anyway" else null
        searching = true; legs = null
        App.scope.launch {
            legs = AdsbLol.route(p.first, p.second)
            if (legs.isNullOrEmpty()) message = "Route not found for ${p.first} ${p.second}. Enter the airports below."
            searching = false
        }
    }

    Page(if (s.friend) "Track a Friend" else "Add Flight", onBack = { Nav.pop() }, backLabel = if (s.friend) "Friends" else "Flights") {
        item("import") {
            // Importing comes first: it's the fastest way in and fills seats and confirmation codes too.
            Group {
                Cell(
                    "Import a confirmation", subtitle = "PDF, screenshot, .eml or byAir export", icon = Icons.Filled.Description,
                    chevron = true, onClick = openPicker,
                )
                Hairline(50.dp)
                Cell("Paste email text", icon = Icons.Filled.ContentPaste, chevron = true, onClick = openPaste)
                Hairline(50.dp)
                Cell(
                    "Print from Gmail", subtitle = "Print the email to \"Save to Air\". Turn on Air in print settings once.",
                    icon = Icons.Filled.Print, chevron = true,
                ) { ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
            }
            Footnote("Read on this phone only. You can also share an email or PDF from Gmail straight to Air.")
        }
        if (s.friend) item("who") {
            SectionHeader("Who's flying")
            Group { FieldCell("Name", friend, "Alex") { friend = it } }
            val names = Store.friends
            if (names.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Pills(names, names.indexOf(friend)) { friend = names[it] }
            }
        }
        item("search") {
            SectionHeader("Or search by flight number")
            Group {
                FieldCell("Flight", number, "UA 1234", caps = true) { number = it; legs = null; message = null }
                Hairline()
                Cell("Date", dayLabel(date), chevron = true) { pickDate(ctx, date) { date = it; legs = null } }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                if (searching) "Searching…" else "Find flight", Modifier.fillMaxWidth(), icon = Icons.Filled.Search,
                enabled = parsed != null && !searching && !needsName,
            ) { search() }
            message?.let { Footnote(it, if (legs.isNullOrEmpty()) C.orange else C.secondary) }
            if (needsName) Footnote("Add who's flying first.")
        }
        legs?.let { route ->
            item("legs") {
                if (route.size >= 2) {
                    SectionHeader(if (route.size > 2) "Pick a leg" else "Found")
                    Group {
                        route.zipWithNext().forEachIndexed { i, (a, b) ->
                            if (i > 0) Hairline()
                            val o = Db.airport(a); val d = Db.airport(b)
                            Cell(
                                "$a → $b", subtitle = "${o?.city ?: a} to ${d?.city ?: b} · ${Db.airline(parsed?.first)?.name ?: ""}",
                                value = "Add", chevron = true,
                            ) { add(a, b) }
                        }
                    }
                    Footnote("Route from adsb.lol. Schedule, gates and delays fill in when a status key is set (Settings › Data).")
                } else {
                    SectionHeader("Airports")
                    Group {
                        FieldCell("From", manualFrom, "PIT", caps = true) { manualFrom = it.uppercase().take(3) }
                        Hairline()
                        FieldCell("To", manualTo, "SFO", caps = true) { manualTo = it.uppercase().take(3) }
                    }
                    val ok = Db.airport(manualFrom) != null && Db.airport(manualTo) != null && manualFrom != manualTo
                    Spacer(Modifier.height(12.dp))
                    Button("Add flight", Modifier.fillMaxWidth(), enabled = ok) { add(manualFrom, manualTo) }
                    if (manualFrom.length == 3 && Db.airport(manualFrom) == null) Footnote("Unknown airport $manualFrom", C.red)
                    if (manualTo.length == 3 && Db.airport(manualTo) == null) Footnote("Unknown airport $manualTo", C.red)
                }
            }
        }
    }
}
