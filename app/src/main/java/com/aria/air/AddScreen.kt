package com.aria.air

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** The document being imported, reviewed on the Add screen before anything is saved. */
object Imports {
    var busy by mutableStateOf(false)
    var found by mutableStateOf<List<Found>?>(null)
    var traveler by mutableStateOf<String?>(null) // name on the booking, if the document says
    var work by mutableStateOf(false)             // booked through a company
    var error by mutableStateOf<String?>(null)
    val active get() = busy || found != null || error != null
    fun clear() { busy = false; found = null; traveler = null; work = false; error = null }
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
        if (t != null && isByAirCsv(t)) {
            val flights = byAirFlights(t)
            val added = Store.addAll(flights)
            TrackWorker.schedule(ctx)
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
            Imports.work = Importer.isWork(t)
            Imports.found = found
            if (found.isEmpty()) Imports.error = "No flight numbers found in that document."
        }
        Imports.busy = false
    }
}

/** "UA2000", "ua 2000" or the ICAO form "UAL2000" -> ("UA", "2000"). */
fun parseFlightNumber(s: String): Pair<String, String>? {
    val t = s.uppercase().replace(" ", "")
    Regex("^([A-Z]{3})(\\d{1,4})$").find(t)?.let { m -> Db.airlinesByIcao[m.groupValues[1]]?.let { return it.iata to m.groupValues[2].trimStart('0') } }
    val m = Regex("^([A-Z0-9]{2})(\\d{1,4})$").find(t) ?: return null
    return m.groupValues[1] to m.groupValues[2].trimStart('0')
}

/**
 * "Jane Q Doe", "Janeq Doe" (airlines glue the middle initial on) and "Jane Doe" are the same
 * person; "John Doe" isn't.
 */
fun sameName(a: String, b: String): Boolean {
    val x = a.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
    val y = b.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
    if (x.isEmpty() || y.isEmpty()) return false
    val (p, q) = x.first() to y.first()
    val first = p == q || (kotlin.math.abs(p.length - q.length) == 1 && (p.startsWith(q) || q.startsWith(p)))
    return first && (x.size == 1 || y.size == 1 || x.last() == y.last())
}

/** A saved flight this imported one probably is: same number and date, or same route and date. */
fun existingFor(f: Found): Flight? {
    val d = f.date ?: return null
    return Store.flights.firstOrNull { it.date == d && it.airline == f.airline && it.number == f.number }
        ?: Store.flights.firstOrNull { it.date == d && f.from != null && it.from == f.from && it.to == f.to }
}

/**
 * Saves a flight, fills in what the APIs know, and starts tracking. Returns its id. If it's already saved, the saved
 * one gets this one's missing details instead, unless [asCopy], which saves it separately (it then shows up in
 * Settings › Duplicates).
 */
suspend fun addFlight(ctx: Context, f: Flight, asCopy: Boolean = false): String {
    if (asCopy) {
        Store.upsert(f)
        runCatching { Api.refresh(f) }.getOrNull()?.let { n -> if (Store.get(f.id) != null) Store.upsert(n) }
        TrackWorker.schedule(ctx)
        return f.id
    }
    // Already there (say from a byAir import): fill in what this document adds instead of duplicating it.
    Store.findSame(f)?.let { old ->
        Store.upsert(
            old.copy(
                seat = old.seat.ifEmpty { f.seat }, booking = old.booking.ifEmpty { f.booking },
                depSched = old.depSched ?: f.depSched, arrSched = old.arrSched ?: f.arrSched, work = old.work || f.work,
            ),
        )
        return old.id
    }
    Store.upsert(f)
    // One lookup per added flight, past ones included, to backfill actual times, gates and aircraft.
    val n = runCatching { Api.refresh(f) }.getOrDefault(f)
    if (Store.get(f.id) != null) Store.upsert(n)
    TrackWorker.schedule(ctx)
    return f.id
}

