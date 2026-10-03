package com.aria.air.api

// GitHub Releases: is there a newer build of Air?

import com.aria.air.*
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Asks GitHub's latest-release API for Air (nothing else is sent, not even the app version). */
object GitHub {
    private const val LATEST = "https://api.github.com/repos/goldbattle/air/releases/latest"

    /** A release: its build number ([code]), version name, and where to download the APK. */
    data class Release(val code: Int, val name: String, val apk: String)

    /** Tags are "v" + versionName, and versionName is "1.0.<versionCode>", so the build number is the last part. */
    fun codeOf(tag: String): Int? = tag.substringAfterLast('.').toIntOrNull()

    /** [r] if it is a newer build than [installed]. */
    fun newer(r: Release?, installed: Int = BuildConfig.VERSION_CODE) = r?.takeIf { it.code > installed }

    /** The latest release on GitHub, or null if it has no APK. Blocking. */
    fun latest(): Release? {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        val json = try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Air-Android") // GitHub requires one
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode == 404) return null // no releases yet
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode} from GitHub")
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
        val tag = json.getString("tag_name")
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
            ?: return null
        return Release(codeOf(tag) ?: return null, tag.removePrefix("v"), apk.getString("browser_download_url"))
    }
}
