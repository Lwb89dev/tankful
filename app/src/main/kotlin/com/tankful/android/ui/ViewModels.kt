package com.tankful.android.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.tankful.android.data.catalogue.CatalogueEntry
import com.tankful.android.data.catalogue.CatalogueFacet
import com.tankful.android.data.catalogue.CatalogueName
import com.tankful.android.data.catalogue.CatalogueNarrowing
import com.tankful.android.data.catalogue.CatalogueScope
import com.tankful.android.data.catalogue.CatalogueProvenance
import com.tankful.android.data.catalogue.OfficialCatalogue
import com.tankful.android.data.db.ReminderEntity
import com.tankful.android.data.db.ServiceProviderEntity
import com.tankful.android.data.db.SpecificationEntity
import com.tankful.android.data.remote.ExternalData
import com.tankful.android.data.store.BackupStore
import com.tankful.android.data.store.CatalogStore
import com.tankful.android.data.store.DeviceSettings
import com.tankful.android.data.store.GarageLog
import com.tankful.android.data.store.LocalKeyStore
import com.tankful.android.sync.AMBER_PACKAGE
import com.tankful.android.sync.RELAY_LIST_CAP
import com.tankful.android.sync.RelayClient
import com.tankful.android.sync.rawPictureField
import com.tankful.android.sync.readNostrProfile
import com.tankful.android.sync.relayUrlAllowed
import com.tankful.android.transfer.importDocument
import com.tankful.android.data.store.ImportChoice
import com.tankful.android.ledger.ExpenseRow
import com.tankful.android.ledger.MaintenanceItemRow
import com.tankful.android.ledger.MaintenanceRow
import com.tankful.android.ledger.ReadingRow
import com.tankful.android.ledger.RefuelDraft
import com.tankful.android.ledger.VehicleRow
import com.tankful.android.model.ConfirmReason
import com.tankful.android.model.DistanceEntryUnit
import com.tankful.android.model.ExpenseOrigin
import com.tankful.android.model.FuelKind
import com.tankful.android.model.OdometerReason
import com.tankful.android.model.OdometerSource
import com.tankful.android.model.RejectReason
import com.tankful.android.model.SaveOutcome
import com.tankful.android.model.mapQuickCreate
import com.tankful.android.model.pairedSecondary
import com.tankful.android.model.specSourceName
import com.tankful.android.model.QuickFuelChoice
import java.util.concurrent.atomic.AtomicInteger
import com.tankful.android.money.MAJOR_CURRENCIES
import com.tankful.android.money.Money
import com.tankful.android.money.TankfulRounding
import com.tankful.android.money.currencyMinorScale
import com.tankful.android.odometer.OdometerDecision
import com.tankful.android.odometer.OdometerDraft
import com.tankful.android.odometer.StoredReading
import com.tankful.android.odometer.evaluateOdometer
import com.tankful.android.providers.PricedStation
import com.tankful.android.quantity.MEASURE_IMPERIAL
import com.tankful.android.quantity.MEASURE_METRIC
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.currencyForLocale
import com.tankful.android.quantity.measureForLocale
import com.tankful.android.quantity.odometerEntryText
import com.tankful.android.quantity.odometerText
import com.tankful.android.quantity.odometerMetres
import com.tankful.android.quantity.parseDecimal
import com.tankful.android.quantity.priceEntryText
import com.tankful.android.quantity.quantityEntryText
import com.tankful.android.quantity.smallestQuantity
import com.tankful.android.quantity.storedFill
import com.tankful.android.reminders.SyncWorker
import com.tankful.android.time.InsightRange
import com.tankful.android.transfer.ImportPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val STOP = 5_000L

sealed interface FormResult {
    data object Idle : FormResult
    data object Saved : FormResult
    data class Confirm(val reasons: List<ConfirmReason>) : FormResult
    data class Rejected(val reasons: List<RejectReason>) : FormResult
    data class OdometerConfirm(val reasons: List<ConfirmReason>) : FormResult
}

@HiltViewModel
class ThemeViewModel @Inject constructor(device: DeviceSettings) : ViewModel() {
    val theme = device.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), "SYSTEM")
}

@HiltViewModel
class GateViewModel @Inject constructor(garage: GarageLog) : ViewModel() {
    val liveVehicles: StateFlow<Int?> = garage.observe()
        .map<com.tankful.android.ledger.Logbook, Int?> { liveVehicleCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), null)
}

@HiltViewModel
class DashboardViewModel @Inject constructor(garage: GarageLog, catalog: CatalogStore) : ViewModel() {
    val ui = combine(garage.observe(), catalog.specifications()) { book, specs ->
        projectDashboard(book, specs, Instant.now(), java.time.ZoneId.systemDefault())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), blankDashboard())
}

@HiltViewModel
class TimelineViewModel @Inject constructor(private val garage: GarageLog) : ViewModel() {
    val rows = garage.observe().map { book -> timelineRows(book) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), emptyList())
    private val hits = MutableStateFlow<List<String>>(emptyList())
    val searchHits = hits.asStateFlow()

    fun search(raw: String) {
        viewModelScope.launch {
            val found = garage.search(raw)
            hits.value = found.map { "${it.first} ${it.second}" }
        }
    }
}

data class TimelineLine(
    val refuelId: String?,
    val whenMillis: Long,
    val fuel: String?,
    val detail: String,
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    garage: GarageLog,
    catalog: CatalogStore,
    device: DeviceSettings,
) : ViewModel() {
    private val range = MutableStateFlow(InsightRange.CURRENT_MONTH)
    val selected = range.asStateFlow()
    val ui = combine(garage.observe(), catalog.specifications(), range, device.measure) { book, specs, chosen, measure ->
        val imperial = measure == MEASURE_IMPERIAL
        projectInsights(book, specs, chosen, Instant.now(), java.time.ZoneId.systemDefault(), imperial)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), blankInsights())

    fun choose(next: InsightRange) {
        range.value = next
    }
}

