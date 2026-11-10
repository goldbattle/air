package com.aria.air.ui.screens

// Emails with flights in them, to review, merge or dismiss.

import com.aria.air.*
import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.importer.*
import com.aria.air.ui.components.*
import com.aria.air.util.*
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter

/**
 * Opens the first of [keys] in the import review. Adding it marks it imported and moves on to the next; Cancel
 * stops, leaving the rest ticked. Ones whose flights got saved along the way are skipped.
 */
fun reviewMail(keys: List<String>) {
    val queue = keys.mapNotNull { k -> Mail.hits.firstOrNull { it.key == k && it.state.isEmpty() } }
    val h = queue.firstOrNull() ?: return
    Imports.clear()
    Imports.found = h.flights
    Imports.traveler = Importer.traveler(h.text, Store.friends + Prefs.name)
    Imports.to = h.to
    Imports.work = Importer.isWork(h.text, h.to, Prefs::workFor)
    Imports.then = { added -> if (added) { Mail.mark(listOf(h.key), "i"); reviewMail(queue.drop(1).map { it.key }) } }
    Nav.push(Screen.Add(friend = false))
}

/** Emails with flights in them: new ones to tick and review, and ones already saved to merge or dismiss. */
@Composable
fun MailFoundScreen() {
    val ctx = LocalContext.current
    val list = Mail.suggestions
    val dupes = Mail.duplicates
    val picked = Mail.picked.filter { k -> list.any { it.key == k } }
    val dismissed = Mail.hits.count { it.state == "d" }
    var confirmAll by remember { mutableStateOf(false) }

    Page(
        "Found in Email", onBack = { Nav.pop() },
        // Pull down to search the mailboxes again.
        refreshing = Mail.progress != null, onRefresh = { if (Mail.accounts.isNotEmpty()) MailJobs.scanNow(ctx) },
        actions = {
            if (list.size > 1) Text(
                if (picked.size == list.size) "Select None" else "Select All", color = C.blue, fontSize = 17.sp,
                modifier = Modifier.clickable {
                    Mail.picked.clear()
                    if (picked.size != list.size) Mail.picked.addAll(list.map { it.key })
                }.padding(8.dp),
            )
        },
    ) {
        Mail.progress?.let { p ->
            item("progress") {
                Group {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.blue)
                        Spacer(Modifier.width(12.dp))
                        Text(p, fontSize = 15.sp)
                    }
                }
            }
        }
        if (list.isEmpty() && dupes.isEmpty()) item("empty") {
            Spacer(Modifier.height(8.dp))
            Group { Cell("No new flight emails", icon = Icons.Filled.CheckCircle, iconTint = C.green) }
            Footnote(if (Mail.accounts.isEmpty()) "Link an email account first." else "Emails you imported or dismissed aren't shown.")
            Spacer(Modifier.height(12.dp))
            if (Mail.accounts.isEmpty()) Button("Link an account", Modifier.fillMaxWidth()) { Nav.pop(); Nav.push(Screen.MailAccounts) }
            else Button("Scan now", Modifier.fillMaxWidth(), enabled = Mail.progress == null) { MailJobs.scanNow(ctx) }
        }

        if (list.isNotEmpty()) {
            item("new-head") {
                SectionHeader("${list.size} new")
            }
            list.forEach { h ->
                item(h.key) {
                    val on = h.key in picked
                    EmailCard(h, selected = on, onSelect = { if (on) Mail.picked.remove(h.key) else Mail.picked.add(h.key) }) {
                        Action("Dismiss", C.secondary) { Mail.mark(listOf(h.key), "d") }
                        Action("Review", C.blue) { reviewMail(listOf(h.key)) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            item("new-actions") {
                if (picked.size > 1) Button("Review ${picked.size} selected", Modifier.fillMaxWidth()) { reviewMail(picked) }
                Footnote("Review opens each email's flights to check and import, one at a time.")
            }
        }

        if (dupes.isNotEmpty()) {
            item("dupe-head") {
                SectionHeader("Already saved")
            }
            dupes.forEach { (h, adds) ->
                item(h.key) {
                    EmailCard(
                        h, saved = true,
                        note = if (adds.isEmpty()) "Nothing new: you have these flights with the same details."
                        else "Merging adds the " + adds.joinToString(" and ") + " from this email.",
                    ) {
                        Action("Dismiss", C.secondary) { Mail.mark(listOf(h.key), "d") }
                        Action("Import anyway", C.blue) { reviewMail(listOf(h.key)) }
                        if (adds.isNotEmpty()) Action("Merge", C.blue, bold = true) {
                            Mail.merge(h.key)
                            Toast.makeText(ctx, "Added the ${adds.joinToString(" and ")} to your saved flights", Toast.LENGTH_SHORT).show()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        item("footer") {
            if (list.isNotEmpty() || dupes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Button("Dismiss all", Modifier.fillMaxWidth(), filled = false, color = C.red) { confirmAll = true }
            }
            if (dismissed > 0) {
                Spacer(Modifier.height(10.dp))
                Button("Show $dismissed dismissed email${if (dismissed == 1) "" else "s"} again", Modifier.fillMaxWidth(), filled = false) {
                    Mail.restoreDismissed()
                }
            }
        }
    }

    if (confirmAll) {
        val all = list.map { it.key } + dupes.map { it.hit.key }
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Dismiss ${all.size} emails?") },
            text = { Text("They won't be suggested again unless you bring them back with Show dismissed emails again.") },
            confirmButton = { TextButton({ Mail.mark(all, "d"); confirmAll = false }) { Text("Dismiss all", color = C.red) } },
            dismissButton = { TextButton({ confirmAll = false }) { Text("Cancel") } },
        )
    }
}

private val hourMinute = DateTimeFormatter.ofPattern("h:mm a")

/** One email: subject and sender, its flights laid out like the Flights tab, and actions along the bottom. */
@Composable
private fun EmailCard(
    h: MailHit,
    selected: Boolean? = null,
    onSelect: () -> Unit = {},
    saved: Boolean = false,
    note: String? = null,
    actions: @Composable RowScope.() -> Unit,
) = Group {
    Row(
        Modifier.fillMaxWidth().then(if (selected != null) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selected != null) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                tint = if (selected) C.blue else C.tertiary, modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(h.subject.ifEmpty { "(no subject)" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(h.from, dayLabel(h.sent), h.account.takeIf { Mail.accounts.size > 1 }).joinToString(" · "),
                fontSize = 13.sp, color = C.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    h.flights.take(3).forEach { f ->
        Hairline()
        FoundRow(f, saved)
    }
    if (h.flights.size > 3) {
        Hairline()
        Text("+${h.flights.size - 3} more flights", fontSize = 13.sp, color = C.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
    note?.let {
        Hairline()
        Text(it, fontSize = 13.sp, color = C.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
    Hairline(0.dp)
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
}

/** A text button sharing the card's bottom row equally with the others. */
@Composable
private fun RowScope.Action(text: String, color: androidx.compose.ui.graphics.Color, bold: Boolean = false, onClick: () -> Unit) = Box(
    Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick), contentAlignment = Alignment.Center,
) {
    Text(text, color = color, fontSize = 15.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
}

/** "DL 400 / DTW → PWM" on the left, the day and times on the right, as on a flight card. */
@Composable
private fun FoundRow(f: Found, saved: Boolean) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
) {
    AirlineBadge(f.airline)
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.secondary)
            if (saved) {
                Spacer(Modifier.width(6.dp))
                Badge("Saved", C.green)
            }
        }
        Text(
            if (f.from != null && f.to != null) "${f.from} → ${f.to}" else "Route looked up when you review it",
            fontSize = if (f.from != null && f.to != null) 20.sp else 14.sp, fontWeight = FontWeight.Bold,
        )
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(f.date?.let(::dayLabel) ?: "No date", fontSize = 15.sp, color = if (f.date == null) C.red else C.label)
        if (f.dep != null) Text(
            f.dep.format(hourMinute) + (f.arr?.let { " – " + it.format(hourMinute) } ?: ""),
            fontSize = 13.sp, color = C.secondary,
        )
    }
}
