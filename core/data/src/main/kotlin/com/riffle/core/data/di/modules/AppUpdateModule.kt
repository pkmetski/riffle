package com.riffle.core.data.di.modules

import com.riffle.core.data.AppUpdateRepositoryImpl
import com.riffle.core.domain.AppUpdateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppUpdateModule {

    @Binds
    @Singleton
    abstract fun bindAppUpdateRepository(impl: AppUpdateRepositoryImpl): AppUpdateRepository
}
