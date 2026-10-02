package com.aria.air

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Precipitation as it was during a flight, for flights that are over (live radar only keeps the last two hours):
 * NASA GIBS's IMERG half-hourly rain rate, worldwide, free, no key, about two hours behind real time. One low-res
 * picture of the whole globe per 3-hour slot, kept on the phone, shared by every flight in that slot and included in
 * exports: a middle resolution (about 20 km a pixel) that still reads when zoomed in. Sends only the time.
 */
object WxMaps {
    private const val WMS = "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?SERVICE=WMS&REQUEST=GetMap" +
        "&VERSION=1.3.0&LAYERS=IMERG_Precipitation_Rate_30min&CRS=EPSG:4326&FORMAT=image/png&TRANSPARENT=TRUE"
    val WORLD = GeoBox(-180.0, 180.0, -90.0, 90.0)
    /** Flights within the same 3 hours share a picture: storms don't move far in that time at this scale. */
    val SLOT: Duration = Duration.ofHours(3)
    /** IMERG's delay (its "early run" is about 6 h behind); a slot newer than this isn't published yet. */
    val LAG: Duration = Duration.ofHours(7)
    private val tried = ConcurrentHashMap<String, Instant>()
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC)
    private var dir: File? = null
    private val mem = ConcurrentHashMap<String, RadarImage>()

    fun init(ctx: android.content.Context) { dir = File(ctx.filesDir, "wxmaps").apply { mkdirs() } }

    /** The slot [at] falls in, as "yyyyMMddHH" (UTC, hour a multiple of 3). */
    fun key(at: Instant): String {
        val s = at.epochSecond
        return fmt.format(Instant.ofEpochSecond(s - Math.floorMod(s, SLOT.seconds)))
    }

    /** The picture's moment: the middle of the slot, on IMERG's half hours. */
    fun time(key: String): Instant = java.time.LocalDateTime.parse(key, fmt).toInstant(ZoneOffset.UTC).plus(SLOT.dividedBy(2))

    /** The slot for [f]: the middle of the flight. */
    fun keyFor(f: Flight): String {
        val dep = f.dep ?: f.depGuess
        return key(dep.plus(Duration.between(dep, f.arr ?: f.arrGuess).dividedBy(2)))
    }

    /** A flight that's over, recent enough to be published. Upcoming and flying ones get live radar instead. */
    fun relevant(f: Flight, now: Instant) = !f.cancelled && f.origin != null && f.dest != null &&
        (f.arrActual != null || now > f.arrGuess) && time(keyFor(f)) < now.minus(LAG)

    fun url(box: GeoBox, at: Instant, w: Int, h: Int) =
        "$WMS&BBOX=${box.latMin},${box.lonMin},${box.latMax},${box.lonMax}&WIDTH=$w&HEIGHT=$h" +
            "&TIME=${DateTimeFormatter.ISO_INSTANT.format(at)}"

    private fun file(key: String) = dir?.let { File(it, "$key.png") }

    /** The slot's picture from memory or disk, without the network. */
    fun cached(key: String): RadarImage? = mem[key] ?: file(key)?.takeIf { it.exists() }?.let { f ->
        BitmapFactory.decodeFile(f.path)?.let { RadarImage(soften(it), WORLD, time(key), Instant.ofEpochMilli(f.lastModified())) }
            ?.also { mem[key] = it }
    }

    /** The slot's picture, downloading it once if it isn't on the phone yet. */
    suspend fun get(key: String): RadarImage? = cached(key) ?: withContext(Dispatchers.IO) {
        // A slot that isn't out yet comes back blank: don't keep that, try again in a while.
        val now = Instant.now(clock)
        if (tried[key]?.let { Duration.between(it, now) < Duration.ofMinutes(30) } == true) return@withContext null
        tried[key] = now
        runCatching {
            val bytes = Layers.loading(Layers.RADAR) { Radar.tile(url(WORLD, time(key), 2048, 1024)) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            if (blank(bmp)) { Debug.note("rain map $key not published yet"); return@runCatching null }
            file(key)?.writeBytes(bytes)
            RadarImage(soften(bmp), WORLD, time(key), Instant.now(clock)).also { mem[key] = it }
        }.onFailure { Debug.note("rain map $key failed: $it") }.getOrNull()
    }

    /** No rain anywhere on Earth means the slot isn't published (there's always rain somewhere). */
    fun blank(b: Bitmap): Boolean {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return (px.indices step 7).none { px[it] ushr 24 != 0 }
    }

    /** IMERG paints light rain a solid green; let the lightest rates fade so it reads like the live radar. */
    private fun soften(b: Bitmap): Bitmap {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        for (i in px.indices) {
            val c = px[i]
            if (c ushr 24 == 0) continue
            val g = c shr 8 and 0xFF; val r = c shr 16 and 0xFF
            px[i] = (if (r < 60 && g > 100) 0xB0 else 0xF0) shl 24 or (c and 0xFFFFFF) // light (green) softer
        }
        return Bitmap.createBitmap(px, b.width, b.height, Bitmap.Config.ARGB_8888)
    }

    /** Pictures for [flights] that are on the phone, base64, for the export. */
    fun export(flights: List<Flight>): JSONObject = JSONObject().apply {
        flights.map { keyFor(it) }.distinct().forEach { k ->
            file(k)?.takeIf { it.exists() }?.let { put(k, Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) }
        }
    }

    /** Restores pictures from an export (keeps ones already here). */
    fun import(j: JSONObject?) {
        j ?: return
        j.keys().forEach { k ->
            if (Regex("\\d{10}").matches(k)) file(k)?.takeIf { !it.exists() }?.writeBytes(Base64.decode(j.getString(k), Base64.DEFAULT))
        }
    }
}

/**
 * The precipitation under a flight's map: live radar from a few hours before departure to landing, refreshed while
 * shown; afterwards the rain as it was during the flight. [force] changes on pull-to-refresh to fetch what's missing.
 */
@Composable
fun rememberWeatherMap(f: Flight, force: Int = 0): RadarImage? {
    val live = rememberRadar(f)
    val forecast = rememberForecastRadar(f) // before takeoff, US routes, its own layer (off by default)
    var past by remember(f.id) { mutableStateOf(WxMaps.keyFor(f).let { WxMaps.cached(it) }) }
    LaunchedEffect(f.id, force, f.arrActual) {
        if (WxMaps.relevant(f, Instant.now(clock))) past = WxMaps.get(WxMaps.keyFor(f))
    }
    return past?.takeIf { Layers.radar && WxMaps.relevant(f, Instant.now(clock)) } ?: forecast ?: live
}

/** [r] drawn once per world copy, so a world-wide picture also covers routes past ±180°. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWeatherMap(r: RadarImage, x: (Double) -> Float, y: (Double) -> Float, alpha: Float) {
    if (r.box != WxMaps.WORLD) return drawRadar(r, x, y, alpha)
    for (shift in listOf(-360.0, 0.0, 360.0)) drawRadar(r, { x(it + shift) }, y, alpha)
}
