package com.riffle.shared

import com.riffle.core.data.di.iosDatabaseModule
import com.riffle.core.data.di.iosDataModule
import com.riffle.core.logging.iosLoggingModule
import org.koin.core.context.startKoin as koinStartKoin

fun startKoin() {
    koinStartKoin {
        modules(
            iosLoggingModule,
            iosDataModule,
            iosDatabaseModule,
        )
    }
}
