package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * A sticky user decision that suppresses an auto-match from re-surfacing (ADR 0021):
 *
 *  - [SCOPE_BOOK] — "No match — don't ask again" for a whole readaloud. The book moves to
 *    Unmatched and the matcher never proposes candidates for it again. [absServerId] /
 *    [absLibraryItemId] are empty.
 *  - [SCOPE_CANDIDATE] — "Dismiss this candidate" for a single (readaloud, ABS item) pair.
 *    That specific pair never reappears in Pending Review; other candidates are unaffected.
 *
 * Empty-string ABS ids for book-scope rows keep the four-column primary key unique without a
 * nullable key column (a real ABS item id is never empty). Only the Storyteller server column
 * FK-cascades — the ABS id is a sentinel for book scope, so it cannot carry a foreign key.
 */
@Entity(
    tableName = "readaloud_dismissals",
    primaryKeys = ["storytellerServerId", "storytellerBookId", "absServerId", "absLibraryItemId"],
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["storytellerServerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadaloudDismissalEntity(
    val storytellerServerId: String,
    val storytellerBookId: String,
    val scope: String,
    val absServerId: String = "",
    val absLibraryItemId: String = "",
) {
    companion object {
        const val SCOPE_BOOK = "BOOK"
        const val SCOPE_CANDIDATE = "CANDIDATE"
    }
}
