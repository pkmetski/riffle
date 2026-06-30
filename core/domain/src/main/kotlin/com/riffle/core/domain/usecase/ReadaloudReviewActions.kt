package com.riffle.core.domain.usecase

import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudReviewMutator
import javax.inject.Inject

/**
 * Action coordinator for the readaloud-matches review screen. Wraps every link/unlink mutation in
 * the audio-settings rekey choreography: if the change moves the readaloud's canonical audio
 * identity (ADR 0028), the per-book audio-settings record (speed etc.) migrates with it.
 *
 * Pure-JVM testable: depends only on domain interfaces.
 */
class ReadaloudReviewActions @Inject constructor(
    private val mutator: ReadaloudReviewMutator,
    private val linkRepository: ReadaloudLinkRepository,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
) {

    suspend fun confirmCandidate(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    ) {
        rekeyAudioSettingsAround(storytellerServerId, storytellerBookId) {
            mutator.createUserConfirmedLink(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
            // The book is now Confirmed; drop all of its Pending-Review candidates.
            mutator.deleteCandidatesForBook(storytellerServerId, storytellerBookId)
        }
    }

    suspend fun dismissCandidate(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    ) {
        mutator.upsertCandidateDismissal(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
        mutator.deleteCandidate(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
    }

    suspend fun dismissBook(storytellerServerId: String, storytellerBookId: String) {
        mutator.upsertBookDismissal(storytellerServerId, storytellerBookId)
        mutator.deleteCandidatesForBook(storytellerServerId, storytellerBookId)
    }

    suspend fun unlinkBook(storytellerServerId: String, storytellerBookId: String) {
        rekeyAudioSettingsAround(storytellerServerId, storytellerBookId) {
            mutator.deleteLinksForStorytellerBook(storytellerServerId, storytellerBookId)
        }
    }

    suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) {
        val link = linkRepository.findByAbsItem(absServerId, absLibraryItemId)
        if (link == null) {
            mutator.deleteLinkForAbsItem(absServerId, absLibraryItemId)
            return
        }
        rekeyAudioSettingsAround(link.storytellerServerId, link.storytellerBookId) {
            mutator.deleteLinkForAbsItem(absServerId, absLibraryItemId)
        }
    }

    suspend fun pairManually(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    ) {
        rekeyAudioSettingsAround(storytellerServerId, storytellerBookId) {
            mutator.createUserConfirmedLink(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
            // Manual pairing overrides any prior "don't ask again" and clears stale candidates.
            mutator.clearBookDismissal(storytellerServerId, storytellerBookId)
            mutator.deleteCandidatesForBook(storytellerServerId, storytellerBookId)
        }
    }

    /**
     * Runs [mutate] (a link/unlink change), then migrates the per-book audio-settings record if the
     * change moved the readaloud's canonical audio identity (ADR 0028) — e.g. linking an audiobook
     * moves the saved speed from the Storyteller id onto the audiobook id; unlinking moves it back.
     */
    private suspend fun rekeyAudioSettingsAround(
        storytellerServerId: String,
        storytellerBookId: String,
        mutate: suspend () -> Unit,
    ) {
        val before = audioIdentityResolver.resolveForStorytellerBook(storytellerServerId, storytellerBookId)
        mutate()
        val after = audioIdentityResolver.resolveForStorytellerBook(storytellerServerId, storytellerBookId)
        if (before != after) audioPlaybackPreferencesStore.rekey(before, after)
    }
}
