package com.aria.air

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Which flights get HRRR forecast radar, and which image step (ForecastRadar.kt). */
class ForecastRadarTest : AirTest() {
    @Test
    fun steps() {
        val init = Instant.parse("2026-10-02T11:00:00Z")
        assertEquals(60, ForecastRadar.minute(init, Instant.parse("2026-10-02T12:00:00Z")))
        assertEquals(75, ForecastRadar.minute(init, Instant.parse("2026-10-02T12:10:00Z"))) // nearest 15 minutes
        assertEquals("https://mesonet.agron.iastate.edu/data/gis/images/4326/hrrr/refd_0075.png", ForecastRadar.url(75, "png"))
    }

    @Test
    fun onlyUpcomingUsFlights() {
        assertTrue(ForecastRadar.relevant(flight(hours(5)), NOW))                        // SFO-BOS tomorrow-ish
        assertFalse(ForecastRadar.relevant(flight(hours(50)), NOW))                      // past HRRR's 48 h
        assertFalse(ForecastRadar.relevant(flight(hours(-1)), NOW))                      // already left
        assertFalse(ForecastRadar.relevant(flight(hours(5), from = "LHR", to = "CDG"), NOW)) // outside the US
        assertTrue(ForecastRadar.relevant(flight(hours(5), from = "LHR", to = "BOS"), NOW))  // one end in the US
    }
}
