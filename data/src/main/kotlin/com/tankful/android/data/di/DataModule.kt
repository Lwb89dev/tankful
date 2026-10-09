package com.tankful.android.data.di

import android.content.Context
import com.tankful.android.data.db.SyncDao
import com.tankful.android.data.db.TankfulDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): TankfulDatabase {
        return TankfulDatabase.create(context, "tankful.db")
    }

    @Provides
    fun syncDao(database: TankfulDatabase): SyncDao = database.sync()

    @Provides
    @Singleton
    fun http(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}
