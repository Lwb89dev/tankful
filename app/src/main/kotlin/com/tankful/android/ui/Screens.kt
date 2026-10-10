package com.tankful.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.tankful.android.sync.AMBER_PACKAGE
import com.tankful.android.transfer.IMPORT_FILE_CAP
import com.tankful.android.transfer.readCapped
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.tankful.android.R
import com.tankful.android.data.catalogue.CatalogueEntry
import com.tankful.android.data.catalogue.CatalogueFacet
import com.tankful.android.data.catalogue.CatalogueNarrowing
import com.tankful.android.data.catalogue.CataloguePick
import com.tankful.android.data.catalogue.catalogueChoices
import com.tankful.android.data.catalogue.displacementCc
import com.tankful.android.data.catalogue.resolvePick
import com.tankful.android.data.store.ImportChoice
import com.tankful.android.model.ConfirmReason
import com.tankful.android.model.FuelKind
import com.tankful.android.model.OdometerReason
import com.tankful.android.model.QuickFuelChoice
import com.tankful.android.model.RejectReason
import com.tankful.android.money.MAJOR_CURRENCIES
import com.tankful.android.providers.PricedStation
import com.tankful.android.providers.PostedPrice
import com.tankful.android.providers.fuelsOnCard
import com.tankful.android.quantity.fillTotalText
import com.tankful.android.quantity.odometerMetres
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import com.tankful.android.time.InsightRange
import java.util.UUID

@Composable
fun FirstRunScreen(onHome: () -> Unit, model: EditorViewModel = hiltViewModel(), sync: SyncSettingsViewModel = hiltViewModel()) {
    var step by rememberSaveable { mutableStateOf("intro") }
    BackHandler(enabled = step != "intro") { step = previousOnboardingStep(step) }
    val result by model.result.collectAsStateWithLifecycle()
    val message by sync.message.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (step == "intro" || step == "login" || step == "relays") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (step == "intro") IntroStep { step = "login" }
                if (step == "login") LoginStep(sync, message) { step = "relays" }
                if (step == "relays") RelayStep(sync) { step = "vehicle" }
            }
        }
        if (step == "vehicle") {
            StepIndex("04")
            Box(Modifier.weight(1f).fillMaxWidth()) { VehiclePicker(model, result) }
        }
    }
    LaunchedEffect(message, step) {
        if (step == "login" && message == "connected") step = "relays"
    }
    LaunchedEffect(result) {
        if (result !is FormResult.Saved || step != "vehicle") return@LaunchedEffect
        model.reset()
        onHome()
    }
}

internal fun previousOnboardingStep(step: String): String {
    if (step == "vehicle") return "relays"
    if (step == "relays") return "login"
    if (step == "login") return "intro"
    return step
}

@Composable
private fun IntroStep(onNext: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepIndex("01")
        BrandMark()
        Text(stringResource(R.string.app_name), style = HeroStyle.copy(fontSize = 52.sp), color = ink, textAlign = TextAlign.Center)
        Box(Modifier.size(width = 56.dp, height = 2.dp).background(MaterialTheme.colorScheme.secondary))
        Panel {
            Text(
                stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        TankButton(stringResource(R.string.onboarding_next), onNext)
    }
}

@Composable
private fun StepIndex(index: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(index, style = MetricStyle, color = MaterialTheme.colorScheme.secondary)
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)))
    }
}

