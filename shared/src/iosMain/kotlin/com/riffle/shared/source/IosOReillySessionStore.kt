package com.riffle.shared.source

import com.riffle.core.catalog.oreilly.parseOrmJwtFromCookieHeader
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHTTPCookie
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKHTTPCookieStoreObserverProtocol
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * Keeps the most recent O'Reilly session cookie header available synchronously for
 * [com.riffle.core.catalog.oreilly.OReillyCatalogFactory.cookieProvider].
 *
 * [WKHTTPCookieStore.getAllCookies] is async, so a live read cannot be used inside the
 * synchronous `cookieProvider` lambda. Instead this store observes the default
 * [WKWebsiteDataStore] cookie jar and maintains a cached header. The cache is seeded
 * eagerly from the existing cookie jar so a previously-authenticated session survives app
 * restart.
 *
 * Only available for developer mode — O'Reilly requires a WebView login to obtain the
 * `orm-jwt` cookie.
 *
 * This is a module-level singleton rather than a Koin binding because Kotlin/Native does not
 * support [kotlin.reflect.KClass] for Kotlin subclasses of Objective-C classes, which Koin
 * uses as HashMap keys. A plain top-level reference is safe here: there is exactly one
 * instance per process, initialised when `startKoin` is called from `RiffleApp.swift`.
 */
val iosOReillySessionStore: IosOReillySessionStore = IosOReillySessionStore()

@OptIn(ExperimentalForeignApi::class)
class IosOReillySessionStore : NSObject(), WKHTTPCookieStoreObserverProtocol {

    var cookieHeader: String? = null
        private set

    fun startObserving() {
        val store = WKWebsiteDataStore.defaultDataStore().httpCookieStore
        store.addObserver(this)
        // Seed from any pre-existing session so a previous login survives app restart.
        store.getAllCookies { rawCookies ->
            updateFrom(rawCookies)
        }
    }

    /** Called by the login screen immediately after harvesting the cookie from the WebView. */
    fun update(header: String) {
        cookieHeader = header
    }

    override fun cookiesDidChangeInCookieStore(cookieStore: WKHTTPCookieStore) {
        cookieStore.getAllCookies { rawCookies ->
            updateFrom(rawCookies)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateFrom(rawCookies: Any?) {
        val cookies = rawCookies as? List<NSHTTPCookie> ?: return
        val header = cookies.joinToString("; ") { "${it.name}=${it.value}" }
        if (parseOrmJwtFromCookieHeader(header) != null) {
            cookieHeader = header
        }
    }
}
