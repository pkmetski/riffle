package com.riffle.core.logging

import org.koin.dsl.module

val iosLoggingModule =
    module {
        single<Logger> { IosLogger() }
    }