@HiltViewModel
class GarageListViewModel @Inject constructor(garage: GarageLog, device: DeviceSettings) : ViewModel() {
    val lines = combine(garage.observe(), device.measure) { book, measure ->
        projectGarage(book, measure == MEASURE_IMPERIAL)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), emptyList())
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val garage: GarageLog,
    private val catalog: CatalogStore,
    private val device: DeviceSettings,
    private val catalogue: OfficialCatalogue,
) : ViewModel() {
    private val outcome = MutableStateFlow<FormResult>(FormResult.Idle)
    val result = outcome.asStateFlow()
    private val hits = MutableStateFlow<List<CatalogueEntry>>(emptyList())
    val catalogueHits = hits.asStateFlow()
    private val facetRows = MutableStateFlow<List<CatalogueFacet>>(emptyList())
    val catalogueFacets = facetRows.asStateFlow()
    private val makeRows = MutableStateFlow<List<CatalogueName>>(emptyList())
    val catalogueMakes = makeRows.asStateFlow()
    private val modelRows = MutableStateFlow<List<CatalogueName>>(emptyList())
    val catalogueModels = modelRows.asStateFlow()
    private val catalogueBusy = MutableStateFlow(false)
    val catalogueLoading = catalogueBusy.asStateFlow()
    private val searchGeneration = AtomicInteger()
    val categories = catalog.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), emptyList())
    val serviceTypes = catalog.serviceTypes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), emptyList())
    private val fuelState = MutableStateFlow<List<FuelKind>?>(null)
    val refuelFuels = fuelState.asStateFlow()
    private val currencyState = MutableStateFlow(currencyForLocale(Locale.getDefault()))
    val currencyCode = currencyState.asStateFlow()
    private val lastOdometerState = MutableStateFlow("—")
    val lastOdometer = lastOdometerState.asStateFlow()
    val imperial = device.measure.map { it == MEASURE_IMPERIAL }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP),
        measureForLocale(Locale.getDefault()) == MEASURE_IMPERIAL,
    )
    private var editingId: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val book = garage.read()
            fuelState.value = liquidFuelsOf(book)
            currencyState.value = catalog.settings().defaultCurrency
            device.measure.collect { measure ->
                val current = garage.read()
                lastOdometerState.value = latestOdometerText(current, measure == MEASURE_IMPERIAL)
            }
        }
    }

    suspend fun readRefuel(id: String): RefuelEdit? = withContext(Dispatchers.IO) {
        val book = garage.read()
        val row = book.refuels[id] ?: return@withContext null
        if (row.deletedAt != null) return@withContext null
        val imperial = device.measure.first() == MEASURE_IMPERIAL
        val reading = row.odometerReadingId?.let { book.readings[it] }?.takeIf { it.deletedAt == null }
        val instant = Instant.ofEpochMilli(row.timestampEpochMs)
        RefuelEdit(
            quantity = quantityEntryText(row.quantity.amount, row.fuelKind, imperial),
            price = priceEntryText(row.total.minorUnits, row.quantity.amount, row.fuelKind, row.total.currency, imperial),
            odometer = reading?.let { odometerEntryText(it.metres, imperial) }.orEmpty(),
            station = row.stationName.orEmpty(),
            note = row.note.orEmpty(),
            full = row.fullTank,
            dateIso = instant.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            fuel = row.fuelKind,
            readingId = reading?.id ?: "$id-odo",
            clockEpochMs = row.timestampEpochMs,
            currencyCode = row.total.currency,
        )
    }

    fun holdVehicle(id: String?) {
        editingId = id
    }

    fun reset() {
        outcome.value = FormResult.Idle
    }

    fun saveVehicle(
        make: String,
        model: String,
        yearRaw: String,
        choice: QuickFuelChoice,
        also: QuickFuelChoice?,
        plate: String,
        nickname: String,
    ) {
        val existingId = editingId
        viewModelScope.launch(Dispatchers.IO) {
            val year = yearRaw.toIntOrNull()
            if (make.isBlank() || model.isBlank() || year == null || year !in 1950..2100) {
                outcome.value = FormResult.Rejected(listOf(RejectReason.MISSING_IDENTITY))
                return@launch
            }
            if (plate.length > 16) {
                outcome.value = FormResult.Rejected(listOf(RejectReason.PLATE_TOO_LONG))
                return@launch
            }
            val mapped = mapQuickCreate(choice, null)
            garage.edit { book ->
                val prior = existingId?.let { book.vehicles[it] }
                val fresh = VehicleRow(
                    id = prior?.id ?: UUID.randomUUID().toString(),
                    createdAt = prior?.createdAt ?: Instant.now(),
                    manufacturer = make.trim(),
                    model = model.trim(),
                    modelYear = year,
                    powertrain = mapped.first.name,
                    primaryFuel = mapped.second.name,
                    secondaryFuel = pairedSecondary(choice, also)?.name,
                    plate = plate.trim().ifBlank { null },
                    nickname = cleanNickname(nickname),
                )
                val row = kept(prior, fresh)
                book.addVehicle(row)
                book.selectedVehicleId = row.id
            }
            outcome.value = FormResult.Saved
        }
    }

    fun loadMakes() {
        if (makeRows.value.isNotEmpty()) return
        catalogueBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            makeRows.value = runCatching { catalogue.directory().makes }.getOrDefault(emptyList())
            catalogueBusy.value = false
        }
    }

    fun chooseMake(fold: String) {
        viewModelScope.launch(Dispatchers.IO) {
            modelRows.value = catalogue.models(fold)
            hits.value = emptyList()
            facetRows.value = emptyList()
        }
    }

    fun loadScope(scope: CatalogueScope) {
        val generation = searchGeneration.incrementAndGet()
        hits.value = emptyList()
        facetRows.value = emptyList()
        if (scope.manual) {
            catalogueBusy.value = false
            return
        }
        catalogueBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val found = catalogue.facets(scope)
            if (generation != searchGeneration.get()) return@launch
            facetRows.value = found
            catalogueBusy.value = false
        }
    }

    fun narrowScope(scope: CatalogueScope, narrowing: CatalogueNarrowing) {
        val generation = searchGeneration.incrementAndGet()
        hits.value = emptyList()
        catalogueBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val found = catalogue.search(scope, narrowing)
            if (generation != searchGeneration.get()) return@launch
            hits.value = found
            catalogueBusy.value = false
        }
    }

    fun saveCatalogue(entryId: String, plate: String, nickname: String, also: QuickFuelChoice?) {
        val existingId = editingId
        viewModelScope.launch(Dispatchers.IO) {
            val prepared = prepareCatalogue(entryId, plate, nickname, existingId)
            if (prepared is CataloguePrep.Stop) {
                outcome.value = prepared.result
                return@launch
            }
            val ready = prepared as CataloguePrep.Ready
            val secondary = secondaryFuelName(ready.vehicle.primaryFuel, also)
            garage.edit(ready.spec) { book ->
                val prior = existingId?.let { book.vehicles[it] }
                val row = kept(prior, ready.vehicle.copy(secondaryFuel = secondary))
                book.addVehicle(row)
                book.selectedVehicleId = row.id
            }
            outcome.value = FormResult.Saved
        }
    }

    private fun secondaryFuelName(primary: String, also: QuickFuelChoice?): String? {
        if (primary != FuelKind.PETROL.name) return null
        return pairedSecondary(QuickFuelChoice.PETROL, also)?.name
    }

    private fun kept(prior: VehicleRow?, fresh: VehicleRow): VehicleRow {
        if (prior == null) return fresh
        return fresh.copy(
            id = prior.id,
            createdAt = prior.createdAt,
            revision = prior.revision,
            vin = prior.vin,
            purchasePriceMinor = prior.purchasePriceMinor,
            purchaseCurrency = prior.purchaseCurrency,
            distanceUnit = prior.distanceUnit,
            economyPreference = prior.economyPreference,
            notes = prior.notes,
            imageAttachmentId = prior.imageAttachmentId,
        )
    }

    private fun cleanNickname(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(40)
    }

    private suspend fun prepareCatalogue(
        entryId: String,
        plate: String,
        nickname: String,
        existingId: String?,
    ): CataloguePrep {
        if (plate.length > 16) return CataloguePrep.Stop(FormResult.Rejected(listOf(RejectReason.PLATE_TOO_LONG)))
        val entry = catalogue.byId(entryId)
        if (entry == null) return CataloguePrep.Stop(FormResult.Rejected(listOf(RejectReason.MISSING_IDENTITY)))
        val fuel = runCatching { FuelKind.valueOf(entry.fuel) }.getOrNull()
        if (fuel == null) return CataloguePrep.Stop(FormResult.Rejected(listOf(RejectReason.MISSING_FUEL)))
        val source = specSourceName(entry.source)
        if (source == null) return CataloguePrep.Stop(FormResult.Rejected(listOf(RejectReason.MISSING_IDENTITY)))
        val provenance = catalogue.provenance(entry.source)
        if (provenance == null) return CataloguePrep.Stop(FormResult.Rejected(listOf(RejectReason.MISSING_IDENTITY)))
        val prior = existingId?.let { garage.read().vehicles[it] }
        if (existingId != null && prior == null) {
            return CataloguePrep.Stop(FormResult.Rejected(listOf(RejectReason.MISSING_IDENTITY)))
        }
        val id = prior?.id ?: UUID.randomUUID().toString()
        val vehicle = kept(prior, vehicleFromCatalogue(id, entry, fuel.name, plate, nickname))
        return CataloguePrep.Ready(vehicle, specFromCatalogue(id, entry, source, provenance))
    }

    private fun vehicleFromCatalogue(
        id: String,
        entry: CatalogueEntry,
        fuel: String,
        plate: String,
        nickname: String,
    ): VehicleRow {
        val registered = if (entry.source == "EEA") entry.year else null
        return VehicleRow(
            id = id,
            createdAt = Instant.now(),
            manufacturer = entry.make,
            model = entry.model,
            modelYear = entry.year,
            powertrain = entry.powertrain,
            primaryFuel = fuel,
            plate = plate.trim().ifBlank { null },
            nickname = cleanNickname(nickname),
            engine = entry.variant.ifBlank { null },
            powerUnscaled = entry.powerKw?.toLong(),
            powerScale = entry.powerKw?.let { 0 },
            powerUnit = entry.powerKw?.let { "KW" },
            registrationYear = registered,
        )
    }

    private fun specFromCatalogue(
        vehicleId: String,
        entry: CatalogueEntry,
        source: String,
        provenance: CatalogueProvenance,
    ): SpecificationEntity {
        val unit = entry.combinedUnit
        val scale = entry.combinedScale
        return SpecificationEntity(
            id = UUID.randomUUID().toString(),
            vehicleId = vehicleId,
            fuelKind = entry.fuel,
            combinedUnit = unit,
            combinedScale = scale,
            combinedUnscaled = entry.combinedUnscaled,
            cityUnit = entry.cityUnscaled?.let { unit },
            cityScale = entry.cityUnscaled?.let { scale },
            cityUnscaled = entry.cityUnscaled,
            highwayUnit = entry.highwayUnscaled?.let { unit },
            highwayScale = entry.highwayUnscaled?.let { scale },
            highwayUnscaled = entry.highwayUnscaled,
            urbanUnit = entry.urbanUnscaled?.let { unit },
            urbanScale = entry.urbanUnscaled?.let { scale },
            urbanUnscaled = entry.urbanUnscaled,
            extraUrbanUnit = entry.extraUnscaled?.let { unit },
            extraUrbanScale = entry.extraUnscaled?.let { scale },
            extraUrbanUnscaled = entry.extraUnscaled,
            co2Scale = entry.co2Unscaled?.let { 0 },
            co2Unscaled = entry.co2Unscaled,
            co2GramsPerMile = entry.co2Unscaled?.let { entry.co2GramsPerMile },
            testCycle = entry.testCycle,
            measureKind = entry.measureKind,
            source = source,
            sourceUrl = provenance.sourceUrl,
            retrievedAt = provenance.builtAt,
            retrievalProtocol = provenance.retrievalProtocol,
            isActive = true,
            deletedAt = null,
            revision = 1,
            installId = null,
            contentHash = null,
        )
    }

    fun saveSpec(vehicleId: String, fuel: String, combinedRaw: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = parseDecimal(combinedRaw, 1, Locale.getDefault())
            if (parsed == null) {
                outcome.value = FormResult.Rejected(listOf(RejectReason.BAD_NUMBER))
                return@launch
            }
            val unscaled = parsed.movePointRight(1).setScale(0, TankfulRounding.mode).longValueExact()
            catalog.saveSpecification(specRow(vehicleId, fuel, unscaled))
            outcome.value = FormResult.Saved
        }
    }

    fun saveRefuelVisit(first: RefuelInput, second: RefuelInput?) {
        viewModelScope.launch(Dispatchers.IO) {
            outcome.value = visitResult(first, second)
        }
    }

    private suspend fun visitResult(first: RefuelInput, second: RefuelInput?): FormResult {
        val vehicleId = device.peekSelected() ?: return FormResult.Rejected(listOf(RejectReason.MISSING_VEHICLE))
        val imperial = device.measure.first() == MEASURE_IMPERIAL
        if (unusableQuantity(first.quantityRaw, first.fuel, imperial)) return FormResult.Rejected(listOf(RejectReason.BAD_NUMBER))
        if (second != null && unusableQuantity(second.quantityRaw, second.fuel, imperial)) {
            return FormResult.Rejected(listOf(RejectReason.BAD_NUMBER))
        }
        val now = Instant.now()
        val money = currency()
        val lead = buildRefuel(first, money, vehicleId, imperial)
        val follow = followDraft(second, money, vehicleId, lead?.timestamp ?: now, imperial)
        val opened = lead ?: follow ?: return FormResult.Rejected(listOf(RejectReason.BAD_NUMBER))
        val openedInput = if (lead != null) first else second!!
        val trailing = if (lead != null) follow else null
        val saved = garage.edit { book -> savePair(book, opened, openedInput, trailing, second, now) }
        return toForm(saved)
    }

    fun saveOdometer(id: String, raw: String, acknowledgements: Set<ConfirmReason>, reason: OdometerReason?) {
        viewModelScope.launch(Dispatchers.IO) {
            val metres = odometerMetres(raw, device.measure.first() == MEASURE_IMPERIAL, Locale.getDefault())
            if (metres == null) {
                outcome.value = FormResult.Rejected(listOf(RejectReason.BAD_NUMBER))
                return@launch
            }
            val vehicleId = device.peekSelected()
            if (vehicleId == null) {
                outcome.value = FormResult.Rejected(listOf(RejectReason.MISSING_VEHICLE))
                return@launch
            }
            val decision = garage.edit { book ->
                writeReading(book, id, vehicleId, metres, acknowledgements, reason)
            }
            outcome.value = when (decision) {
                is OdometerDecision.Accept -> FormResult.Saved
                is OdometerDecision.NeedsConfirmation -> FormResult.OdometerConfirm(decision.reasons)
                is OdometerDecision.Rejected -> FormResult.Rejected(decision.reasons)
            }
        }
    }

    fun saveExpense(id: String, categoryId: String, totalRaw: String, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val vehicleId = device.peekSelected() ?: return@launch rejected(RejectReason.MISSING_VEHICLE)
            val currency = currency()
            val minor = parseMinor(totalRaw, currency) ?: return@launch rejected(RejectReason.BAD_NUMBER)
            val saved = garage.edit { book ->
                book.saveStandaloneExpense(
                    ExpenseRow(id, vehicleId, categoryId, System.currentTimeMillis(), Money.of(currency, minor), note.ifBlank { null }, ExpenseOrigin.STANDALONE, null),
                )
            }
            outcome.value = toForm(saved)
        }
    }

    fun saveMaintenance(input: MaintenanceInput) {
        viewModelScope.launch(Dispatchers.IO) {
            val vehicleId = device.peekSelected() ?: return@launch rejected(RejectReason.MISSING_VEHICLE)
            val currency = currency()
            val total = parseMinor(input.totalRaw, currency) ?: return@launch rejected(RejectReason.BAD_NUMBER)
            val labour = input.labourRaw.trim().takeIf { it.isNotEmpty() }?.let { parseMinor(it, currency) }
            val parts = input.partsRaw.trim().takeIf { it.isNotEmpty() }?.let { parseMinor(it, currency) }
            if (labour == null && input.labourRaw.isNotBlank()) return@launch rejected(RejectReason.BAD_NUMBER)
            if (parts == null && input.partsRaw.isNotBlank()) return@launch rejected(RejectReason.BAD_NUMBER)
            val saved = garage.edit { book ->
                val providerId = input.provider.trim().ifBlank { null }
                book.saveMaintenance(
                    MaintenanceRow(
                        id = input.id,
                        vehicleId = vehicleId,
                        total = Money.of(currency, total),
                        labour = labour?.let { Money.of(currency, it) },
                        parts = parts?.let { Money.of(currency, it) },
                        amountsDisagreed = false,
                        expenseId = "expense-${input.id}",
                        odometerReadingId = null,
                        note = input.note.ifBlank { null },
                        timestampEpochMs = System.currentTimeMillis(),
                        serviceTypeId = input.serviceTypeId,
                        providerId = providerId,
                        categoryId = input.categoryId,
                    ),
                    listOf(MaintenanceItemRow("${input.id}-item", input.id, input.serviceTypeId)),
                    input.acknowledgements,
                )
            }
            outcome.value = toForm(saved)
        }
    }

    private suspend fun currency(): String = catalog.settings().defaultCurrency

    private fun rejected(reason: RejectReason) {
        outcome.value = FormResult.Rejected(listOf(reason))
    }
}

