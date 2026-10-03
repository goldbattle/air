package com.aria.air.api

// NOAA HRRR via Iowa State's IEM: forecast radar for US takeoffs.

import com.aria.air.data.*
import com.aria.air.debug.*
import com.aria.air.util.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Forecast radar for the takeoff time of a US flight: NOAA's HRRR model (hourly runs, 3 km, simulated radar up to
 * 18 h ahead, 48 h from the 00/06/12/18 UTC runs), as plain lon/lat images of the lower 48 from Iowa State's IEM
 * (free, no key, a university service: one image per flight, refreshed when a new run is out). Off by default
 * (Layers › Forecast radar). Elsewhere, or further out, there's no forecast picture.
 */
object NoaaHrrr {
    private const val BASE = "https://mesonet.agron.iastate.edu/data/gis/images/4326/hrrr/"

    /** The images' extent: 3050 x 1340 pixels of 0.02° from 126°W, 50°N. */
    val BOX = GeoBox(-126.0, -65.0, 23.2, 50.0)
    val MAX_AHEAD: Duration = Duration.ofHours(48)

    /** A new run comes out every hour. */
    val EVERY: Duration = Duration.ofMinutes(60)

    private val cache = ConcurrentHashMap<String, RadarImage>()
    private val tried = ConcurrentHashMap<String, Instant>()

    /** Whether a point lies in the HRRR pictures (the lower 48 and a little around). */
    fun inside(lat: Double, lon: Double) = lon in BOX.lonMin..BOX.lonMax && lat in BOX.latMin..BOX.latMax

    /** A flight that hasn't left, with an end in the HRRR area, departing within [MAX_AHEAD]. */
    fun relevant(f: Flight, now: Instant): Boolean {
        val o = f.origin ?: return false; val d = f.dest ?: return false
        val dep = f.dep ?: f.depGuess
        return !f.cancelled && f.tookOff == null && dep > now && Duration.between(now, dep) <= MAX_AHEAD &&
            (inside(o.lat, o.lon) || inside(d.lat, d.lon))
    }

    /** The image's minute offset for [dep] from the run started at [init]: on IEM's 15-minute steps. */
    fun minute(init: Instant, dep: Instant): Int = (Math.round(Duration.between(init, dep).toMinutes() / 15.0) * 15).toInt()

    /**
     * IEM's file for the forecast [minute]s after the latest run started: "json" for its metadata, "png" for the
     * picture.
     */
    fun url(minute: Int, ext: String) = BASE + "refd_%04d.$ext".format(minute)

    /** The forecast picture for [f]'s takeoff, from memory or fetched when a newer run may be out. */
    suspend fun get(f: Flight): RadarImage? {
        val now = Instant.now(clock)
        if (!relevant(f, now)) return null
        if (tried[f.id]?.let { Duration.between(it, now) < EVERY } == true) return cache[f.id]
        tried[f.id] = now
        return runCatching { Layers.loading(Layers.FORECAST) { fetch(f) } }
            .onFailure { Debug.note("forecast radar for ${f.label} failed: $it") }.getOrNull()
            ?.also { cache[f.id] = it } ?: cache[f.id]
    }

    /** Downloads the step nearest [f]'s takeoff from the latest run, or null when that run doesn't reach that far. */
    private suspend fun fetch(f: Flight): RadarImage? = withContext(Dispatchers.IO) {
        val dep = f.dep ?: f.depGuess
        val init = Instant.parse(JSONObject(Http.get(url(0, "json"))).getString("model_init_utc"))
        val m = minute(init, dep)
        if (m < 0) return@withContext null
        // The step must exist in this run (18 h on most runs, 48 h on four a day) and be for our time.
        val meta = runCatching { JSONObject(Http.get(url(m, "json"))) }.getOrNull() ?: return@withContext null
        val valid = Instant.parse(meta.getString("model_forecast_utc"))
        val bytes = Http.bytes(url(m, "png"))
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            ?: return@withContext null
        RadarImage(clear(bmp), BOX, valid, Instant.now(clock))
    }

    /** Black is "no echo": made transparent; weak echoes softer, like the live radar. */
    private fun clear(b: Bitmap): Bitmap {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        for (i in px.indices) {
            val c = px[i] and 0xFFFFFF
            val r = c shr 16; val g = c shr 8 and 0xFF; val bl = c and 0xFF
            px[i] = when {
                r + g + bl < 40 -> 0
                bl > r && bl > g -> 0x90 shl 24 or c // light (blue) echoes softer
                else -> 0xE0 shl 24 or c
            }
        }
        return Bitmap.createBitmap(px, b.width, b.height, Bitmap.Config.ARGB_8888)
    }
}
