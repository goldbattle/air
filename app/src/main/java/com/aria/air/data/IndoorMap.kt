package com.aria.air.data

// Airport indoor maps from OpenStreetMap: terminals, rooms, gates and points of interest, by floor.

import com.aria.air.util.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** What a mapped thing is, with the icon and name shown for it. Areas first, then gates, then points of interest. */
enum class Place(val icon: String, val label: String) {
    APRON("", "Apron"), TERMINAL("", "Terminal"), AREA("", "Concourse"), ROOM("", "Room"),
    GATE("", "Gate"),
    FOOD("🍔", "Food"), COFFEE("☕", "Coffee"), BAR("🍸", "Bar"), SHOP("🛍️", "Shop"),
    TOILETS("🚻", "Restrooms"), WATER("🚰", "Water"), LOUNGE("🛋️", "Lounge"), MONEY("🏧", "ATM & money"),
    CHARGING("🔌", "Charging"), MEDICAL("⚕️", "Pharmacy & medical"), INFO("ℹ️", "Information"),
    SECURITY("🛂", "Security"), CHECKIN("🎫", "Check-in"), BAGGAGE("🛄", "Baggage claim"),
    ELEVATOR("🛗", "Elevator"), STAIRS("↕️", "Stairs & escalators"), WORSHIP("🛐", "Prayer room");

    /** Drawn as a shape only; no icon. */
    val area get() = ordinal <= ROOM.ordinal
}

/**
 * One mapped thing: its kind, name, gate or shop number ([ref]), the floors it's on ([levels], empty = all of them),
 * opening hours, and its outline as rings of points (a single point for a node).
 */
data class Feature(
    val kind: Place, val name: String = "", val ref: String = "", val levels: List<Double> = emptyList(),
    val hours: String = "", val rings: List<List<Pt>>,
) {
    /** Where its icon and label go: the average of its outer ring's corners. */
    // ponytail: vertex average, can land outside an L-shaped room; use a polylabel if that ever looks wrong.
    val at: Pt by lazy { rings.first().let { r -> Pt(r.sumOf { it.lat } / r.size, r.sumOf { it.lon } / r.size) } }

    /** On floor [level] (things without a floor show on every floor). */
    fun on(level: Double?) = level == null || levels.isEmpty() || level in levels
}

/** An airport's indoor map, as downloaded from OpenStreetMap at [at]. */
data class IndoorMap(val code: String, val at: Instant, val features: List<Feature>) {
    /**
     * Floors worth offering, lowest first: those with at least a few things mapped on them. Elevators and stairs don't
     * count, or a parking garage's elevators would add a floor per deck.
     */
    val levels: List<Double> by lazy {
        features.filter { it.kind != Place.ELEVATOR && it.kind != Place.STAIRS }.flatMap { it.levels }
            .groupingBy { it }.eachCount().filter { it.value >= 3 }.keys.sorted()
    }

    /** The floor to open on: the one with the most rooms and places (usually the departures level). */
    val mainLevel: Double? by lazy {
        features.filter { it.kind >= Place.ROOM && it.kind != Place.ELEVATOR && it.kind != Place.STAIRS }.flatMap { it.levels }
            .groupingBy { it }.eachCount().filterKeys { it in levels }.maxByOrNull { it.value }?.key
    }

    /** The gate called [gate] ("A8", "a 8", "Gate A8", or one end of a mapped "G11-G12"), or null when it isn't mapped. */
    fun gate(gate: String): Feature? {
        val want = gateKey(gate).ifEmpty { return null }
        return features.firstOrNull { it.kind == Place.GATE && (gateKeys(it.ref) + gateKeys(it.name)).contains(want) }
    }

    /**
     * The mapped gate closest in number to [gate] in the same concourse (F5 for an unmapped F3), so the map still opens
     * in the right place; null when the concourse has none.
     */
    fun nearGate(gate: String): Feature? {
        val (letters, num) = Regex("^([A-Z]*)([0-9]+)").find(gateKey(gate))?.destructured ?: return null
        return features.filter { it.kind == Place.GATE }.mapNotNull { f ->
            gateKeys(f.ref).mapNotNull { k -> Regex("^([A-Z]*)([0-9]+)$").find(k)?.destructured }
                .filter { it.component1() == letters }.minOfOrNull { kotlin.math.abs(it.component2().toInt() - num.toInt()) }
                ?.let { f to it }
        }.minByOrNull { it.second }?.first
    }

