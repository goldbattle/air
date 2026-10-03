package com.aria.air.data

// A precipitation picture placed on the map, whichever service it came from.

import com.aria.air.util.*
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.time.Instant

/** Radar resampled onto [box] in the map's lon/lat projection, from the frame taken at [frame]. */
class RadarImage(val bitmap: Bitmap, val box: GeoBox, val frame: Instant, val fetched: Instant) {
    val image: ImageBitmap by lazy { bitmap.asImageBitmap() }
}
