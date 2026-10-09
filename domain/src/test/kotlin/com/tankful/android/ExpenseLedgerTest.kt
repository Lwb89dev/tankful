package com.tankful.android

import com.tankful.android.ledger.ExpenseRow
import com.tankful.android.ledger.Logbook
import com.tankful.android.ledger.MaintenanceItemRow
import com.tankful.android.ledger.MaintenanceRow
import com.tankful.android.ledger.RefuelDraft
import com.tankful.android.ledger.TimelineRow
import com.tankful.android.model.ExpenseOrigin
import com.tankful.android.model.FuelKind
import com.tankful.android.model.SaveOutcome
import com.tankful.android.money.Money
import com.tankful.android.quantity.Quantity
import com.tankful.android.quantity.QuantityUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ExpenseLedgerTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z")

    @Test
    fun refuelCreatesOneFuelExpenseAndEditsMoveTogether() {
        val book = Logbook()
        val saved = book.saveRefuel(draft(total = 7468L), emptySet(), now) as SaveOutcome.Saved
        assertEquals(1, book.expenses.values.count { it.origin == ExpenseOrigin.REFUEL && it.deletedAt == null })
        assertEquals(7468L, book.expenses.getValue(saved.value.expenseId).total.minorUnits)
        val edited = book.saveRefuel(draft(total = 7500L), emptySet(), now) as SaveOutcome.Saved
        assertEquals(7500L, edited.value.total.minorUnits)
        assertEquals(7500L, book.expenses.getValue(edited.value.expenseId).total.minorUnits)
        assertEquals(1, book.expenses.values.count { it.originId == "r1" && it.deletedAt == null })
        book.deleteRefuel("r1")
        assertTrue(book.refuels.getValue("r1").deletedAt != null)
        assertTrue(book.expenses.getValue(saved.value.expenseId).deletedAt != null)
    }

    @Test
    fun maintenancePostsTheTotalOnceAndChartsDoNotDoubleCountFuel() {
        val book = Logbook()
        book.saveRefuel(draft(total = 1000L), emptySet(), now)
        val record = MaintenanceRow(
            id = "m1",
            vehicleId = "v",
            total = Money.of("EUR", 8_000L),
            labour = Money.of("EUR", 3_000L),
            parts = Money.of("EUR", 5_000L),
            amountsDisagreed = false,
            expenseId = "em1",
            odometerReadingId = null,
            note = null,
        )
        val items = listOf(
            MaintenanceItemRow("i1", "m1", "pad"),
            MaintenanceItemRow("i2", "m1", "disc"),
        )
        book.saveMaintenance(record, items, emptySet())
        assertEquals(1, book.expenses.values.count { it.origin == ExpenseOrigin.MAINTENANCE })
        assertEquals(8_000L, book.expenses.getValue("em1").total.minorUnits)
        val expenseSum = book.expenseMinor("EUR")
        val fuelExpenses = book.categoryMinor("fuel", "EUR")
        assertEquals(book.refuelMinor("EUR"), fuelExpenses)
        assertEquals(expenseSum, fuelExpenses + 8_000L)
        assertTrue(expenseSum != expenseSum + book.refuelMinor("EUR"))
        val timeline = book.timeline()
        assertTrue(timeline.any { it is TimelineRow.RefuelItem })
        assertTrue(timeline.none { it is TimelineRow.ExpenseItem && it.id == "expense-r1" })
        book.saveStandaloneExpense(
            ExpenseRow("e1", "v", "legal.insurance", 0L, Money.of("EUR", 50L), null, ExpenseOrigin.STANDALONE, null),
        )
        assertTrue(book.timeline().any { it is TimelineRow.ExpenseItem && it.id == "e1" })
    }

    private fun draft(total: Long) = RefuelDraft(
        id = "r1",
        vehicleId = "v",
        timestamp = now,
        quantity = Quantity.of(QuantityUnit.MILLILITRE, 42_700L),
        fuelKind = FuelKind.PETROL,
        currency = "EUR",
        unitPriceUnscaled = null,
        totalMinor = total,
        fullTank = true,
    )
}
