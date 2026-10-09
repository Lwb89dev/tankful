package com.tankful.android.data.store

import com.tankful.android.data.db.ExpenseEntity
import com.tankful.android.data.db.JudgementEntity
import com.tankful.android.data.db.MaintenanceEntity
import com.tankful.android.data.db.MaintenanceItemEntity
import com.tankful.android.data.db.OdometerReadingEntity
import com.tankful.android.data.db.RefuelEntity
import com.tankful.android.data.db.VehicleEntity
import com.tankful.android.ledger.ExpenseRow
import com.tankful.android.ledger.JudgementRow
import com.tankful.android.ledger.MaintenanceItemRow
import com.tankful.android.ledger.MaintenanceRow
import com.tankful.android.ledger.ReadingRow
import com.tankful.android.ledger.RefuelRow
import com.tankful.android.ledger.VehicleRow
import com.tankful.android.model.EnergyKind
import com.tankful.android.model.ExpenseOrigin
import com.tankful.android.model.FuelKind
import com.tankful.android.model.OdometerSource
import com.tankful.android.money.Money
import com.tankful.android.money.UnitPrice
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.QuantityUnit
import java.time.Instant

fun VehicleEntity.toRow(tagIds: List<String>): VehicleRow {
    return VehicleRow(
        id = id,
        createdAt = Instant.ofEpochMilli(createdAt),
        manufacturer = manufacturer,
        model = model,
        modelYear = modelYear,
        purchaseDate = purchaseDate,
        deletedAt = deletedAt,
        powertrain = powertrain,
        primaryFuel = primaryFuel,
        secondaryFuel = secondaryFuel,
        plate = plate,
        vin = vin,
        purchasePriceMinor = purchasePriceMinor,
        purchaseCurrency = purchaseCurrency,
        generation = generation,
        engine = engine,
        powerUnscaled = powerUnscaled,
        powerScale = powerScale,
        powerUnit = powerUnit,
        transmission = transmission,
        drivetrain = drivetrain,
        registrationYear = registrationYear,
        distanceUnit = distanceUnit,
        economyPreference = economyPreference,
        notes = notes,
        imageAttachmentId = imageAttachmentId,
        lastFuelKind = lastFuelKind,
        lastStationName = lastStationName,
        lastFullTank = lastFullTank,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
        tagIds = tagIds,
        nickname = nickname,
    )
}

fun VehicleRow.toEntity(): VehicleEntity {
    return VehicleEntity(
        id = id,
        createdAt = createdAt.toEpochMilli(),
        manufacturer = manufacturer,
        model = model,
        modelYear = modelYear,
        powertrain = powertrain,
        primaryFuel = primaryFuel,
        secondaryFuel = secondaryFuel,
        plate = plate,
        vin = vin,
        purchaseDate = purchaseDate,
        purchasePriceMinor = purchasePriceMinor,
        purchaseCurrency = purchaseCurrency,
        generation = generation,
        engine = engine,
        powerUnscaled = powerUnscaled,
        powerScale = powerScale,
        powerUnit = powerUnit,
        transmission = transmission,
        drivetrain = drivetrain,
        registrationYear = registrationYear,
        distanceUnit = distanceUnit,
        economyPreference = economyPreference,
        notes = notes,
        imageAttachmentId = imageAttachmentId,
        lastFuelKind = lastFuelKind,
        lastStationName = lastStationName,
        lastFullTank = lastFullTank,
        deletedAt = deletedAt,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
        nickname = nickname,
    )
}

fun ExpenseEntity.toRow(tagIds: List<String>): ExpenseRow {
    return ExpenseRow(
        id = id,
        vehicleId = vehicleId,
        categoryId = categoryId,
        timestampEpochMs = timestamp,
        total = Money.of(totalCurrency, totalMinor),
        note = note,
        origin = ExpenseOrigin.valueOf(origin),
        originId = originId,
        deletedAt = deletedAt,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
        tagIds = tagIds,
    )
}

fun ExpenseRow.toEntity(): ExpenseEntity {
    return ExpenseEntity(
        id = id,
        vehicleId = vehicleId,
        categoryId = categoryId,
        timestamp = timestampEpochMs,
        totalCurrency = total.currency,
        totalMinor = total.minorUnits,
        note = note,
        origin = origin.name,
        originId = originId,
        deletedAt = deletedAt,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
    )
}

fun OdometerReadingEntity.toRow(): ReadingRow {
    return ReadingRow(
        id = id,
        vehicleId = vehicleId,
        timestamp = Instant.ofEpochMilli(timestamp),
        metres = metres,
        epochId = epochId,
        source = OdometerSource.valueOf(source),
        sourceEntityId = sourceEntityId,
        deletedAt = deletedAt,
        entryUnit = entryUnit,
        entryUnscaled = entryUnscaled,
        entryScale = entryScale,
        reason = reason,
        note = note,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
    )
}

fun ReadingRow.toEntity(): OdometerReadingEntity {
    val unscaled = if (entryUnscaled == 0L && entryUnit == "KM_WHOLE") metres else entryUnscaled
    return OdometerReadingEntity(
        id = id,
        vehicleId = vehicleId,
        timestamp = timestamp.toEpochMilli(),
        metres = metres,
        entryUnit = entryUnit,
        entryUnscaled = unscaled,
        entryScale = entryScale,
        source = source.name,
        sourceEntityId = sourceEntityId,
        epochId = epochId,
        reason = reason,
        note = note,
        deletedAt = deletedAt,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
    )
}