@Composable
private fun LoginStep(sync: SyncSettingsViewModel, message: String, onLocal: () -> Unit) {
    val installed by sync.amberInstalled.collectAsStateWithLifecycle()
    var nsec by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        acceptAmberResult(result, sync)
    }
    val ink = MaterialTheme.colorScheme.onBackground
    StepIndex("02")
    Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleLarge, color = ink)
    Panel {
        if (installed) Text(stringResource(R.string.login_amber_found), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (!installed) Text(stringResource(R.string.login_amber_missing), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.login_amber_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
    TankButton(stringResource(R.string.login_amber)) { sync.connect() }
    SignerLines(message)
    Text(stringResource(R.string.login_nsec_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = nsec,
        onValueChange = { nsec = it },
        label = { Text(stringResource(R.string.login_nsec_hint)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        colors = instrumentFieldColors(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
    TextButton(onClick = { sync.useNsec(nsec) }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.login_nsec_action))
    }
    LaunchedEffect(message) { launchAmber(message, sync, launcher) }
    TextButton(onClick = onLocal, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.continue_locally))
    }
}

@Composable
private fun SignerLines(message: String) {
    val ink = MaterialTheme.colorScheme.onBackground
    if (message == "unavailable") Text(stringResource(R.string.signer_unavailable), color = ink)
    if (message == "no-answer") Text(stringResource(R.string.signer_no_answer), color = ink)
    if (message == "connected") Text(stringResource(R.string.signer_connected), color = ink)
    if (message == "nsec-invalid") Text(stringResource(R.string.login_nsec_invalid), color = ink)
}

private fun launchAmber(message: String, sync: SyncSettingsViewModel, launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>) {
    if (message != "launch") return
    val intent = sync.publicKeyIntent()
    sync.consumeLaunch()
    if (intent == null) {
        sync.markNoAnswer()
        return
    }
    val opened = runCatching { launcher.launch(intent) }.isSuccess
    if (!opened) sync.markNoAnswer()
}

private fun acceptAmberResult(result: androidx.activity.result.ActivityResult, sync: SyncSettingsViewModel) {
    if (result.resultCode != android.app.Activity.RESULT_OK) {
        sync.markNoAnswer()
        return
    }
    val data = result.data
    val value = data?.getStringExtra("signature") ?: data?.getStringExtra("result")
    val pkg = data?.getStringExtra("package")
    if (pkg != null && pkg != AMBER_PACKAGE) {
        sync.markNoAnswer()
        return
    }
    if (value.isNullOrBlank()) sync.markNoAnswer() else sync.acceptAmber(AMBER_PACKAGE, value)
}

@Composable
private fun RelayStep(sync: SyncSettingsViewModel, onNext: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf("") }
    var home by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    StepIndex("03")
    Text(stringResource(R.string.relay_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    Text(stringResource(R.string.relay_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    PUBLIC_RELAYS.forEach { url ->
        RelayPick(url, pickContains(chosen, url)) { chosen = togglePick(chosen, url) }
    }
    Field(stringResource(R.string.relay_home), home, stringResource(R.string.relay_home_hint)) {
        home = it
        invalid = false
    }
    if (invalid) Text(stringResource(R.string.relay_home_invalid), color = MaterialTheme.colorScheme.error)
    TankButton(stringResource(R.string.onboarding_next)) {
        val saved = sync.saveChosenRelays(chosenUrls(chosen), home)
        if (saved) onNext()
        if (!saved) invalid = true
    }
}

@Composable
private fun RelayPick(url: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = on, onCheckedChange = { onToggle() })
        Text(
            url,
            modifier = Modifier.weight(1f).clickable(onClick = onToggle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun pickContains(chosen: String, url: String): Boolean = chosen.split('\n').any { it == url }

private fun togglePick(chosen: String, url: String): String {
    val selected = chosen.split('\n').filter { it.isNotEmpty() }.toMutableSet()
    if (!selected.add(url)) selected.remove(url)
    return PUBLIC_RELAYS.filter { it in selected }.joinToString("\n")
}

private fun chosenUrls(chosen: String): List<String> = PUBLIC_RELAYS.filter { pickContains(chosen, it) }

@Composable
fun HomeScreen(nav: NavHostController, model: DashboardViewModel = hiltViewModel()) {
    val ui by model.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_home), style = MaterialTheme.typography.titleLarge)
            SettingsGear { nav.navigate("settings") }
        }
        if (ui.emptyGarage) {
            Panel {
                Text(stringResource(R.string.empty_garage), style = MaterialTheme.typography.bodyLarge)
            }
            TankButton(stringResource(R.string.add_vehicle)) { nav.navigate("vehicle/new") }
            return@Column
        }
        Panel {
            Text(ui.name, style = HudLabel, color = MaterialTheme.colorScheme.secondary)
            Text(ui.hero, style = HeroStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { contentDescription = ui.hero })
            val secondary = ui.secondary
            if (secondary != null) Text(secondary, style = MetricStyle)
            if (ui.liquidOnly) Text(stringResource(R.string.liquid_fuel_only))
            if (ui.showOpen) Text(stringResource(R.string.fill_to_full))
            DirectionLine(ui.direction, ui.deltaText)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(Modifier.weight(1f)) {
                Text(stringResource(R.string.month_spend), style = HudLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ui.monthSpend, style = MetricStyle)
            }
            Panel(Modifier.weight(1f)) {
                Text(stringResource(R.string.spend_span_title), style = HudLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ui.spendPerDistance, style = MetricStyle)
            }
        }
        Panel {
            Text(ui.spanValue, style = MetricStyle)
            val ownership = ui.ownership
            if (ownership != null) {
                Text("${stringResource(R.string.ownership)} $ownership", style = MaterialTheme.typography.bodyLarge)
            }
        }
        TankButton(stringResource(R.string.add_refuel)) { nav.navigate("refuel/new") }
    }
}

@Composable
fun TimelineScreen(nav: NavHostController, model: TimelineViewModel = hiltViewModel()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nav_timeline), style = MaterialTheme.typography.titleLarge)
        TimelineBody(nav, model)
    }
}

@Composable
private fun TimelineBody(nav: NavHostController, model: TimelineViewModel) {
    val rows by model.rows.collectAsStateWithLifecycle()
    val hits by model.searchHits.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(stringResource(R.string.search), query) { query = it }
        TankButton(stringResource(R.string.search)) { model.search(query) }
        if (rows.isEmpty()) {
            Panel {
                Text(stringResource(R.string.empty_timeline), style = MaterialTheme.typography.bodyLarge)
            }
            TankButton(stringResource(R.string.add_refuel)) { nav.navigate("refuel/new") }
            return@Column
        }
        rows.forEach { line ->
            val refuelId = line.refuelId
            if (refuelId != null) {
                DeckCard(selected = false, onClick = { nav.navigate("refuel/$refuelId") }) { TimelineCopy(line) }
            }
            if (refuelId == null) Panel { TimelineCopy(line) }
        }
        hits.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun InsightsScreen(
    nav: NavHostController,
    model: InsightsViewModel = hiltViewModel(),
    timeline: TimelineViewModel = hiltViewModel(),
) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.nav_insights), style = MaterialTheme.typography.titleLarge)
        ChoiceRow {
            InsightRange.entries.filter { it != InsightRange.CUSTOM }.forEach { range ->
                ChoiceChip(rangeLabel(range), range == selected) { model.choose(range) }
            }
        }
        Panel {
            Text(ui.hero, style = HeroStyle, color = MaterialTheme.colorScheme.onSurface)
            DirectionLine(ui.direction, ui.deltaText)
        }
        Text(stringResource(R.string.insight_consumption), style = MaterialTheme.typography.titleLarge)
        Panel {
            RateAxisLabel(ui.rateUnit)
            RateChart(ui.points)
            if (ui.points.isEmpty()) Text(stringResource(R.string.insight_no_consumption), style = MaterialTheme.typography.bodyLarge)
            ui.points.forEach { point -> ConsumptionRow(point) }
        }
        val secondaryFuel = ui.secondaryFuel
        if (secondaryFuel != null && ui.secondaryPoints.isNotEmpty()) {
            Text(fuelKindLabel(secondaryFuel), style = MaterialTheme.typography.titleLarge)
            Panel {
                RateAxisLabel(rateUnitOf(secondaryFuel))
                RateChart(ui.secondaryPoints)
                ui.secondaryPoints.forEach { point -> ConsumptionRow(point) }
            }
        }
        Text(stringResource(R.string.insight_distance_spend), style = MaterialTheme.typography.titleLarge)
        if (ui.spend.isEmpty()) {
            Text(stringResource(R.string.insight_no_ratio), style = MaterialTheme.typography.bodyLarge)
        }
        if (ui.spend.isNotEmpty()) {
            Panel { RateChart(ui.spend) }
        }
        val best = ui.best
        val worst = ui.worst
        if (best != null) {
            Panel {
                Text(stringResource(R.string.best), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
                Text(best, style = MetricStyle)
            }
        }
        if (worst != null) {
            Panel {
                Text(stringResource(R.string.worst), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
                Text(worst, style = MetricStyle)
            }
        }
        Text(stringResource(R.string.co2_unavailable))
        Text(stringResource(R.string.nav_timeline), style = MaterialTheme.typography.titleLarge)
        TimelineBody(nav, timeline)
    }
}

@Composable
fun GarageScreen(onOpen: (String) -> Unit, onNew: () -> Unit, model: GarageListViewModel = hiltViewModel()) {
    val lines by model.lines.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nav_garage), style = MaterialTheme.typography.titleLarge)
        if (lines.isEmpty()) {
            Panel {
                Text(stringResource(R.string.empty_garage), style = MaterialTheme.typography.bodyLarge)
            }
            TankButton(stringResource(R.string.add_vehicle), onNew)
            return@Column
        }
        lines.forEach { line ->
            DeckCard(selected = false, onClick = { onOpen(line.id) }) {
                Text(line.name, style = MaterialTheme.typography.titleLarge)
                if (line.titledByNick) Text("${line.make} ${line.model}", style = MaterialTheme.typography.bodyLarge)
                Text(garageFuel(line.fuel, line.secondaryFuel), style = MaterialTheme.typography.bodyLarge)
                Text("${stringResource(R.string.odometer)} ${line.odometer}", style = MaterialTheme.typography.bodyMedium)
                Text(line.consumption, style = MetricStyle)
                val secondary = line.secondary
                if (secondary != null) Text(secondary, style = MaterialTheme.typography.bodyMedium)
                Text(line.spend, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TankButton(stringResource(R.string.add_vehicle), onNew)
    }
}

@Composable
fun VehicleScreen(id: String?, onDone: () -> Unit, model: EditorViewModel = hiltViewModel()) {
    if (id == null) NewVehicleScreen(onDone, model)
    if (id != null) ExistingVehicleScreen(id, onDone, model)
}

@Composable
private fun NewVehicleScreen(onDone: () -> Unit, model: EditorViewModel) {
    val result by model.result.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        VehiclePicker(model, result)
    }
    LaunchedEffect(result) {
        if (result is FormResult.Saved) onDone()
    }
}

