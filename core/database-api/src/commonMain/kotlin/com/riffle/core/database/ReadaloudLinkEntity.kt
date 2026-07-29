package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Link from an ABS Library Item to its Storyteller readaloud counterpart (ADR 0021).
 *
 * Multiplicity rationale: an ABS book has at most one readaloud — there's one narration per
 * conceptual work. A readaloud can point at *many* ABS items though, because the same
 * conceptual book often lives in two ABS libraries simultaneously (a Books-library ebook
 * and an Audiobooks-library audiobook stub of the same title+author). The primary key is
 * therefore the ABS side; the (storytellerSourceId, storytellerBookId) pair is indexed but
 * not unique.
 *
 * Both source columns FK-cascade to `sources.id` so the row disappears automatically when
 * either side's Source is removed.
 *
 * `state` is forward-compat with [ADR 0021]'s Pending/Unmatched states; in this slice only
 * Confirmed rows are persisted.
 */
@Entity(
    tableName = "readaloud_links",
    primaryKeys = ["absSourceId", "absLibraryItemId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["storytellerSourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["absSourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["storytellerSourceId", "storytellerBookId"]),
        Index(value = ["storytellerSourceId"]),
    ],
)
data class ReadaloudLinkEntity(
    val absSourceId: String,
    val absLibraryItemId: String,
    val storytellerSourceId: String,
    val storytellerBookId: String,
    val state: String = STATE_CONFIRMED,
    val userConfirmed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Streaming identity verdict for this link (ADR 0028): VERIFIED / MISMATCH / NO_AUDIOBOOK / UNKNOWN. */
    val identityResult: String = IDENTITY_UNKNOWN,
) {
    companion object {
        const val STATE_CONFIRMED = "CONFIRMED"
        const val IDENTITY_UNKNOWN = "UNKNOWN"
    }
}
