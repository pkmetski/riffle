package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A Tier 3 (fuzzy) ABS candidate for a Storyteller readaloud, awaiting the user's review
 * decision (ADR 0021). One row per (readaloud, ABS item) pair the auto-matcher surfaced;
 * [score] is the matcher's combined title/author similarity.
 *
 * Keyed on the full (Storyteller, ABS) pair because a single readaloud can have several
 * fuzzy candidates and a single ABS item could (in principle) be a candidate for more than
 * one readaloud. Both server columns FK-cascade to `servers.id` so candidates vanish when
 * either side's Server is removed.
 */
@Entity(
    tableName = "readaloud_candidates",
    primaryKeys = ["storytellerServerId", "storytellerBookId", "absServerId", "absLibraryItemId"],
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["storytellerServerId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["absServerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // The storyteller FK is covered by the PK prefix; the ABS FK needs its own index.
        Index(value = ["absServerId"]),
    ],
)
data class ReadaloudCandidateEntity(
    val storytellerServerId: String,
    val storytellerBookId: String,
    val absServerId: String,
    val absLibraryItemId: String,
    val score: Double,
)
