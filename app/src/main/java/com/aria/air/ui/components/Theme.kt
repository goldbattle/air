package com.aria.air.ui.components

// Colors (the iOS system palette, light and dark) and the Material theme built from them.

import com.aria.air.data.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

// ---------- Colors (iOS system palette) ----------

/** The app's colors (the iOS system palette), light or dark by [dark], which follows the system at launch. */
object C {
    var dark = false

    /** The light or dark variant, by the current mode. */
    private fun pick(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    val bg get() = pick(0xFFF2F2F7, 0xFF000000)
    val card get() = pick(0xFFFFFFFF, 0xFF1C1C1E)
    val raised get() = pick(0xFFFFFFFF, 0xFF636366) // selected segment
    val bar get() = pick(0xF7F9F9F9, 0xF0161618)
    val label get() = pick(0xFF000000, 0xFFFFFFFF)
    val secondary get() = pick(0x993C3C43, 0x99EBEBF5)
    val tertiary get() = pick(0x4D3C3C43, 0x4DEBEBF5)
    val separator get() = pick(0x4A3C3C43, 0x99545458)
    val fill get() = pick(0x1F767680, 0x3D767680)
    val blue get() = pick(0xFF007AFF, 0xFF0A84FF)
    val indigo get() = pick(0xFF5856D6, 0xFF5E5CE6)
    val green get() = pick(0xFF34C759, 0xFF30D158)
    val orange get() = pick(0xFFFF9500, 0xFFFF9F0A)
    val red get() = pick(0xFFFF3B30, 0xFFFF453A)
    val land get() = pick(0xFFE3E6EC, 0xFF2C2C2E)
    val sea get() = pick(0xFFF7F8FA, 0xFF111113)
}

/** Material 3 with [C]'s colors, so stock pieces (dialogs, switches, menus) match the rest of the app. */
@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = (if (C.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = C.blue, background = C.bg, surface = C.card, onSurface = C.label, onSurfaceVariant = C.secondary,
        surfaceContainerLow = C.card, surfaceContainerHigh = C.card, surfaceContainerHighest = C.card, secondaryContainer = C.fill,
    ),
) {
    CompositionLocalProvider(LocalContentColor provides C.label, content = content)
}
