package com.tankful.android.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.tankful.android.R
import kotlin.math.max

@Composable
fun RoadAtmosphere(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val context = LocalContext.current
    val plate = remember(dark) {
        val id = if (dark) R.drawable.drive_night else R.drawable.drive_day
        BitmapFactory.decodeResource(context.resources, id).asImageBitmap()
    }
    val zoom by rememberInfiniteTransition(label = "drive").animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(14000), RepeatMode.Reverse),
        label = "zoom",
    )
    Canvas(modifier) { drivePlate(plate, zoom, dark) }
}

private fun DrawScope.drivePlate(plate: ImageBitmap, zoom: Float, dark: Boolean) {
    cover(plate, zoom)
    scrim(dark)
}

private fun DrawScope.cover(plate: ImageBitmap, zoom: Float) {
    val scale = max(size.width / plate.width, size.height / plate.height) * zoom
    val width = plate.width * scale
    val height = plate.height * scale
    drawImage(
        plate,
        dstOffset = IntOffset(((size.width - width) / 2f).toInt(), ((size.height - height) / 2f).toInt()),
        dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
    )
}

private fun DrawScope.scrim(dark: Boolean) {
    val veil = if (dark) Color(0xFF07090E) else Color(0xFFF4F1E8)
    val mid = if (dark) 0.78f else 0.42f
    drawRect(
        Brush.verticalGradient(
            0f to veil.copy(alpha = if (dark) 0.28f else 0.12f),
            0.22f to veil.copy(alpha = mid),
            0.62f to veil.copy(alpha = mid),
            1f to veil.copy(alpha = if (dark) 0.35f else 0.16f),
        ),
    )
    if (!dark) return
    drawLine(
        Color(0xFFF4E2A8).copy(alpha = 0.16f),
        Offset(size.width * 0.08f, size.height * 0.82f),
        Offset(size.width * 0.92f, size.height * 0.78f),
        strokeWidth = 2f,
    )
}
