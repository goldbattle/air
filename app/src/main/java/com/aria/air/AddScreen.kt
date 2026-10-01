package com.aria.air

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Print
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
    var work by mutableStateOf(false)             // booked through a company, or sent to an address you marked work
    var to by mutableStateOf<String?>(null)       // the address the email was sent to, if it says
    var error by mutableStateOf<String?>(null)
    /** Called when the review ends, with whether flights were added (instead of opening them); see [Mail.review]. */
    var then: ((Boolean) -> Unit)? = null
    val active get() = busy || found != null || error != null
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
            TrackWorker.schedule(ctx)
            Imports.clear()
            Nav.pop()
            Toast.makeText(ctx, "Restored $added flights from backup", Toast.LENGTH_LONG).show()
            return@launch
        }
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
            Imports.to = Importer.recipient(t) { Prefs.workFor(it) != null }
            Imports.work = Importer.isWork(t, Imports.to, Prefs::workFor)
            Imports.found = found
            if (found.isEmpty()) Imports.error = "No flight numbers found in that document."
        }
        Imports.busy = false
    }
}

/** "UA1234", "ua 1234" or the ICAO form "UAL1234" -> ("UA", "1234"). */
fun parseFlightNumber(s: String): Pair<String, String>? {
    val t = s.uppercase().replace(" ", "")
    Regex("^([A-Z]{3})(\\d{1,4})$").find(t)?.let { m -> Db.airlinesByIcao[m.groupValues[1]]?.let { return it.iata to m.groupValues[2].trimStart('0') } }
    val m = Regex("^([A-Z][A-Z0-9]|[0-9][A-Z])(\\d{1,4})$").find(t) ?: return null
    return m.groupValues[1] to m.groupValues[2].trimStart('0')
}

/**
 * "John Q Doe", "Johnq Doe" (airlines glue the middle initial on) and "John Doe" are the same
 * person; "Jane Doe" isn't.
 */
fun sameName(a: String, b: String): Boolean {
    val x = a.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
    val y = b.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
    if (x.isEmpty() || y.isEmpty()) return false
    val (p, q) = x.first() to y.first()
    val first = p == q || (kotlin.math.abs(p.length - q.length) == 1 && (p.startsWith(q) || q.startsWith(p)))
    return first && (x.size == 1 || y.size == 1 || x.last() == y.last())
}

/** People to pick as the traveler: you (""), friends in saved flights, and the booking's name if it's someone new. */
fun travelerChoices(me: String, friends: List<String>, booking: String?): List<String> =
    listOf("") + friends + listOfNotNull(booking?.takeIf { b -> !(me.isNotBlank() && sameName(b, me)) && friends.none { sameName(it, b) } })

/**
 * Who an import is for until you say otherwise: you if the booking has your name, the friend it matches, or the name
 * on it. No name: you, or nobody yet (null) when adding from Friends.
 */
