package com.riffle.core.data.credentialed

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.READALOUD_MEDIA_TYPE
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the "commit an authenticated credentialed source" side of the Add-Source flow — the
 * counterpart to [com.riffle.core.data.websource.SingletonWebSourceInstaller] for user-configured
 * sources (Audiobookshelf servers, Storyteller Services, and any future credentialed source like
 * Komga). Persists the [SourceEntity], its libraries, credentials, and hidden-library prefs in
 * one atomic-ish sweep so [SourceRepository.commit] can stay a thin delegator.
 *
 * Notably fixes the pre-refactor `type = "ABS"` hard-code by reading [PendingSource.sourceType] —
 * a new credentialed source now round-trips its own type through the installer without an edit
 * here.
 */
@Singleton
class CredentialedSourceInstaller @Inject constructor(
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val tokenStorage: TokenStorage,
    private val visibilityStore: LibraryVisibilityPreferencesStore,
) {

    /**
     * Persist [pending] and its libraries. Returns [CommitSourceResult.Success] wrapping the
     * committed [Source], or [CommitSourceResult.Failure] wrapping any thrown cause.
     *
     * Storyteller-specific carve-outs (never becomes the active source, seeds a synthetic
     * "Readalouds" library row per ADR 0026) are still branched here on
     * [PendingSource.serverType]. Once #441 splits Storyteller into its own SourceType those
     * branches move behind a descriptor capability, but until then they're isolated to this file
     * so `SourceRepositoryImpl` doesn't have to know.
     */
    suspend fun install(
        pending: PendingSource,
        hiddenLibraryIds: Set<String>,
    ): CommitSourceResult = try {
        val id = UUID.randomUUID().toString()
        val entity = SourceEntity(
            id = id,
            url = pending.url.value,
            isActive = false,                    // overridden inside transaction
            insecureConnectionAllowed = pending.insecureConnectionAllowed,
            username = pending.username,
            serverType = pending.serverType.name,
            // ABS exposes `user.id` on the login response — persist it now so annotation sync has
            // a cross-device-stable namespace from first open. Storyteller's login response
            // carries no equivalent identity (auth is username + token), so leave it null.
            absUserId = pending.userId.takeIf {
                it.isNotBlank() && pending.serverType == ServerType.AUDIOBOOKSHELF
            },
            type = pending.sourceType.name,
        )
        // A Storyteller Source is a Settings-only readaloud backend (ADR 0026) — it must never
        // become the active browsable Source, not even as the first source added. Other
        // credentialed sources keep the "first source becomes active" convenience.
        val inserted = if (pending.serverType == ServerType.STORYTELLER_SERVICE) {
            sourceDao.upsert(entity)
            entity
        } else {
            sourceDao.upsertAsFirstIfNoActive(entity)
        }
        tokenStorage.saveToken(id, pending.token)
        tokenStorage.savePassword(id, pending.password)
        val libraryRows = pending.libraries.map {
            LibraryEntity(id = it.id, name = it.name, mediaType = it.mediaType, sourceId = id)
        }.toMutableList()
        // A Storyteller Source contributes no browsable Library (ADR 0026), but its readaloud books
        // are stored in `library_items` as matcher input. They need an owning `libraries` row so the
        // matcher's library→source join resolves their serverType and the source-removal cascade
        // cleans them up. This row is never surfaced in the drawer or any library picker — the
        // Source is never the active browsable Source.
        if (pending.serverType == ServerType.STORYTELLER_SERVICE) {
            libraryRows += LibraryEntity(
                id = readaloudLibraryId(id),
                name = "Readalouds",
                mediaType = READALOUD_MEDIA_TYPE,
                sourceId = id,
            )
        }
        libraryDao.replaceAllForSource(sourceId = id, libraries = libraryRows)
        hiddenLibraryIds.forEach { hidden -> visibilityStore.hideLibrary(id, hidden) }
        CommitSourceResult.Success(inserted.toDomain())
    } catch (t: Throwable) {
        CommitSourceResult.Failure(t)
    }

    companion object {
        // The local-only library id that namespaces a Storyteller Source's readaloud rows in
        // `library_items` (matcher input; never browsable — ADR 0026). Its mediaType is the shared
        // [READALOUD_MEDIA_TYPE] so consumers can filter it out of browsable surfaces.
        fun readaloudLibraryId(sourceId: String): String = "readaloud:$sourceId"
    }
}

// package-private so both the installer and SourceRepositoryImpl can share the entity→domain map.
internal fun SourceEntity.toDomain(): Source {
    val parsedUrl = SourceUrl.parse(url) ?: SourceUrl.parse("https://invalid.example.com")!!
    return Source(
        id = id,
        url = parsedUrl,
        isActive = isActive,
        insecureConnectionAllowed = insecureConnectionAllowed,
        username = username,
        type = runCatching { SourceType.valueOf(type) }.getOrDefault(SourceType.ABS),
        serverType = ServerType.fromStorageString(serverType),
        absUserId = absUserId,
    )
}
