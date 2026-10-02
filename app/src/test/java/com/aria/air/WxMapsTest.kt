package com.aria.air

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The 3-hour slots flights share a rain map in (WxMaps.kt). */
class WxMapsTest : AirTest() {
    @Test
    fun slots() {
        assertEquals("2026100209", WxMaps.key(Instant.parse("2026-10-02T09:00:00Z")))
        assertEquals("2026100209", WxMaps.key(Instant.parse("2026-10-02T11:59:00Z")))
        assertEquals("2026100212", WxMaps.key(Instant.parse("2026-10-02T12:00:00Z")))
        assertEquals(Instant.parse("2026-10-02T10:30:00Z"), WxMaps.time("2026100209")) // the middle, on a half hour
        // A flight's slot is its middle: 5 h from 2 h ago lands in the 09-12 slot at NOW (12:00) - 2h + 2.5h = 12:30.
        assertEquals("2026100212", WxMaps.keyFor(flight(hours(-2))))
        // Only finished flights, once IMERG has published that slot.
        assertFalse(WxMaps.relevant(flight(hours(2)), NOW))
        assertFalse(WxMaps.relevant(flight(hours(-5).minus(min(1))), NOW)) // just landed, middle 2.5 h ago: not published yet
        assertFalse(WxMaps.relevant(flight(hours(-8)), NOW))  // middle 5.5 h ago: IMERG is still ~6 h behind
        assertTrue(WxMaps.relevant(flight(hours(-12)), NOW))
    }
}
