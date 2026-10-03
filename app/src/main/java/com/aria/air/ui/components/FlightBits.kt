package com.aria.air.ui.components

// Small pieces shown wherever flights are: airline badges, status colors, progress, the ticking clock.

import com.aria.air.data.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import kotlinx.coroutines.delay

/** Ticks once a minute so countdowns and progress stay current while a screen is open. */
@Composable
fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    return now
}

/** The app color for a [Tone]. */
fun toneColor(t: Tone) = when (t) {
    Tone.Good -> C.green
    Tone.Warn -> C.orange
    Tone.Bad -> C.red
    Tone.Info -> C.blue
    Tone.Neutral -> C.secondary
}

/** Airline emblem on its badge color if we have one, else a colored monogram. */
@Composable
fun AirlineBadge(code: String, size: Int = 28) {
    val icon = remember(code) { Guides.airlineIcon(code) }
    val bg = if (icon != null) Guides.airlineIconBg(code) else Guides.airlineColor(code)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(bg), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, code, Modifier.fillMaxSize())
        else Text(code, color = Color.White, fontSize = (size * 0.42).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Route line with a plane riding along it. */
@Composable
fun ProgressLine(p: Double, height: Int = 4, color: Color = C.blue) {
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(22.dp).onSizeChanged { width = it.width }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(height.dp).clip(CircleShape).background(C.fill))
        Box(Modifier.fillMaxWidth(p.toFloat()).height(height.dp).clip(CircleShape).background(color))
        val x = with(density) { (width * p).toFloat().toDp() } - 11.dp
        Icon(
            Icons.Filled.Flight, null, tint = color,
            modifier = Modifier.offset(x = x.coerceAtLeast(0.dp)).size(22.dp).rotate(90f),
        )
    }
}