fun RefuelEntity.toRow(tagIds: List<String>): RefuelRow {
    return RefuelRow(
        id = id,
        vehicleId = vehicleId,
        timestampEpochMs = timestamp,
        quantity = Quantity.of(QuantityUnit.valueOf(quantityUnit), quantityAmount),
        fuelKind = FuelKind.valueOf(fuelKind),
        energyKind = EnergyKind.valueOf(energyKind),
        unitPrice = UnitPrice.of(unitPriceCurrency, unitPriceScale, unitPriceUnscaled, unitPriceDerived),
        total = Money.of(totalCurrency, totalMinor),
        amountsDisagreed = amountsDisagreed,
        fullTank = fullTank,
        stationName = stationName,
        note = note,
        tagIds = tagIds,
        odometerReadingId = odometerReadingId,
        expenseId = expenseId,
        deletedAt = deletedAt,
        revision = revision,
        stationOsmType = stationOsmType,
        stationOsmId = stationOsmId,
        latitude = latitude,
        longitude = longitude,
        installId = installId,
        contentHash = contentHash,
    )
}

fun RefuelRow.toEntity(): RefuelEntity {
    return RefuelEntity(
        id = id,
        vehicleId = vehicleId,
        timestamp = timestampEpochMs,
        quantityAmount = quantity.amount,
        quantityUnit = quantity.unit.name,
        fuelKind = fuelKind.name,
        energyKind = energyKind.name,
        unitPriceCurrency = unitPrice.currency,
        unitPriceScale = unitPrice.scale,
        unitPriceUnscaled = unitPrice.unscaledAmount,
        unitPriceDerived = unitPrice.derived,
        totalCurrency = total.currency,
        totalMinor = total.minorUnits,
        amountsDisagreed = amountsDisagreed,
        fullTank = fullTank,
        stationName = stationName,
        stationOsmType = stationOsmType,
        stationOsmId = stationOsmId,
        latitude = latitude,
        longitude = longitude,
        note = note,
        odometerReadingId = odometerReadingId,
        expenseId = expenseId,
        deletedAt = deletedAt,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
    )
}

fun MaintenanceEntity.toRow(tagIds: List<String>): MaintenanceRow {
    val labour = moneyOrNull(labourCurrency, labourMinor)
    val parts = moneyOrNull(partsCurrency, partsMinor)
    return MaintenanceRow(
        id = id,
        vehicleId = vehicleId,
        total = Money.of(totalCurrency, totalMinor),
        labour = labour,
        parts = parts,
        amountsDisagreed = amountsDisagreed,
        expenseId = expenseId,
        odometerReadingId = odometerReadingId,
        note = note,
        deletedAt = deletedAt,
        revision = revision,
        timestampEpochMs = timestamp,
        serviceTypeId = serviceTypeId,
        providerId = providerId,
        categoryId = categoryId,
        nextDate = nextDate,
        nextOdometerMetres = nextOdometerMetres,
        installId = installId,
        contentHash = contentHash,
        tagIds = tagIds,
    )
}

fun MaintenanceRow.toEntity(): MaintenanceEntity {
    return MaintenanceEntity(
        id = id,
        vehicleId = vehicleId,
        timestamp = timestampEpochMs,
        serviceTypeId = serviceTypeId,
        providerId = providerId,
        categoryId = categoryId,
        odometerReadingId = odometerReadingId,
        expenseId = expenseId,
        labourCurrency = labour?.currency,
        labourMinor = labour?.minorUnits,
        partsCurrency = parts?.currency,
        partsMinor = parts?.minorUnits,
        totalCurrency = total.currency,
        totalMinor = total.minorUnits,
        amountsDisagreed = amountsDisagreed,
        nextDate = nextDate,
        nextOdometerMetres = nextOdometerMetres,
        note = note,
        deletedAt = deletedAt,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
    )
}

fun MaintenanceItemEntity.toRow(): MaintenanceItemRow {
    return MaintenanceItemRow(
        id = id,
        maintenanceId = maintenanceId,
        description = description,
        deletedAt = deletedAt,
        costMinor = costMinor,
        costCurrency = costCurrency,
        quantityText = quantityText,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
    )
}

fun MaintenanceItemRow.toEntity(): MaintenanceItemEntity {
    return MaintenanceItemEntity(
        id = id,
        maintenanceId = maintenanceId,
        description = description,
        costMinor = costMinor,
        costCurrency = costCurrency,
        quantityText = quantityText,
        deletedAt = deletedAt,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
    )
}

fun JudgementEntity.toRow(): JudgementRow {
    return JudgementRow(
        id = id,
        openingRefuelId = openingRefuelId,
        closingRefuelId = closingRefuelId,
        confirmed = confirmed,
        deletedAt = deletedAt,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
    )
}

fun JudgementRow.toEntity(): JudgementEntity {
    return JudgementEntity(
        id = id,
        openingRefuelId = openingRefuelId,
        closingRefuelId = closingRefuelId,
        confirmed = confirmed,
        deletedAt = deletedAt,
        revision = revision,
        installId = installId,
        contentHash = contentHash,
    )
}

private fun moneyOrNull(currency: String?, minor: Long?): Money? {
    if (currency == null || minor == null) return null
    return Money.of(currency, minor)
}
