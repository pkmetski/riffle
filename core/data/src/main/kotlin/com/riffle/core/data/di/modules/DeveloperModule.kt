package com.riffle.core.data.di.modules

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.riffle.core.data.developer.AndroidPatStore
import com.riffle.core.data.developer.DeveloperOptionsRepositoryImpl
import com.riffle.core.data.developer.PatStore
import com.riffle.core.data.di.DeveloperOptionsDataStore
import com.riffle.core.data.di.developerOptionsDataStore
import com.riffle.core.data.di.DeveloperOptionsPatStore
import com.riffle.core.domain.developer.DeveloperOptionsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DeveloperModule {

    @Binds
    @Singleton
    abstract fun bindDeveloperOptionsRepository(
        impl: DeveloperOptionsRepositoryImpl,
    ): DeveloperOptionsRepository

    companion object {

        @Provides
        @Singleton
        @DeveloperOptionsDataStore
        fun provideDeveloperOptionsDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = context.developerOptionsDataStore

        @Provides
        @Singleton
        @DeveloperOptionsPatStore
        fun providePatStore(@ApplicationContext context: Context): PatStore = AndroidPatStore(context)
    }
}
