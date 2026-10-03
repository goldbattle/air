package com.aria.air

import com.aria.air.data.*
import com.aria.air.importer.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recipient address, the work-trip default learned from it, and who an import is for. All names and addresses are made up. */
class ImportReviewTest {
    private val printed = """
        Example Air <noreply@example.com> Sun, Aug 23, 2026 at 2:55 PM
        Reply-to: Example Air <reply@example.com>
        To: Jane Q Doe <Jane.Doe@work.example>, jane.doe@mail.example
        Your confirmation code TESTAB
        ---------- Forwarded message ---------
        To: <someone@else.example>
    """.trimIndent()

    @Test
    fun recipientFromFirstToLine() {
        assertEquals("jane.doe@work.example", Importer.recipient(printed))
        // A known address beats the first one.
        assertEquals("jane.doe@mail.example", Importer.recipient(printed) { it == "jane.doe@mail.example" })
        assertNull(Importer.recipient("Your trip to Boston\nUA 100 BOS SFO"))
        assertNull(Importer.recipient("Itinerary sent to jane@x.example")) // not a To: header
    }

    @Test
    fun recipientSurvivesHtmlStripping() {
        val eml = Importer.stripHtml("From: Example Air <a@example.com>\r\nTo: Jane Q Doe <jane@work.example>\r\n\r\n<p>Hi</p>")
        assertEquals("jane@work.example", Importer.recipient(eml))
    }

    @Test
    fun learnedWorkDefault() {
        val learned = mapOf("jane@work.example" to true, "jane@mail.example" to false)
        val corporate = "Booked through Concur\nTo: jane@mail.example"
        // What you chose for this address wins over the text...
        assertEquals(false, Importer.isWork(corporate, "jane@mail.example", learned::get))
        assertEquals(true, Importer.isWork("To: jane@work.example", "jane@work.example", learned::get))
        // ...a new address or no address falls back to it.
        assertEquals(true, Importer.isWork(corporate, "new@x.example", learned::get))
        assertEquals(false, Importer.isWork("Your trip", null, learned::get))
    }

    @Test
    fun travelerChoicesAndDefault() {
        val friends = listOf("Alex Rivera", "Sam Lee")
        assertEquals(listOf("", "Alex Rivera", "Sam Lee"), travelerChoices("Jane Doe", friends, null))
        assertEquals(listOf("", "Alex Rivera", "Sam Lee"), travelerChoices("Jane Doe", friends, "Jane Q Doe"))
        assertEquals(listOf("", "Alex Rivera", "Sam Lee"), travelerChoices("Jane Doe", friends, "Alexj Rivera"))
        assertEquals(listOf("", "Alex Rivera", "Sam Lee", "Chris Park"), travelerChoices("Jane Doe", friends, "Chris Park"))

        assertEquals("", defaultTraveler("Jane Doe", friends, "Jane Q Doe", friendTab = true))
        assertEquals("Alex Rivera", defaultTraveler("Jane Doe", friends, "Alex J Rivera", friendTab = false))
        assertEquals("Chris Park", defaultTraveler("Jane Doe", friends, "Chris Park", friendTab = false))
        assertEquals("", defaultTraveler("Jane Doe", friends, null, friendTab = false))
        assertNull(defaultTraveler("Jane Doe", friends, null, friendTab = true))
        assertTrue(travelerChoices("", emptyList(), "Jane Doe").contains("Jane Doe"))
    }
}