@Composable
private fun ExistingVehicleScreen(id: String, onDone: () -> Unit, model: EditorViewModel) {
    val result by model.result.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        VehiclePicker(model, result, id)
    }
    LaunchedEffect(result) {
        if (result is FormResult.Saved) onDone()
    }
}

@Composable
fun RefuelScreen(refuelId: String?, onDone: () -> Unit, model: EditorViewModel = hiltViewModel()) {
    val freshId = rememberSaveable { UUID.randomUUID().toString() }
    val secondId = rememberSaveable { UUID.randomUUID().toString() }
    val today = rememberSaveable { LocalDate.now().toString() }
    val id = refuelId ?: freshId
    var quantity by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var secondQuantity by rememberSaveable { mutableStateOf("") }
    var secondPrice by rememberSaveable { mutableStateOf("") }
    var odometer by rememberSaveable { mutableStateOf("") }
    var station by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var full by rememberSaveable { mutableStateOf(true) }
    var dateIso by rememberSaveable { mutableStateOf(today) }
    var secondDate by rememberSaveable { mutableStateOf(today) }
    var readingId by rememberSaveable { mutableStateOf("") }
    var clockEpoch by rememberSaveable { mutableStateOf(0L) }
    var savedCurrency by rememberSaveable { mutableStateOf("") }
    var lockedFuel by rememberSaveable { mutableStateOf("") }
    var odometerMissing by rememberSaveable { mutableStateOf(false) }
    var formReady by rememberSaveable { mutableStateOf(refuelId == null) }
    var acks by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    val fuels by model.refuelFuels.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (formReady) RefuelBody(
            fuels, id, secondId, quantity, price, secondQuantity, secondPrice,
            odometer, station, note, full, dateIso, secondDate, readingId, clockEpoch, savedCurrency,
            lockedFuel, odometerMissing, acks, reason, result, model,
            { quantity = it }, { price = it },
            { secondQuantity = it }, { secondPrice = it },
            { odometer = it }, { station = it }, { note = it }, { full = it },
            { dateIso = it }, { secondDate = it }, { odometerMissing = it },
            { acks = it }, { reason = it },
        )
    }
    LaunchedEffect(refuelId) {
        if (refuelId == null) return@LaunchedEffect
        val loaded = model.readRefuel(refuelId) ?: return@LaunchedEffect
        quantity = loaded.quantity
        price = loaded.price
        odometer = loaded.odometer
        station = loaded.station
        note = loaded.note
        full = loaded.full
        dateIso = loaded.dateIso
        readingId = loaded.readingId
        clockEpoch = loaded.clockEpochMs
        savedCurrency = loaded.currencyCode
        lockedFuel = loaded.fuel.name
        formReady = true
    }
    LaunchedEffect(result) {
        if (result is FormResult.Saved) onDone()
    }
}

@Composable
fun OdometerScreen(onDone: () -> Unit, model: EditorViewModel = hiltViewModel()) {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var value by rememberSaveable { mutableStateOf("") }
    var acks by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    val result by model.result.collectAsStateWithLifecycle()
    val imperial by model.imperial.collectAsStateWithLifecycle()
    val lastOdometer by model.lastOdometer.collectAsStateWithLifecycle()
    val odometerHint = if (imperial) R.string.refuel_odometer_miles else R.string.refuel_odometer_hint
    val hint = stringResource(odometerHint) + "\n" + stringResource(R.string.refuel_last_update) + " " + lastOdometer
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(stringResource(R.string.odometer), value, hint, KeyboardType.Number) { value = it }
        TankButton(stringResource(R.string.save)) {
            model.saveOdometer(id, value, acks.map { ConfirmReason.valueOf(it) }.toSet(), reason?.let { OdometerReason.valueOf(it) })
        }
        if (result is FormResult.OdometerConfirm) {
            TankButton(stringResource(R.string.save_anyway)) { acks = confirmReasons(result).map { it.name } }
            TankButton(stringResource(R.string.reason_correction)) { reason = OdometerReason.CORRECTION.name }
            TankButton(stringResource(R.string.reason_replacement)) { reason = OdometerReason.REPLACEMENT.name }
        }
        OutcomeText(result)
    }
    LaunchedEffect(acks, reason) {
        if (acks.isNotEmpty() || reason != null) {
            model.saveOdometer(id, value, acks.map { ConfirmReason.valueOf(it) }.toSet(), reason?.let { OdometerReason.valueOf(it) })
        }
    }
    LaunchedEffect(result) {
        if (result is FormResult.Saved) onDone()
    }
}

@Composable
fun ExpenseScreen(onDone: () -> Unit, model: EditorViewModel = hiltViewModel()) {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var total by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("legal.insurance") }
    val categories by model.categories.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.category), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
        ChoiceRow {
            categories.forEach { row ->
                ChoiceChip(row.displayName, row.id == category) { category = row.id }
            }
        }
        Field(stringResource(R.string.total), total) { total = it }
        Field(stringResource(R.string.note), note) { note = it }
        TankButton(stringResource(R.string.save)) { model.saveExpense(id, category, total, note) }
        OutcomeText(result)
    }
    LaunchedEffect(result) {
        if (result is FormResult.Saved) onDone()
    }
}

@Composable
fun MaintenanceScreen(onDone: () -> Unit, model: EditorViewModel = hiltViewModel()) {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    var total by rememberSaveable { mutableStateOf("") }
    var labour by rememberSaveable { mutableStateOf("") }
    var parts by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("") }
    var service by rememberSaveable { mutableStateOf("general_service") }
    var acks by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val types by model.serviceTypes.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.service_type), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
        ChoiceRow {
            types.forEach { row ->
                ChoiceChip(row.displayName, row.id == service) { service = row.id }
            }
        }
        Field(stringResource(R.string.total), total) { total = it }
        Field(stringResource(R.string.labour), labour) { labour = it }
        Field(stringResource(R.string.parts), parts) { parts = it }
        Field(stringResource(R.string.provider), provider) { provider = it }
        Field(stringResource(R.string.note), note) { note = it }
        TankButton(stringResource(R.string.save)) {
            model.saveMaintenance(
                MaintenanceInput(id, total, labour, parts, note, service, "maintenance.scheduled_service", provider, acks.map { ConfirmReason.valueOf(it) }.toSet()),
            )
        }
        if (result is FormResult.Confirm) {
            TankButton(stringResource(R.string.confirm)) { acks = confirmReasons(result).map { it.name } }
        }
        OutcomeText(result)
    }
    LaunchedEffect(result) {
        if (result is FormResult.Saved) onDone()
    }
}