    /** Saved form: short keys and coordinates rounded to about 10 cm. */
    fun toJson(): JSONObject = JSONObject().put("code", code).put("at", at.epochSecond).put("f", JSONArray().apply {
        features.forEach { f ->
            put(JSONObject().put("k", f.kind.name).apply {
                if (f.name.isNotEmpty()) put("n", f.name)
                if (f.ref.isNotEmpty()) put("r", f.ref)
                if (f.levels.isNotEmpty()) put("l", JSONArray(f.levels))
                if (f.hours.isNotEmpty()) put("h", f.hours)
                put("g", JSONArray(f.rings.map { r -> JSONArray(r.flatMap { listOf(round6(it.lat), round6(it.lon)) }) }))
            })
        }
    })

    companion object {
        private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

        /** Reads what [toJson] wrote; null when it's damaged. */
        fun fromJson(j: JSONObject): IndoorMap? = runCatching {
            val fs = j.getJSONArray("f")
            IndoorMap(j.getString("code"), Instant.ofEpochSecond(j.getLong("at")), (0 until fs.length()).mapNotNull { i ->
                val o = fs.getJSONObject(i)
                val kind = runCatching { Place.valueOf(o.getString("k")) }.getOrNull() ?: return@mapNotNull null
                val g = o.getJSONArray("g")
                Feature(
                    kind, o.optString("n"), o.optString("r"),
                    o.optJSONArray("l")?.let { l -> (0 until l.length()).map { l.getDouble(it) } } ?: emptyList(),
                    o.optString("h"),
                    (0 until g.length()).map { k -> g.getJSONArray(k).let { r -> (0 until r.length() / 2).map { Pt(r.getDouble(2 * it), r.getDouble(2 * it + 1)) } } },
                )
            })
        }.getOrNull()

        /**
         * Turns an Overpass answer ("out geom") into features: classifies each element, keeps its floors, name and
         * hours, and assembles multipolygon relations from their member ways.
         */
        fun fromOverpass(code: String, json: String, at: Instant): IndoorMap {
            val els = JSONObject(json).optJSONArray("elements") ?: JSONArray()
            val out = (0 until els.length()).mapNotNull { i ->
                val el = els.getJSONObject(i)
                val tags = el.optJSONObject("tags") ?: return@mapNotNull null
                fun tag(k: String) = tags.optString(k)
                val kind = classify(::tag) ?: return@mapNotNull null
                val rings = when (el.optString("type")) {
                    "node" -> listOf(listOf(Pt(el.getDouble("lat"), el.getDouble("lon"))))
                    "way" -> listOf(points(el.optJSONArray("geometry")))
                    else -> el.optJSONArray("members")?.let { m ->
                        joinRings((0 until m.length()).map { m.getJSONObject(it) }
                            .filter { it.optString("type") == "way" && it.optString("role") in setOf("outer", "inner", "") }
                            .map { points(it.optJSONArray("geometry")) })
                    }.orEmpty()
                }.filter { it.isNotEmpty() }
                if (rings.isEmpty()) return@mapNotNull null
                // Stairs and other lines that aren't areas: an icon at their middle.
                val shape = if (kind.area || rings.first().let { it.size >= 4 && it.first() == it.last() }) rings
                else listOf(listOf(rings.first()[rings.first().size / 2]))
                if (kind.area && shape.first().size < 4) return@mapNotNull null
                Feature(
                    kind, tag("name").ifEmpty { tag("brand") }, tag("ref").ifEmpty { tag("local_ref") },
                    levels(tag("level")), tag("opening_hours"), shape,
                )
            }
            return IndoorMap(code, at, out)
        }

        /** What an OSM element is, from its tags; null for things not worth showing. */
        internal fun classify(tag: (String) -> String): Place? {
            val aeroway = tag("aeroway"); val amenity = tag("amenity"); val shop = tag("shop")
            val name = tag("name").lowercase()
            return when {
                aeroway == "gate" -> Place.GATE
                aeroway == "terminal" || tag("building") == "terminal" -> Place.TERMINAL
                aeroway == "apron" -> Place.APRON
                aeroway in setOf("baggage_claim", "baggage_reclaim") -> Place.BAGGAGE
                aeroway == "check-in" -> Place.CHECKIN
                amenity in setOf("restaurant", "fast_food", "food_court", "ice_cream") -> Place.FOOD
                amenity == "cafe" -> Place.COFFEE
                amenity in setOf("bar", "pub") -> Place.BAR
                amenity == "toilets" -> Place.TOILETS
                amenity == "drinking_water" -> Place.WATER
                amenity == "lounge" || Regex("\\blounge\\b|\\bclub\\b").containsMatchIn(name) && shop.isEmpty() -> Place.LOUNGE
                amenity in setOf("atm", "bank", "bureau_de_change") -> Place.MONEY
                amenity in setOf("pharmacy", "clinic", "doctors") || tag("emergency") == "defibrillator" -> Place.MEDICAL
                amenity == "charging_station" -> Place.CHARGING
                amenity == "information" || tag("tourism") == "information" -> Place.INFO
                amenity == "place_of_worship" -> Place.WORSHIP
                shop.isNotEmpty() && shop != "no" && shop != "vacant" -> Place.SHOP
                tag("highway") == "elevator" -> Place.ELEVATOR
                tag("highway") == "steps" || tag("highway") == "escalator" -> Place.STAIRS
                Regex("\\bsecurity\\b|\\btsa\\b|checkpoint").containsMatchIn(name) -> Place.SECURITY
                Regex("restroom|toilet|washroom").containsMatchIn(name) -> Place.TOILETS
                tag("indoor") == "room" -> Place.ROOM
                tag("indoor") in setOf("area", "corridor") -> Place.AREA
                else -> null
            }
        }

        /** OSM's level tag as floors: "1", "1;2", "-1;0;1.5", "0-2". */
        internal fun levels(s: String): List<Double> = s.split(';').flatMap { part ->
            val p = part.trim()
            val range = Regex("^(-?\\d+)-(-?\\d+)$").find(p)
            if (range != null) {
                val (a, b) = range.destructured
                (a.toInt()..b.toInt()).map { it.toDouble() }
            } else listOfNotNull(p.toDoubleOrNull())
        }.distinct().sorted()

        /** A gate's ref as keys: "G11-G12" and "G11;12" -> G11, G12 (a bare number takes the first one's letters). */
        internal fun gateKeys(ref: String): List<String> {
            val parts = ref.split('-', ';', ',', '/').map(::gateKey).filter { it.isNotEmpty() }
            val letters = parts.firstOrNull()?.takeWhile { it.isLetter() }.orEmpty()
            return parts.map { if (it.all(Char::isDigit)) letters + it else it }
        }

        /** "Gate A 8" -> "A8": letters and digits, uppercase, leading zeros dropped from numbers. */
        internal fun gateKey(s: String) =
            s.uppercase().removePrefix("GATE").replace(Regex("[^A-Z0-9]"), "").replace(Regex("(?<![0-9])0+(?=[0-9])"), "")

        private fun points(g: JSONArray?): List<Pt> =
            if (g == null) emptyList() else (0 until g.length()).mapNotNull { g.optJSONObject(it) }.map { Pt(it.getDouble("lat"), it.getDouble("lon")) }

        /** Joins a relation's member ways end to end into closed rings; leftovers that never close are kept as they are. */
        internal fun joinRings(ways: List<List<Pt>>): List<List<Pt>> {
            val left = ways.filter { it.size >= 2 }.toMutableList()
            val rings = mutableListOf<List<Pt>>()
            while (left.isNotEmpty()) {
                val ring = left.removeAt(0).toMutableList()
                while (ring.first() != ring.last()) {
                    val i = left.indexOfFirst { it.first() == ring.last() || it.last() == ring.last() }
                    if (i < 0) break
                    val w = left.removeAt(i)
                    ring += (if (w.first() == ring.last()) w else w.reversed()).drop(1)
                }
                rings += ring
            }
            return rings
        }
    }
}
