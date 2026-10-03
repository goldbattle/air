package com.aria.air

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.importer.*
import com.aria.air.util.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate
import kotlin.concurrent.thread

class MailTest {
    companion object {
        @BeforeClass @JvmStatic
        fun load() = Db.load(File("src/main/assets/airports.tsv").reader(), File("src/main/assets/airlines.tsv").reader())
    }

    // ---------- IMAP responses ----------

    @Test
    fun sexpParsesFetchWithLiteral() {
        val l = Imap.sexp("* 3 FETCH (UID 42 FLAGS (\\Seen) BODY[HEADER.FIELDS (FROM SUBJECT)] {5} X NIL \"a \\\"q\\\"\")", listOf("hello"))
        assertEquals(listOf("*", "3", "FETCH", listOf("UID", "42", "FLAGS", listOf("\\Seen"), "BODY[HEADER.FIELDS (FROM SUBJECT)]", "hello", "X", null, "a \"q\"")), l)
    }

    @Test
    fun bodyStructureParts() {
        // multipart/mixed: (alternative: plain, html), pdf attachment, forwarded message with one text part.
        val bs = Imap.sexp(
            "((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"QUOTED-PRINTABLE\" 120 4 NIL NIL NIL)" +
                "(\"TEXT\" \"HTML\" (\"CHARSET\" \"iso-8859-1\") NIL NIL \"BASE64\" 900 12 NIL NIL NIL) \"ALTERNATIVE\" (\"BOUNDARY\" \"b1\") NIL NIL)" +
                "(\"APPLICATION\" \"OCTET-STREAM\" NIL NIL NIL \"BASE64\" 5000 NIL (\"ATTACHMENT\" (\"FILENAME\" \"eticket.pdf\")) NIL)" +
                "(\"MESSAGE\" \"RFC822\" NIL NIL NIL \"7BIT\" 300 (NIL \"Fwd\" NIL NIL NIL NIL NIL NIL NIL NIL) " +
                "(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"us-ascii\") NIL NIL \"7BIT\" 100 3 NIL NIL NIL) 9 NIL NIL NIL) \"MIXED\" (\"BOUNDARY\" \"b0\") NIL NIL",
        )
        val parts = Mime.parts(bs)
        assertEquals(listOf("1.1", "1.2", "2", "3.1"), parts.map { it.section })
        assertEquals("text/html", parts[1].type)
        assertEquals("iso-8859-1", parts[1].charset)
        assertEquals("eticket.pdf", parts[2].name)
        assertEquals(listOf("1.1", "3.1", "1.2", "2"), Mime.readable(parts).map { it.section })
        // A plain single-part message is part 1.
        assertEquals("1", Mime.parts(Imap.sexp("\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"7BIT\" 10 1 NIL NIL NIL"))[0].section)
    }

    // ---------- MIME decoding ----------

    @Test
    fun quotedPrintable() {
        assertEquals("Flight UA 1000 → PIT", Mime.text("Flight UA 1000 =E2=86=92 =\r\nPIT", "quoted-printable", "utf-8"))
        assertEquals("café", Mime.text("caf=E9", "QUOTED-PRINTABLE", "ISO-8859-1"))
        assertEquals("a=+1", Mime.text("a=+1", "quoted-printable", "utf-8")) // not an escape, left alone
    }

    @Test
    fun base64AndCharsets() {
        val raw = java.util.Base64.getMimeEncoder().encodeToString("Seat 21C · Jane Q Doe".toByteArray())
        assertEquals("Seat 21C · Jane Q Doe", Mime.text(raw, "base64", "UTF-8"))
        assertEquals("plain", Mime.text("plain", "7bit", "bogus-charset"))
        // 8-bit UTF-8 kept byte-exact as Latin-1 by the IMAP reader.
        assertEquals("Zürich", Mime.text(String("Zürich".toByteArray(), Charsets.ISO_8859_1), "8bit", "utf-8"))
    }

    @Test
    fun encodedHeaders() {
        assertEquals("Your trip to Zürich", Mime.header("=?UTF-8?Q?Your_trip_to_Z=C3=BCrich?="))
        assertEquals("Confirmation TESTAB", Mime.header("=?utf-8?B?Q29uZmlybWF0aW9u?= =?utf-8?B?IFRFU1RBQg==?="))
        val h = Mime.headers("From: \"Example Air\" <noreply@example.com>\r\nSubject: Itinerary\r\n for Jane\r\nTo: Jane Q Doe <jane@example.com>\r\n")
        assertEquals("Itinerary for Jane", h["subject"])
        assertEquals("Example Air", Mime.sender(h["from"]!!))
        assertEquals("jane@example.com", Mime.address(h["to"]!!))
        assertEquals(LocalDate.of(2019, 9, 3), Mime.internalDate(" 3-Sep-2019 14:02:11 -0700"))
    }

    @Test
    fun htmlKeepsLines() {
        assertEquals("Travelers\nJane Q Doe\nSFO → PIT", Mime.html("<div>Travelers</div><p>Jane&nbsp;Q Doe</p>SFO &#8594; PIT"))
    }

    // ---------- Search and bookkeeping ----------

