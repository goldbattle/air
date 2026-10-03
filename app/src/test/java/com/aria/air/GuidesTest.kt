package com.aria.air

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GuidesTest {
    /** Every airline with a bundled guide also has an emblem, so its badge is never a bare monogram. */
    @Test
    fun guidedAirlinesHaveIcons() {
        val guides = File("src/main/assets/guides/airlines").list()!!.map { it.substringBefore('.') }
        val icons = File("src/main/assets/airline-icons").list()!!.map { it.substringBefore('.') }.toSet()
        assertEquals(emptyList<String>(), guides.filter { it !in icons }.sorted())
    }
}