@Composable
fun SettingsScreen(
    nav: NavHostController,
    model: SettingsViewModel = hiltViewModel(),
    sync: SyncSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val file by model.exportFile.collectAsStateWithLifecycle()
    val theme by model.theme.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
        ProfileRow(sync)
        UnitsAndCurrency(model)
        Text(stringResource(R.string.theme), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
        ChoiceRow {
            ChoiceChip(stringResource(R.string.theme_system), theme == "SYSTEM") { model.setTheme("SYSTEM") }
            ChoiceChip(stringResource(R.string.theme_light), theme == "LIGHT") { model.setTheme("LIGHT") }
            ChoiceChip(stringResource(R.string.theme_dark), theme == "DARK") { model.setTheme("DARK") }
        }
        Text(stringResource(R.string.export_warning))
        TankButton(stringResource(R.string.export_action)) { model.export() }
        TankButton(stringResource(R.string.import_action)) { nav.navigate("import") }
        TankButton(stringResource(R.string.sync_title)) { nav.navigate("settings/sync") }
        RelayEditor(sync)
        SupportRow()
    }
    LaunchedEffect(file) {
        val ready = file ?: return@LaunchedEffect
        shareExport(context, ready)
    }
}

@Composable
fun MoreScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nav_more), style = MaterialTheme.typography.titleLarge)
        TankButton(stringResource(R.string.reminders)) { nav.navigate("reminders") }
        TankButton(stringResource(R.string.add_refuel)) { nav.navigate("refuel/new") }
        TankButton(stringResource(R.string.add_odometer)) { nav.navigate("odometer/new") }
        TankButton(stringResource(R.string.add_expense)) { nav.navigate("expense/new") }
        TankButton(stringResource(R.string.add_maintenance)) { nav.navigate("maintenance/new") }
    }
}

@Composable
private fun RelayEditor(sync: SyncSettingsViewModel) {
    val stored by sync.storedRelays.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val saved = stored.orEmpty().lines().filter { it.isNotEmpty() }
    val health by sync.relayHealth.collectAsStateWithLifecycle()
    Text(stringResource(R.string.relays), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
    saved.forEach { url -> RelayRow(url, health[url]) { sync.removeRelay(url) } }
    Field(stringResource(R.string.relay_add), draft, stringResource(R.string.relay_add_hint)) {
        draft = it
        invalid = false
    }
    if (invalid) Text(stringResource(R.string.relay_invalid), color = MaterialTheme.colorScheme.error)
    TankButton(stringResource(R.string.relay_add)) {
        val accepted = sync.addRelay(draft)
        if (!accepted) invalid = true
        if (accepted) draft = ""
    }
}

@Composable
private fun SupportRow() {
    val context = LocalContext.current
    Text(LIGHTNING_ADDRESS, style = MaterialTheme.typography.bodyMedium)
    TankButton(stringResource(R.string.support_tankful)) {
        val opened = openLightningSupport(context)
        if (!opened) showCopied(context)
    }
}

private fun showCopied(context: Context) {
    val copied = context.getString(R.string.support_copied)
    Toast.makeText(context, copied, Toast.LENGTH_LONG).show()
}

@Composable
private fun ProfileRow(sync: SyncSettingsViewModel) {
    val card by sync.profile.collectAsStateWithLifecycle()
    val shown = card ?: return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileMark(shown.picture)
        Text(shown.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun ProfileMark(bitmap: Bitmap?) {
    if (bitmap == null) {
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
        return
    }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(48.dp).clip(CircleShape),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun RelayRow(url: String, up: Boolean?, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RelayMark(up)
        Text(url, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.relay_remove))
        }
    }
}

@Composable
private fun RelayMark(up: Boolean?) {
    val color = when (up) {
        true -> Color(0xFF2E9B4A)
        false -> Color(0xFFC4473A)
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val description = when (up) {
        true -> stringResource(R.string.relay_up)
        false -> stringResource(R.string.relay_down)
        null -> ""
    }
    Canvas(
        Modifier.size(12.dp).semantics { if (description.isNotEmpty()) contentDescription = description },
    ) {
        drawCircle(color)
    }
}

@Composable
fun SyncScreen(model: SyncSettingsViewModel = hiltViewModel()) {
    val enabled by model.enabled.collectAsStateWithLifecycle()
    val nip09 by model.nip09.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.sync_off))
        TankButton(if (enabled) stringResource(R.string.disable_sync) else stringResource(R.string.enable_sync)) {
            model.setEnabled(!enabled)
        }
        val installed by model.amberInstalled.collectAsStateWithLifecycle()
        if (installed) Text(stringResource(R.string.login_amber_found))
        if (!installed) Text(stringResource(R.string.login_amber_missing))
        TankButton(stringResource(R.string.connect_signer)) { model.connect() }
        TankButton(stringResource(R.string.disconnect_signer)) { model.disconnect() }
        TankButton(stringResource(R.string.nip09)) { model.setNip09(!nip09) }
        SignerLines(message)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        acceptAmberResult(result, model)
    }
    LaunchedEffect(message) { launchAmber(message, model, launcher) }
}

@Composable
fun ImportScreen(model: ImportViewModel = hiltViewModel()) {
    val preview by model.preview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { readCapped(it, IMPORT_FILE_CAP) }
        model.loadDocument(bytes)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.import_action), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.export_warning))
        TankButton(stringResource(R.string.import_action)) { open.launch(arrayOf("application/json", "application/zip", "*/*")) }
        val rows = preview?.rows.orEmpty()
        rows.forEach { row ->
            Text("${row.id} ${row.kind}")
            if (row.kind == com.tankful.android.transfer.ImportRowKind.CONFLICT) {
                TankButton("Keep") { model.choose(row.id, ImportChoice.KEEP) }
                TankButton("Replace") { model.choose(row.id, ImportChoice.REPLACE) }
                TankButton("Skip") { model.choose(row.id, ImportChoice.SKIP) }
            }
        }
        if (preview?.refused == null && rows.isNotEmpty()) TankButton(stringResource(R.string.save)) { model.apply() }
        val refused = preview?.refused
        if (refused != null) Text(refused)
    }
    LaunchedEffect(open) { context.packageName }
}

@Composable
fun StationsScreen(model: StationsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val stations by model.stations.collectAsStateWithLifecycle()
    val centre by model.centre.collectAsStateWithLifecycle()
    val searching by model.searching.collectAsStateWithLifecycle()
    val searchFailed by model.searchFailed.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf("") }
    var anchor by remember { mutableStateOf<PinAnchor?>(null) }
    var roadstrMissing by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<MapTarget?>(null) }
    var mapFailed by remember { mutableStateOf(false) }
    var locationOff by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val allowed = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationOff = !allowed
        if (allowed) placeOnMap(context) { latitude, longitude -> model.aim(latitude, longitude) }
    }
    var areaMissing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        StationMap(
            stations,
            centre,
            selected.ifBlank { null },
            { selected = it },
            { view = it },
            { mapFailed = it },
            { anchor = it },
            Modifier.fillMaxSize(),
        )
        StationOverlay(
            stations,
            selected,
            searching,
            searchFailed || areaMissing,
            mapFailed,
            locationOff,
            roadstrMissing,
            { selected = it },
            { askForPosition(context, permission, { latitude, longitude -> model.aim(latitude, longitude) }) { locationOff = it } },
            { searchVisibleArea(view, { areaMissing = it }, model::search) },
        )
        val spot = anchor
        val chosen = stations.firstOrNull { stationKey(it.station) == selected }
        if (spot != null && chosen != null) {
            RoadstrBubble(Modifier.offset { IntOffset(spot.x.toInt(), spot.y.toInt()) }, spot.tailOnStart) {
                val station = chosen.station
                roadstrMissing = !openInRoadstr(context, station.latitude, station.longitude, station.name)
            }
        }
    }
}

