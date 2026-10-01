package com.aria.air

import org.junit.After
import org.junit.Before
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Base for the JVM tests: bundled airports/airlines, US formatting, and the clock pinned to [NOW]. */
abstract class AirTest {
    companion object {
        /** Fri, Oct 2, 2026, 5:00 AM in San Francisco. */
        val NOW: Instant = Instant.parse("2026-10-02T12:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 10, 2)
    }

    @Before
    fun pin() {
        if (Db.airports.isEmpty()) Db.load(File("src/main/assets/airports.tsv").reader(), File("src/main/assets/airlines.tsv").reader())
        Locale.setDefault(Locale.US)
        clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    @After
    fun unpin() {
        clock = Clock.systemUTC()
    }

    fun min(m: Long): Duration = Duration.ofMinutes(m)
    fun hours(h: Long): Duration = Duration.ofHours(h)

    /** A UA 100 SFO -> BOS (5 h) leaving [depIn] from now, or with no times when [depIn] is null. */
    fun flight(depIn: Duration? = hours(24), block: Duration = hours(5), from: String = "SFO", to: String = "BOS") = Flight(
        airline = "UA", number = "100", date = TODAY, from = from, to = to,
        depSched = depIn?.let { NOW.plus(it) }, arrSched = depIn?.let { NOW.plus(it).plus(block) },
    )
}
