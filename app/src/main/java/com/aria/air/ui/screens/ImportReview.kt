package com.aria.air.ui.screens

// Reviewing an imported document's flights before anything is saved.

import com.aria.air.*
import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.importer.*
import com.aria.air.ui.components.*
import com.aria.air.util.*
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

/**
 * Review of an imported document: whose trip it is (from the name on the booking), personal or work, and which
 * flights to add. Everything read from the document is marked Auto and one tap from fixing; nothing is saved until "Add".
 */
@Composable
internal fun ImportReview(s: Screen.Add, openPicker: () -> Unit) {
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
                val route = if (f.from == null || f.to == null) AdsbLol.route(f.airline, f.number) else emptyList()
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
            Footnote("Values marked Auto were read from the document. Tap any row to change it; red rows need filling in.")
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

/** "9:05 AM". */
private fun fmt(t: LocalTime) = t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
