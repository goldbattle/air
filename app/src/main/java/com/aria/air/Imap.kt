package com.aria.air

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private val LATIN1 = Charsets.ISO_8859_1

/**
 * Just enough read-only IMAP to find flight emails: LOGIN, EXAMINE (read-only), UID SEARCH and UID FETCH with
 * BODY.PEEK, so nothing is marked read or changed. Bytes are kept as Latin-1 strings so literals stay byte-exact
 * until [Mime] decodes them.
 */
class Imap(private val sock: Socket) : Closeable {
    class Error(msg: String) : IOException(msg)

    /** One response: its text, with each `{n}` literal's bytes in [literals], in order. */
    class Resp(val line: String, val literals: List<String>) {
        val list by lazy { sexp(line, literals) }
    }

    data class Box(val validity: Long, val uidNext: Long)

    private val input = BufferedInputStream(sock.getInputStream())
    private val out = sock.getOutputStream()
    private var tag = 0

    init {
        val hello = read().line
        if (!hello.startsWith("* OK") && !hello.startsWith("* PREAUTH")) throw Error(hello)
    }

    private fun readLine(): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw EOFException("Connection closed")
            if (c == '\n'.code) break
            b.write(c)
        }
        return String(b.toByteArray(), LATIN1).removeSuffix("\r")
    }

    private fun read(): Resp {
        val line = StringBuilder()
        val lits = mutableListOf<String>()
        while (true) {
            val l = readLine()
            line.append(l)
            val n = literalAtEnd.find(l)?.groupValues?.get(1)?.toInt() ?: break
            lits += String(input.readNBytes(n).also { if (it.size < n) throw EOFException("Connection closed") }, LATIN1)
        }
        return Resp(line.toString(), lits)
    }

    /** Sends a command and returns its untagged responses; a NO or BAD throws with the server's text. */
    fun cmd(c: String): List<Resp> {
        val t = "a${++tag}"
        out.write("$t $c\r\n".toByteArray(LATIN1))
        out.flush()
        val got = mutableListOf<Resp>()
        while (true) {
            val r = read()
            if (!r.line.startsWith("$t ")) { got += r; continue }
            if (r.line.startsWith("$t OK", ignoreCase = true)) return got
            // Never echo the command: for LOGIN it holds the password.
            throw Error(r.line.substringAfter(' ').substringAfter(' '))
        }
    }

    fun login(user: String, pass: String) { cmd("LOGIN ${quote(user)} ${quote(pass)}") }

    fun caps(): Set<String> = cmd("CAPABILITY").filter { it.line.startsWith("* CAPABILITY", true) }
        .flatMap { it.line.split(' ').drop(2) }.map { it.uppercase() }.toSet()

    /** Gmail's All Mail (its name is localized; it's flagged \All), else INBOX. */
    fun allMail(): String = cmd("LIST \"\" \"*\"").map { it.list }.firstOrNull { l ->
        (l.getOrNull(2) as? List<*>)?.any { "\\All".equals(it as? String, true) } == true
    }?.getOrNull(4) as? String ?: "INBOX"

    fun examine(box: String): Box {
        val rs = cmd("EXAMINE ${quote(box)}")
        fun num(k: String) = rs.firstNotNullOfOrNull { Regex("\\[$k (\\d+)\\]",RegexOption.IGNORE_CASE).find(it.line) }?.groupValues?.get(1)?.toLong() ?: 0
        return Box(num("UIDVALIDITY"), num("UIDNEXT"))
    }

    fun search(criteria: String): List<Long> = cmd("UID SEARCH $criteria").filter { it.line.startsWith("* SEARCH", true) }
        .flatMap { it.line.split(' ').drop(2).mapNotNull(String::toLongOrNull) }

    /** UID -> its fetched items by upper-cased name ("UID", "BODYSTRUCTURE", "BODY[1]", ...). */
    fun fetch(uids: String, items: String): Map<Long, Map<String, Any?>> = cmd("UID FETCH $uids ($items)").mapNotNull { r ->
        val l = r.list
        if (!"FETCH".equals(l.getOrNull(2) as? String, true)) return@mapNotNull null
        val kv = (l.getOrNull(3) as? List<*>).orEmpty().chunked(2).associate { (it[0] as? String).orEmpty().uppercase() to it.getOrNull(1) }
        (kv["UID"] as? String)?.toLongOrNull()?.let { it to kv }
    }.toMap()

    override fun close() {
        runCatching { cmd("LOGOUT") }
        runCatching { sock.close() }
    }

    companion object {
        // Android's regex engine (ICU), unlike the JVM's, rejects an unescaped '}' or ']'.
        private val literalAtEnd = Regex("\\{(\\d+)\\}$")
        private val literal = Regex("\\{(\\d+)\\}")

        /** TLS on port 993, with the certificate checked against the host name. */
        fun connect(host: String): Imap {
            // Emulator testing against a stub on the host machine, in debug builds only (R8 drops this in release).
            if (BuildConfig.DEBUG && host == "10.0.2.2") return Imap(Socket().apply { connect(InetSocketAddress(host, 1143), 20_000); soTimeout = 60_000 })
            // Connect first (for the timeout), then layer TLS on top naming the host, so the server gets SNI.
            val plain = Socket().apply { connect(InetSocketAddress(host, 993), 20_000); soTimeout = 60_000 }
            val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, 993, true) as SSLSocket
            // The TLS layer checks the certificate is for this host before any byte, password included, is sent.
            s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            s.startHandshake()
            return Imap(s)
        }

        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** Parses an IMAP response into atoms (String), NIL (null) and nested lists; `{n}` takes the next literal. */
        fun sexp(s: String, literals: List<String> = emptyList()): List<Any?> {
            var i = 0
            var lit = 0
            fun list(): List<Any?> {
                val out = mutableListOf<Any?>()
                while (i < s.length) {
                    when (s[i]) {
                        ' ' -> i++
                        '(' -> { i++; out.add(list()) }
                        ')' -> { i++; return out }
                        '"' -> {
                            val b = StringBuilder()
                            i++
                            while (i < s.length && s[i] != '"') {
                                if (s[i] == '\\') i++
                                if (i < s.length) b.append(s[i])
                                i++
                            }
                            i++
                            out += b.toString()
                        }
                        else -> {
                            val m = literal.matchAt(s, i)
                            if (m != null) { i = m.range.last + 1; out += literals.getOrNull(lit++); continue }
                            // Atoms run to a space or paren, except inside [...] ("BODY[HEADER.FIELDS (FROM)]").
                            val st = i
                            var depth = 0
                            while (i < s.length && (depth > 0 || s[i] !in " ()")) {
                                if (s[i] == '[') depth++ else if (s[i] == ']' && depth > 0) depth--
                                i++
                            }
                            val a = s.substring(st, i)
                            out += if (a.equals("NIL", true)) null else a
                        }
                    }
                }
                return out
            }
            return list()
        }
    }
}

