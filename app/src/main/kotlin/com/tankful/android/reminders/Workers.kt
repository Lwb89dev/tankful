package com.tankful.android.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tankful.android.MainActivity
import com.tankful.android.R
import com.tankful.android.data.store.CatalogStore
import com.tankful.android.data.store.DeviceSettings
import com.tankful.android.data.store.GarageLog
import com.tankful.android.sync.SyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val catalog: CatalogStore,
    private val garage: GarageLog,
    private val device: DeviceSettings,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!notificationsAllowed(applicationContext)) return Result.success()
        val book = garage.read()
        val today = LocalDate.now()
        for (reminder in catalog.enabledReminders()) {
            if (reminder.deletedAt != null) continue
            if (!due(reminder.dueDate, reminder.leadTimeDays, reminder.intervalDays, reminder.vehicleId, reminder.kind, book, today)) continue
            if (device.notifiedOn(reminder.id) == today.toString()) continue
            post(reminder.id, textFor(reminder.kind))
            device.markNotified(reminder.id, today.toString())
        }
        return Result.success()
    }

    private fun post(id: String, text: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channelId = "tankful-reminders"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Tankful", NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }
        val snooze = Intent(applicationContext, SnoozeReceiver::class.java).putExtra(SnoozeReceiver.EXTRA_ID, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        val action = PendingIntent.getBroadcast(applicationContext, id.hashCode(), snooze, flags)
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_pump)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .addAction(0, applicationContext.getString(R.string.snooze), action)
            .setAutoCancel(true)
            .build()
        manager.notify(id.hashCode(), notification)
    }

    private fun textFor(kind: String): String {
        val res = when (kind) {
            "INSURANCE" -> R.string.reminder_insurance
            "TAX" -> R.string.reminder_tax
            "INSPECTION" -> R.string.reminder_inspection
            "ROADSIDE_ASSISTANCE" -> R.string.reminder_roadside
            "WARRANTY" -> R.string.reminder_warranty
            "SCHEDULED_SERVICE" -> R.string.reminder_service
            "ODOMETER_PROMPT" -> R.string.reminder_odometer
            else -> R.string.reminder_custom
        }
        return applicationContext.getString(res)
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(MANUAL, false)
        engine.pull(manual)
        engine.publish()
        return Result.success()
    }

    companion object {
        const val MANUAL = "manual"
    }
}

@AndroidEntryPoint
class SnoozeReceiver : BroadcastReceiver() {
    @Inject lateinit var device: DeviceSettings

    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            device.markNotified(id, LocalDate.now().toString())
            pending.finish()
        }
    }

    companion object {
        const val EXTRA_ID = "reminder_id"
    }
}

private fun notificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
    return granted == PackageManager.PERMISSION_GRANTED
}

private fun pendingImmutable(): Int {
    if (Build.VERSION.SDK_INT >= 23) return PendingIntent.FLAG_IMMUTABLE
    return 0
}

private fun due(
    dueDate: String?,
    leadDays: Int,
    intervalDays: Int?,
    vehicleId: String,
    kind: String,
    book: com.tankful.android.ledger.Logbook,
    today: LocalDate,
): Boolean {
    if (dueDate != null) {
        val due = runCatching { LocalDate.parse(dueDate) }.getOrNull() ?: return false
        val start = due.minusDays(leadDays.toLong())
        return !today.isBefore(start) && !today.isAfter(due)
    }
    if (kind != "ODOMETER_PROMPT" || intervalDays == null) return false
    val latest = book.readings.values
        .filter { it.vehicleId == vehicleId && it.deletedAt == null }
        .maxByOrNull { it.timestamp }
    if (latest == null) return true
    val age = ChronoUnit.DAYS.between(latest.timestamp, Instant.now())
    return age >= intervalDays
}
