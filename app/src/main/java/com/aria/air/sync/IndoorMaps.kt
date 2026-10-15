package com.aria.air.sync

// Saved airport indoor maps: downloaded once, kept for a month, and fetched ahead for upcoming flights.

import com.aria.air.api.*
import com.aria.air.data.*
import com.aria.air.util.*
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Indoor maps by airport code, saved in filesDir/indoor so they open offline (at the gate, or in the air on the way
 * there). Maps change slowly, so a saved one is refreshed only once it's a month old.
 */
object IndoorMaps {
    private var dir: File? = null // null in unit tests: memory only
    private val lock = Mutex()

    /** Maps loaded so far, as Compose state so an open map redraws when its download finishes. */
    val maps = mutableStateMapOf<String, IndoorMap>()

    /** Airports downloading right now. */
    val busy = mutableStateMapOf<String, Boolean>()

    /** The last download error per airport, in words. */
    val errors = mutableStateMapOf<String, String>()

    /** How long a saved map is used before it's downloaded again. */
    val MAX_AGE: Duration = Duration.ofDays(30)

    fun init(ctx: android.content.Context) { dir = File(ctx.filesDir, "indoor").apply { mkdirs() } }

    private fun file(code: String) = dir?.let { File(it, "${code.uppercase()}.json") }

    /** The saved map for [code], from memory or disk; null when there's none yet. */
    fun saved(code: String): IndoorMap? = maps[code] ?: file(code)?.takeIf { it.exists() }
        ?.let { runCatching { IndoorMap.fromJson(JSONObject(it.readText())) }.getOrNull() }
        ?.also { maps[code] = it }

    /**
     * The map for [code]: the saved one, downloaded first when there's none, it's over a month old, or [force]. A failed
     * download keeps the saved map and records the error.
     */
    suspend fun get(code: String, force: Boolean = false): IndoorMap? = withContext(Dispatchers.IO) {
        val have = saved(code)
        if (have != null && !force && Duration.between(have.at, Instant.now(clock)) < MAX_AGE) return@withContext have
        val a = Db.airport(code) ?: return@withContext have
        lock.withLock {
            busy[code] = true
            try {
                val m = Overpass.indoorMap(a, Instant.now(clock))
                file(code)?.writeText(m.toJson().toString())
                maps[code] = m
                errors.remove(code)
                m
            } catch (e: Exception) {
                errors[code] = e.message ?: "No connection"
                have
            } finally {
                busy.remove(code)
            }
        }
    }

    /** Downloads both airports' maps ahead of an upcoming flight, so they're there offline. */
    suspend fun prefetch(f: Flight) {
        if (f.phase() == Phase.Done) return
        for (c in listOf(f.from, f.to)) if (saved(c) == null) get(c)
    }
}
