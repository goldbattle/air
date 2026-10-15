package com.aria.air.sync

// Airport indoor maps: bundled for the big US airports, downloaded and saved for the rest, refreshed monthly.

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
 * Indoor maps by airport code. The big US airports ship with the app (assets/airport-maps, made by
 * tools/airport_maps.py); others are downloaded from OpenStreetMap the first time and saved in filesDir/indoor, so
 * every map opens offline once you have it. A map over a month old is refreshed quietly; if that fails it's kept.
 */
object IndoorMaps {
    private var dir: File? = null // null in unit tests: memory only
    private val lock = Mutex()

    /** A bundled map's Overpass JSON by airport code, null when none ships with the app. Tests swap it. */
    internal var bundled: (String) -> String? = { null }

    /** Maps loaded so far, as Compose state so an open map redraws when its download finishes. */
    val maps = mutableStateMapOf<String, IndoorMap>()

    /** Airports downloading right now. */
    val busy = mutableStateMapOf<String, Boolean>()

    /** The last download error per airport, in words. */
    val errors = mutableStateMapOf<String, String>()

    /** When each airport's download was last tried, so a busy server isn't asked again on every opening. */
    private val tried = mutableMapOf<String, Instant>()

    /** How long a saved map is used before it's downloaded again. */
    val MAX_AGE: Duration = Duration.ofDays(30)

    /** How soon a failed refresh of a map we already have is tried again. */
    private val RETRY: Duration = Duration.ofHours(6)

    fun init(ctx: android.content.Context) {
        dir = File(ctx.filesDir, "indoor").apply { mkdirs() }
        val assets = ctx.assets
        // Kept gzipped in the repo; the Android build unzips .gz assets and drops the extension (the APK is compressed).
        bundled = { code -> runCatching { assets.open("airport-maps/${code.uppercase()}.json").bufferedReader().use { it.readText() } }.getOrNull() }
    }

    private fun file(code: String) = dir?.let { File(it, "${code.uppercase()}.json") }

    /** The map for [code] already on the phone (downloaded, else bundled), or null when there's none yet. */
    fun saved(code: String): IndoorMap? = maps[code]
        ?: file(code)?.takeIf { it.exists() }?.let { runCatching { IndoorMap.fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.also { maps[code] = it }
        ?: bundled(code)?.let { json ->
            runCatching {
                IndoorMap.fromOverpass(code, json, Instant.ofEpochSecond(JSONObject(json).optLong("generated")))
            }.getOrNull()
        }?.also { maps[code] = it }

    /**
     * The map for [code]: the one on the phone, downloaded first when there's none, it's over a month old, or [force].
     * A failed download keeps what we have; the error is recorded for the screen only when there's nothing to show.
     */
    suspend fun get(code: String, force: Boolean = false): IndoorMap? = withContext(Dispatchers.IO) {
        val have = saved(code)
        val now = Instant.now(clock)
        val stale = have == null || Duration.between(have.at, now) >= MAX_AGE
        val recentlyTried = tried[code]?.let { Duration.between(it, now) < RETRY } == true
        if (!force && (!stale || (have != null && recentlyTried))) return@withContext have
        val a = Db.airport(code) ?: return@withContext have
        lock.withLock {
            busy[code] = true
            tried[code] = now
            try {
                val m = Overpass.indoorMap(a, now)
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
    suspend fun prefetch(f: Flight) = withContext(Dispatchers.IO) {
        if (f.phase() == Phase.Done) return@withContext
        for (c in listOf(f.from, f.to)) if (saved(c) == null) get(c)
    }
}
