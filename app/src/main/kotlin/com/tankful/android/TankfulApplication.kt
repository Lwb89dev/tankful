package com.tankful.android

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class TankfulApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().penaltyLog().build())
        }
        val reminders = PeriodicWorkRequestBuilder<com.tankful.android.reminders.ReminderWorker>(1, TimeUnit.DAYS).build()
        val sync = PeriodicWorkRequestBuilder<com.tankful.android.reminders.SyncWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("tankful-reminders", ExistingPeriodicWorkPolicy.KEEP, reminders)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("tankful-sync", ExistingPeriodicWorkPolicy.KEEP, sync)
    }
}
