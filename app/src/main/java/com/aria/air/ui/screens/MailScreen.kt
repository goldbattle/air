package com.aria.air.ui.screens

// Linked mailboxes: adding one with an app password, scanning, schedule, history.

import com.aria.air.*
import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.importer.*
import com.aria.air.ui.components.*
import com.aria.air.util.*
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import java.time.Instant
import kotlinx.coroutines.launch

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
                    if (!busy) MailJobs.scanNow(ctx)
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
                        Prefs["mailWeeks"] = SCHEDULE[it].first.toString(); MailJobs.schedule(ctx)
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