fun defaultTraveler(me: String, friends: List<String>, booking: String?, friendTab: Boolean): String? = when {
    booking == null -> if (friendTab) null else ""
    me.isNotBlank() && sameName(booking, me) -> ""
    else -> friends.firstOrNull { sameName(it, booking) } ?: booking
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
        val p = parsed ?: run { message = "Enter a flight number like UA 1234"; return }
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

/**
 * Review of an imported document: whose trip it is (from the name on the booking), personal or work, and which
 * flights to add. Everything read from the document is marked Auto and one tap from fixing; nothing is saved until "Add".
 */
@Composable
private fun ImportReview(s: Screen.Add, openPicker: () -> Unit) {
    val ctx = LocalContext.current
    val found = Imports.found.orEmpty()
    val traveler = Imports.traveler
    val friends = Store.friends
    var who by remember(found) { mutableStateOf(defaultTraveler(Prefs.name, friends, traveler, s.friend)) } // "" = me, null = not chosen
    var whoAuto by remember(found) { mutableStateOf(traveler != null) }
    // First time we see a name and don't know yours: ask once, and remember the answer.
    var askedWho by remember(found) { mutableStateOf(traveler == null || Prefs.name.isNotBlank()) }
    var work by remember(found) { mutableStateOf(Imports.work) }
    var items by remember(found) { mutableStateOf(found) }
    var edited by remember(found) { mutableStateOf(emptySet<String>()) } // "index:field" you changed, no longer Auto
    var picked by remember(found) { mutableStateOf(found.indices.filter { found[it].date != null }.toSet()) }
    var copies by remember(found) { mutableStateOf(emptySet<Int>()) } // already saved, but import separately anyway
    var adding by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<EditRequest?>(null) }
    val mine = who == ""
    val undated = picked.filter { items[it].date == null }

    fun cancel() { val then = Imports.then; Imports.clear(); Nav.pop(); then?.invoke(false) }
    fun change(i: Int, field: String, f: (Found) -> Found) { items = items.toMutableList().also { it[i] = f(it[i]) }; edited = edited + "$i:$field" }
    fun choose(name: String) { who = if (Prefs.name.isNotBlank() && sameName(name, Prefs.name)) "" else name; whoAuto = false }

    fun addAll() {
        val list = picked.sorted().map { i -> items[i] to (i in copies) }
        val owner = who.orEmpty()
        if (mine) Imports.to?.let { Prefs.setWorkFor(it, work) } // next email to this address starts the same way
        adding = true
        App.scope.launch {
            val ids = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            for ((f, copy) in list) {
                val route = if (f.from == null || f.to == null) Api.route(f.airline, f.number) else emptyList()
                val flight = f.toFlight(route, owner, work)
                if (flight == null) skipped += f.label else ids += addFlight(ctx, flight, asCopy = copy)
            }
            val then = Imports.then
            Imports.clear()
            if (then != null) { Nav.pop(); then(ids.isNotEmpty()) } else {
                Nav.open(if (owner.isEmpty()) Tab.Flights else Tab.Friends)
                if (ids.size == 1) Nav.push(Screen.FlightDetail(ids[0]))
            }
            Toast.makeText(
                ctx,
                "Added ${ids.size} flight${if (ids.size == 1) "" else "s"}" +
                    if (skipped.isNotEmpty()) " · couldn't place ${skipped.joinToString()} (no airports)" else "",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Page("Review Import", onBack = ::cancel, backLabel = "Cancel") {
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

        item("legend") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge("Auto", C.indigo)
                Text(
                    "  was read from the document. Tap any row to fix it; red means it's needed.",
                    fontSize = 13.sp, color = C.secondary,
                )
            }
        }

        if (!askedWho && traveler != null) item("isyou") {
            Group {
                Column(Modifier.padding(16.dp)) {
                    Text("Is this you?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("This booking is for $traveler.", fontSize = 15.sp, color = C.secondary, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button("Yes, that's me", Modifier.weight(1f)) { Prefs.name = traveler; who = ""; whoAuto = false; askedWho = true }
                        Button("Someone else", Modifier.weight(1f), filled = false) { askedWho = true }
                    }
                }
            }
            Footnote("Answering \"yes\" saves it as your name (Settings › About), so future imports know.")
        }

        if (askedWho) item("who") {
            SectionHeader("Who's flying")
            val me = if (Prefs.name.isNotBlank()) "Me · ${Prefs.name.substringBefore(' ')}" else "Me"
            val choices = travelerChoices(Prefs.name, friends, traveler).let { c -> who?.takeIf { it !in c }?.let { c + it } ?: c }
            val other = { edit = EditRequest("Who's flying", who.orEmpty()) { if (it.isNotBlank()) choose(it) } }
            Group {
                ReviewCell("Traveler", who?.let { if (it.isEmpty()) me else it }, whoAuto, required = true, onClick = other)
                Box(Modifier.padding(start = 16.dp, bottom = 12.dp)) {
                    Pills(choices.map { if (it.isEmpty()) me else it } + "Other…", choices.indexOf(who)) { i ->
                        if (i == choices.size) other() else { who = choices[i]; whoAuto = false }
                    }
                }
                if (mine) {
                    Hairline()
                    val to = Imports.to
                    val learned = to?.let(Prefs::workFor)
                    SwitchCell(
                        "Work trip", work,
                        when {
                            learned != null -> "Sent to $to, ${if (learned) "a work trip" else "personal"} last time"
                            Imports.work -> "Looks like a company booking"
                            to != null -> "Sent to $to · remembered for next time"
                            else -> "Counts separately in your passport"
                        },
                    ) { work = it }
                }
            }
        }

        items.forEachIndexed { i, f ->
            item("flight$i") {
                if (i == 0) SectionHeader("${items.size} flight${if (items.size == 1) "" else "s"} found")
                else Spacer(Modifier.height(12.dp))
                val on = i in picked
                fun auto(field: String) = "$i:$field" !in edited
                Group {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            // A flight with no date can't be saved; including it asks for one.
                            if (!on && f.date == null) pickDate(ctx, LocalDate.now()) { d -> change(i, "date") { it.copy(date = d) }; picked = picked + i }
                            else picked = if (on) picked - i else picked + i
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
                                when {
                                    on -> Db.airline(f.airline)?.name ?: "Will be added"
                                    f.date == null -> "No date found · tap to add it anyway"
                                    else -> "Skipped · tap to add"
                                },
                                fontSize = 14.sp, color = C.secondary,
                            )
                        }
                    }
                    if (on) {
                        Hairline()
                        ReviewCell("Flight", f.label, auto("number")) {
                            edit = EditRequest("Flight number", f.label, caps = true) { v ->
                                parseFlightNumber(v)?.let { (a, n) -> change(i, "number") { it.copy(airline = a, number = n) } }
                            }
                        }
                        Hairline()
                        ReviewCell("Date", f.date?.let(::dayLabel), auto("date"), required = true) {
                            pickDate(ctx, f.date ?: LocalDate.now()) { d -> change(i, "date") { it.copy(date = d) } }
                        }
                        Hairline()
                        ReviewCell("Route", if (f.from != null && f.to != null) "${f.from} → ${f.to}" else null, auto("route"), "Looked up when added") {
                            edit = EditRequest("Route, like PIT SFO", listOfNotNull(f.from, f.to).joinToString(" "), caps = true) { v ->
                                val c = Regex("[A-Za-z]{3}").findAll(v).map { it.value.uppercase() }.filter { Db.airport(it) != null }.toList()
                                if (c.size == 2 && c[0] != c[1]) change(i, "route") { it.copy(from = c[0], to = c[1]) }
                            }
                        }
                        Hairline()
                        ReviewCell("Times", f.dep?.let { "${fmt(it)} – ${f.arr?.let(::fmt) ?: "?"}" }, auto("times"), "From flight status") {
                            pickTime(ctx, f.dep ?: LocalTime.of(9, 0)) { d ->
                                pickTime(ctx, f.arr ?: d) { a -> change(i, "times") { it.copy(dep = d, arr = a) } }
                            }
                        }
                        Hairline()
                        ReviewCell("Seat", f.seat.ifEmpty { null }, auto("seat")) {
                            edit = EditRequest("Seat", f.seat, caps = true) { v -> change(i, "seat") { it.copy(seat = v.uppercase()) } }
                        }
                        Hairline()
                        ReviewCell("Confirmation", f.booking.ifEmpty { null }, auto("booking")) {
                            edit = EditRequest("Confirmation code", f.booking, caps = true) { v -> change(i, "booking") { it.copy(booking = v.uppercase()) } }
                        }
                        existingFor(f)?.let { old ->
                            val copy = i in copies
                            Hairline()
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    "Already saved (${if (old.mine) "yours" else old.friend}, via ${old.source}). " +
                                        if (copy) "Will be added as a copy." else "Will fill in the saved one.",
                                    fontSize = 13.sp, color = C.orange,
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
        }
        item("notes") {
            if (copies.isNotEmpty()) Footnote("Copies show up in Settings › Duplicates, where you can merge or keep them.", C.orange)
        }

        item("add") {
            Spacer(Modifier.height(16.dp))
            val n = picked.size
            val owner = who.orEmpty()
            Button(
                if (adding) "Adding…" else "Add $n flight${if (n == 1) "" else "s"}" + if (owner.isNotEmpty()) " for ${owner.substringBefore(' ')}" else "",
                Modifier.fillMaxWidth(), enabled = n > 0 && !adding && who != null && undated.isEmpty(),
            ) { addAll() }
            when {
                who == null -> Footnote("Choose who's flying.", C.red)
                undated.isNotEmpty() -> Footnote("Add a date for ${undated.joinToString { items[it].label }}.", C.red)
            }
        }
    }
    EditDialog(edit) { edit = null }
}

/** A value read from the document: marked Auto until you change it, red when it's [required] and missing. */
@Composable
private fun ReviewCell(title: String, value: String?, auto: Boolean, empty: String = "Add", required: Boolean = false, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    val missing = value == null && required
    Text(title, fontSize = 17.sp, color = if (missing) C.red else C.label)
    Spacer(Modifier.weight(1f))
    if (value != null && auto) {
        Badge("Auto", C.indigo)
        Spacer(Modifier.width(8.dp))
    }
    Text(
        value ?: if (required) "Required" else empty, fontSize = 17.sp, maxLines = 1,
        color = when { missing -> C.red; value == null -> C.tertiary; else -> C.secondary },
    )
    Icon(Icons.Filled.ChevronRight, null, tint = C.tertiary)
}

private fun fmt(t: LocalTime) = t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
