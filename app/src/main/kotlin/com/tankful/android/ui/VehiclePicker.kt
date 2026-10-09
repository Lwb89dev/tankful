package com.tankful.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tankful.android.R
import com.tankful.android.data.catalogue.CatalogueEntry
import com.tankful.android.data.catalogue.CatalogueFacet
import com.tankful.android.data.catalogue.CatalogueName
import com.tankful.android.data.catalogue.CatalogueNarrowing
import com.tankful.android.data.catalogue.CataloguePick
import com.tankful.android.data.catalogue.CatalogueScope
import com.tankful.android.data.catalogue.catalogueChoices
import com.tankful.android.data.catalogue.foldCatalogue
import com.tankful.android.data.catalogue.resolvePick
import com.tankful.android.model.QuickFuelChoice

private val vehicleFuels = listOf(
    QuickFuelChoice.PETROL,
    QuickFuelChoice.DIESEL,
    QuickFuelChoice.LPG,
    QuickFuelChoice.CNG,
    QuickFuelChoice.FULL_HYBRID,
    QuickFuelChoice.MILD_HYBRID,
    QuickFuelChoice.PHEV,
    QuickFuelChoice.ELECTRIC,
    QuickFuelChoice.HYDROGEN,
)

@Composable
internal fun VehiclePicker(model: EditorViewModel, result: FormResult, vehicleId: String? = null) {
    val makes by model.catalogueMakes.collectAsStateWithLifecycle()
    val models by model.catalogueModels.collectAsStateWithLifecycle()
    val hits by model.catalogueHits.collectAsStateWithLifecycle()
    val facets by model.catalogueFacets.collectAsStateWithLifecycle()
    val loading by model.catalogueLoading.collectAsStateWithLifecycle()
    CatalogueStep(
        makes,
        models,
        facets,
        loading,
        hits,
        { model.loadMakes() },
        { model.chooseMake(it) },
        { model.loadScope(it) },
        { scope, narrowing -> model.narrowScope(scope, narrowing) },
        { id, plate, nick, also -> model.saveCatalogue(id, plate, nick, also) },
        { make, car, year, choice, also, plate, nick -> model.saveVehicle(make, car, year, choice, also, plate, nick) },
    )
    OutcomeText(result)
    LaunchedEffect(vehicleId) { model.holdVehicle(vehicleId) }
}

@Composable
private fun CatalogueStep(
    makes: List<CatalogueName>,
    models: List<CatalogueName>,
    facets: List<CatalogueFacet>,
    loading: Boolean,
    hits: List<CatalogueEntry>,
    onLoadMakes: () -> Unit,
    onMake: (String) -> Unit,
    onScope: (CatalogueScope) -> Unit,
    onNarrow: (CatalogueScope, CatalogueNarrowing) -> Unit,
    onSaveListed: (String, String, String, QuickFuelChoice?) -> Unit,
    onSaveManual: (String, String, String, QuickFuelChoice, QuickFuelChoice?, String, String) -> Unit,
) {
    var stage by rememberSaveable { mutableStateOf("make") }
    var filter by rememberSaveable { mutableStateOf("") }
    var makeLabel by rememberSaveable { mutableStateOf("") }
    var makeSpellings by rememberSaveable { mutableStateOf("") }
    var modelLabel by rememberSaveable { mutableStateOf("") }
    var modelSpellings by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { onLoadMakes() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.catalogue_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.catalogue_coverage), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (stage == "make") {
            NameList(stringResource(R.string.catalogue_filter_make), filter, { filter = it }, makes, loading) { name ->
                makeLabel = name.label
                makeSpellings = name.spellings.joinToString("\n")
                filter = ""
                stage = "model"
                onMake(name.fold)
            }
        }
        if (stage == "model") {
            PickedLabel(makeLabel) {
                stage = "make"
                filter = ""
            }
            NameList(stringResource(R.string.catalogue_filter_model), filter, { filter = it }, models, loading) { name ->
                modelLabel = name.label
                modelSpellings = name.spellings.joinToString("\n")
                filter = ""
                stage = "detail"
            }
        }
        if (stage == "detail") {
            DetailStep(
                makeLabel,
                modelLabel,
                makeSpellings,
                modelSpellings,
                facets,
                loading,
                hits,
                onScope,
                onNarrow,
                onSaveListed,
                onSaveManual,
            ) {
                stage = "model"
            }
        }
    }
}

@Composable
private fun PickedLabel(label: String, onBack: () -> Unit) {
    Text(label, style = HudLabel, color = MaterialTheme.colorScheme.secondary)
    TextButton(onClick = onBack) { Text(stringResource(R.string.catalogue_back)) }
}

