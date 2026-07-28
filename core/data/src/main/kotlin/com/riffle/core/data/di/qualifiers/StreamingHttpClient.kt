package com.riffle.core.data.di.qualifiers

import javax.inject.Qualifier

/** Marks a Ktor client configured for raw response streaming without content negotiation. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamingHttpClient
