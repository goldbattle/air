package com.aria.air.importer

// Scanning linked mailboxes for flight confirmations, entirely from the phone.

import com.aria.air.api.*
import com.aria.air.background.*
import com.aria.air.data.*
import com.aria.air.util.*
import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A linked mailbox. [secret] is the app password, encrypted by [Vault]. At the last scan [matched] of the mailbox's
 * [total] emails matched the booking search.
 */
data class MailAccount(
    val email: String, val host: String, val secret: String,
    val scanned: Long = 0, val error: String = "", val matched: Long = 0, val total: Long = 0,
) {
    /** "180 of 9,412 emails looked like bookings". */
    val summary: String
        get() = if (total > 0) "%,d of %,d emails looked like bookings".format(matched, total) else ""
}

/** An email with flights in it. [state]: "" suggested, "i" imported, "d" dismissed (the history: never suggested again). */
data class MailHit(
    val account: String, val id: String, val subject: String, val from: String, val to: String,
    val sent: LocalDate, val text: String, val state: String = "",
) {
    val key get() = "$account|$id"

    /** Parsed as of the day it arrived, so dates without a year land in the right one. */
    val flights: List<Found> by lazy { Importer.parse(text, sent) }
}

/** Emails to suggest: not imported or dismissed, with at least one flight that isn't [saved] yet. Newest first. */
fun suggest(hits: List<MailHit>, saved: (Found) -> Boolean): List<MailHit> =
    hits.filter { h -> h.state.isEmpty() && h.flights.any { !saved(it) } }.sortedByDescending { it.sent }

/** An email whose flights are all saved already; [adds] is what merging it would fill in ("seat", ...). */
data class MailDupe(val hit: MailHit, val adds: List<String>)

/** "UA 1000 SFO→PIT · Oct 3" for each flight. */
fun MailHit.summary(): String {
    val shown = flights.take(3).joinToString("\n") { f ->
        f.label + (if (f.from != null && f.to != null) " ${f.from}→${f.to}" else "") + (f.date?.let { " · " + dayLabel(it) } ?: "")
    }
    return if (flights.size > 3) "$shown\n+${flights.size - 3} more" else shown
}

/**
 * Scans linked mailboxes for flight confirmations, entirely from the phone: it signs in to the mail server directly
 * with an app password, searches on the server, and downloads only the matching emails' text.
 */
object Mail {
    const val GMAIL = "imap.gmail.com"

    /** Linked mailboxes. */
    val accounts = mutableStateListOf<MailAccount>()

    /** Every email found with flights in it, including the imported and dismissed ones (the history). */
    val hits = mutableStateListOf<MailHit>()

    /** Keys ticked on the found-emails page; outlives that page while you step through the reviews. */
    val picked = mutableStateListOf<String>()

    /** What a running scan is doing ("Reading x: 12 of 180"), or null when idle. */
    var progress by mutableStateOf<String?>(null)
    private lateinit var file: File
    private val lock = Mutex()

