package com.riffle.core.domain

/**
 * A Confirmed pairing between a Storyteller readaloud and an ABS Library Item (ADR 0021).
 *
 * Only Confirmed links are persisted in this slice — there is no Pending state yet. The
 * [userConfirmed] flag distinguishes user-Confirmed (sticky) from auto-Confirmed-via-Tier-1-or-2
 * links so the auto-matcher knows what it may safely re-evaluate.
 */
data class ReadaloudLink(
    val storytellerSourceId: String,
    val storytellerBookId: String,
    val absSourceId: String,
    val absLibraryItemId: String,
    val userConfirmed: Boolean,
    /** Streaming identity verdict for this ABS item, persisted after a check (ADR 0028). */
    val identityResult: AudiobookIdentityResult = AudiobookIdentityResult.UNKNOWN,
)