data class RefuelInput(
    val id: String,
    val quantityRaw: String,
    val priceRaw: String,
    val totalRaw: String,
    val odometerRaw: String,
    val fullTank: Boolean,
    val station: String,
    val note: String,
    val fuel: FuelKind,
    val acknowledgements: Set<ConfirmReason>,
    val reason: OdometerReason?,
    val dateIso: String,
    val readingId: String,
    val clockEpochMs: Long,
    val currencyCode: String,
)

data class RefuelEdit(
    val quantity: String,
    val price: String,
    val odometer: String,
    val station: String,
    val note: String,
    val full: Boolean,
    val dateIso: String,
    val fuel: FuelKind,
    val readingId: String,
    val clockEpochMs: Long,
    val currencyCode: String,
)

data class MaintenanceInput(
    val id: String,
    val totalRaw: String,
    val labourRaw: String,
    val partsRaw: String,
    val note: String,
    val serviceTypeId: String,
    val categoryId: String,
    val provider: String,
    val acknowledgements: Set<ConfirmReason>,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val device: DeviceSettings,
    private val backup: BackupStore,
    private val catalog: CatalogStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val theme = device.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), "SYSTEM")
    val imperial = device.measure.map { it == MEASURE_IMPERIAL }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP),
        measureForLocale(Locale.getDefault()) == MEASURE_IMPERIAL,
    )
    private val currencyState = MutableStateFlow(currencyForLocale(Locale.getDefault()))
    val currency = currencyState.asStateFlow()
    private val exported = MutableStateFlow<File?>(null)
    val exportFile = exported.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { currencyState.value = catalog.settings().defaultCurrency }
    }

    fun setTheme(value: String) {
        viewModelScope.launch { device.setTheme(value) }
    }

    fun setImperial(enabled: Boolean) {
        val value = if (enabled) MEASURE_IMPERIAL else MEASURE_METRIC
        viewModelScope.launch { device.setMeasure(value) }
    }

    fun setCurrency(code: String) {
        if (code !in MAJOR_CURRENCIES) return
        viewModelScope.launch(Dispatchers.IO) {
            val row = catalog.settings()
            catalog.saveSettings(row.copy(defaultCurrency = code))
            currencyState.value = code
        }
    }

    fun export() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = backup.exportZip(com.tankful.android.BuildConfig.VERSION_NAME, context.filesDir)
            val dir = File(context.cacheDir, "export")
            dir.mkdirs()
            val file = File(dir, "tankful-backup-v1.zip")
            file.writeBytes(bytes)
            exported.value = file
        }
    }
}