    /** Loads linked accounts and the email history from files/mail.json; call once at startup. */
    fun init(ctx: Context) {
        file = File(ctx.filesDir, "mail.json")
        if (!file.exists()) return
        runCatching {
            val j = JSONObject(file.readText())
            j.optJSONArray("accounts")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).run {
                    accounts += MailAccount(
                        getString("email"), optString("host", GMAIL), getString("secret"),
                        optLong("scanned"), optString("error"), optLong("matched"), optLong("total"),
                    )
                }
            }
            j.optJSONArray("hits")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).run {
                    hits += MailHit(
                        getString("account"), getString("id"), optString("subject"), optString("from"), optString("to"),
                        LocalDate.parse(getString("sent")), optString("text"), optString("state"),
                    )
                }
            }
        }
    }

    /** Main thread only. Kept out of the flights backup: passwords and email text never leave the phone. */
    private fun save() = synchronized(this) {
        val j = JSONObject()
            .put("accounts", JSONArray(accounts.map {
                JSONObject().put("email", it.email).put("host", it.host).put("secret", it.secret)
                    .put("scanned", it.scanned).put("error", it.error).put("matched", it.matched).put("total", it.total)
            }))
            .put("hits", JSONArray(hits.map {
                JSONObject().put("account", it.account).put("id", it.id).put("subject", it.subject).put("from", it.from)
                    .put("to", it.to).put("sent", it.sent.toString()).put("text", it.text).put("state", it.state)
            }))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(file)
    }

    /** Emails worth suggesting: not acted on, with a flight that isn't saved yet. */
    val suggestions get() = suggest(hits) { existingFor(it) != null }

    /** Emails not yet acted on whose flights are all saved, newest first. */
    val duplicates: List<MailDupe>
        get() = hits.filter { h -> h.state.isEmpty() && h.flights.isNotEmpty() && h.flights.all { existingFor(it) != null } }
            .sortedByDescending { it.sent }
            .map { h -> MailDupe(h, h.flights.flatMap { f -> existingFor(f)?.let { mergeAdds(it, f) }.orEmpty() }.distinct()) }

    /** Fills the saved flights' missing details (seat, confirmation, times) from the email [key], then files it. */
    fun merge(key: String) {
        val h = hits.firstOrNull { it.key == key } ?: return
        for (f in h.flights) {
            val old = existingFor(f) ?: continue
            f.toFlight(listOf(old.from, old.to), old.friend, old.work)?.let { Store.upsert(old.fillFrom(it)) }
        }
        mark(listOf(key), "i")
    }

    /** Links an account. The app password is encrypted with a key in the Android Keystore before it's saved. */
    fun add(ctx: Context, email: String, host: String, password: String) {
        accounts.removeAll { it.email == email }
        accounts += MailAccount(email, host, Vault.encrypt(password.filterNot { it.isWhitespace() }))
        save(); MailJobs.schedule(ctx)
    }

    /** Forgets the account and its password. Its import/dismiss history stays, so a re-link doesn't re-suggest. */
    fun remove(ctx: Context, email: String) {
        accounts.removeAll { it.email == email }
        hits.removeAll { it.account == email && it.state.isEmpty() }
        picked.removeAll { it.startsWith("$email|") }
        save(); MailJobs.schedule(ctx)
    }

    /** Saves a scan's result for [a], unless the account was removed meanwhile. */
    private fun put(a: MailAccount) {
        val i = accounts.indexOfFirst { it.email == a.email }
        if (i >= 0) { accounts[i] = a; save() } // removed mid-scan: drop it
    }

    /**
     * Files emails as imported ("i", their text dropped) or dismissed ("d"); either way they're never suggested again.
     */
    fun mark(keys: Collection<String>, state: String) {
        hits.replaceAll { if (it.key in keys) it.copy(state = state, text = if (state == "i") "" else it.text) else it }
        picked.removeAll(keys)
        save()
    }

    /** Brings every dismissed email back into the suggestions. */
    fun restoreDismissed() { hits.replaceAll { if (it.state == "d") it.copy(state = "") else it }; save() }

    /** Signs in and opens the mailbox; null when it works, else what went wrong. */
    suspend fun test(email: String, host: String, password: String): String? = withContext(Dispatchers.IO) {
        runCatching { Imap.connect(host).use { it.login(email, password.filterNot { it.isWhitespace() }); it.examine("INBOX") }; null }.getOrElse(::friendly)
    }

    /** Tests a linked account with its stored password; null when it works. */
    suspend fun test(a: MailAccount): String? = runCatching { Vault.decrypt(a.secret) }.fold({ test(a.email, a.host, it) }, ::friendly)

    /** An error as something the user can act on: wrong password, needs an app password, no connection. */
    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("AUTHENTICATIONFAILED", true) || m.contains("Invalid credentials", true) -> "Wrong email or app password"
            m.contains("Application-specific password", true) -> "Needs an app password, not your Google password"
            e is java.net.UnknownHostException -> "No connection"
            e is java.net.SocketTimeoutException -> "The server didn't answer"
            e is javax.crypto.AEADBadTagException || e is java.security.GeneralSecurityException -> "Password unreadable; remove and add the account again"
            else -> "Couldn't connect: ${e.javaClass.name}${if (m.isNotEmpty()) " ($m)" else ""}".also { android.util.Log.w("Air", "mail", e) }
        }
    }

    /** Scans every account for new mail. Returns how many new flights were found. */
    suspend fun scanAll(ctx: Context): Int = lock.withLock {
        var n = 0
        for (a in withContext(Dispatchers.Main) { accounts.toList() }) {
            n += runCatching { scan(ctx, a) }.getOrElse { e ->
                withContext(Dispatchers.Main) {
                    accounts.firstOrNull { it.email == a.email }?.let { put(it.copy(error = friendly(e), scanned = System.currentTimeMillis())) }
                }
                0
            }
        }
        progress = null
        n
    }

    /**
     * Scans one account: signs in, searches the whole mailbox on the server, and reads only emails it hasn't seen.
     * Returns how many new flights turned up.
     */
    private suspend fun scan(ctx: Context, account: MailAccount): Int = withContext(Dispatchers.IO) {
        var a = account
        var added = 0
        val known = withContext(Dispatchers.Main) { hits.map { it.key }.toHashSet() }
        progress = "Signing in to ${a.email}…"
        Imap.connect(a.host).use { im ->
            im.login(a.email, Vault.decrypt(a.secret))
            val gmail = "X-GM-EXT-1" in im.caps()
            val box = im.examine(if (gmail) im.allMail() else "INBOX")
            // The whole mailbox every time: the server does the searching, and only a few hundred emails match.
            // Ones already suggested, imported or dismissed are skipped before their text is downloaded.
            progress = "Searching ${a.email}…"
            val uids = im.search(if (gmail) MailQuery.gmail() else MailQuery.standard()).distinct().sorted()
            var done = 0
            for (chunk in uids.chunked(20)) {
                val heads = im.fetch(chunk.joinToString(","), "INTERNALDATE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT MESSAGE-ID)]")
                val found = chunk.mapNotNull { uid ->
                    progress = "Reading ${a.email}: ${++done} of ${uids.size}"
                    heads[uid]?.let { read(ctx, im, a.email, box.validity, uid, it, known) }
                }
                withContext(Dispatchers.Main) {
                    hits += found
                    added += suggest(found) { existingFor(it) != null }.sumOf { it.flights.size }
                    save()
                }
            }
            a = a.copy(scanned = System.currentTimeMillis(), error = "", matched = uids.size.toLong(), total = box.exists)
            withContext(Dispatchers.Main) { put(a) }
        }
        added
    }

    /** Reads one email's text (plain, else HTML, else PDF attachment) and keeps it if flights are in it. */
    private suspend fun read(
        ctx: Context, im: Imap, account: String, validity: Long, uid: Long, kv: Map<String, Any?>, known: MutableSet<String>,
    ): MailHit? {
        val h = Mime.headers(kv.entries.firstOrNull { it.key.startsWith("BODY[HEADER") }?.value as? String ?: "")
        val id = h["message-id"]?.trim()?.trim('<', '>')?.ifEmpty { null } ?: "uid:$validity:$uid"
        if (!known.add("$account|$id")) return null // already suggested, imported or dismissed
        val sent = Mime.internalDate(kv["INTERNALDATE"] as? String) ?: LocalDate.now()
        for (p in Mime.readable(Mime.parts(kv["BODYSTRUCTURE"] as? List<*> ?: return null))) {
            val raw = im.fetch("$uid", "BODY.PEEK[${p.section}]")[uid]?.entries?.firstOrNull { it.key.startsWith("BODY[") }?.value as? String ?: continue
            val text = when (p.type) {
                "text/plain" -> Mime.text(raw, p.encoding, p.charset)
                "text/html" -> Mime.html(Mime.text(raw, p.encoding, p.charset))
                else -> pdfText(ctx, Mime.bytes(raw, p.encoding))
            }
            if (Importer.parse(text, sent).isEmpty()) continue
            val to = h["to"]?.let(Mime::address)?.takeIf { '@' in it } ?: account
            return MailHit(account, id, h["subject"].orEmpty(), Mime.sender(h["from"].orEmpty()), to, sent, text.take(100_000))
        }
        return null
    }

    /** The text of a PDF attachment, read through a temporary file. */
    private suspend fun pdfText(ctx: Context, bytes: ByteArray): String {
        val f = File(ctx.cacheDir, "mail.pdf")
        return try {
            f.writeBytes(bytes)
            runCatching { Importer.text(ctx, Uri.fromFile(f)) }.getOrDefault("")
        } finally { f.delete() }
    }
}