@Composable
private fun ColumnScope.NameList(
    hint: String,
    filter: String,
    onFilter: (String) -> Unit,
    names: List<CatalogueName>,
    loading: Boolean,
    onPick: (CatalogueName) -> Unit,
) {
    val shown = filterNames(names, filter)
    Column(Modifier.fillMaxWidth().weight(1f)) {
        Field(hint, filter, onValue = onFilter)
        if (names.isEmpty() && loading) Text(stringResource(R.string.catalogue_searching))
        if (names.isEmpty() && !loading) Text(stringResource(R.string.catalogue_empty))
        NameRows(shown, onPick)
    }
}

@Composable
private fun ColumnScope.NameRows(shown: List<CatalogueName>, onPick: (CatalogueName) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shown, key = { it.fold }) { name ->
            DeckCard(selected = false, onClick = { onPick(name) }) {
                Text(name.label, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun ColumnScope.DetailStep(
    makeLabel: String,
    modelLabel: String,
    makeSpellings: String,
    modelSpellings: String,
    facets: List<CatalogueFacet>,
    loading: Boolean,
    hits: List<CatalogueEntry>,
    onScope: (CatalogueScope) -> Unit,
    onNarrow: (CatalogueScope, CatalogueNarrowing) -> Unit,
    onSaveListed: (String, String, String, QuickFuelChoice?) -> Unit,
    onSaveManual: (String, String, String, QuickFuelChoice, QuickFuelChoice?, String, String) -> Unit,
    onBack: () -> Unit,
) {
    var choiceName by rememberSaveable { mutableStateOf("") }
    var alsoName by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    var plate by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var pick by remember { mutableStateOf(CataloguePick()) }
    val choice = choiceName.toChoice()
    val scope = choice?.let { catalogueScope(it, splitSpellings(makeSpellings), splitSpellings(modelSpellings)) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PickedLabel("$makeLabel  $modelLabel", onBack)
        Text(stringResource(R.string.fuel), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
        FuelPicker(choiceName, vehicleFuels) { picked ->
            choiceName = picked
            if (picked != QuickFuelChoice.PETROL.name) alsoName = ""
            selectedId = ""
            pick = CataloguePick()
            val next = catalogueScope(QuickFuelChoice.valueOf(picked), splitSpellings(makeSpellings), splitSpellings(modelSpellings))
            onScope(next)
        }
        if (choice == QuickFuelChoice.PETROL) AlsoFuel(alsoName) { alsoName = it }
        DetailBody(scope, year, { year = it }, facets, pick, { pick = it; selectedId = "" }, loading, hits, selectedId) { selectedId = it }
        Field(stringResource(R.string.nickname), nickname) { if (it.length <= 40) nickname = it }
        Field(stringResource(R.string.plate), plate) { plate = it }
        TankButton(stringResource(R.string.save)) {
            val listed = scope != null && !scope.manual && facets.isNotEmpty()
            val also = alsoName.toChoice()
            saveVehicle(listed, selectedId, makeLabel, modelLabel, year, choice, also, plate, nickname, onSaveListed, onSaveManual)
        }
    }
    LaunchedEffect(facets, pick, scope?.manual) {
        if (scope == null || scope.manual || facets.isEmpty()) return@LaunchedEffect
        val resolved = resolvePick(facets, pick)
        if (resolved != pick) {
            pick = resolved
            return@LaunchedEffect
        }
        if (resolved.ready) onNarrow(scope, resolved.narrowing())
    }
}

@Composable
private fun DetailBody(
    scope: CatalogueScope?,
    year: String,
    onYear: (String) -> Unit,
    facets: List<CatalogueFacet>,
    pick: CataloguePick,
    onPick: (CataloguePick) -> Unit,
    loading: Boolean,
    hits: List<CatalogueEntry>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    if (scope == null) return
    if (loading && facets.isEmpty()) {
        Text(stringResource(R.string.catalogue_searching))
        return
    }
    val unlisted = scope.manual || facets.isEmpty()
    if (unlisted) {
        Text(stringResource(R.string.catalogue_no_official), style = MaterialTheme.typography.bodyMedium)
        Field(stringResource(R.string.model_year), year, onValue = onYear)
        return
    }
    if (loading) Text(stringResource(R.string.catalogue_searching))
    PickerFilters(facets, pick, onPick)
    if (!pick.ready) return
    hits.forEach { entry ->
        CatalogueHitRow(entry, entry.id == selectedId) { onSelect(entry.id) }
    }
    if (!loading && hits.isEmpty() && facets.isNotEmpty()) Text(stringResource(R.string.catalogue_empty))
}

@Composable
private fun PickerFilters(facets: List<CatalogueFacet>, pick: CataloguePick, onPick: (CataloguePick) -> Unit) {
    val choices = catalogueChoices(facets, pick)
    if (choices.years.isEmpty()) return
    Text(stringResource(R.string.catalogue_year), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
    ChoiceRow { YearChoices(choices.years, pick, onPick) }
    if (!pick.yearSet) return
    if (choices.fuels.size > 1) {
        Text(stringResource(R.string.fuel), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
        ChoiceRow { FuelChoices(choices.fuels, pick, onPick) }
    }
    if (!pick.fuelSet) return
    if (choices.displacementsCc.isNotEmpty()) {
        Text(stringResource(R.string.catalogue_displacement), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
        ChoiceRow { DisplacementChoices(choices.displacementsCc, pick, onPick) }
    }
    if (!pick.displacementSet || choices.powersKw.isEmpty()) return
    Text(stringResource(R.string.catalogue_power), style = HudLabel, color = MaterialTheme.colorScheme.secondary)
    ChoiceRow { PowerChoices(choices.powersKw, pick, onPick) }
}

@Composable
private fun YearChoices(years: List<Int>, pick: CataloguePick, onPick: (CataloguePick) -> Unit) {
    if (years.size > 1) ChoiceChip(stringResource(R.string.catalogue_any), pick.yearSet && pick.year == null) { onPick(pick.chooseYear(null)) }
    years.forEach { year ->
        ChoiceChip(year.toString(), pick.yearSet && pick.year == year) { onPick(pick.chooseYear(year)) }
    }
}

@Composable
private fun FuelChoices(fuels: List<String>, pick: CataloguePick, onPick: (CataloguePick) -> Unit) {
    if (fuels.size > 1) ChoiceChip(stringResource(R.string.catalogue_any), pick.fuelSet && pick.fuel == null) { onPick(pick.chooseFuel(null)) }
    fuels.forEach { fuel ->
        ChoiceChip(fuelKindLabel(fuel), pick.fuelSet && pick.fuel == fuel) { onPick(pick.chooseFuel(fuel)) }
    }
}

@Composable
private fun DisplacementChoices(capacities: List<Int>, pick: CataloguePick, onPick: (CataloguePick) -> Unit) {
    if (capacities.size > 1) {
        ChoiceChip(stringResource(R.string.catalogue_any), pick.displacementSet && pick.displacementCc == null) {
            onPick(pick.chooseDisplacement(null))
        }
    }
    capacities.forEach { capacity ->
        val selected = pick.displacementSet && pick.displacementCc == capacity
        ChoiceChip("$capacity cm3", selected) { onPick(pick.chooseDisplacement(capacity)) }
    }
}

@Composable
private fun PowerChoices(powers: List<Int>, pick: CataloguePick, onPick: (CataloguePick) -> Unit) {
    if (powers.size > 1) ChoiceChip(stringResource(R.string.catalogue_any), pick.powerSet && pick.powerKw == null) { onPick(pick.choosePower(null)) }
    powers.forEach { power ->
        val selected = pick.powerSet && pick.powerKw == power
        ChoiceChip(cataloguePower(power), selected) { onPick(pick.choosePower(power)) }
    }
}

@Composable
private fun AlsoFuel(alsoName: String, onAlso: (String) -> Unit) {
    val lpg = QuickFuelChoice.LPG.name
    val cng = QuickFuelChoice.CNG.name
    ChoiceRow {
        ChoiceChip(stringResource(R.string.fuel_also_lpg), alsoName == lpg) { onAlso(toggleFuel(alsoName, lpg)) }
        ChoiceChip(stringResource(R.string.fuel_also_cng), alsoName == cng) { onAlso(toggleFuel(alsoName, cng)) }
    }
}

private fun toggleFuel(current: String, next: String): String {
    if (current == next) return ""
    return next
}

private fun saveVehicle(
    listed: Boolean,
    selectedId: String,
    make: String,
    model: String,
    year: String,
    choice: QuickFuelChoice?,
    also: QuickFuelChoice?,
    plate: String,
    nickname: String,
    onSaveListed: (String, String, String, QuickFuelChoice?) -> Unit,
    onSaveManual: (String, String, String, QuickFuelChoice, QuickFuelChoice?, String, String) -> Unit,
) {
    if (choice == null) return
    if (listed && selectedId.isNotBlank()) {
        onSaveListed(selectedId, plate, nickname, also)
        return
    }
    if (listed) return
    onSaveManual(make, model, year, choice, also, plate, nickname)
}

private fun filterNames(names: List<CatalogueName>, raw: String): List<CatalogueName> {
    val fold = foldCatalogue(raw)
    if (fold.isEmpty()) return names
    return names.filter { fold in it.fold }
}

private fun splitSpellings(raw: String): List<String> {
    return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}

private fun String.toChoice(): QuickFuelChoice? {
    if (isBlank()) return null
    return runCatching { QuickFuelChoice.valueOf(this) }.getOrNull()
}
