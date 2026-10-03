package com.aria.air.api

// RainViewer: live precipitation radar tiles, reprojected onto the route's box.

import com.aria.air.data.*
import com.aria.air.debug.*
import com.aria.air.util.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Layers
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Live precipitation radar under the route, from RainViewer's free Weather Maps API (personal use, Web Mercator
 * tiles up to zoom 7, Universal Blue colors only). Sends only tile coordinates. The latest picture per flight is
 * also kept in the app's cache folder, so it's still there offline (on board) for a while; it's about now, so it isn't
 * stored with the flight or in backups.
 */
object RainViewer {
    const val TILE = 512 // RainViewer's large tiles: twice the detail at the same zoom
    private const val MAX_ZOOM = 7
    private const val MAX_TILES = 9
    /** RainViewer publishes a frame every 10 minutes. */
    val EVERY: Duration = Duration.ofMinutes(10)
    /** Radar shows now; hours before departure it says nothing about the flight. */
    val LEAD: Duration = Duration.ofHours(6)
    private const val MAPS = "https://api.rainviewer.com/public/weather-maps.json"

    fun tileX(lon: Double, z: Int) = (lon + 180) / 360 * (1 shl z)
    fun tileY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)
    }
    fun tileLat(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))

    /**
     * What to cover for a route: its great circle with room around it, and tall enough for a portrait map (the
     * full-screen one); the edges fade out (see [fade]) for whatever lies beyond.
     */
    fun box(arc: List<Pair<Double, Double>>): GeoBox {
        val lonMin = arc.minOf { it.second }; val lonMax = arc.maxOf { it.second }
        val latMin = arc.minOf { it.first }; val latMax = arc.maxOf { it.first }
        val padLon = max(10.0, (lonMax - lonMin) * 0.5)
        val kx = cos(Math.toRadians((latMin + latMax) / 2))
        val half = max((latMax - latMin) / 2 + max(7.0, (latMax - latMin) * 0.8), (lonMax - lonMin + 2 * padLon) * kx * 0.75)
        val cLat = (latMin + latMax) / 2
        return GeoBox(lonMin - padLon, lonMax + padLon, (cLat - half).coerceAtLeast(-85.0), (cLat + half).coerceAtMost(85.0))
    }

    /** Fades [px] (w by h) out toward its edges, so the radar never ends in a hard line. */
    fun fade(px: IntArray, w: Int, h: Int): IntArray {
        fun ramp(i: Int, n: Int) = (minOf(i, n - 1 - i) / (n * 0.1)).coerceAtMost(1.0)
        for (j in 0 until h) for (i in 0 until w) {
            val c = px[j * w + i]
            if (c != 0) px[j * w + i] = ((c ushr 24) * ramp(i, w) * ramp(j, h)).toInt() shl 24 or (c and 0xFFFFFF)
        }
        return px
    }

    /** Tiles x0 until x0+nx, y0 until y0+ny at zoom [z]; x unwrapped (can be negative or past the last column). */
    data class Plan(val z: Int, val x0: Int, val y0: Int, val nx: Int, val ny: Int)

    /** The sharpest zoom (up to RainViewer's 7) at which [b] takes at most [MAX_TILES] tiles. */
    fun plan(b: GeoBox): Plan {
        fun at(z: Int): Plan {
            val x0 = floor(tileX(b.lonMin, z)).toInt(); val x1 = floor(tileX(b.lonMax, z)).toInt()
            val y0 = floor(tileY(b.latMax, z)).toInt(); val y1 = floor(tileY(b.latMin, z)).toInt().coerceAtMost((1 shl z) - 1)
            return Plan(z, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        }
        return (MAX_ZOOM downTo 1).map(::at).firstOrNull { it.nx * it.ny <= MAX_TILES } ?: at(1)
    }

    /** Smoothed, snow shown, Universal Blue (scheme 2, the only one left on the free API). */
    fun tileUrl(frame: String, z: Int, x: Int, y: Int) = "$frame/$TILE/$z/${Math.floorMod(x, 1 shl z)}/$y/2/1_1.png"

    /** Output size for [b]: about the tiles' own resolution. */
    fun size(p: Plan, b: GeoBox): Pair<Int, Int> =
        ((tileX(b.lonMax, p.z) - tileX(b.lonMin, p.z)) * TILE).roundToInt().coerceIn(1, 1200) to
            ((tileY(b.latMin, p.z) - tileY(b.latMax, p.z)) * TILE).roundToInt().coerceIn(1, 1200)

    /**
     * The plan's [tiles] (row by row, each TILE*TILE ARGB in Web Mercator, null if missing) resampled to a [w] by [h]
     * image of [b] that is linear in longitude and latitude, like the map, so it can be stretched over the box's corners.
     */
    fun reproject(tiles: Array<IntArray?>, p: Plan, b: GeoBox, w: Int, h: Int): IntArray {
        val sw = p.nx * TILE; val sh = p.ny * TILE
        val out = IntArray(w * h)
        val cols = IntArray(w) { i -> floor((tileX(b.lonMin + (i + 0.5) / w * (b.lonMax - b.lonMin), p.z) - p.x0) * TILE).toInt() }
        for (j in 0 until h) {
            val sy = floor((tileY(b.latMax - (j + 0.5) / h * (b.latMax - b.latMin), p.z) - p.y0) * TILE).toInt()
            if (sy !in 0 until sh) continue
            for (i in 0 until w) {
                val sx = cols[i]
                if (sx in 0 until sw) tiles[sy / TILE * p.nx + sx / TILE]?.let { out[j * w + i] = it[sy % TILE * TILE + sx % TILE] }
            }
        }
        return out
    }

    /**
     * Universal Blue (the only free scheme) as a calm green, yellow, red: the faint beige below rain goes, the blues
     * (light to moderate rain, darker = heavier) become greens, yellow and up stay as they are.
     */
    fun recolor(c: Int): Int {
        val a = c ushr 24; val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        return when {
            a < 255 || b <= r -> if (a == 255) c else 0
            else -> {
                val t = (1 - (r + g) / 357.0).coerceIn(0.0, 1.0) // 0 light cyan .. 1 dark blue
                ((150 + 105 * t).toInt() shl 24) or ((110 - 90 * t).toInt() shl 16) or ((210 - 60 * t).toInt() shl 8) or (90 - 40 * t).toInt()
            }
        }
    }

    /** Whether radar means anything for [f] now: from [LEAD] before departure to landing. */
    fun relevant(f: Flight, now: Instant) = !f.cancelled && f.origin != null && f.dest != null &&
        now >= f.depGuess.minus(LEAD) && now < (f.arr ?: f.arrGuess).plus(NasaGibs.LAG)

    /** Relevant and the radar layer is on. The layer only hides the picture; fetching and the cache ignore it. */
    fun shown(f: Flight, now: Instant) = Layers.radar && relevant(f, now)

    fun due(lastTry: Instant?, now: Instant) = lastTry == null || Duration.between(lastTry, now) >= EVERY

    private val cache = ConcurrentHashMap<String, RadarImage>()
    private val tried = ConcurrentHashMap<String, Instant>()

    /** The latest frame's tile path prefix and time. */
    suspend fun frame(near: Instant? = null): Pair<String, Instant>? = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(Http.get(MAPS))
            val past = j.getJSONObject("radar").getJSONArray("past")
            val frames = (0 until past.length()).map { past.getJSONObject(it) }
            val pick = near?.let { t -> frames.minByOrNull { Math.abs(it.getLong("time") - t.epochSecond) } } ?: frames.last()
            j.getString("host") + pick.getString("path") to Instant.ofEpochSecond(pick.getLong("time"))
        }.getOrNull()
    }


    /**
     * Radar for [f]: fetched again once [EVERY] has passed since the last try (success or not), else from memory.
     * Null when it isn't shown or there's none (offline, no coverage); the map just goes without.
     */
    suspend fun get(f: Flight): RadarImage? {
        val now = Instant.now(clock)
        if (!relevant(f, now)) {
            cache.remove(f.id)
            dir?.let { d -> java.io.File(d, "${f.id}.png").delete(); java.io.File(d, "${f.id}.txt").delete() }
            return null
        }
        if (due(tried[f.id], now)) {
            tried[f.id] = now
            runCatching { Layers.loading(Layers.RADAR) { fetch(f) } }.onFailure { Debug.note("radar for ${f.label} failed: $it") }.getOrNull()
                ?.let { cache[f.id] = it; save(f.id, it) }
        }
        return cached(f)
    }

    /** How long a picture stays worth showing: through a stretch offline, but storms move, so not for hours. */
    val KEEP: Duration = Duration.ofMinutes(90)
    private var dir: java.io.File? = null

    fun init(ctx: android.content.Context) { dir = java.io.File(ctx.cacheDir, "radar").apply { mkdirs() } }

    /** What's in memory or on disk for [f], without asking the network. An old picture is worse than none. */
    fun cached(f: Flight): RadarImage? {
        val now = Instant.now(clock)
        val r = cache[f.id] ?: load(f.id)?.also { cache[f.id] = it }
        return r?.takeIf { shown(f, now) && Duration.between(it.fetched, now) < KEEP }
    }

    private fun save(id: String, r: RadarImage) = runCatching {
        val d = dir ?: return@runCatching
        java.io.File(d, "$id.png").outputStream().use { r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        java.io.File(d, "$id.txt").writeText(
            listOf(r.box.lonMin, r.box.lonMax, r.box.latMin, r.box.latMax, r.frame.toEpochMilli(), r.fetched.toEpochMilli()).joinToString(","),
        )
    }

    private fun load(id: String): RadarImage? = runCatching {
        val d = dir ?: return null
        val m = java.io.File(d, "$id.txt").readText().split(",")
        val bmp = BitmapFactory.decodeFile(java.io.File(d, "$id.png").path) ?: return null
        RadarImage(
            bmp, GeoBox(m[0].toDouble(), m[1].toDouble(), m[2].toDouble(), m[3].toDouble()),
            Instant.ofEpochMilli(m[4].toLong()), Instant.ofEpochMilli(m[5].toLong()),
        )
    }.getOrNull()

    private suspend fun fetch(f: Flight): RadarImage? {
        val o = f.origin!!; val d = f.dest!!
        // Landed: the frame closest to the middle of the flight (RainViewer keeps two hours), until the rain map is out.
        val landed = f.arrActual != null || Instant.now(clock) > f.arrGuess
        val dep = f.dep ?: f.depGuess
        val mid = dep.plus(Duration.between(dep, f.arr ?: f.arrGuess).dividedBy(2))
        return fetchBox(box(greatCircle(o.lat, o.lon, d.lat, d.lon)), if (landed) mid else null)
    }

    /** Radar over a box: the latest frame, or the one nearest [near]. */
    suspend fun fetchBox(b: GeoBox, near: Instant? = null): RadarImage? = withContext(Dispatchers.IO) {
        val (frame, at) = frame(near) ?: return@withContext null
        val p = plan(b)
        val tiles = Array(p.nx * p.ny) { k ->
            val bytes = runCatching { Http.bytes(tileUrl(frame, p.z, p.x0 + k % p.nx, p.y0 + k / p.nx)) }.getOrNull() ?: return@Array null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width == TILE && it.height == TILE }
                ?.let { bmp -> IntArray(TILE * TILE).also { bmp.getPixels(it, 0, TILE, 0, 0, TILE, TILE); bmp.recycle() } }
        }
        if (tiles.all { it == null }) return@withContext null
        val (w, h) = size(p, b)
        RadarImage(Bitmap.createBitmap(fade(reproject(tiles, p, b, w, h).map(::recolor).toIntArray(), w, h), w, h, Bitmap.Config.ARGB_8888), b, at, Instant.now(clock))
    }
}
