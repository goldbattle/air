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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import java.time.Instant

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
                    Cell("Open Google account security", icon = Icons.Filled.Security, chevron = true) { browse("https://myaccount.google.com/signinoptions/twosv") }
                }
                Footnote("Skip this if it's already on. Google only offers app passwords with it.")
                SectionHeader("2. Create an app password")
                Group {
                    Cell("Open App passwords", icon = Icons.Filled.Key, chevron = true) { browse("https://myaccount.google.com/apppasswords") }
                }
                Footnote("Name it \"Air\" and tap Create. Copy the 16-letter password Google shows.")
                SectionHeader("3. Enter it here")
                Group {
                    FieldCell("Gmail address", email, "you@gmail.com") { email = it.trim().lowercase(); test = null }
                    Hairline()
                    FieldCell("App password", pass, "16 letters", secret = true) { pass = it; test = null }
                    if (host != Mail.GMAIL) {
                        Hairline()
                        FieldCell("IMAP server", host, "imap.example.com") { host = it.trim().lowercase(); test = null }
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
                Cell("Flights found in email", "${Mail.suggestions.size}", icon = Icons.Filled.Inbox, chevron = true) { Nav.push(Screen.MailFound) }
                Hairline(50.dp)
                val busy = Mail.progress != null
                Cell(Mail.progress ?: "Scan now", icon = Icons.Filled.Refresh, color = if (busy) C.secondary else C.blue) {
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
                    if (i > 0) Hairline(50.dp)
                    Cell(
                        a.email, icon = Icons.Filled.AlternateEmail, chevron = true,
                        subtitle = a.error.ifEmpty { if (a.scanned > 0) "Scanned ${ago(Instant.ofEpochMilli(a.scanned))}" else "Not scanned yet" },
                    ) { open = a; test = null }
                }
                Hairline(50.dp)
                Cell("Add account", icon = Icons.Filled.Add, color = C.blue) { adding = true }
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
                    Cell("Suggest dismissed emails again", color = C.blue) { Mail.restoreDismissed() }
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

/** Emails with flights in them: tick the ones to import, then review them one by one. */
@Composable
fun MailFoundScreen() {
    val ctx = LocalContext.current
    val list = Mail.suggestions
    val picked = Mail.picked.filter { k -> list.any { it.key == k } }
    var confirmAll by remember { mutableStateOf(false) }

    Page(
        "Found in Email", onBack = { Nav.pop() },
        actions = {
            if (list.isNotEmpty()) Text(
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
        if (list.isEmpty()) {
            item("empty") {
                Spacer(Modifier.height(8.dp))
                Group { Cell("No new flight emails", icon = Icons.Filled.CheckCircle, iconTint = C.green) }
                Footnote(
                    if (Mail.accounts.isEmpty()) "Link an email account first."
                    else "Emails you imported or dismissed, and ones whose flights are already saved, aren't shown.",
                )
                Spacer(Modifier.height(12.dp))
                if (Mail.accounts.isEmpty()) Button("Link an account", Modifier.fillMaxWidth()) { Nav.pop(); Nav.push(Screen.MailAccounts) }
                else Button("Scan now", Modifier.fillMaxWidth(), enabled = Mail.progress == null) { Mail.scanNow(ctx) }
            }
            return@Page
        }
        item("list") {
            SectionHeader("${list.size} email${if (list.size == 1) "" else "s"} with flights")
            Group {
                list.forEachIndexed { i, h ->
                    if (i > 0) Hairline(52.dp)
                    val on = h.key in picked
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) Mail.picked.remove(h.key) else Mail.picked.add(h.key) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (on) C.blue else C.tertiary, modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.subject.ifEmpty { "(no subject)" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(h.from, dayLabel(h.sent), h.account.takeIf { Mail.accounts.size > 1 }).joinToString(" · "),
                                fontSize = 13.sp, color = C.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(h.summary(), fontSize = 14.sp, color = C.indigo, modifier = Modifier.padding(top = 3.dp))
                            Text(
                                "Dismiss", fontSize = 13.sp, color = C.secondary, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp).clickable { Mail.mark(listOf(h.key), "d") },
                            )
                        }
                    }
                }
            }
            Footnote("Tick the ones that are your flights, then review and import them one at a time.")
        }
        item("actions") {
            Spacer(Modifier.height(16.dp))
            Button("Review ${picked.size} selected", Modifier.fillMaxWidth(), enabled = picked.isNotEmpty()) { Mail.review(picked) }
            Spacer(Modifier.height(10.dp))
            Button("Dismiss all", Modifier.fillMaxWidth(), filled = false, color = C.red) { confirmAll = true }
        }
    }

    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text("Dismiss ${list.size} emails?") },
        text = { Text("They won't be suggested again. You can bring them back from Settings › Backup › Find flights in email.") },
        confirmButton = { TextButton({ Mail.mark(list.map { it.key }, "d"); confirmAll = false }) { Text("Dismiss all", color = C.red) } },
        dismissButton = { TextButton({ confirmAll = false }) { Text("Cancel") } },
    )
}
