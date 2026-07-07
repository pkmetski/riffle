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
        storytellerSourceId: String,
        storytellerBookId: String,
        absSourceId: String,
        absLibraryItemId: String,
    ) {
        rekeyAudioSettingsAround(storytellerSourceId, storytellerBookId) {
            mutator.createUserConfirmedLink(storytellerSourceId, storytellerBookId, absSourceId, absLibraryItemId)
            // The book is now Confirmed; drop all of its Pending-Review candidates.
            mutator.deleteCandidatesForBook(storytellerSourceId, storytellerBookId)
        }
    }

    suspend fun dismissCandidate(
        storytellerSourceId: String,
        storytellerBookId: String,
        absSourceId: String,
        absLibraryItemId: String,
    ) {
        mutator.upsertCandidateDismissal(storytellerSourceId, storytellerBookId, absSourceId, absLibraryItemId)
        mutator.deleteCandidate(storytellerSourceId, storytellerBookId, absSourceId, absLibraryItemId)
    }

    suspend fun dismissBook(storytellerSourceId: String, storytellerBookId: String) {
        mutator.upsertBookDismissal(storytellerSourceId, storytellerBookId)
        mutator.deleteCandidatesForBook(storytellerSourceId, storytellerBookId)
    }

    suspend fun unlinkBook(storytellerSourceId: String, storytellerBookId: String) {
        rekeyAudioSettingsAround(storytellerSourceId, storytellerBookId) {
            mutator.deleteLinksForStorytellerBook(storytellerSourceId, storytellerBookId)
        }
    }

    suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) {
        val link = linkRepository.findByAbsItem(absSourceId, absLibraryItemId)
        if (link == null) {
            mutator.deleteLinkForAbsItem(absSourceId, absLibraryItemId)
            return
        }
        rekeyAudioSettingsAround(link.storytellerSourceId, link.storytellerBookId) {
            mutator.deleteLinkForAbsItem(absSourceId, absLibraryItemId)
        }
    }

    suspend fun pairManually(
        storytellerSourceId: String,
        storytellerBookId: String,
        absSourceId: String,
        absLibraryItemId: String,
    ) {
        rekeyAudioSettingsAround(storytellerSourceId, storytellerBookId) {
            mutator.createUserConfirmedLink(storytellerSourceId, storytellerBookId, absSourceId, absLibraryItemId)
            // Manual pairing overrides any prior "don't ask again" and clears stale candidates.
            mutator.clearBookDismissal(storytellerSourceId, storytellerBookId)
            mutator.deleteCandidatesForBook(storytellerSourceId, storytellerBookId)
        }
    }

    /**
     * Runs [mutate] (a link/unlink change), then migrates the per-book audio-settings record if the
     * change moved the readaloud's canonical audio identity (ADR 0028) — e.g. linking an audiobook
     * moves the saved speed from the Storyteller id onto the audiobook id; unlinking moves it back.
     */
    private suspend fun rekeyAudioSettingsAround(
        storytellerSourceId: String,
        storytellerBookId: String,
        mutate: suspend () -> Unit,
    ) {
        val before = audioIdentityResolver.resolveForStorytellerBook(storytellerSourceId, storytellerBookId)
        mutate()
        val after = audioIdentityResolver.resolveForStorytellerBook(storytellerSourceId, storytellerBookId)
        if (before != after) audioPlaybackPreferencesStore.rekey(before, after)
    }
}
