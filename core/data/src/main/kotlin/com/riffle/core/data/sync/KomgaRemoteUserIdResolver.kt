package com.riffle.core.data.sync

import com.riffle.core.catalog.komga.KomgaHttpClient
import com.riffle.core.catalog.komga.probeKomgaUserId
import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.domain.Source
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RemoteUserIdResolver] for [com.riffle.core.domain.SourceType.KOMGA]. `token` is the pre-built
 * `Authorization: Basic <base64>` header value stashed by [com.riffle.core.data.credentialed
 * .KomgaCredentialedAuthenticator] — pass it straight through the shared [KomgaHttpClient].
 *
 * Legacy Komga rows (installed before #529 wired the id into the add-source handshake) get
 * their id backfilled here on first sync. Delegates the actual `/users/me` handshake to
 * [probeKomgaUserId] so the authenticator and the resolver stay in lockstep on fallback rules
 * and cancellation semantics.
 */
@Singleton
class KomgaRemoteUserIdResolver @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : RemoteUserIdResolver {
    override suspend fun resolve(source: Source, token: String): String? {
        val http = KomgaHttpClient(okHttpClient, token)
        // Any HTTP status is preserved but only 2xx-with-parsable-body yields a usable id;
        // callers treat a null return as "leave the source in PendingRemoteId and retry next
        // time". IOException collapses to null too — offline resolve is a no-op, not a fatal
        // error. CancellationException is not caught: it propagates so the enclosing scope
        // (viewModelScope, reader lifecycle, sweep) can actually cancel the coroutine.
        return try {
            probeKomgaUserId(http, source.url.value).userId
        } catch (_: IOException) {
            null
        }
    }
}
