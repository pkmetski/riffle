package com.riffle.core.data.di.qualifiers

import javax.inject.Qualifier

/**
 * Marks the shared [okhttp3.OkHttpClient] used by web-source scrapers (chitanka today,
 * Gutenberg etc. later — ADR 0043). This client carries a 10 MB disk cache plus two
 * interceptors that force a 24 h TTL onto every response and serve stale on network
 * failure.
 *
 * Web-source Hilt providers inject via this qualifier so all upstream fetches benefit
 * from caching by construction — there is no unqualified alternative in `catalog-*`
 * DI bindings for them to reach for. ABS/Storyteller stay on the plain
 * [okhttp3.OkHttpClient] binding; their sync semantics are different.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WebSourceOkHttpClient
