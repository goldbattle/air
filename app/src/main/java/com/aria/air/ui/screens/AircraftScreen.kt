package com.aria.air.ui.screens

// One aircraft's page: what it is, how old, and how its last week of flights ran.

import com.aria.air.*
import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.sync.*
import com.aria.air.ui.components.*
import com.aria.air.util.*
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy")

/**
 * The aircraft flying a flight, by tail number [reg]: model, airline, age, seats and engines, then each of its flights
 * over the last week with how late it landed. Looked up when opened (AeroDataBox, or FlightAware for the flights) and
 * saved for a few hours; pull to look up again. [model] shows until the lookup answers.
 */
@Composable
fun AircraftScreen(reg: String, model: String) {
    val key = AeroDataBox.tailKey(reg)
    LaunchedEffect(key) { Tails.get(key) }
    val r = Tails.reports[key]
    val busy = Tails.busy[key] == true
    val info = r?.info
    Page(reg, onBack = { Nav.pop() }, refreshing = busy, onRefresh = { App.scope.launch { Tails.get(key, force = true) } }) {
        item("head") {
            Text(
                info?.model?.ifEmpty { null } ?: model.ifEmpty { "Aircraft" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
            info?.airline?.takeIf { it.isNotEmpty() }?.let {
                Text(it, fontSize = 15.sp, color = C.secondary, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            }
            if (info != null) Group {
                val rows = listOfNotNull(
                    info.ageYears?.let { age ->
                        Triple("Age", "%.1f years".format(age), info.delivered?.let { "Delivered ${it.format(monthYear)}" })
                    },
                    info.firstFlight?.let { Triple("First flight", it.format(monthYear), null) },
                    info.type.takeIf { it.isNotEmpty() && it != info.model }?.let { Triple("Type", it, null) },
                    info.seats?.let { Triple("Seats", "$it", null) },
                    info.engines.takeIf { it.isNotEmpty() }?.let { Triple("Engines", it, null) },
                    info.serial.takeIf { it.isNotEmpty() }?.let { Triple("Serial number", it, null) },
                    info.hex.takeIf { it.isNotEmpty() }?.let { Triple("Transponder (Mode S)", it.uppercase(), null) },
                )
                rows.forEachIndexed { i, (title, value, sub) ->
                    if (i > 0) Hairline()
                    Cell(title, value, subtitle = sub)
                }
            }
        }
        if (r != null) item("recent") {
            SectionHeader("Last 7 days")
            Group {
                val landed = r.landed.size
                Cell(
                    "On time", if (landed == 0) "—" else "${r.onTime} of $landed",
                    subtitle = r.averageDelay?.let { if (it == 0L) "Every landing on schedule" else "Average arrival ${span(Duration.ofMinutes(it))} late" }
                        ?: "No landings yet",
                )
                r.legs.forEach { l ->
                    Hairline()
                    LegRow(l)
                }
            }
        }
        item("source") {
            val err = Tails.errors[key]
            Footnote(
                when {
                    !Prefs.hasStatusKey -> "Add an AeroDataBox or FlightAware key (Settings › Data) to see this aircraft's age and recent flights."
                    err != null -> "Couldn't look it up: $err" + (r?.let { " · showing what was saved ${ago(it.at)}" } ?: "")
                    r == null -> "Looking up $reg…"
                    else -> "From ${r.source} · ${ago(r.at)}" +
                        if (Prefs.adbKey.isEmpty()) ". Age and model need an AeroDataBox key." else ""
                },
            )
        }
    }
}

/** One recent flight: number, route and day, and how it went (late, on time, cancelled, or still to come). */
@Composable
private fun LegRow(l: TailLeg) {
    val zone = Db.airport(l.from)?.tz ?: ZoneId.systemDefault()
    val d = l.arrDelay
    val (value, color) = when {
        l.cancelled -> "Cancelled" to C.red
        d == null && l.depActual != null -> "In the air" to C.blue
        d == null -> "Scheduled" to C.secondary
        d >= 15 -> "+${span(Duration.ofMinutes(d))}" to C.red
        d <= -5 -> "${span(Duration.ofMinutes(-d))} early" to C.green
        else -> "On time" to C.green
    }
    Cell(
        "${l.flight.replace(Regex("^([A-Z0-9]{2})([0-9]+)$"), "$1 $2")}  ${l.from} → ${l.to}", value, subtitle = l.depSched?.let { "${dayLabel(it.atZone(zone).toLocalDate())} · ${time(it, zone)}" },
        valueColor = color,
    )
}
