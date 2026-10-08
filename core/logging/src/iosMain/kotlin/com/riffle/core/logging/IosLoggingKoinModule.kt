package com.riffle.core.logging

import org.koin.dsl.module

val iosLoggingModule =
    module {
        single { InMemoryLogBuffer() }
        single<Logger> { IosLogger(buffer = get()) }
    }