private fun Found.toFlight(route: List<String>, friend: String, work: Boolean): Flight? {
    val d = date ?: return null
    val from = from ?: route.firstOrNull() ?: return null
    val to = to ?: route.lastOrNull()?.takeIf { it != from } ?: return null
    val fromTz = Db.airport(from)?.tz ?: return null
    val toTz = Db.airport(to)?.tz ?: return null
    val depAt = dep?.let { d.atTime(it).atZone(fromTz).toInstant() }
    var arrAt = arr?.let { d.atTime(it).atZone(toTz).toInstant() }
    if (depAt != null && arrAt != null) while (arrAt!! < depAt) arrAt = arrAt.plus(Duration.ofDays(1))
    return Flight(
        airline = airline, number = number, date = d, from = from, to = to, depSched = depAt, arrSched = arrAt,
        seat = seat, booking = booking, friend = friend, source = "import", work = work && friend.isEmpty(),
    )
}

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
        val p = parsed ?: run { message = "Enter a flight number like UA 2000"; return }
        message = if (Db.airline(p.first) == null) "Unknown airline code ${p.first}, searching anyway" else null
        searching = true; legs = null
        App.scope.launch {
            legs = Api.route(p.first, p.second)
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
                FieldCell("Flight", number, "UA 2000", caps = true) { number = it; legs = null; message = null }
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

/**
 * Review of an imported document: whose trip it is (from the name on the booking), personal or work, and which
 * flights to add. Nothing is saved until "Add".
 */
@Composable
private fun ImportReview(s: Screen.Add, openPicker: () -> Unit) {
    val ctx = LocalContext.current
    val found = Imports.found.orEmpty()
    val traveler = Imports.traveler
    val isMe = traveler != null && Prefs.name.isNotBlank() && sameName(traveler, Prefs.name)
    // No name on the booking: go by the tab you came from. A name: it's yours if it matches your name.
    var mine by remember(found) { mutableStateOf(if (traveler == null) !s.friend else isMe) }
    var friendName by remember(found) { mutableStateOf(if (traveler != null && !isMe) traveler else "") }
    var askedWho by remember(found) { mutableStateOf(traveler == null || Prefs.name.isNotBlank()) }
    var work by remember(found) { mutableStateOf(Imports.work) }
    var items by remember(found) { mutableStateOf(found) }
    var picked by remember(found) { mutableStateOf(found.indices.filter { found[it].date != null }.toSet()) }
    var copies by remember(found) { mutableStateOf(emptySet<Int>()) } // already saved, but import separately anyway
    var adding by remember { mutableStateOf(false) }
    val owner = if (mine) "" else friendName.trim()

    fun cancel() { Imports.clear(); Nav.pop() }

    fun addAll() {
        val list = picked.sorted().map { i -> items[i] to (i in copies) }
        adding = true
        App.scope.launch {
            val ids = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            for ((f, copy) in list) {
                val route = if (f.from == null || f.to == null) Api.route(f.airline, f.number) else emptyList()
                val flight = f.toFlight(route, owner, work)
                if (flight == null) skipped += f.label else ids += addFlight(ctx, flight, asCopy = copy)
            }
            Imports.clear()
            Nav.open(if (mine) Tab.Flights else Tab.Friends)
            if (ids.size == 1) Nav.push(Screen.FlightDetail(ids[0]))
            Toast.makeText(
                ctx,
                "Added ${ids.size} flight${if (ids.size == 1) "" else "s"}" +
                    if (skipped.isNotEmpty()) " · couldn't place ${skipped.joinToString()} (no airports)" else "",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Page("Import", onBack = ::cancel, backLabel = "Cancel") {
        if (Imports.busy) item("busy") {
            Group {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = C.blue)
                    Spacer(Modifier.width(14.dp))
                    Text("Reading the document on your phone…", fontSize = 17.sp)
                }
            }
        }
        Imports.error?.let { e ->
            item("err") {
                Group { Text(e, fontSize = 17.sp, modifier = Modifier.padding(16.dp)) }
                Spacer(Modifier.height(12.dp))
                Button("Choose another file", Modifier.fillMaxWidth(), onClick = openPicker)
            }
        }
        if (found.isEmpty()) return@Page

        // First time we see a name and don't know yours: ask once, and remember the answer.
        if (!askedWho && traveler != null) item("isyou") {
            Group {
                Column(Modifier.padding(16.dp)) {
                    Text("Is this you?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("This booking is for $traveler.", fontSize = 15.sp, color = C.secondary, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button("Yes, that's me", Modifier.weight(1f)) {
                            Prefs.name = traveler; mine = true; askedWho = true
                        }
                        Button("Someone else", Modifier.weight(1f), filled = false) {
                            mine = false; friendName = traveler; askedWho = true
                        }
                    }
                }
            }
            Footnote("Answering \"yes\" saves it as your name (Settings › About), so future imports know.")
        }

        if (askedWho) item("who") {
            SectionHeader("Traveler")
            Group {
                Column(Modifier.padding(12.dp)) {
                    Segmented(listOf(if (Prefs.name.isNotBlank()) "Me · ${Prefs.name.substringBefore(' ')}" else "Me", "A friend"), if (mine) 0 else 1) {
                        mine = it == 0
                        if (!mine && friendName.isBlank() && traveler != null && !isMe) friendName = traveler
                    }
                }
                if (!mine) {
                    Hairline()
                    FieldCell("Name", friendName, "Who's flying") { friendName = it }
                } else {
                    Hairline()
                    SwitchCell("Work trip", work, if (Imports.work) "Looks like a company booking" else "Counts separately in your passport") { work = it }
                }
            }
            if (!mine) {
                val names = Store.friends
                if (names.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Pills(names, names.indexOf(friendName)) { friendName = names[it] }
                }
            }
        }

        item("flights") {
            SectionHeader("${found.size} flight${if (found.size == 1) "" else "s"} found")
            Group {
                items.forEachIndexed { i, f ->
                    if (i > 0) Hairline(52.dp)
                    val on = i in picked
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            // A flight with no date can't be saved; tapping it asks for one.
                            if (f.date == null) pickDate(ctx, LocalDate.now()) { d ->
                                items = items.toMutableList().also { it[i] = f.copy(date = d) }; picked = picked + i
                            } else picked = if (on) picked - i else picked + i
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (on) C.blue else C.tertiary, modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        AirlineBadge(f.airline, 26)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.label + if (f.from != null && f.to != null) "  ${f.from} → ${f.to}" else "",
                                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                listOfNotNull(
                                    f.date?.let(::dayLabel) ?: "No date: tap to set",
                                    f.dep?.let { "${fmt(it)} – ${f.arr?.let(::fmt) ?: "?"}" },
                                ).joinToString(" · "),
                                fontSize = 14.sp, color = if (f.date == null) C.red else C.secondary,
                            )
                            val extras = listOfNotNull(
                                f.seat.takeIf { it.isNotEmpty() }?.let { "Seat $it" },
                                f.booking.takeIf { it.isNotEmpty() }?.let { "Conf. $it" },
                            )
                            if (extras.isNotEmpty()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                extras.forEach { Badge(it, C.indigo) }
                            }
                            existingFor(f)?.let { old ->
                                val copy = i in copies
                                Text(
                                    "Already saved (${if (old.mine) "yours" else old.friend}, via ${old.source}). " +
                                        if (copy) "Will be added as a copy." else "Will fill in the saved one.",
                                    fontSize = 13.sp, color = C.orange, modifier = Modifier.padding(top = 4.dp),
                                )
                                Text(
                                    if (copy) "Fill in existing instead" else "Add as a copy",
                                    fontSize = 13.sp, color = C.blue, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 2.dp).clickable { copies = if (copy) copies - i else copies + i },
                                )
                            }
                        }
                    }
                }
            }
            if (found.any { it.from == null || it.to == null }) Footnote("Missing airports are looked up by flight number when you add.")
            if (copies.isNotEmpty()) Footnote("Copies show up in Settings › Duplicates, where you can merge or keep them.", C.orange)
        }

        item("add") {
            Spacer(Modifier.height(16.dp))
            val n = picked.size
            Button(
                if (adding) "Adding…" else "Add $n flight${if (n == 1) "" else "s"}" + if (!mine && owner.isNotEmpty()) " for ${owner.substringBefore(' ')}" else "",
                Modifier.fillMaxWidth(), enabled = n > 0 && !adding && (mine || owner.isNotEmpty()),
            ) { addAll() }
            if (!mine && owner.isEmpty()) Footnote("Add who's flying.", C.orange)
        }
    }
}

private fun fmt(t: LocalTime) = t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
