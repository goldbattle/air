package com.aria.air.util

// How times, spans, numbers, countries and headings are written on screen.

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
private val dayYearFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val longDayFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")

fun time(t: Instant?, zone: ZoneId): String = t?.atZone(zone)?.format(timeFmt) ?: "—"
fun longDay(d: LocalDate): String = d.format(longDayFmt)

fun dayLabel(d: LocalDate): String {
    val today = localToday()
    return when {
        d == today -> "Today"
        d == today.plusDays(1) -> "Tomorrow"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> d.format(dayFmt)
        else -> d.format(dayYearFmt)
    }
}

/** "5h 24m", "45m", "3d 4h". */
fun span(d: Duration): String {
    val m = abs(d.toMinutes())
    return when {
        m >= 48 * 60 -> "${m / 1440}d ${(m % 1440) / 60}h"
        m >= 60 -> "${m / 60}h ${m % 60}m"
        else -> "${m}m"
    }
}

fun ago(t: Instant): String {
    val s = Duration.between(t, Instant.now(clock)).seconds
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

fun fmtInt(n: Number): String = "%,d".format(n.toLong())
fun flag(iso: String): String =
    if (iso.length != 2) "" else iso.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

fun countryName(iso: String): String = Locale("", iso).getDisplayCountry(Locale.US).ifEmpty { iso }
internal fun compass(deg: Double) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8]
