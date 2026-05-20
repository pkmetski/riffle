package com.riffle.core.data.di

import android.content.Context
import androidx.room.Room
import com.riffle.core.data.KeystoreTokenStorage
import com.riffle.core.data.ServerRepositoryImpl
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.ServerDao
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsApiClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: KeystoreTokenStorage): TokenStorage

    companion object {
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

        @Provides
        @Singleton
        fun provideAbsApi(okHttpClient: OkHttpClient): AbsApi =
            AbsApiClient(okHttpClient)

        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): RiffleDatabase =
            Room.databaseBuilder(context, RiffleDatabase::class.java, "riffle.db").build()

        @Provides
        @Singleton
        fun provideServerDao(db: RiffleDatabase): ServerDao = db.serverDao()
    }
}
