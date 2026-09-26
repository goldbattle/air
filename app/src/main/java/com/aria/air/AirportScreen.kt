package com.aria.air

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Hand-curated airport and airline guides bundled in assets/guides (added with the /add-airport and /add-airline
 * Claude skills). The schema is documented in .claude/skills/add-airport/SKILL.md.
 */
object Guides {
    private lateinit var assets: AssetManager
    private val cache = mutableMapOf<String, JSONObject?>()
    private val images = mutableMapOf<String, ImageBitmap?>()

    fun init(ctx: Context) { assets = ctx.assets }

    private fun json(path: String): JSONObject? = cache.getOrPut(path) {
        runCatching { JSONObject(assets.open(path).bufferedReader().readText()) }.getOrNull()
    }

    fun airport(code: String) = json("guides/airports/${code.uppercase()}.json")
    fun airline(code: String) = json("guides/airlines/${code.uppercase()}.json")

    fun image(path: String): ImageBitmap? = images.getOrPut(path) {
        runCatching { assets.open("guides/$path").use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
    }

    fun airlineLogo(code: String) = airline(code)?.optString("logo")?.takeIf { it.isNotEmpty() }?.let { image("airlines/$it") }

    private val palette = listOf(0xFF0A84FF, 0xFF5E5CE6, 0xFFFF375F, 0xFF30B0C7, 0xFFFF9F0A, 0xFF34C759, 0xFFAF52DE, 0xFF8E8E93)

    fun airlineColor(code: String): Color =
        airline(code)?.optString("color")?.takeIf { it.startsWith("#") }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
            ?: Color(palette[Math.floorMod(code.hashCode(), palette.size)])
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }

private fun openUrl(ctx: Context, url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }

@Composable
fun AirportScreen(code: String) {
    val ctx = LocalContext.current
    val a = Db.airport(code)
    val g = Guides.airport(code)
    val mine = Store.flights.filter { it.from == code || it.to == code }.sortedByDescending { it.depGuess }
    var zoom by remember { mutableStateOf<ImageBitmap?>(null) }

    Page(code, onBack = { Nav.pop() }) {
        item("head") {
            Text(a?.name ?: "Unknown airport", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
            Text(
                "${flag(a?.country ?: "")} ${a?.city ?: ""}, ${countryName(a?.country ?: "")} · ${a?.icao ?: ""}",
                fontSize = 15.sp, color = C.secondary, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
            )
            if (a != null) {
                val routes = mine.mapNotNull { f -> f.origin?.let { o -> f.dest?.let { d -> MapRoute(o, d) } } }.ifEmpty { listOf(MapRoute(a, a)) }
                WorldMap(routes, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)), focus = true)
                Spacer(Modifier.height(12.dp))
                Group {
                    Cell("Local time", time(Instant.now(), a.tz), icon = Icons.Filled.Schedule)
                    Hairline(50.dp)
                    Cell("Time zone", a.tz.id.replace('_', ' '), icon = Icons.Filled.Public)
                    Hairline(50.dp)
                    Cell("Your flights here", "${mine.size}", icon = Icons.Filled.Flight)
                }
            }
        }
        if (g == null) item("noguide") {
            Footnote("No guide for $code yet. In Claude Code, run /add-airport $code to add terminals, maps, lounges and tips.")
        }
        g?.optString("summary")?.takeIf { it.isNotEmpty() }?.let { item("summary") { SectionHeader("About"); Group { Text(it, fontSize = 15.sp, modifier = Modifier.padding(16.dp)) } } }
        g?.optJSONArray("terminals").objects().takeIf { it.isNotEmpty() }?.let { terms ->
            item("terminals") {
                SectionHeader("Terminals")
                Group {
                    terms.forEachIndexed { i, t ->
                        if (i > 0) Hairline()
                        Column(Modifier.padding(16.dp)) {
                            Text(t.optString("name"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            val airlines = t.optJSONArray("airlines").strings()
                            if (airlines.isNotEmpty()) Text(airlines.joinToString(", ") { Db.airline(it)?.name ?: it }, fontSize = 15.sp, color = C.secondary)
                            t.optString("gates").takeIf { it.isNotEmpty() }?.let { Text("Gates $it", fontSize = 15.sp, color = C.secondary) }
                            t.optString("notes").takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
            }
        }
        g?.optJSONArray("maps").objects().takeIf { it.isNotEmpty() }?.let { maps ->
            item("maps") {
                SectionHeader("Maps")
                maps.forEach { m ->
                    val img = m.optString("image").takeIf { it.isNotEmpty() }?.let { Guides.image("airports/$it") }
                    Group(Modifier.padding(bottom = 10.dp)) {
                        if (img != null) Image(
                            img, m.optString("title"),
                            Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Color.White).clickable { zoom = img },
                            contentScale = ContentScale.Fit,
                        )
                        Cell(
                            m.optString("title"), subtitle = if (img != null) "Tap the map to zoom" else null,
                            icon = Icons.Filled.Map, chevron = m.optString("url").isNotEmpty(),
                            onClick = m.optString("url").takeIf { it.isNotEmpty() }?.let { u -> { openUrl(ctx, u) } },
                        )
                    }
                }
            }
        }
        g?.optJSONArray("lounges").objects().takeIf { it.isNotEmpty() }?.let { lounges ->
            item("lounges") {
                SectionHeader("Lounges")
                Group {
                    lounges.forEachIndexed { i, l ->
                        if (i > 0) Hairline(50.dp)
                        Cell(l.optString("name"), subtitle = listOf(l.optString("where"), l.optString("access")).filter { it.isNotEmpty() }.joinToString(" · "), icon = Icons.Filled.Weekend)
                    }
                }
            }
        }
        listOf("transport" to "Getting there", "tips" to "Tips").forEach { (key, title) ->
            g?.optJSONArray(key).strings().takeIf { it.isNotEmpty() }?.let { lines ->
                item(key) {
                    SectionHeader(title)
                    Group {
                        lines.forEachIndexed { i, s ->
                            if (i > 0) Hairline()
                            Text(s, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
        g?.let { guide -> item("links") { Links(guide) } }
        if (mine.isNotEmpty()) item("history") {
            SectionHeader("Your flights")
            Group {
                mine.take(10).forEachIndexed { i, f ->
                    if (i > 0) Hairline()
                    Cell(
                        "${f.label}  ${f.from} → ${f.to}", subtitle = dayLabel(f.date) + if (!f.mine) " · ${f.friend}" else "", chevron = true,
                    ) { Nav.push(Screen.FlightDetail(f.id)) }
                }
            }
        }
        g?.optString("updated")?.takeIf { it.isNotEmpty() }?.let { item("updated") { Footnote("Guide updated $it") } }
    }
    zoom?.let { ZoomDialog(it) { zoom = null } }
}

@Composable
fun AirlineScreen(code: String) {
    val al = Db.airline(code)
    val g = Guides.airline(code)
    val mine = Store.flights.filter { it.airline == code && it.mine }
    Page(al?.name ?: code, onBack = { Nav.pop() }, titleSize = 28.sp) {
        item("head") {
            Group {
                Cell("Codes", "$code · ${al?.icao ?: ""}")
                g?.optString("alliance")?.takeIf { it.isNotEmpty() }?.let { Hairline(); Cell("Alliance", it) }
                g?.optJSONArray("hubs").strings().takeIf { it.isNotEmpty() }?.let { Hairline(); Cell("Hubs", it.joinToString(", ")) }
                Hairline()
                Cell("Your flights", "${mine.size}")
            }
            g?.optString("summary")?.takeIf { it.isNotEmpty() }?.let { Footnote(it) }
        }
        listOf("checkin" to "Check-in", "baggage" to "Baggage").forEach { (key, title) ->
            g?.optString(key)?.takeIf { it.isNotEmpty() }?.let { text ->
                item(key) { SectionHeader(title); Group { Text(text, fontSize = 15.sp, modifier = Modifier.padding(16.dp)) } }
            }
        }
        g?.optJSONArray("tips").strings().takeIf { it.isNotEmpty() }?.let { tips ->
            item("tips") {
                SectionHeader("Tips")
                Group { tips.forEachIndexed { i, s -> if (i > 0) Hairline(); Text(s, fontSize = 15.sp, modifier = Modifier.padding(16.dp)) } }
            }
        }
        g?.let { guide -> item("links") { Links(guide) } }
    }
}

@Composable
private fun Links(g: JSONObject) {
    val ctx = LocalContext.current
    val links = g.optJSONArray("links").objects()
    if (links.isEmpty()) return
    SectionHeader("Links")
    Group {
        links.forEachIndexed { i, l ->
            if (i > 0) Hairline(50.dp)
            Cell(l.optString("title"), icon = Icons.Filled.Link, chevron = true) { openUrl(ctx, l.optString("url")) }
        }
    }
}

@Composable
private fun ZoomDialog(img: ImageBitmap, onDismiss: () -> Unit) = Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = rememberTransformableState { z, pan, _ -> scale = (scale * z).coerceIn(1f, 8f); offset += pan }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
        Image(
            img, null,
            Modifier.fillMaxSize().transformable(state)
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            contentScale = ContentScale.Fit,
        )
    }
}
