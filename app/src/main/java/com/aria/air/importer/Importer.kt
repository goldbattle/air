package com.aria.air.importer

// Reading flights out of documents on the phone: text, PDF and OCR, then the parser.

import com.aria.air.data.*
import com.aria.air.ui.components.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything here runs on the phone: Android's PDF text layer, and ML Kit's bundled OCR model for images. */
object Importer {
    /**
     * The readable text of a shared or picked file: a PDF's text (OCR for scans), OCR for images, else the file as text
     * with HTML stripped.
     */
    suspend fun text(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        when {
            mime == "application/pdf" || uri.toString().endsWith(".pdf", true) -> pdf(ctx, uri)
            mime.startsWith("image/") -> ocr(InputImage.fromFilePath(ctx, uri))
            else -> ctx.contentResolver.openInputStream(uri)!!.use { it.reader().readText() }.let(::stripHtml)
        }
    }

    /**
     * The PDF's text layer. Only when the whole document has none (a scan or a photo saved as PDF) are pages rendered
     * and OCR'd; emails printed to PDF often have picture-only pages (logos, ads) that aren't worth reading.
     */
    private fun pdf(ctx: Context, uri: Uri): String = ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
        PdfRenderer(fd).use { r ->
            val pages = 0 until minOf(r.pageCount, 20)
            val text = pages.joinToString("\n") { i ->
                runCatching { r.openPage(i).use { page -> page.textContents.joinToString("\n") { it.text } } }.getOrDefault("")
            }
            if (text.isNotBlank()) return@use text
            pages.joinToString("\n") { i ->
                // One unreadable page shouldn't sink the import.
                runCatching {
                    r.openPage(i).use { page ->
                        val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ocr(InputImage.fromBitmap(bmp, 0))
                    }
                }.getOrDefault("")
            }
        }
    }

    /** Runs ML Kit's on-device text recognition on one image. */
    private fun ocr(img: InputImage): String {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { Tasks.await(client.process(img)).text } finally { client.close() }
    }

    /** Emails saved as .eml/.html: drop tags and quoted-printable soft breaks so the text reads normally. */
    fun stripHtml(s: String): String = s.replace("=\r\n", "").replace("=\n", "").replace("=3D", "=")
        .replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
        .replace(Regex("<([\\w.+-]+@[\\w.-]+)>"), " $1 ") // keep the address in "To: Jane <jane@x.com>"
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("[ \\t]+"), " ")

    // ---------- Parsing ----------

    private val flightRe = Regex("(?<![A-Z0-9:/-])([A-Z][A-Z0-9]|[0-9][A-Z])\\s?(\\d{1,4})(?![0-9:])")
    private val codeRe = Regex("(?<![A-Za-z])([A-Z]{3})(?![A-Za-z])")
    private val timeRe = Regex("(?<![\\d:])(\\d{1,2}):(\\d{2})\\s*([AaPp])?\\.?\\s?[Mm]?\\.?(?![\\d])")
    // Keywords in any case, the code itself uppercase ("Trip Confirmation Booked..." must not give "Booked").
    private val bookingRe = Regex("(?i:confirmation(?: number| code| #)?|record locator|booking (?:reference|code|ref)|reservation (?:code|number)|airline reference|reference #|PNR)\\W{0,12}([A-Z0-9]{6})(?![A-Za-z0-9])")
    private val seatRe = Regex("(?i)\\bseats?\\W{0,6}(\\d{1,2}[A-K])\\b")
    private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
    private val isoDate = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
    private val monthDay = Regex("(?i)\\b($MONTHS)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?")
    private val dayMonth = Regex("(?i)\\b(\\d{1,2})\\s?($MONTHS)[a-z]*\\.?,?\\s?(\\d{4}|\\d{2}(?![\\d:]))?")
    private val usDate = Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})\\b")
    private val ymdSlash = Regex("\\b(\\d{4})/(\\d{1,2})/(\\d{1,2})\\b")

    /** "Portland, ME" / "London, GB": a city and a state or country code, as Delta and others write airports. */
    private val cityRe = Regex("([A-Z][A-Za-z.'-]+(?: [A-Z][A-Za-z.'-]+){0,3}), ([A-Z]{2})\\b")
    private const val NAME = "[A-Za-z][A-Za-z'.-]*(?:[ \\t]+[A-Za-z][A-Za-z'.-]*){0,3}"
    private val travelerRes = listOf(
        Regex("(?i)(?:trip (?:information|details|itinerary)|travel itinerary|itinerary) for:?[ \\t]*\\r?\\n?[ \\t]*($NAME)"),
        Regex("(?i)(?:passenger|traveler|traveller)s?(?: name)?[ \\t]*:[ \\t]*($NAME)"),
        // United: a "Travelers" heading with the name on the next line.
        Regex("(?im)^[ \\t]*(?:travelers|travellers|passengers)[ \\t]*\\r?\\n[ \\t]*($NAME)[ \\t]*$"),
        // Airline receipts: "Traveler Details" then "DOE/JOHNQ" (LAST/FIRST).
        Regex("(?im)^[ \\t]*(?:traveler|traveller|passenger)s? (?:details|information)[ \\t]*\\r?\\n[ \\t]*([A-Za-z'-]+/[A-Za-z'-]+(?:[ \\t]+[A-Za-z]+)?)[ \\t]*$"),
        Regex("\\b([A-Z][a-z]+(?: [A-Z]\\.?)?(?: [A-Z][a-z]+)?)'s (?:Upcoming )?(?:Trip|Flight|Itinerary)"),
    )

    /** 3-letter words that are airport codes but usually just words in an itinerary. */
    private val notAirports = setOf(
        "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT",
        "NOV", "DEC", "THE", "AND", "FOR", "YOU", "ARE", "USD", "USA", "EUR", "GBP", "PDF", "MIN", "EST", "PST", "EDT",
        "PDT", "CDT", "CST", "MST", "MDT", "UTC", "GMT", "PNR", "ETA", "ETD", "NON", "STOP", "SEAT", "BAG", "CAR", "TAX",
        "FEE", "ALL", "NEW", "ONE", "TWO", "ADT", "CHD", "INF", "OUT", "OFF", "VIA", "TSA", "CEO", "APP", "WEB", "AIR",
    )

    /** The month for a name or abbreviation ("Oct", "October"). */
    private fun month(s: String) = Month.entries.first { it.getDisplayName(TextStyle.SHORT, Locale.US).equals(s.take(3), true) }

    /** A year written with 2 or 4 digits, or [today]'s year when missing. */
    private fun year(s: String?, today: LocalDate) = when {
        s == null -> today.year
        s.length == 2 -> 2000 + s.toInt()
        else -> s.toInt()
    }

    /**
     * Every date in [text] with its position, in any of the usual formats. Dates without a year take it from the first
     * full date (usually when the email was sent).
     */
    private fun dates(text: String, today: LocalDate): List<Pair<Int, LocalDate>> {
        val out = mutableListOf<Triple<Int, LocalDate, Boolean>>() // position, date, had a year
        fun add(pos: Int, hasYear: Boolean = true, f: () -> LocalDate) { runCatching(f).getOrNull()?.let { out += Triple(pos, it, hasYear) } }
        isoDate.findAll(text).forEach { m -> add(m.range.first) { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) } }
        monthDay.findAll(text).forEach { m -> add(m.range.first, m.groups[3] != null) { LocalDate.of(year(m.groups[3]?.value, today), month(m.groupValues[1]), m.groupValues[2].toInt()) } }
        dayMonth.findAll(text).forEach { m -> add(m.range.first, m.groups[3] != null) { LocalDate.of(year(m.groups[3]?.value, today), month(m.groupValues[2]), m.groupValues[1].toInt()) } }
        usDate.findAll(text).forEach { m -> add(m.range.first) { LocalDate.of(year(m.groupValues[3], today), m.groupValues[1].toInt(), m.groupValues[2].toInt()) } }
        ymdSlash.findAll(text).forEach { m -> add(m.range.first) { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) } }
        // "Tuesday, December 3" has no year: take it from the first full date in the document (usually when the
        // email was sent), choosing the first matching day on or after it. Old emails then land in the right year.
        val ref = out.filter { it.third }.minByOrNull { it.first }?.second
        return out.sortedBy { it.first }.map { (pos, d, hasYear) ->
            if (hasYear || ref == null) pos to d
            else pos to d.withYear(ref.year).let { if (it < ref.minusDays(7)) it.plusYears(1) else it }
        }
    }

    /** Times of day in the order they appear, 12- or 24-hour. */
    private fun times(s: String): List<LocalTime> = timeRe.findAll(s).mapNotNull { m ->
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        when (m.groupValues[3].lowercase()) {
            "p" -> if (h < 12) h += 12
            "a" -> if (h == 12) h = 0
        }
        if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }.toList()

    /** Ligatures ("ﬁ"), narrow no-break spaces before AM/PM and the like, folded to plain text. */
    fun clean(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).replace(' ', ' ')

    private val workRe = Regex(
        "(?i)cost cent(?:er|re)|charged to department|travel (?:&|and) expense|expense policy|in policy fare|project number|" +
            "business travel|corporate travel|concur|egencia|navan|amex gbt|bcd travel|cwt\\b|purpose of travel",
    )

    /** Booked through a company: expense/cost-center language or a corporate travel agency. */
    fun isWork(text: String) = workRe.containsMatchIn(clean(text))

    private val emailRe = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+")

    /**
     * The address an email was sent to, from its first "To:" line (the header of a .eml or a Gmail printout; later ones
     * are quoted forwards). Of several, one you've already marked work or not ([known]), else the first.
     */
    fun recipient(text: String, known: (String) -> Boolean = { false }): String? {
        val line = Regex("(?im)^[ \\t]*to:(.*)$").find(clean(text))?.groupValues?.get(1) ?: return null
        val all = emailRe.findAll(line).map { it.value.lowercase() }.toList()
        return all.firstOrNull(known) ?: all.firstOrNull()
    }

    /** Work trip or not: what you chose before for mail sent [to] that address, else whether it reads like a company booking. */
    fun isWork(text: String, to: String?, learned: (String) -> Boolean?) = to?.let(learned) ?: isWork(text)

    /** Who the booking is for, e.g. "Trip Information For: Jane Q Doe", or null. */
    fun traveler(text: String): String? {
        val t = clean(text)
        var raw = travelerRes.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.trim() } ?: return null
        // "Jane Q. Doe. Thu, Oct 22" -> "Jane Q. Doe": a sentence ends after a word, not after an initial.
        raw = raw.replace(Regex("(?<=\\p{L}{2})[.;,](\\s.*)?$"), "")
        if ('/' in raw) raw = raw.substringAfter('/') + " " + raw.substringBefore('/') // "DOE/JOHN" -> "JOHN DOE"
        val words = raw.split(Regex("[ \\t]+")).filter { it.isNotEmpty() && it.uppercase() !in setOf("MR", "MS", "MRS", "MISS", "DR") }
        if (words.isEmpty() || words.any { it.lowercase() in setOf("the", "and", "your", "this", "information", "details") }) return null
        // "JOHN DOE" -> "John Doe"
        return words.joinToString(" ") { w -> if (w.length > 1 && w == w.uppercase()) w.lowercase().replaceFirstChar { it.uppercase() } else w }
    }

    /**
     * Links and long encoded runs (email tracking URLs, base64, CSS leftovers) are full of things like "YQ4" or "SH8"
     * that read as flight numbers. Blank them out, keeping the text's length so nothing else shifts.
     */
    private val noise = Regex("(?i)(?:https?://|www\\.)\\S+|(?<!\\S)(?=\\S*[%=&?_+/])\\S{30,}")

    /**
     * [text] with links and long encoded runs blanked out (same length, so positions don't shift), so they can't pass
     * for flight numbers.
     */
    fun denoise(text: String): String = noise.replace(text) { " ".repeat(it.value.length) }

    // ponytail: regex heuristics, not a model. Handles the common airline formats; an on-device LLM
    // (Gemini Nano via ML Kit GenAI) is the upgrade path for messy layouts.
    /**
     * Pulls flights out of a confirmation email, e-ticket or boarding pass. Each flight number that belongs to a known
     * airline starts a segment; the date is the nearest one before it, times and airports are the first ones in its
     * segment. [today] anchors dates written without a year. Returns each flight and date once, best mention kept.
     */
    fun parse(text: String, today: LocalDate = LocalDate.now()): List<Found> {
        val t = denoise(clean(text))
        val flights = flightRe.findAll(t).filter { m ->
            val code = m.groupValues[1]
            code !in setOf("AM", "PM") && Db.airline(code) != null
        }.toList()
        val allDates = dates(t, today)
        val booking = bookingRe.find(t)?.groupValues?.get(1).orEmpty()
        val out = LinkedHashMap<String, Found>()
        val routed = HashSet<String>() // flights whose airports sit next to their own number, not borrowed nearby
        var claimed = 0 // text up to here belongs to an earlier flight (its details came after its number)
        fun airports(s: String) = codeRe.findAll(s).map { it.groupValues[1] }
            .filter { it !in notAirports && Db.airport(it) != null }.distinct().take(2).toList()
        fun cities(s: String) = cityRe.findAll(s).mapNotNull { Db.airportForCity(it.groupValues[1], it.groupValues[2])?.iata }
            .distinct().take(2).toList()
        fun codesIn(s: String) = airports(s).takeIf { it.size == 2 } ?: cities(s).takeIf { it.size == 2 } ?: airports(s)
        fun completeness(s: String) = (if (codesIn(s).size == 2) 1 else 0) + (if (times(s).size >= 2) 1 else 0)
        flights.forEachIndexed { i, m ->
            val start = m.range.first
            val next = flights.getOrNull(i + 1)?.range?.first ?: minOf(t.length, start + 600)
            val prevEnd = flights.getOrNull(i - 1)?.range?.last?.plus(1) ?: 0
            val lastDate = allDates.lastOrNull { it.first in prevEnd until start }
            // A date right after the number ("UA1234 Class: Economy / Fri, Oct 02", "WN500 09JUL") is this flight's;
            // otherwise it's the nearest date before the number.
            val date = allDates.firstOrNull { it.first in m.range.last + 1 until minOf(next, start + 45) }?.second
                ?: allDates.lastOrNull { it.first < start }?.second ?: allDates.firstOrNull { it.first in start until next }?.second
            // Details sit either after the flight number (Delta: number, then cities and times) or before it
            // (United: date, times and airports, then the number). "After" stops at the next date, which starts the
            // next flight's block; "before" starts at this flight's date. Use whichever side is more complete.
            val afterEnd = allDates.firstOrNull { it.first in m.range.last + 1 until next && it.second != date }?.first ?: next
            val after = t.substring(start, afterEnd)
            val before = t.substring(minOf(start, maxOf(claimed, prevEnd, lastDate?.first ?: (start - 160), start - 600)), start)
            val seg = if (completeness(before) > completeness(after)) before else after
            claimed = if (seg === after && completeness(after) > 0) afterEnd else start
            val codes = codesIn(seg).takeIf { it.size == 2 } ?: airports(t.substring(maxOf(prevEnd, start - 160), afterEnd))
            val tm = times(seg)
            val from = codes.getOrNull(0); val to = codes.getOrNull(1)
            // Seats are often listed per leg elsewhere: "SFO to PWM: 22C", "SFO-PIT 21C".
            val seat = seatRe.find(seg)?.groupValues?.get(1)
                ?: if (from != null && to != null) Regex("\\b$from(?: to |-)$to\\W{0,4}(\\d{1,2}[A-K])\\b").find(t)?.groupValues?.get(1) else null
            val f = Found(
                airline = m.groupValues[1], number = m.groupValues[2].trimStart('0').ifEmpty { "0" }, date = date,
                from = from, to = to, dep = tm.getOrNull(0), arr = tm.getOrNull(1),
                // The airline's code next to this flight beats the document's first one (agency or hotel numbers).
                seat = seat?.uppercase().orEmpty(), booking = bookingRe.find(seg)?.groupValues?.get(1) ?: booking,
            )
            // The same flight is often mentioned several times; keep the most complete mention.
            val key = "${f.label}|${f.date}"
            val prev = out[key]
            if (prev == null || score(f) > score(prev)) out[key] = f
            if (codesIn(seg).size == 2) routed += key
        }
        // When some flights come with both airports, a bare number next to nothing ("CI 9" in a footer) isn't one,
        // unless it's on the same airline as a complete flight (a connection whose airports didn't parse).
        val complete = out.filterKeys { it in routed }
        val airlines = complete.values.map { it.airline }.toSet()
        return if (complete.isEmpty()) out.values.toList()
        else out.filter { (k, f) -> k in routed || f.airline in airlines && f.date != null }.values.toList()
    }

    /** How complete one mention of a flight is, to keep the best of several. */
    private fun score(f: Found) = listOf(f.date, f.from, f.to, f.dep, f.arr).count { it != null } + (if (f.seat.isNotEmpty()) 1 else 0)
}
