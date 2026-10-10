package com.tankful.android

import com.tankful.android.ledger.Logbook
import com.tankful.android.ledger.VehicleRow
import com.tankful.android.model.ExpenseOrigin
import com.tankful.android.model.FuelKind
import com.tankful.android.quantity.QuantityUnit
import com.tankful.android.sync.httpsPicture
import com.tankful.android.sync.readNostrProfile
import com.tankful.android.transfer.ExpenseContent
import com.tankful.android.transfer.MaintenanceContent
import com.tankful.android.transfer.RefuelContent
import com.tankful.android.transfer.restoreExpense
import com.tankful.android.transfer.restoreMaintenance
import com.tankful.android.transfer.restoreRefuel
import com.tankful.android.transfer.systemCategoryIds
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreRoundTripTest {
    @Test
    fun refuelKeepsStationNoteOdometerAndCngMass() {
        val book = garage()
        val content = RefuelContent(
            id = "r",
            vehicleId = "v",
            timestamp = "2026-10-03T12:00:00.000Z",
            quantityAmount = 5000,
            quantityUnit = "GRAM",
            fuelKind = "CNG",
            energyKind = "LIQUID",
            totalCurrency = "EUR",
            totalMinor = 800,
            fullTank = true,
            stationName = "Metano",
            note = "north",
            unitPriceScale = 3,
            unitPriceUnscaled = 160000,
            unitPriceDerived = false,
            odometerMetres = 12_000,
        )
        assertTrue(book.restoreRefuel(content, 2, "install", "hash"))
        val row = book.refuels.getValue("r")
        assertEquals(FuelKind.CNG, row.fuelKind)
        assertEquals(QuantityUnit.GRAM, row.quantity.unit)
        assertEquals("Metano", row.stationName)
        assertEquals("north", row.note)
        assertEquals(12_000L, book.readings.getValue(row.odometerReadingId!!).metres)
        assertEquals(800L, book.expenses.getValue(row.expenseId).total.minorUnits)
        assertEquals(ExpenseOrigin.REFUEL, book.expenses.getValue(row.expenseId).origin)
    }

    @Test
    fun standaloneExpenseAndMaintenanceStayVisible() {
        val book = garage()
        val allowed = systemCategoryIds()
        val expense = ExpenseContent(
            id = "e1",
            vehicleId = "v",
            categoryId = "legal.insurance",
            timestamp = "2026-10-03T12:00:00.000Z",
            totalCurrency = "EUR",
            totalMinor = 1500,
            origin = "STANDALONE",
            note = "year",
        )
        assertTrue(book.restoreExpense(expense, allowed, 1, null, null))
        val service = MaintenanceContent(
            id = "m1",
            vehicleId = "v",
            timestamp = "2026-10-04T12:00:00.000Z",
            totalCurrency = "EUR",
            totalMinor = 8000,
            categoryId = "maintenance.scheduled_service",
            serviceTypeId = "general_service",
            note = "oil",
            odometerMetres = 20_000,
        )
        assertTrue(book.restoreMaintenance(service, allowed, 1, null, null))
        assertEquals(1, book.timeline().count { it.id == "e1" })
        assertEquals(1, book.timeline().count { it.id == "m1" })
        assertEquals(8000L, book.expenses.values.single { it.origin == ExpenseOrigin.MAINTENANCE }.total.minorUnits)
    }

    @Test
    fun profileUsesDisplayNameAndRejectsCleartextPictures() {
        val profile = readNostrProfile("""{"name":"npub","display_name":"Ada","picture":"https://cdn.example/a.png"}""")
        assertEquals("Ada", profile?.name)
        assertEquals("https://cdn.example/a.png", profile?.picture)
        assertNull(httpsPicture("http://cdn.example/a.png"))
        assertNull(readNostrProfile("""{"name":"","picture":"https://cdn.example/a.png"}"""))
        val long = "https://cdn.example/" + "a".repeat(400)
        assertEquals(long, httpsPicture(long))
        assertNull(httpsPicture("https://cdn.example/" + "b".repeat(2100)))
    }

    private fun garage(): Logbook {
        val book = Logbook()
        book.addVehicle(VehicleRow("v", Instant.parse("2026-10-01T00:00:00Z"), "Test", "Car", 2020))
        return book
    }
}