@Composable
fun RemindersScreen(model: RemindersViewModel = hiltViewModel()) {
    val reminders by model.reminders.collectAsStateWithLifecycle()
    var due by rememberSaveable { mutableStateOf("") }
    var vehicle by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { model.selectedVehicle { vehicle = it } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.reminders), style = MaterialTheme.typography.titleLarge)
        Field(stringResource(R.string.next_due), due) { due = it }
        TankButton(stringResource(R.string.enable_reminder)) {
            val id = vehicle ?: return@TankButton
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            model.save(id, "INSURANCE", due, true)
        }
        reminders.forEach { row ->
            Panel {
                Text("${row.kind} ${row.dueDate.orEmpty()}", style = MaterialTheme.typography.titleLarge)
                TankButton(stringResource(R.string.renewed)) { model.renewed(row, due) }
            }
        }
        Text(stringResource(R.string.permission_needed))
    }
}

@Composable
private fun UnitsAndCurrency(model: SettingsViewModel) {
    val imperial by model.imperial.collectAsStateWithLifecycle()
    val currency by model.currency.collectAsStateWithLifecycle()
    val unitLabel = if (imperial) R.string.units_imperial else R.string.units_metric
    Text(stringResource(R.string.units_label), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(unitLabel), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = imperial, onCheckedChange = { model.setImperial(it) })
    }
    Text(stringResource(R.string.currency_label), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
    ChoiceRow {
        MAJOR_CURRENCIES.forEach { code ->
            ChoiceChip(code, code == currency) { model.setCurrency(code) }
        }
    }
}

@Composable
private fun garageFuel(fuel: String, secondary: String?): String {
    val primary = fuelKindLabel(fuel)
    if (secondary.isNullOrBlank()) return primary
    return "$primary + ${fuelKindLabel(secondary)}"
}

@Composable
private fun VehicleFields(
    make: String,
    modelName: String,
    year: String,
    onMake: (String) -> Unit,
    onModel: (String) -> Unit,
    onYear: (String) -> Unit,
) {
    Field(stringResource(R.string.make), make, onValue = onMake)
    Field(stringResource(R.string.model), modelName, onValue = onModel)
    Field(stringResource(R.string.model_year), year, onValue = onYear)
}

@Composable
private fun RefuelBody(
    fuels: List<FuelKind>?,
    id: String,
    secondId: String,
    quantity: String,
    price: String,
    secondQuantity: String,
    secondPrice: String,
    odometer: String,
    station: String,
    note: String,
    full: Boolean,
    dateIso: String,
    secondDate: String,
    readingId: String,
    clockEpoch: Long,
    savedCurrency: String,
    lockedFuel: String,
    odometerMissing: Boolean,
    acks: List<String>,
    reason: String?,
    result: FormResult,
    model: EditorViewModel,
    onQuantity: (String) -> Unit,
    onPrice: (String) -> Unit,
    onSecondQuantity: (String) -> Unit,
    onSecondPrice: (String) -> Unit,
    onOdometer: (String) -> Unit,
    onStation: (String) -> Unit,
    onNote: (String) -> Unit,
    onFull: (Boolean) -> Unit,
    onDate: (String) -> Unit,
    onSecondDate: (String) -> Unit,
    onOdometerMissing: (Boolean) -> Unit,
    onAcks: (List<String>) -> Unit,
    onReason: (String) -> Unit,
) {
    val imperial by model.imperial.collectAsStateWithLifecycle()
    val settingsCurrency by model.currencyCode.collectAsStateWithLifecycle()
    val lastOdometer by model.lastOdometer.collectAsStateWithLifecycle()
    val currency = savedCurrency.ifBlank { settingsCurrency }
    val total = fillTotalText(quantity, price, currency, Locale.getDefault())
    val secondTotal = fillTotalText(secondQuantity, secondPrice, currency, Locale.getDefault())
    if (fuels == null) return
    if (fuels.isEmpty() && lockedFuel.isEmpty()) {
        Text(stringResource(R.string.liquid_fuel_only))
        return
    }
    val firstFuel = lockedFuel.takeIf { it.isNotEmpty() }?.let { runCatching { FuelKind.valueOf(it) }.getOrNull() } ?: fuels.firstOrNull()
    if (firstFuel == null) return
    FuelGroup {
        FillBlock(firstFuel, quantity, price, total, dateIso, imperial, onQuantity, onPrice, onDate)
    }
    val secondFuel = if (lockedFuel.isEmpty()) fuels.getOrNull(1) else null
    if (secondFuel != null) {
        FuelGroup {
            Text(stringResource(R.string.refuel_second), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
            FillBlock(secondFuel, secondQuantity, secondPrice, secondTotal, secondDate, imperial, onSecondQuantity, onSecondPrice, onSecondDate)
        }
    }
    val odometerHint = if (imperial) R.string.refuel_odometer_miles else R.string.refuel_odometer_hint
    val hint = stringResource(odometerHint) + "\n" + stringResource(R.string.refuel_last_update) + " " + lastOdometer
    FuelGroup {
        Field(stringResource(R.string.odometer), odometer, hint, KeyboardType.Number, onOdometer)
        if (odometerMissing) Text(stringResource(R.string.refuel_odometer_required))
        Field(stringResource(R.string.station), station, onValue = onStation)
        Field(stringResource(R.string.note), note, onValue = onNote)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(full, onFull)
            Text(stringResource(R.string.full_tank))
        }
    }
    TankButton(stringResource(R.string.save)) {
        if (odometerMetres(odometer, imperial, Locale.getDefault()) == null) {
            onOdometerMissing(true)
            return@TankButton
        }
        onOdometerMissing(false)
        val ackSet = acks.map { ConfirmReason.valueOf(it) }.toSet()
        val chosen = reason?.let { OdometerReason.valueOf(it) }
        val second = secondFuel?.let {
            refuelInput(secondId, secondQuantity, secondPrice, secondTotal, odometer, full, station, note, it, ackSet, chosen, secondDate, "", 0L, savedCurrency)
        }
        val first = refuelInput(id, quantity, price, total, odometer, full, station, note, firstFuel, ackSet, chosen, dateIso, readingId, clockEpoch, savedCurrency)
        model.saveRefuelVisit(first, second)
    }
    OutcomeText(result)
    if (result is FormResult.Confirm || result is FormResult.OdometerConfirm) {
        TankButton(stringResource(R.string.confirm)) { onAcks(confirmReasons(result).map { it.name }) }
        TankButton(stringResource(R.string.reason_correction)) { onReason(OdometerReason.CORRECTION.name) }
        TankButton(stringResource(R.string.reason_replacement)) { onReason(OdometerReason.REPLACEMENT.name) }
    }
}