/** Decoding of the MIME pieces IMAP hands back: headers, transfer encodings, charsets, body structure. */
object Mime {
    /** A leaf of a message's BODYSTRUCTURE; [section] is what BODY[...] fetches. */
    data class Part(val section: String, val type: String, val charset: String?, val encoding: String?, val name: String, val size: Long)

    fun charset(name: String?): Charset =
        runCatching { Charset.forName(name!!.trim().trim('"').substringBefore('*')) }.getOrDefault(Charsets.UTF_8)

    fun qp(s: String): ByteArray {
        val o = ByteArrayOutputStream()
        fun hex(c: Char) = c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=') {
                when {
                    s.startsWith("\r\n", i + 1) -> { i += 3; continue }
                    s.startsWith("\n", i + 1) -> { i += 2; continue }
                    i + 2 < s.length && hex(s[i + 1]) && hex(s[i + 2]) -> { o.write(s.substring(i + 1, i + 3).toInt(16)); i += 3; continue }
                }
            }
            o.write(c.code and 0xff)
            i++
        }
        return o.toByteArray()
    }

    fun bytes(raw: String, encoding: String?): ByteArray = when (encoding?.lowercase()) {
        "base64" -> runCatching { Base64.getMimeDecoder().decode(raw) }.getOrDefault(ByteArray(0))
        "quoted-printable" -> qp(raw)
        else -> raw.toByteArray(LATIN1)
    }

    fun text(raw: String, encoding: String?, charset: String?) = String(bytes(raw, encoding), charset(charset))

    private val word = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")

    /** A header value: unfolded, raw UTF-8 read as such, and =?charset?B/Q?...?= words decoded. */
    fun header(raw: String): String {
        val s = String(raw.toByteArray(LATIN1), Charsets.UTF_8).replace(Regex("\\r?\\n[ \\t]+"), " ")
            .replace(Regex("(\\?=)\\s+(?==\\?)"), "\$1") // space between encoded words isn't text
        return word.replace(s) { m ->
            val d = m.groupValues[3]
            val b = if (m.groupValues[2].equals("B", true)) runCatching { Base64.getMimeDecoder().decode(d) }.getOrDefault(ByteArray(0))
            else qp(d.replace('_', ' '))
            String(b, charset(m.groupValues[1]))
        }.trim()
    }

    /** Header block -> lower-case name to decoded value (first one wins). */
    fun headers(block: String): Map<String, String> = block.replace(Regex("\\r?\\n[ \\t]+"), " ").lines().mapNotNull { l ->
        l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to header(l.substring(it + 1)) }
    }.reversed().toMap()

    /** "Jane Q Doe <jane@example.com>" -> "jane@example.com". */
    fun address(s: String) = Regex("<([^>]+)>").find(s)?.groupValues?.get(1)?.trim()
        ?: Regex("[^\\s,<>\"]+@[^\\s,<>\"]+").find(s)?.value ?: s.trim()

    /** "\"United Airlines\" <x@united.com>" -> "United Airlines". */
    fun sender(s: String) = s.substringBefore('<').trim().trim('"').trim().ifEmpty { address(s) }

    private val internal = DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss Z", Locale.US)

    /** IMAP INTERNALDATE ("03-Sep-2019 14:02:11 -0700") -> the day it arrived. */
    fun internalDate(s: String?): LocalDate? = s?.let { runCatching { ZonedDateTime.parse(it.trim(), internal).toLocalDate() }.getOrNull() }

    fun parts(bs: List<*>, prefix: String = ""): List<Part> {
        if (bs.firstOrNull() is List<*>) return bs.takeWhile { it is List<*> }.flatMapIndexed { i, p ->
            parts(p as List<*>, if (prefix.isEmpty()) "${i + 1}" else "$prefix.${i + 1}")
        }
        val section = prefix.ifEmpty { "1" }
        val type = "${bs.getOrNull(0)}/${bs.getOrNull(1)}".lowercase()
        // A forwarded email: look inside it.
        if (type == "message/rfc822") (bs.getOrNull(8) as? List<*>)?.let { inner ->
            return parts(inner, if (inner.firstOrNull() is List<*>) section else "$section.1")
        }
        fun params(x: Any?) = (x as? List<*>).orEmpty().chunked(2).associate { (it[0] as? String)?.lowercase() to it.getOrNull(1) as? String }
        val p = params(bs.getOrNull(2))
        val file = bs.drop(7).filterIsInstance<List<*>>().firstNotNullOfOrNull { params(it.getOrNull(1))["filename"] }
        return listOf(Part(section, type, p["charset"], bs.getOrNull(5) as? String, p["name"] ?: file ?: "", (bs.getOrNull(6) as? String)?.toLongOrNull() ?: 0))
    }

    /** Parts worth reading, best first: the plain text, then the HTML, then PDF attachments (e-tickets). */
    fun readable(parts: List<Part>): List<Part> =
        parts.filter { it.type == "text/plain" && it.name.isEmpty() } + parts.filter { it.type == "text/html" } +
            parts.filter { (it.type == "application/pdf" || it.name.endsWith(".pdf", true)) && it.size < 8_000_000 }

    /** HTML to text that keeps line breaks (the traveler-name patterns go by lines). */
    fun html(s: String): String = Importer.stripHtml(s.replace(Regex("(?i)<br\\s*/?>|</(p|div|tr|li|h\\d|table)>"), "\n"))
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "" }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&rarr;", "→")
        .replace(Regex("[ \\t]*\\n\\s*"), "\n").trim()
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
        "itinerary", "confirmation", "confirmed", "e-ticket", "eticket", "receipt", "reservation", "booking", "\"trip to\"",
        "\"boarding pass\"", "\"check in\"", "flight",
    )

    /**
     * Gmail search: mail from an airline or travel site, or with a booking-like subject, that mentions a flight.
     * Promotions are skipped (that's where airline deals land); the parser weeds out whatever else slips through.
     */
    val gmail = "{" + (senders.map { "from:$it" } + subjects.map { "subject:$it" }).joinToString(" ") + "} " +
        "{flight flights \"boarding pass\" \"e-ticket\"} -category:promotions"

    private fun range(from: Long) = if (from > 1) "UID $from:* " else ""

    /** UID SEARCH criteria for Gmail (its X-GM-RAW extension takes the query as typed in Gmail's search box). */
    fun gmail(from: Long) = range(from) + "X-GM-RAW " + Imap.quote(gmail)

    /** Any other IMAP server: booking-like subjects. */
    fun standard(from: Long) = range(from) + subjects.map { "SUBJECT " + Imap.quote(it.trim('"')) }.reduce { a, b -> "OR $a $b" }

    /** First UID to look at: everything the first time, or if the server renumbered the mailbox (UIDVALIDITY changed). */
    fun start(validity: Long, lastValidity: Long, lastUid: Long) = if (validity == lastValidity) lastUid + 1 else 1L

    /** "UID n:*" always includes the newest message even when it's below n, so drop what was already seen. */
    fun fresh(uids: List<Long>, start: Long) = uids.filter { it >= start }.distinct().sorted()
}
