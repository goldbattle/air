package com.aria.air.util

// The overridable clock behind every "now" in the app.

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The wall clock behind every "now" in the flight logic; unit tests pin it. */
var clock: Clock = Clock.systemUTC()

/** Today in the phone's current time zone, by [clock]. */
fun localToday(): LocalDate = LocalDate.ofInstant(Instant.now(clock), ZoneId.systemDefault())
