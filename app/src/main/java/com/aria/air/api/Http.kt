package com.aria.air.api

// The one way out to the network: plain GETs with logging, and a test hook.

import com.aria.air.*
import com.aria.air.debug.*
import java.net.HttpURLConnection
import java.net.URL

/** A non-2xx answer; [code] tells a rejected key (401/403) from a used-up quota (429). */
class HttpError(val code: Int, msg: String) : Exception(msg)

/**
 * Every request the app makes (except GitHub and the mail server) goes through here, so each one is logged in the
 * developer request log and unit tests can answer with canned responses instead of the network.
 *
 * What leaves the phone: flight numbers, tail numbers, airport coordinates, dates and map tile coordinates, never who
 * you are or what's in your documents. AeroDataBox and FlightAware are only asked when you add your own key.
 */
object Http {
    /** Every request goes through here; unit tests swap in canned responses. */
    internal var transport: (url: String, headers: Map<String, String>) -> String = ::fetch

    /**
     * GETs [url] and returns the body as text, logging the request (URL and outcome, never the headers, which hold API
     * keys). Throws [HttpError] on a non-2xx answer and an IOException when offline.
     */
    internal fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val start = System.currentTimeMillis()
        val r = runCatching { transport(url, headers) }
        Debug.request(url, System.currentTimeMillis() - start, r)
        return r.getOrThrow()
    }

    /** The real network call behind [transport]: 10 s to connect, 15 s to read (90 s for Overpass), with Air's User-Agent. */
    private fun fetch(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = if ("overpass" in url) 90_000 else 15_000 // a big airport's indoor map takes a while to gather
            c.setRequestProperty("User-Agent", "Air-Android/${BuildConfig.VERSION_NAME}")
            headers.forEach(c::setRequestProperty)
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code, "HTTP $code from ${URL(url).host}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Binary downloads (radar tiles and rain maps), not through [transport]; logged like every other request. */
    internal var bytes: (String) -> ByteArray = { url ->
        val start = System.currentTimeMillis()
        val r = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", "Air-Android/${BuildConfig.VERSION_NAME}")
                if (c.responseCode !in 200..299) throw HttpError(c.responseCode, "HTTP ${c.responseCode} from ${c.url.host}")
                c.inputStream.readBytes()
            } finally { c.disconnect() }
        }
        Debug.request(url, System.currentTimeMillis() - start, r.map { " ".repeat(it.size) })
        r.getOrThrow()
    }
}
