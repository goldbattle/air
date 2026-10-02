package com.aria.air

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Developer options (tap the version in Settings › About seven times): a log of every request the app makes and what
 * came back, and reports to copy or share when something looks wrong. Off by default; nothing leaves the phone
 * unless you copy or share a report yourself. API keys are never logged.
 */
object Debug {
    val on get() = Prefs.flag("dev", default = false)
    private var file: File? = null
    private val stamp = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault())
    private const val KEEP = 300 // lines

    fun init(ctx: Context) { file = File(ctx.filesDir, "debug.log") }

    /** One line in the log, when developer options are on. */
    fun note(s: String) {
        if (!on) return
        val f = file ?: return
        synchronized(this) {
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "${stamp.format(Instant.now())}  ${s.replace('\n', ' ')}"
            f.writeText(lines.takeLast(KEEP).joinToString("\n", postfix = "\n"))
        }
    }

    /** A request: URL (query string kept, headers with keys never), outcome, time, and the start of the body. */
    fun request(url: String, ms: Long, result: Result<String>) {
        if (!on) return
        val r = result.fold({ "${it.length} B" }, { "failed: ${it.message}" })
        note("GET ${url.substringAfter("://")}  $r  ${ms}ms")
    }

    private fun statusFile(f: Flight) = file?.parentFile?.let { File(it, "debug-${f.airline}${f.number}_${f.date}.json") }

    /** The latest raw status response for [f] (AeroDataBox or FlightAware), the one worth reading in full. */
    fun status(f: Flight, body: String) {
        if (on) statusFile(f)?.writeText(body.ifBlank { "(empty: the service has no such flight that day)" })
    }

    fun log(): String = file?.takeIf { it.exists() }?.readText().orEmpty()

    fun clear() {
        val dir = file?.parentFile ?: return
        file?.delete()
        dir.listFiles { f -> f.name.startsWith("debug-") }?.forEach { it.delete() }
    }

    /** Everything about one flight: stored fields, what the app works out from them, and the last status response. */
    fun flightReport(f: Flight, now: Instant = Instant.now()): String = buildString {
        appendLine("== ${f.label} ${f.from}-${f.to} ${f.date}  (${if (f.mine) "mine" else "friend"})")
        appendLine("now ${stamp.format(now)}  phase ${f.phase(now)}  status ${f.status(now).first}")
        appendLine("dep ${f.dep}  arr ${f.arr}  tookOff ${f.tookOff}  airborne ${f.isAirborne}")
        appendLine("gate dep ${f.depTerminal}/${f.depGate.ifEmpty { "-" }}  arr ${f.arrTerminal}/${f.arrGate.ifEmpty { "-" }}  bags ${f.baggage.ifEmpty { "-" }}")
        appendLine("aircraft ${f.aircraft.ifEmpty { "-" }}  reg ${f.reg.ifEmpty { "-" }}  boarding lead ${f.boardingLead.toMinutes()} min")
        appendLine("last status lookup ${f.updated?.let(stamp::format) ?: "never"}  status every ${Api.statusEvery(f, now) ?: "never"}  live every ${Api.liveEvery(f, now, true) ?: "never"}")
        appendLine("inbound ${f.inbound ?: "none"}")
        appendLine("inbound line: ${f.inboundLine() ?: "-"}")
        appendLine("live ${f.live ?: "none"}")
        f.connection().takeIf { it.size > 1 }?.let { c -> appendLine("connection: ${c.joinToString(" > ") { it.label }}") }
        appendLine("stored: ${f.toJson()}")
        statusFile(f)?.takeIf { it.exists() }?.let { appendLine("last status response: ${it.readText().take(8000)}") }
            ?: appendLine("last status response: none saved yet (tap refresh with developer options on)")
        // This flight's lines from the log: its refreshes and the requests about its number or callsign.
        val tags = listOf(f.label, "${f.airline}${f.number}", Api.callsign(f.airline, f.number), f.reg).filter { it.isNotEmpty() }
        log().lines().filter { l -> tags.any { it in l } }.takeLast(30).takeIf { it.isNotEmpty() }
            ?.let { appendLine("log:"); it.forEach(::appendLine) }
    }

    /** The whole picture: app, phone, settings that matter (keys only as set / not set), nearby flights, the log. */
    fun report(ctx: Context): String = buildString {
        val now = Instant.now()
        appendLine("Air ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_COMMIT}  ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("time ${stamp.format(now)} ${ZoneId.systemDefault()}")
        appendLine("AeroDataBox key ${if (Prefs.adbKey.isBlank()) "not set" else "set"}  FlightAware key ${if (Prefs.aeroKey.isBlank()) "not set" else "set"}  lookups this month ${Api.lookupsThisMonth()}")
        appendLine("flights ${Store.flights.size}  email accounts ${Mail.accounts.size}")
        appendLine()
        // Flights from yesterday to the next few days: the ones anything is happening on.
        val today = java.time.LocalDate.now()
        Store.flights.filter { it.date in today.minusDays(1)..today.plusDays(3) }.sortedBy { it.depGuess }
            .forEach { appendLine(flightReport(it, now)) }
        appendLine("== log")
        append(log())
    }

    fun copy(ctx: Context, text: String, what: String = "Debug report") {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(what, text))
        Toast.makeText(ctx, "$what copied", Toast.LENGTH_SHORT).show()
    }

    fun share(ctx: Context, text: String) = ctx.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share debug report"),
    )
}
