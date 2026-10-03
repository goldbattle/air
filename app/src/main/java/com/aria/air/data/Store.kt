package com.aria.air.data

// All flights, in flights.json, plus backup export and import.

import com.aria.air.api.*
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** All flights, in one JSON file in app storage. Mutate on the main thread only. */
object Store {
    /** Every saved flight, as Compose state so screens follow changes. */
    val flights = mutableStateListOf<Flight>()
    internal lateinit var file: File

    /** Called after every save (the app refreshes the home screen widget). */
    var onChange: (() -> Unit)? = null

    /** Loads flights.json from app storage; call once at startup. */
    fun init(ctx: Context) {
        file = File(ctx.filesDir, "flights.json")
        if (file.exists()) flights.addAll(parse(file.readText()))
    }

    /**
     * Flights from flights.json or backup text. A record that can't be read is skipped instead of failing the whole
     * file.
     */
    fun parse(text: String): List<Flight> {
        val arr = JSONObject(text).getJSONArray("flights")
        return (0 until arr.length()).mapNotNull { runCatching { flightFrom(arr.getJSONObject(it)) }.getOrNull() }
    }

    /** [list] in the flights.json format, with the traveler's name. */
    fun json(list: List<Flight> = flights): String =
        JSONObject().put("app", "air").put("version", 1).put("name", Prefs.name).put("flights", JSONArray(list.map { it.toJson() })).toString()

    /** A backup: the flights plus the rain maps of their flights (NasaGibs), so a restore looks the same. */
    fun exportJson(): String = JSONObject(json()).put("wxmaps", NasaGibs.export(flights)).toString()

    /** Write to a temp file and rename, so a crash mid-write never loses the file. */
    private fun save() = synchronized(this) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json())
        tmp.renameTo(file)
        onChange?.invoke()
    }

    /** The saved flight with this id, or null (deleted, or never saved). */
    fun get(id: String?) = flights.firstOrNull { it.id == id }

    /**
     * Saves [f]: replaces the flight with the same id, or adds it. Tracks from both copies are kept, since the worker
     * and an open page can save the same flight at once.
     */
    fun upsert(f: Flight) {
        val i = flights.indexOfFirst { it.id == f.id }
        if (i >= 0) flights[i] = f.copy(track = mergeTracks(flights[i].track, f.track)) else flights.add(f)
        save()
    }

    /** Deletes the flight with this id. */
    fun remove(id: String) {
        flights.removeAll { it.id == id }
        save()
    }

    /** Same flight number, date and traveler = same flight (so re-imports don't duplicate). */
    fun findSame(f: Flight) = flights.firstOrNull { it.code == f.code && it.date == f.date && it.friend.equals(f.friend, true) }

    /** Whether [text] is an Air backup (or a flights.json): JSON that says "app": "air" near the start. */
    fun isBackup(text: String) = text.trimStart().startsWith("{") && Regex("\"app\"\\s*:\\s*\"air\"").containsMatchIn(text.take(200))

    /**
     * Restores a backup: takes its traveler's name if none is set, restores its rain maps, and adds the flights that
     * aren't here yet (same id, or same number, date and traveler, are left alone). Returns how many were added.
     */
    fun import(text: String): Int {
        // A backup also carries the traveler's name; take it if none is set yet.
        runCatching { JSONObject(text).optString("name") }.getOrNull()?.takeIf { it.isNotBlank() && Prefs.name.isBlank() }?.let { Prefs.name = it }
        runCatching { NasaGibs.import(JSONObject(text).optJSONObject("wxmaps")) }
        val known = flights.map { it.id }.toSet()
        val add = parse(text).filter { it.id !in known && findSame(it) == null }
        flights.addAll(add)
        save()
        return add.size
    }

    /** Adds flights that aren't already stored (same number, date and traveler). Returns how many were added. */
    fun addAll(list: List<Flight>): Int {
        val add = list.filter { findSame(it) == null }.distinctBy { Triple(it.code, it.date, it.friend) }
        flights.addAll(add)
        save()
        return add.size
    }

    /** Names of the friends whose flights are saved, sorted. */
    val friends: List<String> get() = flights.filter { !it.mine }.map { it.friend }.distinct().sorted()
}
