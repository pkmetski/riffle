package com.riffle.core.logging

import org.koin.dsl.module

val loggingKoinModule = module {
    single { InMemoryLogBuffer() }
    single<Logger> { AndroidLogger(get()) }
}
