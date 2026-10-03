package com.aria.air

import com.aria.air.api.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test
    fun versionFromTag() {
        assertEquals(64, GitHub.codeOf("v1.0.64"))
        assertEquals(64, GitHub.codeOf("1.0.64"))
        assertNull(GitHub.codeOf("latest"))
    }

    @Test
    fun newerOnlyWhenTheBuildNumberIsHigher() {
        fun rel(tag: String) = GitHub.codeOf(tag)?.let { GitHub.Release(it, tag.removePrefix("v"), "https://example.com/air.apk") }
        assertEquals(65, GitHub.newer(rel("v1.0.65"), 64)?.code)
        assertNull(GitHub.newer(rel("v1.0.64"), 64))
        assertNull(GitHub.newer(rel("v1.0.63"), 64))
        assertNull(GitHub.newer(rel("nightly"), 64))
    }
}
