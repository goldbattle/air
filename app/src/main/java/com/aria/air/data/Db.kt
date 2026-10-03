package com.aria.air.data

// Bundled reference data, offline: airports, airlines and the coastlines for the maps.

import com.aria.air.util.*
import android.content.Context
import java.io.Reader
import java.time.ZoneId
import java.time.ZoneOffset

// ---------- Reference data (bundled, offline) ----------

/** An airport from the bundled list (OurAirports, built by tools/make_data.py). */
data class Airport(
    val iata: String, val icao: String, val name: String, val city: String, val country: String,
    val lat: Double, val lon: Double, val tz: ZoneId,
    val region: String = "", // "US-ME"
    val rank: Int = 3,        // 0 large ... 3 small, for picking a city's main airport
)

/** An airline: its IATA code ("UA"), ICAO code ("UAL", the one in callsigns) and name. */
data class Airline(val iata: String, val icao: String, val name: String)

/** Airports and airlines from assets (built by tools/make_data.py). */
object Db {
    /** Airports by IATA code. */
    var airports: Map<String, Airport> = emptyMap(); private set

    /** Airlines by IATA code. */
    var airlines: Map<String, Airline> = emptyMap(); private set

    /** Airlines by ICAO code, to read callsigns ("UAL523"). */
    var airlinesByIcao: Map<String, Airline> = emptyMap(); private set

    /** Loads the bundled airports.tsv and airlines.tsv; call once at startup. */
    fun load(ctx: Context) = load(ctx.assets.open("airports.tsv").reader(), ctx.assets.open("airlines.tsv").reader())

    /**
     * Parses the two TSV files. Short rows are skipped and an unknown time zone becomes UTC. Tests call this directly.
     */
    fun load(airportsTsv: Reader, airlinesTsv: Reader) {
        airports = airportsTsv.useLines { lines ->
            lines.map { it.split('\t') }.filter { it.size >= 8 }.associate { r ->
                r[0] to Airport(
                    r[0], r[1], r[2], r[3], r[4], r[5].toDouble(), r[6].toDouble(),
                    runCatching { ZoneId.of(r[7]) }.getOrDefault(ZoneOffset.UTC),
                    r.getOrElse(8) { "" }, r.getOrNull(9)?.toIntOrNull() ?: 3,
                )
            }
        }
        airlines = airlinesTsv.useLines { lines ->
            lines.map { it.split('\t') }.filter { it.size >= 3 }.associate { r -> r[0] to Airline(r[0], r[1], r[2]) }
        }
        airlinesByIcao = airlines.values.associateBy { it.icao }
    }

    /** The airport with this IATA code (any case), or null. */
    fun airport(code: String?): Airport? = code?.let { airports[it.uppercase()] }

    /** The airline with this IATA code (any case), or null. */
    fun airline(code: String?): Airline? = code?.let { airlines[it.uppercase()] }

    /** Main airport of a city, as written in itineraries: ("Portland", "ME") -> PWM. [area] is a state or country code. */
    fun airportForCity(city: String, area: String): Airport? = airports.values
        .filter { it.city.equals(city, true) && (it.region.equals("US-$area", true) || it.region.endsWith("-$area", true) || it.country == area) }
        .minByOrNull { it.rank }
}

/** Great-circle distance between two airports, in km. */
fun distKm(a: Airport, b: Airport) = distKm(a.lat, a.lon, b.lat, b.lon)

/** Natural Earth 1:110m coastlines, one ring per line of "lon lat lon lat ...". */
object Land {
    /** Each coastline ring as lon, lat pairs. */
    var rings: List<FloatArray> = emptyList(); private set

    /** Reads the coastlines from assets/land.txt; call once at startup. */
    fun load(ctx: Context) {
        rings = ctx.assets.open("land.txt").bufferedReader().readLines()
            .map { line -> line.split(' ').map { it.toFloat() }.toFloatArray() }
    }
}