fun shareExport(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(send, null))
}

internal val PUBLIC_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.primal.net",
    "wss://relay.nostr.band",
)

internal fun homeRelayOk(raw: String): Boolean {
    val url = raw.trim()
    return url.startsWith("ws://") && !url.startsWith("wss://") && relayUrlAllowed(url)
}

internal fun relayAddressOk(raw: String): Boolean = relayUrlAllowed(raw)

internal fun shortPubkey(pubkey: String): String {
    val hex = pubkey.trim().lowercase()
    if (hex.length < 12) return hex
    return hex.take(8) + "…" + hex.takeLast(4)
}

internal fun relayLinesOf(json: String): String {
    val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull()
    val array = parsed as? kotlinx.serialization.json.JsonArray ?: return ""
    return array.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.joinToString("\n")
}

data class NostrProfileCard(val name: String, val picture: Bitmap?)

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val device: DeviceSettings,
    private val catalog: CatalogStore,
    private val signer: com.tankful.android.sync.Nip55Signer,
    private val localKeys: LocalKeyStore,
    private val relays: RelayClient,
    private val http: OkHttpClient,
    private val cards: ProfileCardCache,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val enabled = device.syncEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), false)
    val nip09 = device.nip09Enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), false)
    private val note = MutableStateFlow("")
    val message = note.asStateFlow()
    private val amber = MutableStateFlow(false)
    val amberInstalled = amber.asStateFlow()
    private val relayLines = MutableStateFlow<String?>(null)
    val storedRelays = relayLines.asStateFlow()
    private val relayGate = Mutex()
    private val profileState = MutableStateFlow<NostrProfileCard?>(null)
    val profile = profileState.asStateFlow()
    private val profileGate = Mutex()
    private val health = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    val relayHealth = health.asStateFlow()
    private var probedKey = ""

    init {
        viewModelScope.launch(Dispatchers.IO) {
            amber.value = signer.amberInstalled()
            relayGate.withLock {
                if (relayLines.value != null) return@withLock
                relayLines.value = runCatching { relayLinesOf(catalog.settings().relayUrlsJson) }.getOrDefault("")
            }
        }
        viewModelScope.launch {
            device.signerPubkey.collect { pubkey ->
                withContext(Dispatchers.IO) { loadProfile(pubkey) }
            }
        }
        viewModelScope.launch {
            relayLines.collect { raw ->
                val urls = raw.orEmpty().lines().filter { it.isNotEmpty() }
                withContext(Dispatchers.IO) {
                    probeRelays(urls)
                    loadProfile(device.signerPubkey.first())
                }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            device.setSyncEnabled(value)
            if (!value) return@launch
            val data = workDataOf(SyncWorker.MANUAL to true)
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setInputData(data).build()
            WorkManager.getInstance(context).enqueueUniqueWork("tankful-sync-now", ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun setNip09(value: Boolean) {
        viewModelScope.launch { device.setNip09Enabled(value) }
    }

    fun saveRelays(raw: String) {
        val urls = raw.lines().map { it.trim() }.filter { relayAddressOk(it) }
        writeRelays(urls)
    }

    fun saveChosenRelays(chosen: Collection<String>, home: String): Boolean {
        val house = home.trim()
        if (house.isNotEmpty() && !homeRelayOk(house)) return false
        val urls = PUBLIC_RELAYS.filter { chosen.contains(it) }.toMutableList()
        if (house.isNotEmpty()) urls.add(house)
        writeRelays(urls)
        return true
    }

    fun addRelay(raw: String): Boolean {
        val url = raw.trim()
        if (!relayAddressOk(url)) return false
        viewModelScope.launch(Dispatchers.IO) {
            relayGate.withLock {
                val urls = currentRelayUrls().toMutableList()
                if (url !in urls) urls.add(url)
                persistRelays(urls)
            }
        }
        return true
    }

    fun removeRelay(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            relayGate.withLock {
                persistRelays(currentRelayUrls().filter { it != url })
            }
        }
    }

    private suspend fun currentRelayUrls(): List<String> {
        return relayLinesOf(catalog.settings().relayUrlsJson).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun writeRelays(urls: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            relayGate.withLock { persistRelays(urls) }
        }
    }

    private suspend fun persistRelays(urls: List<String>) {
        val kept = urls.map { it.trim() }.filter { relayUrlAllowed(it) }.distinct().take(RELAY_LIST_CAP)
        val current = catalog.settings()
        val json = kotlinx.serialization.json.Json.encodeToString(kept)
        catalog.saveSettings(current.copy(relayUrlsJson = json))
        relayLines.value = kept.joinToString("\n")
    }

    private suspend fun probeRelays(urls: List<String>) {
        val key = urls.joinToString("\n")
        if (key == probedKey) return
        probedKey = key
        if (urls.isEmpty()) {
            health.value = emptyMap()
            return
        }
        health.value = urls.associateWith { null }
        val allow = urls.filter { it.startsWith("ws://") }.toSet()
        val results = coroutineScope {
            urls.map { url -> async { url to relays.reachable(url, allow) } }.awaitAll().toMap()
        }
        if (probedKey != key) return
        health.value = results
    }

    private suspend fun loadProfile(pubkey: String?) {
        profileGate.withLock { profileState.value = profileCard(pubkey) }
    }

    private suspend fun profileCard(pubkey: String?): NostrProfileCard? {
        val key = pubkey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (key == null) {
            cards.clear()
            return null
        }
        val stored = cards.read(key)
        val plan = profileFetchPlan(stored)
        if (plan == ProfileFetch.SHOW) return cardOf(stored)
        if (plan == ProfileFetch.PICTURE) return cardOf(downloadPicture(key, stored))
        return cardOf(downloadMetadata(key))
    }

    private fun cardOf(stored: StoredProfile?): NostrProfileCard? {
        if (stored == null) return null
        val bitmap = stored.pictureBytes?.let { decodeProfilePicture(it) }
        return NostrProfileCard(stored.name, bitmap)
    }

    private fun downloadPicture(pubkey: String, stored: StoredProfile?): StoredProfile? {
        val url = stored?.pictureUrl ?: return stored
        val bitmap = profileBitmap(http, url) ?: return stored
        val bytes = encodeProfilePicture(bitmap)
        bitmap.recycle()
        if (bytes == null) return stored
        cards.writePicture(pubkey, bytes)
        return stored.copy(pictureBytes = bytes)
    }

    private suspend fun downloadMetadata(pubkey: String): StoredProfile {
        val urls = relayLinesOf(catalog.settings().relayUrlsJson).lines().filter { it.isNotEmpty() }
        val allow = urls.filter { it.startsWith("ws://") }.toSet()
        val document = relays.profileDocument(urls, allow, pubkey)
        val parsed = document?.let { readNostrProfile(it) }
        val name = parsed?.name ?: shortPubkey(pubkey)
        if (document == null) return StoredProfile(pubkey, name, null, null)
        val rawPicture = rawPictureField(document)
        val url = parsed?.picture
        if (rawPicture.isNotEmpty() && url == null) return StoredProfile(pubkey, name, null, null)
        val bitmap = url?.let { profileBitmap(http, it) }
        val bytes = bitmap?.let { encodeProfilePicture(it) }
        bitmap?.recycle()
        cards.write(pubkey, name, url, bytes)
        return StoredProfile(pubkey, name, url, bytes)
    }

    fun connect() {
        viewModelScope.launch { note.value = connectAmber() }
    }

    fun useNsec(raw: String) {
        viewModelScope.launch(Dispatchers.IO) { note.value = acceptNsec(raw) }
    }

    fun publicKeyIntent(): android.content.Intent? = signer.publicKeyIntent()

    fun acceptAmber(packageName: String, pubkey: String) {
        viewModelScope.launch {
            if (packageName != AMBER_PACKAGE || !amberPubkey(pubkey)) {
                note.value = "no-answer"
                return@launch
            }
            localKeys.clear()
            device.setSigner(AMBER_PACKAGE, pubkey)
            note.value = "connected"
        }
    }

    fun markNoAnswer() {
        note.value = "no-answer"
    }

    fun consumeLaunch() {
        if (note.value == "launch") note.value = ""
    }

    private suspend fun connectAmber(): String {
        val remembered = device.signerPackage.first()
        val known = if (remembered == com.tankful.android.sync.LOCAL_NSEC_SIGNER) null else remembered
        val call = signer.publicKey(known)
        if (call.mapping.outcome == com.tankful.android.sync.SignerFailure.OK && call.value != null) {
            localKeys.clear()
            device.setSigner(call.packageName, call.value)
            return "connected"
        }
        if (signer.amberInstalled()) return "launch"
        return "unavailable"
    }

    private suspend fun acceptNsec(raw: String): String {
        val secret = com.tankful.android.sync.decodeNsec(raw) ?: return "nsec-invalid"
        try {
            return saveNsec(secret)
        } finally {
            secret.fill(0)
        }
    }

    private suspend fun saveNsec(secret: ByteArray): String {
        val pubkey = com.tankful.android.sync.xOnlyPubkeyHex(secret) ?: return "nsec-invalid"
        val wrote = runCatching { localKeys.save(secret) }.isSuccess
        if (!wrote) return "nsec-invalid"
        device.setSigner(com.tankful.android.sync.LOCAL_NSEC_SIGNER, pubkey)
        return "connected"
    }

    fun disconnect() {
        viewModelScope.launch {
            localKeys.clear()
            device.setSigner(null, null)
        }
    }
}

@HiltViewModel
class ImportViewModel @Inject constructor(private val backup: BackupStore) : ViewModel() {
    private val previewState = MutableStateFlow<ImportPreview?>(null)
    val preview = previewState.asStateFlow()
    private var json: String = ""
    private val choices = linkedMapOf<String, ImportChoice>()

    fun loadDocument(bytes: ByteArray?) {
        val text = bytes?.let { importDocument(it) }
        if (text == null) {
            json = ""
            previewState.value = ImportPreview("too large", emptyList())
            return
        }
        json = text
        viewModelScope.launch {
            previewState.value = withContext(Dispatchers.IO) { backup.preview(text) }
        }
    }

    fun choose(id: String, choice: ImportChoice) {
        choices[id] = choice
    }

    fun apply() {
        viewModelScope.launch {
            previewState.value = withContext(Dispatchers.IO) { backup.apply(json, choices.toMap()) }
        }
    }
}

@HiltViewModel
class StationsViewModel @Inject constructor(private val external: ExternalData) : ViewModel() {
    private val found = MutableStateFlow<List<PricedStation>>(emptyList())
    val stations = found.asStateFlow()
    private val centreState = MutableStateFlow<Pair<Double, Double>?>(null)
    val centre = centreState.asStateFlow()
    private val busy = MutableStateFlow(false)
    val searching = busy.asStateFlow()
    private val problem = MutableStateFlow(false)
    val searchFailed = problem.asStateFlow()
    private val searchGate = Mutex()
    private var lastSearchAt = 0L

    fun aim(latitude: Double, longitude: Double) {
        centreState.value = latitude to longitude
    }

    fun search(latitude: Double, longitude: Double, radiusMetres: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!searchGate.tryLock()) return@launch
            try {
                runSearch(latitude, longitude, radiusMetres)
            } finally {
                searchGate.unlock()
            }
        }
    }

    private suspend fun runSearch(latitude: Double, longitude: Double, radiusMetres: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSearchAt < SEARCH_COOLDOWN_MILLIS) return
        lastSearchAt = now
        busy.value = true
        problem.value = false
        val nearby = runCatching { external.stations(latitude, longitude, radiusMetres) }
        val rows = nearby.getOrNull()
        if (rows == null) {
            problem.value = true
            busy.value = false
            return
        }
        found.value = runCatching { external.attachPrices(rows) }.getOrElse { rows.map { PricedStation(it, emptyList()) } }
        busy.value = false
    }
}

