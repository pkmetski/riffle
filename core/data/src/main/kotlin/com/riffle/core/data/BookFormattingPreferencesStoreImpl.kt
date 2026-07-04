package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ServerRepository
import javax.inject.Inject

// Formatting is per-device but keyed by (serverId, itemId) so two Servers' colliding item ids
// don't share one row (ADR 0025). Like LibraryRepositoryImpl, the active Server is resolved here
// — you format the book you're actively reading — keeping the domain interface itemId-only.
class BookFormattingPreferencesStoreImpl @Inject constructor(
    private val dao: BookFormattingPreferencesDao,
    private val serverRepository: ServerRepository,
) : BookFormattingPreferencesStore {

    override suspend fun load(itemId: String): BookFormattingOverrides {
        val serverId = serverRepository.getActive()?.id ?: return BookFormattingOverrides()
        val entity = dao.getByItemId(serverId, itemId) ?: return BookFormattingOverrides()
        return BookFormattingOverrides(
            fontSize = entity.fontSize,
            theme = entity.theme?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() },
            fontFamily = entity.fontFamily?.decodeFontFamily(),
            lineSpacing = entity.lineSpacing,
            margins = entity.margins,
            orientation = entity.orientation?.let { runCatching { ReaderOrientation.valueOf(it) }.getOrNull() },
            showChapterMap = entity.showChapterMap,
            showReadingProgressLabels = entity.showReadingProgressLabels,
            showCurrentChapterLabel = entity.showCurrentChapterLabel,
            doublePageSpread = entity.doublePageSpread,
            justifyText = entity.justifyText,
            showReadingTimeEstimate = entity.showReadingTimeEstimate,
        )
    }

    override suspend fun save(itemId: String, overrides: BookFormattingOverrides) {
        val serverId = serverRepository.getActive()?.id ?: return
        if (overrides.isEmpty) {
            dao.deleteByItemId(serverId, itemId)
            return
        }
        dao.upsert(
            BookFormattingPreferencesEntity(
                serverId = serverId,
                itemId = itemId,
                fontSize = overrides.fontSize,
                theme = overrides.theme?.name,
                fontFamily = overrides.fontFamily?.encodePersistName(),
                lineSpacing = overrides.lineSpacing,
                margins = overrides.margins,
                orientation = overrides.orientation?.name,
                showChapterMap = overrides.showChapterMap,
                showReadingProgressLabels = overrides.showReadingProgressLabels,
                showCurrentChapterLabel = overrides.showCurrentChapterLabel,
                doublePageSpread = overrides.doublePageSpread,
                justifyText = overrides.justifyText,
                showReadingTimeEstimate = overrides.showReadingTimeEstimate,
            )
        )
    }

    override suspend fun clear(itemId: String) {
        val serverId = serverRepository.getActive()?.id ?: return
        dao.deleteByItemId(serverId, itemId)
    }
}
