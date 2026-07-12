package com.riffle.core.data.credentialed

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.SourceUrl

/**
 * Per-[SourceType] plug-in that owns the "user has typed URL + username + password" step of
 * adding a credentialed source. Contributed to a `Map<SourceType, CredentialedAuthenticator>` via
 * Hilt `@IntoMap` + [com.riffle.core.data.di.modules.SourceTypeKey], mirroring the
 * [com.riffle.core.catalog.CatalogFactory] map that already dispatches per-source catalog access.
 *
 * `SourceRepository.authenticate` looks up the entry for the caller's [SourceType] and delegates
 * the network round-trip here — the repository stays generic and a new credentialed source
 * (Komga, Calibre-Web, Jellyfin, …) drops in by contributing one binding.
 *
 * The returned [com.riffle.core.domain.PendingSource] carries [SourceType] via its
 * `sourceType` field so [CredentialedSourceInstaller] can stamp the correct
 * `SourceEntity.type` column at commit — no more `type = "ABS"` hard-code.
 */
interface CredentialedAuthenticator {
    /**
     * The [SourceType] this authenticator handles. Used as a self-check in the Hilt map binding —
     * the map key must match this value; a mismatch is a wiring bug.
     */
    val sourceType: SourceType

    /**
     * Authenticate against the source at [url] with [username]/[password]. [serverType] lets a
     * single-[SourceType] authenticator further discriminate its target product (currently only
     * ABS uses it, for the Audiobookshelf-vs-Storyteller split; Komga and future single-product
     * sources ignore it).
     */
    suspend fun authenticate(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult
}