private const val SEARCH_COOLDOWN_MILLIS = 4_000L

private fun amberPubkey(value: String): Boolean {
    if (value.length != 64) return false
    return value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val catalog: CatalogStore,
    private val device: DeviceSettings,
) : ViewModel() {
    val reminders = catalog.reminders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP), emptyList())

    fun save(vehicleId: String, kind: String, due: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val lead = catalog.settings().defaultLeadTimeDays
            catalog.saveReminder(
                ReminderEntity(
                    id = UUID.randomUUID().toString(),
                    vehicleId = vehicleId,
                    kind = kind,
                    dueDate = due.ifBlank { null },
                    dueOdometerMetres = null,
                    intervalDays = if (kind == "ODOMETER_PROMPT") 30 else null,
                    leadTimeDays = lead,
                    enabled = enabled,
                    customTitle = null,
                    deletedAt = null,
                    revision = 1,
                    installId = null,
                    contentHash = null,
                ),
            )
        }
    }

    fun renewed(current: ReminderEntity, nextDue: String) {
        viewModelScope.launch(Dispatchers.IO) {
            catalog.saveReminder(current.copy(dueDate = nextDue, revision = current.revision + 1))
        }
    }

    fun selectedVehicle(onId: (String?) -> Unit) {
        viewModelScope.launch { onId(device.peekSelected()) }
    }
}

