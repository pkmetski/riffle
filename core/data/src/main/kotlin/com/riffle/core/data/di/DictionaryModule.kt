package com.riffle.core.data.di

import android.content.Context
import com.riffle.core.common.Clock
import com.riffle.core.data.dictionary.DictionaryPackSqliteStore
import com.riffle.core.data.dictionary.KaikkiJsonlToSqliteConverter
import com.riffle.core.data.dictionary.PackDownloader
import com.riffle.core.data.dictionary.WordLookupRepositoryImpl
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.dictionary.DictionaryRepository
import com.riffle.core.dictionary.PackStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DictionaryModule {

    @Binds @Singleton
    abstract fun bindDictionaryRepository(impl: WordLookupRepositoryImpl): DictionaryRepository

    @Binds @Singleton
    abstract fun bindPackStore(impl: WordLookupRepositoryImpl): PackStore

    companion object {
        @Provides @Singleton
        fun provideDictionaryPackSqliteStore(
            @ApplicationContext context: Context,
        ): DictionaryPackSqliteStore = DictionaryPackSqliteStore(context.filesDir)

        @Provides @Singleton
        fun providePackDownloader(
            @ApplicationContext context: Context,
            httpClient: HttpClient,
            dictionaryPackDao: DictionaryPackDao,
            clock: Clock,
        ): PackDownloader = PackDownloader(
            context.filesDir,
            httpClient,
            dictionaryPackDao,
            clock,
            KaikkiJsonlToSqliteConverter(),
        )
    }
}