/** App passwords, encrypted with an AES-GCM key that lives in (and never leaves) the Android Keystore. */
private object Vault {
    private const val ALIAS = "mail"

    /**
     * The AES key in the Android Keystore, created on first use. It can't leave the Keystore, so the passwords only
     * decrypt on this phone.
     */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
    }

    /** IV (12 bytes) + ciphertext, base64. */
    fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.getEncoder().encodeToString(c.iv + c.doFinal(s.toByteArray()))
    }

    /** Decrypts what [encrypt] made; throws when the key is gone (the app was reinstalled). */
    fun decrypt(s: String): String {
        val b = Base64.getDecoder().decode(s)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}

/** What to ask the server for. */
object MailQuery {
    private val senders = listOf(
        "united.com", "delta.com", "aa.com", "southwest.com", "jetblue.com", "alaskaair.com", "hawaiianairlines.com",
        "spirit.com", "flyfrontier.com", "suncountry.com", "aircanada.com", "westjet.com", "ba.com", "britishairways.com",
        "lufthansa.com", "swiss.com", "austrian.com", "airfrance.fr", "airfrance.com", "klm.com", "iberia.com", "aerlingus.com",
        "virginatlantic.com", "turkishairlines.com", "emirates.com", "qatarairways.com", "etihad.com", "singaporeair.com",
        "cathaypacific.com", "ana.co.jp", "jal.com", "koreanair.com", "evaair.com", "qantas.com", "airnewzealand.com",
        "aeromexico.com", "latam.com", "avianca.com", "copaair.com", "ryanair.com", "easyjet.com", "wizzair.com", "norwegian.com",
        "icelandair.com", "tap.pt", "sas.se", "finnair.com", "flyporter.com", "breezeairways.com", "allegiantair.com",
        "expedia.com", "booking.com", "priceline.com", "orbitz.com", "travelocity.com", "kayak.com", "hopper.com", "trip.com",
        "kiwi.com", "cheapoair.com", "edreams.com", "flighthub.com", "amextravel.com",
        "navan.com", "concur.com", "egencia.com", "tripit.com",
    )
    private val subjects = listOf(
        "itinerary", "confirmation", "confirmed", "e-ticket", "eticket", "ticket", "receipt", "reservation", "booking", "trip",
        "travel", "\"boarding pass\"", "\"check in\"", "check-in", "flight", "fwd", "fw",
    )

    /** Phrases in the body of a booking, for confirmations from senders not listed and forwarded ones. */
    private val phrases = listOf(
        "\"confirmation code\"", "\"confirmation number\"", "\"record locator\"", "\"booking reference\"",
        "\"reservation code\"", "\"ticket number\"", "\"e-ticket\"", "\"flight confirmation\"", "itinerary",
    )

    /**
     * Gmail search over the whole mailbox: mail from an airline or travel site, with a booking-like subject, or with
     * booking phrases in it, that mentions a flight. Deliberately broad (Promotions included, where some airlines'
     * confirmations land); the parser drops whatever has no flight in it.
     */
    val gmail = "{" + (senders.map { "from:$it" } + subjects.map { "subject:$it" } + phrases).joinToString(" ") + "} " +
        "{flight flights \"boarding pass\" \"e-ticket\" departure departs}"

    /** UID SEARCH criteria for Gmail (its X-GM-RAW extension takes the query as typed in Gmail's search box). */
    fun gmail() = "X-GM-RAW " + Imap.quote(gmail)

    /** Any other IMAP server: booking-like subjects. */
    fun standard() = subjects.map { "SUBJECT " + Imap.quote(it.trim('"')) }.reduce { a, b -> "OR $a $b" }
}
