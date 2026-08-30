package com.riffle.app.di

import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.openRiffleDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val testDatabaseKoinModule = module {
    single<RiffleDatabaseAccess>(override = true) {
        openRiffleDatabase(
            context = androidContext(),
            fallbackToDestructiveMigration = true,
        )
    }
}
