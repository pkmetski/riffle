package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One-to-one link between a Storyteller readaloud and an ABS Library Item.
 *
 * The issue spec refers to "storytellerBookUuid", but a Storyteller book has no globally
 * unique uuid in Riffle — its identity is (Storyteller server, book id). We therefore store
 * both parts explicitly and use them as the composite primary key.
 *
 * Both server columns FK-cascade to `servers.id` so the row disappears automatically when
 * either side's Server is removed.
 *
 * `state` is forward-compat with [ADR 0021]'s Pending/Unmatched states; in this slice only
 * Confirmed rows are persisted.
 */
@Entity(
    tableName = "readaloud_links",
    primaryKeys = ["storytellerServerId", "storytellerBookId"],
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
        Index(value = ["absServerId", "absLibraryItemId"]),
        Index(value = ["storytellerServerId"]),
        Index(value = ["absServerId"]),
    ],
)
data class ReadaloudLinkEntity(
    val storytellerServerId: String,
    val storytellerBookId: String,
    val absServerId: String,
    val absLibraryItemId: String,
    val state: String = STATE_CONFIRMED,
    val userConfirmed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val STATE_CONFIRMED = "CONFIRMED"
    }
}
