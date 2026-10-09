package com.tankful.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.tankful.android.providers.DEFAULT_MAP_STYLE_URL
import com.tankful.android.providers.OsmStation
import com.tankful.android.providers.PricedStation
import com.tankful.android.providers.STATION_QUERY_MAX_METRES
import com.tankful.android.providers.STATION_QUERY_RADIUS_METRES
import com.tankful.android.providers.visibleSearchRadiusMetres
import kotlin.math.cos
import kotlin.math.sin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

internal data class PinAnchor(val x: Float, val y: Float, val tailOnStart: Boolean)

internal data class MapTarget(val latitude: Double, val longitude: Double, val radiusMetres: Int)

@Composable
internal fun StationMap(
    stations: List<PricedStation>,
    centre: Pair<Double, Double>?,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    onView: (MapTarget) -> Unit,
    onFailed: (Boolean) -> Unit,
    onAnchor: (PinAnchor?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val select = rememberUpdatedState(onSelect)
    val viewTarget = rememberUpdatedState(onView)
    val failed = rememberUpdatedState(onFailed)
    val rows = remember { AtomicReference(stations) }
    val chosen = remember { AtomicReference(selectedKey) }
    val anchor = remember { AtomicReference(onAnchor) }
    rows.set(stations)
    chosen.set(selectedKey)
    anchor.set(onAnchor)
    val alive = remember { AtomicBoolean(true) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember { mutableStateOf(false) }
    val view = remember(context) {
        MapLibre.getInstance(context)
        MapView(context)
    }
    AndroidView(factory = { view }, modifier = modifier.fillMaxSize())
    DisposableEffect(view) {
        view.onCreate(null)
        view.onStart()
        view.onResume()
        view.addOnDidFailLoadingMapListener { failed.value(true) }
        view.getMapAsync { loaded ->
            openMap(loaded, view, alive, rows, chosen, anchor, { select.value(it) }, { viewTarget.value(it) }, { failed.value(it) }, { map = it }, { ready = it })
        }
        onDispose {
            alive.set(false)
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }
    LaunchedEffect(ready, stations, selectedKey) {
        val loaded = map ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        publishPins(loaded, stations, selectedKey)
        val station = stations.firstOrNull { stationKey(it.station) == selectedKey }?.station
        if (station != null) focus(loaded, station)
        placeAnchor(loaded, view, stations, selectedKey, onAnchor)
    }
    LaunchedEffect(ready, centre) {
        val loaded = map ?: return@LaunchedEffect
        val here = centre ?: return@LaunchedEffect
        if (ready) aim(loaded, here)
    }
}

private fun openMap(
    loaded: MapLibreMap,
    host: MapView,
    alive: AtomicBoolean,
    rows: AtomicReference<List<PricedStation>>,
    chosen: AtomicReference<String?>,
    anchor: AtomicReference<(PinAnchor?) -> Unit>,
    onSelect: (String) -> Unit,
    onView: (MapTarget) -> Unit,
    onFailed: (Boolean) -> Unit,
    onMap: (MapLibreMap) -> Unit,
    onReady: (Boolean) -> Unit,
) {
    if (!alive.get()) return
    onMap(loaded)
    loaded.uiSettings.isAttributionEnabled = true
    loaded.uiSettings.isLogoEnabled = true
    loaded.uiSettings.attributionGravity = Gravity.TOP or Gravity.START
    loaded.uiSettings.logoGravity = Gravity.TOP or Gravity.START
    val opened = runCatching {
        loaded.setStyle(Style.Builder().fromUri(DEFAULT_MAP_STYLE_URL)) { style ->
            if (!alive.get()) return@setStyle
            addPins(style, host.resources.displayMetrics.density)
            onReady(true)
            onFailed(false)
            reportView(loaded, onView)
        }
    }
    if (opened.isFailure) onFailed(true)
    loaded.addOnCameraIdleListener {
        if (!alive.get()) return@addOnCameraIdleListener
        reportView(loaded, onView)
        placeAnchor(loaded, host, rows.get(), chosen.get(), anchor.get())
    }
    loaded.addOnCameraMoveListener {
        if (!alive.get()) return@addOnCameraMoveListener
        placeAnchor(loaded, host, rows.get(), chosen.get(), anchor.get())
    }
    loaded.addOnMapClickListener { latLng ->
        val key = pinAt(loaded, latLng)
        onSelect(key.orEmpty())
        key != null
    }
}

private fun reportView(loaded: MapLibreMap, onView: (MapTarget) -> Unit) {
    val target = loaded.cameraPosition.target ?: return
    val radius = runCatching { visibleRadius(loaded, target) }.getOrElse {
        if (loaded.cameraPosition.zoom < 8.0) STATION_QUERY_MAX_METRES else STATION_QUERY_RADIUS_METRES
    }
    onView(MapTarget(target.latitude, target.longitude, radius))
}

private fun visibleRadius(map: MapLibreMap, target: LatLng): Int {
    val region = map.projection.visibleRegion
    val corners = listOf(region.farLeft, region.farRight, region.nearLeft, region.nearRight)
    var radius = STATION_QUERY_RADIUS_METRES
    for (corner in corners) {
        val point = corner ?: continue
        val metres = visibleSearchRadiusMetres(
            target.latitude,
            target.longitude,
            point.latitude,
            point.longitude,
        )
        if (metres > radius) radius = metres
    }
    if (radius == STATION_QUERY_RADIUS_METRES && map.cameraPosition.zoom < 8.0) return STATION_QUERY_MAX_METRES
    return radius
}

private fun addPins(style: Style, density: Float) {
    val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
    style.addSource(GeoJsonSource("stations", empty))
    style.addImage("station-pin", pinBitmap(density))
    style.addLayer(pinLayer("pins", 0.7f))
    val hot = pinLayer("pin-hot", 0.875f)
    hot.setFilter(Expression.eq(Expression.get("on"), Expression.literal("1")))
    style.addLayer(hot)
}

private fun pinLayer(id: String, scale: Float): SymbolLayer {
    val layer = SymbolLayer(id, "stations")
    layer.setProperties(
        PropertyFactory.iconImage("station-pin"),
        PropertyFactory.iconSize(scale),
        PropertyFactory.iconAnchor("bottom"),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
    )
    return layer
}

private fun pinAt(map: MapLibreMap, latLng: LatLng): String? {
    val point = map.projection.toScreenLocation(latLng)
    val slop = 28f
    val box = RectF(point.x - slop, point.y - slop * 2f, point.x + slop, point.y + slop)
    val features = map.queryRenderedFeatures(box, "pin-hot", "pins")
    return features.firstOrNull()?.getStringProperty("key")
}

private fun placeAnchor(
    map: MapLibreMap,
    host: MapView,
    stations: List<PricedStation>,
    selectedKey: String?,
    onAnchor: (PinAnchor?) -> Unit,
) {
    val station = stations.firstOrNull { stationKey(it.station) == selectedKey }?.station
    if (station == null) {
        onAnchor(null)
        return
    }
    val point = map.projection.toScreenLocation(LatLng(station.latitude, station.longitude))
    val density = host.resources.displayMetrics.density
    val gap = 10f * density
    val lift = 36f * density
    val bubble = 168f * density
    val roomOnRight = host.width <= 0 || point.x + gap + bubble <= host.width
    val x = if (roomOnRight) point.x + gap else (point.x - gap - bubble).coerceAtLeast(0f)
    val y = (point.y - lift).coerceAtLeast(gap)
    onAnchor(PinAnchor(x, y, tailOnStart = roomOnRight))
}

private fun focus(map: MapLibreMap, station: OsmStation) {
    val update = CameraUpdateFactory.newLatLng(LatLng(station.latitude, station.longitude))
    map.animateCamera(update)
}

private fun publishPins(map: MapLibreMap, stations: List<PricedStation>, selectedKey: String?) {
    val source = map.style?.getSource("stations") as? GeoJsonSource ?: return
    source.setGeoJson(pins(stations, selectedKey))
}

private fun aim(map: MapLibreMap, centre: Pair<Double, Double>) {
    val position = CameraPosition.Builder()
        .target(LatLng(centre.first, centre.second))
        .zoom(12.0)
        .build()
    map.cameraPosition = position
}

private fun pins(stations: List<PricedStation>, selectedKey: String?): FeatureCollection {
    val features = stations.map { priced -> pinFeature(priced.station, selectedKey) }
    return FeatureCollection.fromFeatures(features)
}

private fun pinFeature(station: OsmStation, selectedKey: String?): Feature {
    val key = stationKey(station)
    val feature = Feature.fromGeometry(Point.fromLngLat(station.longitude, station.latitude))
    feature.addStringProperty("key", key)
    feature.addStringProperty("on", if (key == selectedKey) "1" else "0")
    return feature
}

internal fun stationKey(station: OsmStation): String {
    return "${station.osmType}:${station.osmId}:${station.latitude}:${station.longitude}"
}

private fun pinBitmap(density: Float): Bitmap {
    val width = (44f * density).toInt().coerceAtLeast(44)
    val height = (60f * density).toInt().coerceAtLeast(60)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val stroke = 2.4f * density
    val radius = (width - stroke * 2f) / 2f
    val cx = width / 2f
    val headY = stroke + radius
    val tipY = height - 1f
    drawPinShadow(canvas, cx, tipY, radius)
    val body = pinPath(cx, headY, radius, tipY)
    canvas.drawPath(body, pinFill(cx, headY, radius, tipY))
    drawPinGloss(canvas, cx, headY, radius)
    canvas.drawPath(body, pinInk(stroke))
    val eye = radius * 0.36f
    canvas.drawCircle(cx, headY, eye, eyeFill())
    canvas.drawCircle(cx, headY, eye, pinInk(stroke * 0.9f))
    return bitmap
}

/** Round head and a tangent tail. The tip is the bottom centre of the bitmap. */
private fun pinPath(cx: Float, cy: Float, radius: Float, tipY: Float): Path {
    val theta = Math.toRadians(56.0)
    val dx = (sin(theta) * radius).toFloat()
    val dy = (cos(theta) * radius).toFloat()
    val leftX = cx - dx
    val rightX = cx + dx
    val joinY = cy + dy
    val start = Math.toDegrees(Math.atan2((joinY - cy).toDouble(), (leftX - cx).toDouble())).toFloat()
    val end = Math.toDegrees(Math.atan2((joinY - cy).toDouble(), (rightX - cx).toDouble())).toFloat()
    var sweep = end - start
    if (sweep <= 0f) sweep += 360f
    val path = Path()
    val head = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
    path.arcTo(head, start, sweep, true)
    val pull = radius * 0.72f
    val tangentX = -dy / radius
    val tangentY = dx / radius
    val neck = tipY - joinY
    path.cubicTo(
        rightX + tangentX * pull,
        joinY + tangentY * pull,
        cx + radius * 0.18f,
        tipY - neck * 0.16f,
        cx,
        tipY,
    )
    path.cubicTo(
        cx - radius * 0.18f,
        tipY - neck * 0.16f,
        leftX - tangentX * pull,
        joinY + tangentY * pull,
        leftX,
        joinY,
    )
    path.close()
    return path
}

private fun pinFill(cx: Float, headY: Float, radius: Float, tipY: Float): Paint {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
        cx,
        headY - radius,
        cx,
        tipY,
        Color.parseColor("#FFE566"),
        Color.parseColor("#E6A800"),
        Shader.TileMode.CLAMP,
    )
    return paint
}

private fun pinInk(width: Float): Paint {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = width
    paint.color = Color.parseColor("#1E4D8C")
    paint.strokeJoin = Paint.Join.ROUND
    paint.strokeCap = Paint.Cap.ROUND
    return paint
}

private fun eyeFill(): Paint {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.parseColor("#FFFEF8")
    return paint
}

private fun drawPinGloss(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.parseColor("#66FFFFFF")
    canvas.save()
    canvas.clipPath(Path().apply { addCircle(cx, cy, radius * 0.9f, Path.Direction.CW) })
    canvas.drawOval(
        RectF(cx - radius * 0.62f, cy - radius * 0.82f, cx + radius * 0.08f, cy - radius * 0.18f),
        paint,
    )
    canvas.restore()
}

private fun drawPinShadow(canvas: Canvas, cx: Float, tipY: Float, radius: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.parseColor("#2E141820")
    canvas.drawOval(
        RectF(cx - radius * 0.34f, tipY - radius * 0.18f, cx + radius * 0.42f, tipY),
        paint,
    )
}
