package com.aria.air

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuidesTest {
    private val guides = File("src/main/assets/guides/airlines").listFiles()!!.associate { it.nameWithoutExtension to it.readText() }
    private val icons = File("src/main/assets/airline-icons").list()!!.map { it.substringBefore('.') }.toSet()

    /** Every guided airline has an emblem, and every emblem belongs to a guide. */
    @Test
    fun guidesAndIconsMatch() = assertEquals(guides.keys.sorted(), icons.sorted())

    /** Each icon cites where it came from and under what license. */
    @Test
    fun iconsAreCredited() = guides.forEach { (code, text) ->
        val icon = Regex(""""icon": (\{[^}]*})""").find(text)?.groupValues?.get(1)
        assertTrue("$code has no icon credit", icon != null && "\"source\": \"https://" in icon && "\"license\": \"" in icon)
    }
}
