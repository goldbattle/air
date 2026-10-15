package com.aria.air.api

// OpenStreetMap's Overpass API (free, no key): an airport's terminals, rooms, gates and shops for the indoor map.

import com.aria.air.data.*
import java.net.URLEncoder

/**
 * Overpass, the read-only query service for OpenStreetMap data. One request per airport: everything mapped inside the
 * airport's boundary that the indoor map draws. Sends the airport's ICAO code (or its coordinates). Data is ODbL,
 * © OpenStreetMap contributors, which the map credits.
 */
object Overpass {
    /** The public instance first, then mirrors; all are shared and often busy (429, 5xx, or an error inside a 200). */
    internal val servers = listOf(
        "https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    /** What the indoor map draws, inside [area]: an Overpass area set or an "around" filter. tools/airport_maps.py asks the same. */
    internal fun wanted(area: String) = """
        (
          nwr["indoor"~"^(room|area|corridor|yes)$"]$area;
          nwr["aeroway"~"^(gate|terminal|apron|baggage_claim|baggage_reclaim|check-in)$"]$area;
          nwr["building"="terminal"]$area;
          nwr["amenity"~"^(restaurant|fast_food|food_court|ice_cream|cafe|bar|pub|toilets|drinking_water|lounge|atm|bank|bureau_de_change|pharmacy|clinic|doctors|charging_station|information|place_of_worship)$"]$area;
          nwr["shop"]$area;
          nwr["highway"~"^(elevator|steps|escalator)$"]["indoor"]$area;
          nwr["highway"="elevator"]$area;
          nwr["tourism"="information"]$area;
        );
        out geom qt;
    """.trimIndent()

    /** Inside the airport's mapped boundary, found by its ICAO or IATA code. */
    internal fun byCode(a: Airport) = """
        [out:json][timeout:90];
        (nwr["aeroway"="aerodrome"]["icao"="${a.icao}"];nwr["aeroway"="aerodrome"]["iata"="${a.iata}"];)->.a;
        .a map_to_area->.ap;
    """.trimIndent() + "\n" + wanted("(area.ap)")

    /** Within 2.5 km of the airport's coordinates, for airports whose boundary isn't mapped. */
    internal fun byPlace(a: Airport) = "[out:json][timeout:90];\n" + wanted("(around:2500,${a.lat},${a.lon})")

    /** Downloads [a]'s indoor map, trying the airport's boundary first and then the area around it. */
    fun indoorMap(a: Airport, now: java.time.Instant): IndoorMap {
        val byCode = IndoorMap.fromOverpass(a.iata, run(byCode(a)), now)
        return if (byCode.features.any { !it.kind.area || it.kind == Place.TERMINAL }) byCode
        else IndoorMap.fromOverpass(a.iata, run(byPlace(a)), now)
    }

    /** Overpass's servers were all busy or down; worth trying again later. */
    class Busy : java.io.IOException("OpenStreetMap's servers are busy")

    /**
     * Runs [query] on the first server that gives a real answer. A busy server can fail outright (429, 5xx, timeout)
     * or answer 200 with an error page or a "runtime error" remark instead of data; both move on to the next server.
     */
    private fun run(query: String): String {
        var offline: java.io.IOException? = null // set while no server has answered at all
        var answered = false
        for (s in servers) {
            val body = try {
                Http.get("$s?data=" + URLEncoder.encode(query, "UTF-8"))
            } catch (e: HttpError) {
                answered = true
                if (e.code == 429 || e.code >= 500) continue else throw e
            } catch (e: java.io.IOException) {
                offline = e
                continue
            }
            answered = true
            val ok = body.trimStart().startsWith("{") &&
                runCatching { org.json.JSONObject(body).optString("remark") }.getOrNull()?.contains("error") == false
            if (ok) return body
        }
        // Nobody answered at all: the phone is offline (or the servers unreachable), not busy.
        if (!answered && offline != null) throw java.io.IOException("No connection")
        throw Busy()
    }
}
