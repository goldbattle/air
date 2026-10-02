package com.aria.air

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

private val SCHEDULE = listOf(0 to "Manual", 1 to "Weekly", 2 to "2 weeks", 4 to "4 weeks")

/** Linked mailboxes: add/remove, how to get an app password, schedule, scan now, history. */
@Composable
fun MailScreen() {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(Mail.accounts.isEmpty()) }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(Mail.GMAIL) }
    var test by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<MailAccount?>(null) }
    val ok = '@' in email && pass.isNotBlank() && host.isNotBlank()
    fun browse(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }

    if (adding) {
        // Linking is its own page: three steps top to bottom, one button at the end.
        fun back() { if (Mail.accounts.isEmpty()) Nav.pop() else { adding = false; test = null } }
        BackHandler { back() }
        Page("Add Account", onBack = ::back, backLabel = if (Mail.accounts.isEmpty()) "Settings" else "Email") {
            item("steps") {
                Footnote(
                    "Air reads booking emails straight from Gmail on this phone with an app password. " +
                        "No Google sign-in screen or cloud service is involved.",
                )
                SectionHeader("1. Turn on 2-Step Verification")
                Group {
                    Cell("Open Google account security", chevron = true) { browse("https://myaccount.google.com/signinoptions/twosv") }
                }
                Footnote("Skip this if it's already on. Google only offers app passwords with it.")
                SectionHeader("2. Create an app password")
                Group {
                    Cell("Open App passwords", chevron = true) { browse("https://myaccount.google.com/apppasswords") }
                }
                Footnote("Name it \"Air\" and tap Create. Copy the 16-letter password Google shows.")
                SectionHeader("3. Enter it here")
                Group {
                    FieldCell("Gmail address", email, "you@gmail.com", keyboard = KeyboardType.Email) { email = it.trim().lowercase(); test = null }
                    Hairline()
                    FieldCell("App password", pass, "16 letters", secret = true) { pass = it; test = null }
                    if (host != Mail.GMAIL) {
                        Hairline()
                        FieldCell("IMAP server", host, "imap.example.com", keyboard = KeyboardType.Uri) { host = it.trim().lowercase(); test = null }
                    }
                }
                test?.let { Footnote(it) }
                Spacer(Modifier.height(12.dp))
                Button(if (testing) "Checking…" else "Link account", Modifier.fillMaxWidth(), enabled = ok && !testing) {
                    testing = true
                    App.scope.launch {
                        val err = Mail.test(email, host, pass)
                        testing = false
                        if (err != null) { test = err; return@launch }
                        Mail.add(ctx, email, host, pass)
                        Toast.makeText(ctx, "Linked $email. Tap Scan now to search it.", Toast.LENGTH_LONG).show()
                        email = ""; pass = ""; host = Mail.GMAIL; test = null; adding = false
                    }
                }
                Footnote(
                    "The password is encrypted on this phone and never backed up or exported. To revoke it later, delete it " +
                        "under App passwords.",
                )
                if (host == Mail.GMAIL) Text(
                    "Not Gmail? Use another IMAP server", color = C.blue, fontSize = 15.sp,
                    modifier = Modifier.clickable { host = "" }.padding(16.dp),
                )
            }
        }
    } else Page("Email", onBack = { Nav.pop() }, backLabel = "Settings") {
        item("found") {
            Group {
                Cell("Flights found in email", "${Mail.suggestions.size}", chevron = true) { Nav.push(Screen.MailFound) }
                Hairline()
                val busy = Mail.progress != null
                Cell(Mail.progress ?: "Scan now", color = if (busy) C.secondary else C.blue) {
                    if (!busy) Mail.scanNow(ctx)
                }
            }
            Footnote(
                "Air searches your mailbox for airline and travel emails and downloads only those, straight to this " +
                    "phone. Nothing is marked read, moved or deleted. The first scan covers all your mail, later ones only " +
                    "what's new.",
            )
        }

        item("accounts") {
            SectionHeader("Accounts")
            Group {
                Mail.accounts.forEachIndexed { i, a ->
                    if (i > 0) Hairline()
                    Cell(
                        a.email, chevron = true,
                        subtitle = a.error.ifEmpty {
                            if (a.scanned > 0) "Scanned ${ago(Instant.ofEpochMilli(a.scanned))}" + (a.summary.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "")
                            else "Not scanned yet"
                        },
                    ) { open = a; test = null }
                }
                Hairline()
                Cell("Add account", color = C.blue) { adding = true }
            }
        }

        item("schedule") {
            SectionHeader("Scan automatically")
            Group {
                Column(Modifier.padding(12.dp)) {
                    val weeks = Prefs["mailWeeks"].toIntOrNull() ?: 0
                    Segmented(SCHEDULE.map { it.second }, SCHEDULE.indexOfFirst { it.first == weeks }.coerceAtLeast(0)) {
                        Prefs["mailWeeks"] = SCHEDULE[it].first.toString(); Mail.schedule(ctx)
                    }
                }
            }
            Footnote("In the background when there's a connection. You get a notification when new flights turn up.")
        }

        item("history") {
            SectionHeader("History")
            val dismissed = Mail.hits.count { it.state == "d" }
            Group {
                Cell("Imported", "${Mail.hits.count { it.state == "i" }}")
                Hairline()
                Cell("Dismissed", "$dismissed")
                if (dismissed > 0) {
                    Hairline()
                    Cell("Show dismissed emails again", color = C.blue) { Mail.restoreDismissed() }
                }
            }
            Footnote("Emails you import or dismiss are remembered and never suggested again.")
        }
    }

    open?.let { a ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(a.email) },
            text = {
                Text(
                    (test?.let { "Test: $it\n\n" } ?: "") + "Remove deletes this account and its app password from the phone. " +
                        "To revoke the password itself, delete it at myaccount.google.com/apppasswords.",
                )
            },
            confirmButton = {
                TextButton({ test = "Testing…"; App.scope.launch { test = Mail.test(a) ?: "Works" } }) { Text("Test connection") }
            },
            dismissButton = {
                TextButton({ Mail.remove(ctx, a.email); open = null }) { Text("Remove", color = C.red) }
            },
        )
    }
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
        refreshing = Mail.progress != null, onRefresh = { if (Mail.accounts.isNotEmpty()) Mail.scanNow(ctx) },
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
            else Button("Scan now", Modifier.fillMaxWidth(), enabled = Mail.progress == null) { Mail.scanNow(ctx) }
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
                        Action("Review", C.blue) { Mail.review(listOf(h.key)) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            item("new-actions") {
                if (picked.size > 1) Button("Review ${picked.size} selected", Modifier.fillMaxWidth()) { Mail.review(picked) }
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
                        Action("Import anyway", C.blue) { Mail.review(listOf(h.key)) }
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
            if (f.from != null && f.to != null) "${f.from} → ${f.to}" else "Route looked up on import",
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