@Composable
private fun FuelGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.5.dp, groupOutline(), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun FillBlock(
    fuel: FuelKind,
    quantity: String,
    price: String,
    total: String,
    dateIso: String,
    imperial: Boolean,
    onQuantity: (String) -> Unit,
    onPrice: (String) -> Unit,
    onDate: (String) -> Unit,
) {
    val quantityHint = if (imperial && fuel != FuelKind.CNG) R.string.refuel_quantity_imperial else R.string.refuel_quantity_hint
    Text(fuelKindLabel(fuel.name), style = MaterialTheme.typography.titleLarge)
    DateField(dateIso, onDate)
    Field(stringResource(R.string.quantity), quantity, stringResource(quantityHint), KeyboardType.Decimal, onQuantity)
    Field(stringResource(R.string.unit_price), price, keyboard = KeyboardType.Decimal, onValue = onPrice)
    Field(stringResource(R.string.refuel_paid), total) { }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(iso: String, onIso: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = runCatching { mediumDate(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) }.getOrDefault(iso)
    DeckCard(selected = false, onClick = { open = true }) {
        Text(stringResource(R.string.refuel_date), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
        Text(shown, style = MetricStyle)
    }
    if (open) DateDialog(iso, { onIso(it); open = false }, { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(iso: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis
                if (picked != null) onConfirm(Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate().toString())
                if (picked == null) onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
    ) { DatePicker(state) }
}

private fun refuelInput(
    id: String,
    quantity: String,
    price: String,
    total: String,
    odometer: String,
    full: Boolean,
    station: String,
    note: String,
    fuel: FuelKind,
    acks: Set<ConfirmReason>,
    reason: OdometerReason?,
    dateIso: String,
    readingId: String,
    clockEpoch: Long,
    currency: String,
): RefuelInput {
    return RefuelInput(id, quantity, price, total, odometer, full, station, note, fuel, acks, reason, dateIso, readingId, clockEpoch, currency)
}

@Composable
private fun TimelineCopy(line: TimelineLine) {
    val fuel = line.fuel
    val whenText = if (line.whenMillis > 0L) mediumDate(line.whenMillis) else ""
    val fuelText = if (fuel != null) fuelKindLabel(fuel) else ""
    val headline = listOf(whenText, fuelText).filter { it.isNotEmpty() }.joinToString(" · ")
    if (headline.isNotEmpty()) Text(headline, style = MaterialTheme.typography.titleLarge)
    Text(line.detail, style = MaterialTheme.typography.bodyLarge)
}

private fun mediumDate(epochMs: Long): String {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    return format.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

@Composable
private fun BoxScope.StationOverlay(
    stations: List<PricedStation>,
    selected: String,
    searching: Boolean,
    searchFailed: Boolean,
    mapFailed: Boolean,
    locationOff: Boolean,
    roadstrMissing: Boolean,
    onSelect: (String) -> Unit,
    onLocate: () -> Unit,
    onSearch: () -> Unit,
) {
    LocatePin(Modifier.align(Alignment.TopEnd).padding(12.dp), onLocate)
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (mapFailed) Text(stringResource(R.string.map_failed))
        if (locationOff) Text(stringResource(R.string.location_needed))
        if (searchFailed) Text(stringResource(R.string.station_search_failed))
        if (roadstrMissing) Text(stringResource(R.string.roadstr_missing), color = MaterialTheme.colorScheme.error)
        if (searching) Text(stringResource(R.string.catalogue_searching))
        TankButton(stringResource(R.string.search_area), onSearch)
        StationDrawer(stations, selected, onSelect)
    }
}

@Composable
private fun LocatePin(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val label = stringResource(R.string.map_pin)
    val ink = MaterialTheme.colorScheme.secondary
    val fill = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    Box(
        modifier
            .size(48.dp)
            .background(fill)
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val span = size.minDimension
            val width = 2.dp.toPx()
            drawCircle(ink, span * 0.22f, Offset(span * 0.5f, span * 0.32f), style = Stroke(width))
            drawLine(ink, Offset(span * 0.5f, span * 0.54f), Offset(span * 0.5f, span * 0.92f), width)
        }
    }
}

@Composable
private fun RoadstrBubble(modifier: Modifier, tailOnStart: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.station_open_roadstr)
    val plate = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.secondary
    Row(
        modifier.heightIn(min = 48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tailOnStart) BubbleTail(true, plate, ink)
        Text(
            label,
            modifier = Modifier
                .background(plate, RoundedCornerShape(12.dp))
                .border(1.dp, ink, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!tailOnStart) BubbleTail(false, plate, ink)
    }
}

@Composable
private fun BubbleTail(pointingStart: Boolean, plate: Color, ink: Color) {
    Canvas(Modifier.size(width = 10.dp, height = 16.dp)) {
        val path = Path()
        val mid = size.height / 2f
        if (pointingStart) {
            path.moveTo(size.width, 0f)
            path.lineTo(0f, mid)
            path.lineTo(size.width, size.height)
        }
        if (!pointingStart) {
            path.moveTo(0f, 0f)
            path.lineTo(size.width, mid)
            path.lineTo(0f, size.height)
        }
        path.close()
        drawPath(path, plate)
        drawPath(path, ink, style = Stroke(1.dp.toPx()))
    }
}

/**
 * Roadstr's destination record is {"label","lat","lon"}. Its launcher has no
 * intent filter for that JSON, so this opens the launcher component explicitly
 * and attaches the record. The current activity does not read the extra.
 */
private fun openInRoadstr(context: Context, latitude: Double, longitude: Double, name: String): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage(ROADSTR_PACKAGE) ?: return false
    val label = name.trim().ifEmpty { "Fuel" }.take(ROADSTR_LABEL_LIMIT)
    val json = buildJsonObject {
        put("label", label)
        put("lat", latitude)
        put("lon", longitude)
    }.toString()
    launch.action = Intent.ACTION_SEND
    launch.type = "application/json"
    launch.putExtra(Intent.EXTRA_TEXT, json)
    return runCatching { context.startActivity(launch) }.isSuccess
}

private const val ROADSTR_LABEL_LIMIT = 300

private const val ROADSTR_PACKAGE = "app.roadstr"

@Composable
private fun ConsumptionRow(point: ChartPoint) {
    val mark = if (point.hollow) " ${stringResource(R.string.low_confidence)}" else ""
    Text("${point.label} ${point.text}$mark", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun RateAxisLabel(unit: String) {
    if (unit.isEmpty()) return
    Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StationDrawer(stations: List<PricedStation>, selected: String, onSelect: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(true) }
    val signature = remember(stations) { stations.joinToString("\n") { stationKey(it.station) } }
    var shown by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(signature) { revealFreshStations(signature, shown, { shown = it }, { open = it }) }
    if (stations.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(expandFrom = Alignment.Bottom),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            StationList(stations, selected, onSelect)
        }
        DrawerHandle(open) { open = it }
    }
}

private fun revealFreshStations(
    signature: String,
    shown: String,
    remember: (String) -> Unit,
    open: (Boolean) -> Unit,
) {
    if (signature.isEmpty() || signature == shown) return
    remember(signature)
    open(true)
}

@Composable
private fun DrawerHandle(open: Boolean, onOpen: (Boolean) -> Unit) {
    val label = stringResource(if (open) R.string.station_list_hide else R.string.station_list_show)
    val ink = MaterialTheme.colorScheme.secondary
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .pointerInput(open) { watchDrawer(open, onOpen) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) { drawDrawerGrip(ink, open) }
    }
}

private suspend fun PointerInputScope.watchDrawer(open: Boolean, onOpen: (Boolean) -> Unit) {
    val threshold = 36.dp.toPx()
    val slop = viewConfiguration.touchSlop
    awaitEachGesture { settleDrawer(readDrawerDrag(), open, threshold, slop, onOpen) }
}

private fun settleDrawer(total: Float, open: Boolean, threshold: Float, slop: Float, onOpen: (Boolean) -> Unit) {
    if (total > threshold) {
        onOpen(false)
        return
    }
    if (total < -threshold) {
        onOpen(true)
        return
    }
    if (abs(total) <= slop) onOpen(!open)
}

private suspend fun AwaitPointerEventScope.readDrawerDrag(): Float {
    val down = awaitFirstDown(requireUnconsumed = false)
    return dragTotal(down.id)
}

private suspend fun AwaitPointerEventScope.dragTotal(id: PointerId): Float {
    var total = 0f
    while (true) {
        val change = changeFor(id) ?: return total
        if (!change.pressed) return total
        total += change.position.y - change.previousPosition.y
        if (abs(total) > viewConfiguration.touchSlop) change.consume()
    }
}

private suspend fun AwaitPointerEventScope.changeFor(id: PointerId): PointerInputChange? {
    val event = awaitPointerEvent()
    return event.changes.firstOrNull { it.id == id }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDrawerGrip(ink: Color, open: Boolean) {
    val width = 2.dp.toPx()
    val mid = size.width / 2f
    val arm = 14.dp.toPx()
    val y = size.height / 2f
    drawLine(ink, Offset(mid - 22.dp.toPx(), 1.dp.toPx()), Offset(mid + 22.dp.toPx(), 1.dp.toPx()), width)
    if (open) {
        drawLine(ink, Offset(mid - arm, y - 3.dp.toPx()), Offset(mid, y + 4.dp.toPx()), width)
        drawLine(ink, Offset(mid, y + 4.dp.toPx()), Offset(mid + arm, y - 3.dp.toPx()), width)
        return
    }
    drawLine(ink, Offset(mid - arm, y + 3.dp.toPx()), Offset(mid, y - 4.dp.toPx()), width)
    drawLine(ink, Offset(mid, y - 4.dp.toPx()), Offset(mid + arm, y + 3.dp.toPx()), width)
}

@Composable
private fun StationList(stations: List<PricedStation>, selected: String, onSelect: (String) -> Unit) {
    if (stations.isEmpty()) return
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(stations, key = { stationKey(it.station) }) { priced ->
            val key = stationKey(priced.station)
            StationCard(priced, key == selected) { onSelect(key) }
        }
    }
}

@Composable
private fun StationCard(priced: PricedStation, selected: Boolean, onSelect: () -> Unit) {
    DeckCard(selected, onSelect) {
        Text(priced.station.name, style = MaterialTheme.typography.titleLarge)
        if (priced.station.address.isNotBlank()) Text(priced.station.address, style = MaterialTheme.typography.bodyMedium)
        val fuels = fuelsOnCard(priced.station, priced.prices)
        if (fuels.isEmpty()) {
            Text(stringResource(R.string.station_no_price))
            return@DeckCard
        }
        fuels.forEach { fuel -> FuelPriceLine(fuel, priced.prices.firstOrNull { it.fuel == fuel }) }
    }
}

@Composable
private fun FuelPriceLine(fuel: FuelKind, price: PostedPrice?) {
    val name = fuelKindLabel(fuel.name)
    if (price == null) {
        Text("$name  ${stringResource(R.string.station_no_price)}")
        return
    }
    Text("$name  ${price.euros} EUR  ${price.asOf}")
}

@Composable
internal fun FuelPicker(
    selected: String,
    choices: List<QuickFuelChoice> = QuickFuelChoice.entries,
    onSelect: (String) -> Unit,
) {
    ChoiceRow {
        choices.forEach { choice ->
            ChoiceChip(stringResource(fuelLabel(choice)), choice.name == selected) { onSelect(choice.name) }
        }
    }
}

@Composable
internal fun CatalogueHitRow(entry: CatalogueEntry, selected: Boolean, onSelect: () -> Unit) {
    DeckCard(selected, onSelect) {
        Text("${entry.year}  ${entry.make}", style = HudLabel, color = MaterialTheme.colorScheme.secondary)
        Text(entry.model, style = MaterialTheme.typography.titleLarge)
        if (entry.variant.isNotBlank()) Text(entry.variant, style = MaterialTheme.typography.bodyMedium)
        val capacity = displacementCc(entry.variant)
        if (capacity != null && !entry.variant.contains("cm3")) {
            Text("$capacity cm3", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val power = entry.powerKw
        if (power != null) Text(cataloguePower(power), style = MetricStyle)
        Text(catalogueMeta(entry), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(catalogueNumbers(entry), style = MetricStyle)
    }
}

@Composable
private fun catalogueMeta(entry: CatalogueEntry): String {
    val parts = mutableListOf(fuelKindLabel(entry.fuel))
    val train = trainLabel(entry.powertrain)
    if (train != null) parts += train
    parts += entry.testCycle
    if (entry.measureKind != "LIQUID") parts += entry.measureKind
    parts += entry.source
    return parts.joinToString(" · ")
}

@Composable
internal fun fuelKindLabel(fuel: String): String {
    if (fuel == "PETROL") return stringResource(R.string.fuel_petrol)
    if (fuel == "DIESEL") return stringResource(R.string.fuel_diesel)
    if (fuel == "LPG") return stringResource(R.string.fuel_lpg)
    if (fuel == "CNG") return stringResource(R.string.fuel_cng)
    if (fuel == "ELECTRIC") return stringResource(R.string.fuel_electric)
    if (fuel == "HYDROGEN") return stringResource(R.string.fuel_hydrogen)
    return fuel
}

@Composable
private fun trainLabel(train: String): String? {
    if (train == "HYBRID") return stringResource(R.string.fuel_hybrid)
    if (train == "MILD_HYBRID") return stringResource(R.string.fuel_mild)
    if (train == "PHEV") return stringResource(R.string.fuel_phev)
    if (train == "ELECTRIC") return stringResource(R.string.fuel_electric)
    if (train == "HYDROGEN") return stringResource(R.string.fuel_hydrogen)
    return null
}

internal fun fuelLabel(choice: QuickFuelChoice): Int {
    if (choice == QuickFuelChoice.PETROL) return R.string.fuel_petrol
    if (choice == QuickFuelChoice.DIESEL) return R.string.fuel_diesel
    if (choice == QuickFuelChoice.LPG) return R.string.fuel_lpg
    if (choice == QuickFuelChoice.CNG) return R.string.fuel_cng
    if (choice == QuickFuelChoice.FULL_HYBRID) return R.string.fuel_hybrid
    if (choice == QuickFuelChoice.MILD_HYBRID) return R.string.fuel_mild
    if (choice == QuickFuelChoice.ELECTRIC) return R.string.fuel_electric
    if (choice == QuickFuelChoice.HYDROGEN) return R.string.fuel_hydrogen
    return R.string.fuel_phev
}

@Composable
internal fun Field(
    label: String,
    value: String,
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        supportingText = hint?.let { message -> { Text(message) } },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        textStyle = FieldStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = MaterialTheme.shapes.small,
        colors = instrumentFieldColors(),
    )
}

@Composable
internal fun TankButton(text: String, onClick: () -> Unit) {
    HudButton(text, onClick)
}

@Composable
private fun rangeLabel(range: InsightRange): String {
    val id = when (range) {
        InsightRange.CURRENT_MONTH -> R.string.month_spend
        InsightRange.LAST_MONTH -> R.string.insight_last_month
        InsightRange.LAST_30_DAYS -> R.string.insight_30_days
        InsightRange.LAST_90_DAYS -> R.string.insight_90_days
        InsightRange.CURRENT_YEAR -> R.string.insight_this_year
        InsightRange.PREVIOUS_YEAR -> R.string.insight_last_year
        InsightRange.ALL_TIME -> R.string.insight_all
        InsightRange.CUSTOM -> R.string.insight_custom
    }
    return stringResource(id)
}

@Composable
private fun DirectionLine(direction: com.tankful.android.consumption.ComparisonDirection?, delta: String?) {
    if (direction == null || delta == null) return
    val word = when (direction) {
        com.tankful.android.consumption.ComparisonDirection.ABOVE -> stringResource(R.string.above_official)
        com.tankful.android.consumption.ComparisonDirection.BELOW -> stringResource(R.string.below_official)
        com.tankful.android.consumption.ComparisonDirection.MATCH -> stringResource(R.string.match_official)
    }
    val ink = when (direction) {
        com.tankful.android.consumption.ComparisonDirection.ABOVE -> MaterialTheme.colorScheme.error
        com.tankful.android.consumption.ComparisonDirection.BELOW -> MaterialTheme.colorScheme.tertiary
        com.tankful.android.consumption.ComparisonDirection.MATCH -> MaterialTheme.colorScheme.onSurface
    }
    val sign = if (direction == com.tankful.android.consumption.ComparisonDirection.BELOW) "−" else "+"
    Text("$word $sign $delta", style = MetricStyle, color = ink)
    Text(stringResource(R.string.conditions_caption), style = MaterialTheme.typography.labelMedium)
}

@Composable
internal fun OutcomeText(result: FormResult) {
    when (result) {
        FormResult.Idle -> Unit
        FormResult.Saved -> Text(stringResource(R.string.saved))
        is FormResult.Confirm -> ConfirmLines(result.reasons)
        is FormResult.OdometerConfirm -> ConfirmLines(result.reasons)
        is FormResult.Rejected -> Text(result.reasons.joinToString { it.name.lowercase().replace('_', ' ') })
    }
}

@Composable
private fun ConfirmLines(reasons: List<ConfirmReason>) {
    for (reason in reasons) Text(confirmLabel(reason))
}

@Composable
private fun confirmLabel(reason: ConfirmReason): String {
    val res = when (reason) {
        ConfirmReason.AMOUNTS_DISAGREE -> R.string.confirm_amounts
        ConfirmReason.LABOUR_PARTS_DISAGREE -> R.string.confirm_parts
        ConfirmReason.ODOMETER_ORDER -> R.string.confirm_order
        ConfirmReason.ODOMETER_JUMP -> R.string.confirm_jump
        ConfirmReason.ODOMETER_HUGE -> R.string.confirm_huge
        ConfirmReason.CLOCK_SKEW -> R.string.confirm_clock
        ConfirmReason.LARGE_MONEY -> R.string.confirm_money
        ConfirmReason.LARGE_QUANTITY -> R.string.confirm_quantity
    }
    return stringResource(res)
}

private fun confirmReasons(result: FormResult): List<ConfirmReason> {
    if (result is FormResult.Confirm) return result.reasons
    if (result is FormResult.OdometerConfirm) return result.reasons
    return emptyList()
}

private fun searchVisibleArea(
    view: MapTarget?,
    onMissing: (Boolean) -> Unit,
    onSearch: (Double, Double, Int) -> Unit,
) {
    val here = view
    if (here == null) {
        onMissing(true)
        return
    }
    onMissing(false)
    onSearch(here.latitude, here.longitude, here.radiusMetres)
}

private fun askForPosition(
    context: android.content.Context,
    permission: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    onFix: (Double, Double) -> Unit,
    onDenied: (Boolean) -> Unit,
) {
    val fine = Manifest.permission.ACCESS_FINE_LOCATION
    val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    val allowed = locationAllowed(context, fine) || locationAllowed(context, coarse)
    if (!allowed) {
        onDenied(false)
        permission.launch(arrayOf(fine, coarse))
        return
    }
    onDenied(false)
    placeOnMap(context, onFix)
}

private fun locationAllowed(context: android.content.Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

@Suppress("MissingPermission")
private fun placeOnMap(context: android.content.Context, onFix: (Double, Double) -> Unit) {
    val manager = context.getSystemService(LocationManager::class.java) ?: return
    val fine = locationAllowed(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val providers = ArrayList<String>()
    if (fine) providers.add(LocationManager.GPS_PROVIDER)
    providers.add(LocationManager.NETWORK_PROVIDER)
    providers.add(LocationManager.PASSIVE_PROVIDER)
    for (provider in providers) {
        if (!providerEnabled(manager, provider)) continue
        val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
        onFix(last.latitude, last.longitude)
        return
    }
    val provider = providers.firstOrNull { providerEnabled(manager, it) } ?: return
    requestFix(context, manager, provider, onFix)
}

private fun providerEnabled(manager: LocationManager, provider: String): Boolean {
    return runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
}

@Suppress("MissingPermission", "DEPRECATION")
private fun requestFix(
    context: android.content.Context,
    manager: LocationManager,
    provider: String,
    onFix: (Double, Double) -> Unit,
) {
    if (Build.VERSION.SDK_INT >= 30) {
        manager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
            if (location != null) onFix(location.latitude, location.longitude)
        }
        return
    }
    val listener = object : LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
            onFix(location.latitude, location.longitude)
        }
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }
    runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
}