private fun timelineRows(book: com.tankful.android.ledger.Logbook): List<TimelineLine> {
    val vehicleId = book.selectedVehicleId
    val lines = mutableListOf<Pair<Long, TimelineLine>>()
    for (refuel in book.refuels.values) {
        if (refuel.deletedAt != null) continue
        if (vehicleId != null && refuel.vehicleId != vehicleId) continue
        val station = refuel.stationName?.let { " · $it" }.orEmpty()
        val money = formatMinor(refuel.total.currency, refuel.total.minorUnits)
        lines += refuel.timestampEpochMs to TimelineLine(refuel.id, refuel.timestampEpochMs, refuel.fuelKind.name, "$money$station")
    }
    for (reading in book.readings.values) {
        val manual = reading.source == com.tankful.android.model.OdometerSource.MANUAL && reading.deletedAt == null
        if (!manual) continue
        if (vehicleId != null && reading.vehicleId != vehicleId) continue
        val at = reading.timestamp.toEpochMilli()
        lines += at to TimelineLine(null, at, null, "Odometer")
    }
    for (expense in book.expenses.values) {
        val standalone = expense.origin == com.tankful.android.model.ExpenseOrigin.STANDALONE && expense.deletedAt == null
        if (!standalone) continue
        if (vehicleId != null && expense.vehicleId != vehicleId) continue
        val money = formatMinor(expense.total.currency, expense.total.minorUnits)
        lines += expense.timestampEpochMs to TimelineLine(null, expense.timestampEpochMs, null, "Expense · $money")
    }
    for (record in book.maintenance.values) {
        if (record.deletedAt != null) continue
        if (vehicleId != null && record.vehicleId != vehicleId) continue
        lines += record.timestampEpochMs to TimelineLine(null, record.timestampEpochMs, null, "Maintenance")
    }
    return lines.sortedByDescending { it.first }.map { it.second }
}

