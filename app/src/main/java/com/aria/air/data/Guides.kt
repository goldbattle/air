package com.aria.air.data

// Bundled airport and airline guides, airline emblems and colors.

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject

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
        runCatching { assets.open(path).use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
    }

    /** Airline emblem from assets/airline-icons (sources in CREDITS.md), drawn on [airlineIconBg]. */
    fun airlineIcon(code: String) = image("airline-icons/${code.uppercase()}.png")

    /** Light gray behind colored emblems (shows on white cards); brand color behind the ones drawn in white. */
    fun airlineIconBg(code: String) = Color(when (code.uppercase()) { "LH" -> 0xFF05164D; "KL" -> 0xFF00A1DE; else -> 0xFFF2F2F7 })

    private val palette = listOf(0xFF0A84FF, 0xFF5E5CE6, 0xFFFF375F, 0xFF30B0C7, 0xFFFF9F0A, 0xFF34C759, 0xFFAF52DE, 0xFF8E8E93)

    fun airlineColor(code: String): Color =
        airline(code)?.optString("color")?.takeIf { it.startsWith("#") }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
            ?: Color(palette[Math.floorMod(code.hashCode(), palette.size)])
}