    @Test
    fun queries() {
        val q = MailQuery.gmail()
        assertTrue(q.startsWith("X-GM-RAW \"{from:united.com "))
        assertTrue(q.contains("subject:\\\"boarding pass\\\"")) // inner quotes escaped for IMAP
        assertTrue(q.contains("\\\"record locator\\\"")) // body phrases too
        assertFalse(q.contains("category:promotions"))     // some airlines' confirmations land in Promotions
        val s = MailQuery.standard()
        assertTrue(s.startsWith("OR OR OR "))
        assertEquals(s.split("SUBJECT ").size - 2, Regex("\\bOR\\b").findAll(s).count()) // n terms, n-1 ORs
    }

    @Test
    fun mergingAnAlreadySavedFlight() {
        val d = LocalDate.of(2026, 10, 14)
        val saved = Flight(airline = "DL", number = "400", date = d, from = "DTW", to = "PWM", seat = "14C")
        val email = Found(airline = "DL", number = "400", date = d, from = "DTW", to = "PWM", dep = null, arr = null, seat = "22A", booking = "QWERTY")
        assertEquals(listOf("confirmation"), mergeAdds(saved, email)) // the saved seat wins, so only the code is new
        val merged = saved.fillFrom(saved.copy(seat = "22A", booking = "QWERTY"))
        assertEquals("14C", merged.seat)
        assertEquals("QWERTY", merged.booking)
        assertEquals(emptyList<String>(), mergeAdds(merged, email))
    }

    @Test
    fun suggestions() {
        val text = "Confirmation TESTAB\nOct 3, 2026\nUA 1000 SFO to PIT 8:10 AM 4:04 PM"
        fun hit(id: String, state: String = "") = MailHit("jane@example.com", id, "Trip", "Example Air", "jane@example.com", LocalDate.of(2026, 9, 1), text, state)
        val hits = listOf(hit("a"), hit("b", "d"), hit("c", "i"), hit("d").copy(text = "nothing here"))
        assertEquals("UA 1000 SFO→PIT", hits[0].flights.single().let { "${it.label} ${it.from}→${it.to}" })
        assertEquals(listOf("a"), suggest(hits) { false }.map { it.id })
        assertEquals(emptyList<String>(), suggest(hits) { it.number == "1000" }.map { it.id }) // already saved
    }

    // ---------- The client against a scripted server ----------

    @Test
    fun clientTalksToServer() {
        val body = "Confirmation TESTAB\r\nUA 1000 SFO to PIT\r\n"
        val server = ServerSocket(0)
        val sent = mutableListOf<String>()
        thread {
            server.accept().use { s ->
                val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val w = s.getOutputStream().writer(Charsets.ISO_8859_1)
                fun say(x: String) { w.write(x.replace("\n", "\r\n")); w.flush() }
                say("* OK Fake ready\n")
                while (true) {
                    val line = r.readLine() ?: break
                    sent += line
                    val (tag, cmd) = line.split(' ', limit = 2)
                    when {
                        cmd.startsWith("LOGIN") -> say(if ("\"secret\"" in cmd) "$tag OK\n" else "$tag NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)\n")
                        cmd == "CAPABILITY" -> say("* CAPABILITY IMAP4rev1 X-GM-EXT-1\n$tag OK\n")
                        cmd.startsWith("LIST") -> say("* LIST (\\HasNoChildren) \"/\" \"INBOX\"\n* LIST (\\All \\HasNoChildren) \"/\" \"[Gmail]/All Mail\"\n$tag OK\n")
                        cmd.startsWith("EXAMINE") -> say("* OK [UIDVALIDITY 7] ok\n* OK [UIDNEXT 900] ok\n$tag OK [READ-ONLY]\n")
                        cmd.startsWith("UID SEARCH") -> say("* SEARCH 12 34\n$tag OK\n")
                        cmd.startsWith("UID FETCH 12 (BODY.PEEK[1])") -> say("* 1 FETCH (UID 12 BODY[1] {${body.length}}\n${body.replace("\r\n", "\n")})\n$tag OK\n")
                        cmd.startsWith("LOGOUT") -> { say("* BYE\n$tag OK\n"); break }
                        else -> say("$tag BAD\n")
                    }
                }
            }
        }
        Imap(Socket("127.0.0.1", server.localPort)).use { im ->
            val bad = runCatching { im.login("jane@example.com", "wrong") }.exceptionOrNull()
            assertTrue(bad is Imap.Error && "Invalid credentials" in bad.message!! && "wrong" !in bad.message!!)
            im.login("jane@example.com", "secret")
            assertTrue("X-GM-EXT-1" in im.caps())
            assertEquals("[Gmail]/All Mail", im.allMail())
            assertEquals(Imap.Box(7, 900), im.examine("[Gmail]/All Mail"))
            assertEquals(listOf(12L, 34L), im.search(MailQuery.gmail()))
            assertEquals(body, im.fetch("12", "BODY.PEEK[1]")[12L]!!["BODY[1]"])
        }
        assertTrue(sent.any { it.endsWith("EXAMINE \"[Gmail]/All Mail\"") })
        server.close()
    }
}