private fun <T> toForm(outcome: SaveOutcome<T>): FormResult {
    return when (outcome) {
        is SaveOutcome.Saved -> FormResult.Saved
        is SaveOutcome.NeedsConfirmation -> FormResult.Confirm(outcome.reasons)
        is SaveOutcome.Rejected -> FormResult.Rejected(outcome.reasons)
    }
}

private fun liquidFuelsOf(book: com.tankful.android.ledger.Logbook): List<FuelKind> {
    val vehicle = book.vehicles[book.selectedVehicleId] ?: return emptyList()
    if (vehicle.deletedAt != null) return emptyList()
    val names = listOfNotNull(vehicle.primaryFuel, vehicle.secondaryFuel)
    return names.mapNotNull { name -> runCatching { FuelKind.valueOf(name) }.getOrNull() }
        .filter { it == FuelKind.PETROL || it == FuelKind.DIESEL || it == FuelKind.LPG || it == FuelKind.CNG }
        .distinct()
}

private fun followDraft(
    second: RefuelInput?,
    currency: String,
    vehicleId: String,
    openedAt: Instant,
    imperial: Boolean,
): RefuelDraft? {
    if (second == null || second.quantityRaw.isBlank()) return null
    val draft = buildRefuel(second, currency, vehicleId, imperial) ?: return null
    if (draft.timestamp != openedAt) return draft
    return atTime(draft, openedAt.plusMillis(1))
}

