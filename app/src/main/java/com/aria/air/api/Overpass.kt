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
    /** The public instance first, then a mirror when it's busy (it answers 429/504 under load). */
    private val servers = listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter")

    /** What the indoor map draws, inside [area]: an Overpass area set or an "around" filter. */
    private fun wanted(area: String) = """
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

    /** Runs [query] on the first server that answers. */
    private fun run(query: String): String {
        var last: Exception? = null
        for (s in servers) {
            try {
                return Http.get("$s?data=" + URLEncoder.encode(query, "UTF-8"))
            } catch (e: HttpError) {
                last = e
                if (e.code !in setOf(429, 502, 503, 504)) throw e
            } catch (e: java.io.IOException) {
                last = e
            }
        }
        throw last ?: java.io.IOException("No Overpass server answered")
    }
}
