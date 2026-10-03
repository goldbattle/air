package com.aria.air.data

// A flight read out of a document, before it's looked up and saved.

import java.time.LocalDate
import java.time.LocalTime

/** A flight read out of a document, before it's looked up and saved. */
data class Found(
    val airline: String, val number: String, val date: LocalDate?,
    val from: String? = null, val to: String? = null,
    val dep: LocalTime? = null, val arr: LocalTime? = null,
    val seat: String = "", val booking: String = "",
) {
    val label get() = "$airline $number"
}
