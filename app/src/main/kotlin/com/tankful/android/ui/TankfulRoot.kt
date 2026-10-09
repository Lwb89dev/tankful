package com.tankful.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tankful.android.R

@Composable
fun TankfulRoot() {
    val themeModel: ThemeViewModel = hiltViewModel()
    val theme by themeModel.theme.collectAsStateWithLifecycle()
    TankfulTheme(prefersDark(theme)) { Shell() }
}

@Composable
private fun Shell() {
    val gate: GateViewModel = hiltViewModel()
    val count by gate.liveVehicles.collectAsStateWithLifecycle()
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(count) {
        val live = count ?: return@LaunchedEffect
        if (start == null) start = startDestination(live)
    }
    val route = start ?: return
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val current = back?.destination?.route
    val tabs = current == "home" || current == "insights" || current == "garage" ||
        current == "stations" || current == "more"
    Box(Modifier.fillMaxSize()) {
        RoadAtmosphere(Modifier.fillMaxSize())
        ShellScaffold(nav, route, tabs, current)
    }
}

@Composable
private fun ShellScaffold(nav: NavHostController, route: String, tabs: Boolean, current: String?) {
    // A transparent container leaves content color unspecified, and text then draws black.
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = { if (tabs) Tabs(nav, current) },
    ) { padding ->
        NavHost(nav, route, Modifier.padding(padding)) {
            composable("first-run") { FirstRunScreen(onHome = { nav.navigate("home") { popUpTo("first-run") { inclusive = true } } }) }
            composable("home") { HomeScreen(nav) }
            composable("timeline") { TimelineScreen(nav) }
            composable("insights") { InsightsScreen(nav) }
            composable("garage") { GarageScreen(onOpen = { id -> nav.navigate("vehicle/$id") }, onNew = { nav.navigate("vehicle/new") }) }
            composable("vehicle/new") { VehicleScreen(null, onDone = { nav.popBackStack() }) }
            composable("vehicle/{id}") { entry ->
                VehicleScreen(entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
            }
            composable("refuel/new") { RefuelScreen(null, onDone = { nav.popBackStack() }) }
            composable("refuel/{id}") { entry ->
                RefuelScreen(entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
            }
            composable("odometer/new") { OdometerScreen(onDone = { nav.popBackStack() }) }
            composable("expense/new") { ExpenseScreen(onDone = { nav.popBackStack() }) }
            composable("expense/{id}") { ExpenseScreen(onDone = { nav.popBackStack() }) }
            composable("maintenance/new") { MaintenanceScreen(onDone = { nav.popBackStack() }) }
            composable("maintenance/{id}") { MaintenanceScreen(onDone = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(nav) }
            composable("more") { MoreScreen(nav) }
            composable("settings/sync") { SyncScreen() }
            composable("import") { ImportScreen() }
            composable("stations") { StationsScreen() }
            composable("reminders") { RemindersScreen() }
        }
    }
}

@Composable
private fun Tabs(nav: NavHostController, current: String?) {
    val gold = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Tab(nav, "home", stringResource(R.string.nav_home), current, gold, muted)
        Tab(nav, "insights", stringResource(R.string.nav_insights), current, gold, muted)
        Tab(nav, "garage", stringResource(R.string.nav_garage), current, gold, muted)
        Tab(nav, "stations", stringResource(R.string.nav_map), current, gold, muted)
        Tab(nav, "more", stringResource(R.string.nav_more), current, gold, muted)
    }
}

@Composable
private fun RowScope.Tab(
    nav: NavHostController,
    route: String,
    label: String,
    current: String?,
    gold: Color,
    muted: Color,
    showLabel: Boolean = true,
) {
    val selected = current == route
    val ink = if (selected) gold else muted
    NavigationBarItem(
        selected = selected,
        onClick = {
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        alwaysShowLabel = showLabel,
        label = { if (showLabel) Text(label, style = HudLabel.copy(fontSize = 10.sp, letterSpacing = 0.2.sp), maxLines = 1) },
        icon = { TabMark(route, ink, label, showLabel) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = gold,
            selectedTextColor = gold,
            unselectedIconColor = muted,
            unselectedTextColor = muted,
            indicatorColor = gold.copy(alpha = 0.18f),
        ),
    )
}

@Composable
internal fun SettingsGear(onClick: () -> Unit) {
    val label = stringResource(R.string.settings)
    val ink = MaterialTheme.colorScheme.secondary
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { drawGear(ink, size.minDimension) }
    }
}

@Composable
private fun TabMark(route: String, color: Color, label: String, labeled: Boolean) {
    val description = if (labeled) Modifier else Modifier.semantics { contentDescription = label }
    Canvas(Modifier.size(22.dp).then(description)) {
        drawMark(route, color, size.minDimension)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMark(route: String, color: Color, span: Float) {
    if (route == "home") drawHouse(color, span)
    if (route == "insights") drawAxes(color, span)
    if (route == "garage") drawGarage(color, span)
    if (route == "stations") drawMap(color, span)
    if (route == "settings") drawGear(color, span)
    if (route == "more") drawMore(color, span)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHouse(color: Color, span: Float) {
    val width = 2.dp.toPx()
    val roof = Path()
    roof.moveTo(span * 0.12f, span * 0.46f)
    roof.lineTo(span * 0.50f, span * 0.12f)
    roof.lineTo(span * 0.88f, span * 0.46f)
    drawPath(roof, color, style = Stroke(width))
    drawLine(color, Offset(span * 0.24f, span * 0.46f), Offset(span * 0.24f, span * 0.88f), width)
    drawLine(color, Offset(span * 0.76f, span * 0.46f), Offset(span * 0.76f, span * 0.88f), width)
    drawLine(color, Offset(span * 0.24f, span * 0.88f), Offset(span * 0.76f, span * 0.88f), width)
    drawLine(color, Offset(span * 0.44f, span * 0.88f), Offset(span * 0.44f, span * 0.60f), width)
    drawLine(color, Offset(span * 0.56f, span * 0.88f), Offset(span * 0.56f, span * 0.60f), width)
    drawLine(color, Offset(span * 0.44f, span * 0.60f), Offset(span * 0.56f, span * 0.60f), width)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxes(color: Color, span: Float) {
    val width = 2.dp.toPx()
    val origin = Offset(span * 0.22f, span * 0.78f)
    drawLine(color, origin, Offset(span * 0.22f, span * 0.12f), width)
    drawLine(color, origin, Offset(span * 0.90f, span * 0.78f), width)
    drawLine(color, Offset(span * 0.22f, span * 0.12f), Offset(span * 0.14f, span * 0.24f), width)
    drawLine(color, Offset(span * 0.22f, span * 0.12f), Offset(span * 0.30f, span * 0.24f), width)
    drawLine(color, Offset(span * 0.90f, span * 0.78f), Offset(span * 0.78f, span * 0.70f), width)
    drawLine(color, Offset(span * 0.90f, span * 0.78f), Offset(span * 0.78f, span * 0.86f), width)
    val plot = arrayOf(
        Offset(span * 0.32f, span * 0.62f),
        Offset(span * 0.48f, span * 0.36f),
        Offset(span * 0.62f, span * 0.48f),
        Offset(span * 0.78f, span * 0.26f),
    )
    for (index in 0 until plot.lastIndex) drawLine(color, plot[index], plot[index + 1], width)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGarage(color: Color, span: Float) {
    val width = 2.dp.toPx()
    val roof = Path()
    roof.moveTo(span * 0.08f, span * 0.40f)
    roof.lineTo(span * 0.50f, span * 0.12f)
    roof.lineTo(span * 0.92f, span * 0.40f)
    drawPath(roof, color, style = Stroke(width))
    drawLine(color, Offset(span * 0.18f, span * 0.40f), Offset(span * 0.18f, span * 0.90f), width)
    drawLine(color, Offset(span * 0.82f, span * 0.40f), Offset(span * 0.82f, span * 0.90f), width)
    drawLine(color, Offset(span * 0.18f, span * 0.90f), Offset(span * 0.82f, span * 0.90f), width)
    drawRect(color, Offset(span * 0.30f, span * 0.52f), Size(span * 0.40f, span * 0.38f), style = Stroke(width))
    drawLine(color, Offset(span * 0.30f, span * 0.66f), Offset(span * 0.70f, span * 0.66f), width)
    drawLine(color, Offset(span * 0.30f, span * 0.78f), Offset(span * 0.70f, span * 0.78f), width)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMap(color: Color, span: Float) {
    val width = 2.dp.toPx()
    val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val sheet = Path()
    sheet.moveTo(span * 0.08f, span * 0.32f)
    sheet.lineTo(span * 0.28f, span * 0.14f)
    sheet.lineTo(span * 0.50f, span * 0.32f)
    sheet.lineTo(span * 0.72f, span * 0.14f)
    sheet.lineTo(span * 0.92f, span * 0.32f)
    sheet.lineTo(span * 0.92f, span * 0.88f)
    sheet.lineTo(span * 0.08f, span * 0.88f)
    sheet.close()
    drawPath(sheet, color, style = stroke)
    drawLine(color, Offset(span * 0.28f, span * 0.14f), Offset(span * 0.28f, span * 0.88f), width, StrokeCap.Round)
    drawLine(color, Offset(span * 0.72f, span * 0.14f), Offset(span * 0.72f, span * 0.88f), width, StrokeCap.Round)
    drawNavPin(color, Offset(span * 0.50f, span * 0.76f), span * 0.30f, width)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNavPin(
    color: Color,
    tip: Offset,
    height: Float,
    width: Float,
) {
    val radius = height * 0.32f
    val head = Offset(tip.x, tip.y - height + radius)
    drawCircle(color, radius, head, style = Stroke(width))
    drawLine(color, Offset(head.x - radius * 0.62f, head.y + radius * 0.7f), tip, width, StrokeCap.Round)
    drawLine(color, Offset(head.x + radius * 0.62f, head.y + radius * 0.7f), tip, width, StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGear(color: Color, span: Float) {
    val width = 2.dp.toPx()
    val root = span * 0.26f
    val tip = span * 0.46f
    drawCircle(color, span * 0.10f, center, style = Stroke(width))
    drawCircle(color, root, center, style = Stroke(width))
    val tooth = span * 0.16f
    for (index in 0 until 8) {
        val angle = index * (Math.PI / 4.0) - Math.PI / 2.0
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        drawLine(
            color,
            Offset(center.x + dx * root, center.y + dy * root),
            Offset(center.x + dx * tip, center.y + dy * tip),
            tooth,
            cap = StrokeCap.Butt,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMore(color: Color, span: Float) {
    val width = 2.dp.toPx()
    for (frac in floatArrayOf(0.28f, 0.50f, 0.72f)) {
        drawLine(color, Offset(span * 0.18f, span * frac), Offset(span * 0.82f, span * frac), width)
        drawCircle(color, span * 0.045f, Offset(span * 0.28f, span * frac))
    }
}
