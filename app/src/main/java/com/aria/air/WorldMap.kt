package com.aria.air

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max

/** Natural Earth 1:110m coastlines, one ring per line of "lon lat lon lat ...". */
object Land {
    var rings: List<FloatArray> = emptyList(); private set
    fun load(ctx: Context) {
        rings = ctx.assets.open("land.txt").bufferedReader().readLines()
            .map { line -> line.split(' ').map { it.toFloat() }.toFloatArray() }
    }
}

class MapRoute(val from: Airport, val to: Airport, val highlight: Boolean = false)

/**
 * Equirectangular map. [focus] zooms to the routes (flight detail); otherwise the whole world (passport).
 * Routes are drawn as great circles; [plane] adds the live aircraft pointing along its track.
 */
@Composable
fun WorldMap(
    routes: List<MapRoute>,
    modifier: Modifier = Modifier,
    focus: Boolean = false,
    /** Where the routes sit vertically when focused, as a fraction of the height (0.5 = centered). */
    centerY: Float = 0.5f,
    plane: Live? = null,
    land: Color = C.land,
    sea: Color = C.sea,
    line: Color = C.blue,
    dot: Color = C.blue,
    /** Flown, planned and past paths of one flight (Routes.kt); null draws [routes] as plain arcs. */
    paths: MapPaths? = null,
) {
    val arcs = remember(routes) { routes.map { greatCircle(it.from.lat, it.from.lon, it.to.lat, it.to.lon) } }
    val ref = arcs.firstOrNull()?.first()?.second ?: 0.0
    val extra = remember(paths, ref) { paths?.let { p -> listOf(p.flown, p.rest).plus(p.typical).map { unwrap(it, ref) } } }
    val planePainter = rememberVectorPainter(Icons.Filled.Flight)
    // Clipped: land paths extend past the viewport and would otherwise spill over whatever is below the map.
    Canvas(modifier.clipToBounds()) {
        // Viewport in degrees.
        var lonMin = -170.0; var lonMax = 190.0; var latMin = -58.0; var latMax = 80.0
        if (focus && arcs.isNotEmpty()) {
            val pts = arcs.flatten() + extra.orEmpty().flatten()
            lonMin = pts.minOf { it.second }; lonMax = pts.maxOf { it.second }
            latMin = pts.minOf { it.first }; latMax = pts.maxOf { it.first }
            val padLon = max(6.0, (lonMax - lonMin) * 0.25); val padLat = max(4.0, (latMax - latMin) * 0.35)
            lonMin -= padLon; lonMax += padLon; latMin -= padLat; latMax += padLat
        }
        val midLat = (latMin + latMax) / 2
        val kx = if (focus) cos(Math.toRadians(midLat)) else 1.0
        // Fit, then grow the short side so the map fills the box without distortion.
        val cyf = if (focus) centerY else 0.5f
        val usableH = size.height * 2 * minOf(cyf, 1 - cyf)
        val scale = minOf(size.width / ((lonMax - lonMin) * kx), usableH / (latMax - latMin))
        val cLon = (lonMin + lonMax) / 2; val cLat = (latMin + latMax) / 2
        fun x(lon: Double) = (size.width / 2 + (lon - cLon) * kx * scale).toFloat()
        fun y(lat: Double) = (size.height * cyf - (lat - cLat) * scale).toFloat()

        drawRect(sea)
        val landPath = Path()
        for (shift in listOf(-360.0, 0.0, 360.0)) {
            for (r in Land.rings) {
                if (r.size < 6) continue
                landPath.moveTo(x(r[0] + shift), y(r[1].toDouble()))
                var i = 2
                while (i < r.size) { landPath.lineTo(x(r[i] + shift), y(r[i + 1].toDouble())); i += 2 }
                landPath.close()
            }
        }
        drawPath(landPath, land)

        val w = if (focus) 3.dp.toPx() else 1.6.dp.toPx()
        fun stroke(pts: List<Pair<Double, Double>>, color: Color, width: Float, dash: Boolean = false) {
            if (pts.size < 2) return
            val p = Path()
            pts.forEachIndexed { i, (la, lo) -> if (i == 0) p.moveTo(x(lo), y(la)) else p.lineTo(x(lo), y(la)) }
            drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round,
                pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(2.2f * width, 2f * width)) else null))
        }
        // Past paths faint and thin behind; the plan dashed (from the plane on once flying); what was flown solid.
        if (paths != null && extra != null) {
            extra.drop(2).forEach { stroke(it, line.copy(alpha = 0.22f), 1.4.dp.toPx()) }
            if (paths.arc) arcs.forEach { stroke(it, line.copy(alpha = 0.75f), w * 0.8f, dash = true) }
            stroke(extra[1], line.copy(alpha = 0.5f), w * 0.7f, dash = true)
            stroke(extra[0], line, w)
        } else routes.zip(arcs).sortedBy { it.first.highlight }.forEach { (route, pts) ->
            val p = Path()
            pts.forEachIndexed { i, (la, lo) -> if (i == 0) p.moveTo(x(lo), y(la)) else p.lineTo(x(lo), y(la)) }
            drawPath(p, if (route.highlight || focus) line else line.copy(alpha = 0.75f), style = Stroke(w, cap = StrokeCap.Round))
        }
        val r = if (focus) 5.dp.toPx() else 2.6.dp.toPx()
        arcs.forEach { pts ->
            for ((la, lo) in listOf(pts.first(), pts.last())) {
                drawCircle(Color.White, r + 1.5.dp.toPx(), Offset(x(lo), y(la)))
                drawCircle(dot, r, Offset(x(lo), y(la)))
            }
        }
        if (plane != null) {
            // Put the plane on the same unwrapped longitude as the route line.
            val ref = arcs.firstOrNull()?.first()?.second ?: plane.lon
            var lon = plane.lon
            while (lon - ref > 180) lon -= 360
            while (lon - ref < -180) lon += 360
            val s = 26.dp.toPx()
            val c = Offset(x(lon), y(plane.lat))
            drawCircle(Color.White, s * 0.62f, c)
            translate(c.x - s / 2, c.y - s / 2) {
                rotate(plane.track.toFloat(), Offset(s / 2, s / 2)) {
                    with(planePainter) { draw(Size(s, s), colorFilter = ColorFilter.tint(line)) }
                }
            }
        }
    }
}
