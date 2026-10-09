package com.tankful.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tankful.android.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun DeckCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val width = if (selected) 2.dp else 1.dp
    val color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(width, color),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
fun ChoiceRow(chips: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips()
    }
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface
    val ink = if (selected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
    val edge = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = fill,
        border = BorderStroke(1.dp, edge),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp), style = HudLabel, color = ink)
    }
}

@Composable
fun HudField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        textStyle = FieldStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        colors = instrumentFieldColors(),
    )
}

@Composable
fun instrumentFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.secondary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.secondary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.secondary,
    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
)

@Composable
fun HudButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = HudLabel, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun BrandMark() {
    val gold = MaterialTheme.colorScheme.secondary
    val sweep by rememberInfiniteTransition(label = "idle").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6400, easing = LinearEasing)),
        label = "idle",
    )
    Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { gauge(gold, gold.copy(alpha = 0.35f), sweep) }
        Image(
            painter = painterResource(R.drawable.ic_brand),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(104.dp),
        )
    }
}

@Composable
fun RateChart(points: List<ChartPoint>) {
    val line = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.secondary
    if (points.isEmpty()) {
        ChartCanvas(emptyList(), 0f, 1f, line, grid, accent)
        return
    }
    val max = points.maxOf { it.position }
    val min = points.minOf { it.position }
    val span = if (max - min > 0.01f) max - min else 1f
    val ordered = points.sortedBy { it.atMillis }
    val high = points.maxBy { it.position }
    val low = points.minBy { it.position }
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(high.text, style = HudLabel, color = ink)
        ChartCanvas(ordered, min, span, line, grid, accent)
        if (low.text != high.text) Text(low.text, style = HudLabel, color = ink)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(ordered.first().label, style = MaterialTheme.typography.labelMedium)
            Text(ordered.last().label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ChartCanvas(
    points: List<ChartPoint>,
    min: Float,
    span: Float,
    line: Color,
    grid: Color,
    accent: Color,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = points.joinToString { "${it.label} ${it.text}" } },
    ) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        drawGrid(left, right, top, bottom, grid)
        drawLine(line, Offset(left, top), Offset(left, bottom), 1.5.dp.toPx())
        drawLine(line, Offset(left, bottom), Offset(right, bottom), 1.5.dp.toPx())
        val coords = plot(points, left, right, top, bottom, min, span)
        drawSeries(coords, points, line, accent)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    grid: Color,
) {
    val plotH = bottom - top
    drawLine(grid, Offset(left, top), Offset(left, bottom), strokeWidth = 1.dp.toPx())
    for (frac in floatArrayOf(0f, 0.5f, 1f)) {
        val y = bottom - plotH * frac
        drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
    }
}

private fun plot(
    points: List<ChartPoint>,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    min: Float,
    span: Float,
): List<Offset> {
    val plotW = right - left
    val plotH = bottom - top
    val first = points.minOfOrNull { it.atMillis } ?: 0L
    val last = points.maxOfOrNull { it.atMillis } ?: 0L
    val timed = last > first
    return points.mapIndexed { index, point ->
        val along = if (!timed || points.size == 1) {
            if (points.size == 1) 0.5f else index.toFloat() / (points.size - 1)
        } else {
            (point.atMillis - first).toFloat() / (last - first).toFloat()
        }
        val x = left + plotW * along
        val y = bottom - plotH * ((point.position - min) / span)
        Offset(x, y)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    coords: List<Offset>,
    points: List<ChartPoint>,
    line: Color,
    accent: Color,
) {
    for (index in 0 until coords.lastIndex) {
        drawLine(line, coords[index], coords[index + 1], strokeWidth = 2.5.dp.toPx())
    }
    coords.forEachIndexed { index, at ->
        drawPoint(points[index], at, if (index == coords.lastIndex) accent else line)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPoint(point: ChartPoint, at: Offset, color: Color) {
    if (!point.hollow) {
        drawCircle(color, 5.dp.toPx(), at)
        return
    }
    drawCircle(color, 6.dp.toPx(), at, style = Stroke(2.dp.toPx()))
}

private fun DrawScope.gauge(gold: Color, track: Color, sweep: Float) {
    val arc = Size(size.minDimension * 0.86f, size.minDimension * 0.86f)
    val inset = (size.minDimension - arc.width) / 2f
    drawArc(track, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    for (index in 0..8) tick(gold, 135.0 + index * 270.0 / 8.0, index % 2 == 0)
    val idle = sin(sweep * PI * 2).toFloat() * 0.72f + sin(sweep * PI * 4).toFloat() * 0.28f
    follower(gold, 135.0 + (0.46 + idle * 0.14) * 270.0)
}

private fun DrawScope.tick(gold: Color, degrees: Double, major: Boolean) {
    val outer = size.minDimension * 0.47f
    val inner = outer - if (major) 8.dp.toPx() else 4.dp.toPx()
    val alpha = if (major) 0.9f else 0.4f
    drawLine(gold.copy(alpha = alpha), polar(inner, degrees), polar(outer, degrees), 1.5.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.follower(gold: Color, degrees: Double) {
    val tip = polar(size.minDimension * 0.45f, degrees)
    drawLine(gold, polar(size.minDimension * 0.39f, degrees), tip, 3.dp.toPx(), StrokeCap.Round)
    drawCircle(gold, 3.5.dp.toPx(), tip)
}

private fun DrawScope.polar(radius: Float, degrees: Double): Offset {
    val rad = degrees * PI / 180.0
    return Offset(
        (center.x + cos(rad) * radius).toFloat(),
        (center.y + sin(rad) * radius).toFloat(),
    )
}