private fun atTime(draft: RefuelDraft?, at: Instant): RefuelDraft? {
    if (draft == null) return null
    return draft.copy(timestamp = at, odometer = draft.odometer?.copy(timestamp = at))
}

private fun savePair(
    book: com.tankful.android.ledger.Logbook,
    opened: RefuelDraft,
    openedInput: RefuelInput,
    trailing: RefuelDraft?,
    second: RefuelInput?,
    now: Instant,
): SaveOutcome<com.tankful.android.ledger.RefuelRow> {
    val openedOutcome = book.saveRefuel(opened, openedInput.acknowledgements, now, openedInput.reason)
    if (openedOutcome !is SaveOutcome.Saved || trailing == null || second == null) return openedOutcome
    return book.saveRefuel(trailing, second.acknowledgements, now, second.reason)
}

private fun unusableQuantity(raw: String, fuel: FuelKind, imperial: Boolean): Boolean {
    if (raw.isBlank()) return false
    return smallestQuantity(raw, fuel, imperial, Locale.getDefault()) == null
}

private fun buildRefuel(input: RefuelInput, fallbackCurrency: String, vehicleId: String, imperial: Boolean): RefuelDraft? {
    val locale = Locale.getDefault()
    val currency = input.currencyCode.ifBlank { fallbackCurrency }
    val stored = storedFill(input.quantityRaw, input.priceRaw, input.fuel, currency, imperial, locale) ?: return null
    val metres = odometerMetres(input.odometerRaw, imperial, locale) ?: return null
    val clock = if (input.clockEpochMs == 0L) Instant.now() else Instant.ofEpochMilli(input.clockEpochMs)
    val whenAt = instantOnDate(input.dateIso, clock, ZoneId.systemDefault()) ?: return null
    val readingId = input.readingId.ifBlank { "${input.id}-odo" }
    return RefuelDraft(
        id = input.id,
        vehicleId = vehicleId,
        timestamp = whenAt,
        quantity = Quantity.of(stored.unit, stored.smallest),
        fuelKind = input.fuel,
        currency = currency,
        unitPriceUnscaled = stored.priceUnscaled,
        totalMinor = stored.totalMinor,
        fullTank = input.fullTank,
        stationName = input.station.ifBlank { null },
        note = input.note.ifBlank { null },
        odometer = OdometerDraft(readingId, whenAt, metres),
    )
}

private fun instantOnDate(dateIso: String, clock: Instant, zone: ZoneId): Instant? {
    val date = runCatching { java.time.LocalDate.parse(dateIso) }.getOrNull() ?: return null
    val time = clock.atZone(zone).toLocalTime()
    return date.atTime(time).atZone(zone).toInstant()
}

private fun latestOdometerText(book: com.tankful.android.ledger.Logbook, imperial: Boolean): String {
    val vehicleId = book.selectedVehicleId ?: return "—"
    val reading = book.readings.values
        .filter { it.vehicleId == vehicleId && it.deletedAt == null }
        .maxWithOrNull(compareBy({ it.timestamp }, { it.id }))
        ?: return "—"
    return odometerText(reading.metres, imperial)
}

private fun writeReading(
    book: com.tankful.android.ledger.Logbook,
    id: String,
    vehicleId: String,
    metres: Long,
    acknowledgements: Set<ConfirmReason>,
    reason: OdometerReason?,
): OdometerDecision {
    val odometer = OdometerDraft(id, Instant.now(), metres)
    val existing = book.readings.values
        .filter { it.vehicleId == vehicleId && it.deletedAt == null }
        .map { StoredReading(it.id, it.timestamp, it.metres, it.epochId) }
    val decision = evaluateOdometer(odometer, existing, Instant.now(), acknowledgements, reason, "epoch-$id")
    if (decision !is OdometerDecision.Accept) return decision
    val previous = book.readings[id]
    book.readings[id] = ReadingRow(
        id = id,
        vehicleId = vehicleId,
        timestamp = odometer.timestamp,
        metres = metres,
        epochId = decision.epochId,
        source = OdometerSource.MANUAL,
        sourceEntityId = null,
        entryUnit = DistanceEntryUnit.KM_WHOLE.name,
        entryUnscaled = metres / 1000,
        entryScale = 0,
        reason = decision.reason?.name,
        revision = (previous?.revision ?: 0L) + 1L,
    )
    return decision
}

private fun parseMinor(raw: String, currency: String): Long? {
    val scale = currencyMinorScale(currency)
    val parsed = parseDecimal(raw, scale, Locale.getDefault()) ?: return null
    return parsed.movePointRight(scale).setScale(0, TankfulRounding.mode).longValueExact()
}

private sealed interface CataloguePrep {
    data class Ready(val vehicle: VehicleRow, val spec: SpecificationEntity) : CataloguePrep
    data class Stop(val result: FormResult) : CataloguePrep
}

private fun specRow(vehicleId: String, fuel: String, unscaled: Long): SpecificationEntity {
    return SpecificationEntity(
        id = UUID.randomUUID().toString(),
        vehicleId = vehicleId,
        fuelKind = fuel,
        combinedUnit = "L_PER_100KM",
        combinedScale = 1,
        combinedUnscaled = unscaled,
        cityUnit = null,
        cityScale = null,
        cityUnscaled = null,
        highwayUnit = null,
        highwayScale = null,
        highwayUnscaled = null,
        urbanUnit = null,
        urbanScale = null,
        urbanUnscaled = null,
        extraUrbanUnit = null,
        extraUrbanScale = null,
        extraUrbanUnscaled = null,
        co2Scale = null,
        co2Unscaled = null,
        co2GramsPerMile = null,
        testCycle = "WLTP",
        measureKind = "LIQUID",
        source = "MANUAL",
        sourceUrl = null,
        retrievedAt = null,
        retrievalProtocol = null,
        isActive = true,
        deletedAt = null,
        revision = 1,
        installId = null,
        contentHash = null,
    )
}

