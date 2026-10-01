package com.aria.air

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test
    fun versionFromTag() {
        assertEquals(64, Updater.codeOf("v1.0.64"))
        assertEquals(64, Updater.codeOf("1.0.64"))
        assertNull(Updater.codeOf("latest"))
    }

    @Test
    fun newerOnlyWhenTheBuildNumberIsHigher() {
        fun rel(tag: String) = Updater.codeOf(tag)?.let { Updater.Release(it, tag.removePrefix("v"), "https://example.com/air.apk") }
        assertEquals(65, Updater.newer(rel("v1.0.65"), 64)?.code)
        assertNull(Updater.newer(rel("v1.0.64"), 64))
        assertNull(Updater.newer(rel("v1.0.63"), 64))
        assertNull(Updater.newer(rel("nightly"), 64))
    }
}
