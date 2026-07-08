package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingScope
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.SourceRepository
import javax.inject.Inject

// Formatting is per-device but keyed by (sourceId, itemId, scope) so two Sources' colliding item
// ids don't share one row (ADR 0025) and so the annotations reading view keeps a chain independent
// from the full-book reader. Like LibraryRepositoryImpl, the active Source is resolved here — you
// format the book you're actively reading — keeping the domain interface itemId-only.
class BookFormattingPreferencesStoreImpl @Inject constructor(
    private val dao: BookFormattingPreferencesDao,
    private val sourceRepository: SourceRepository,
) : BookFormattingPreferencesStore {

    override suspend fun load(itemId: String, scope: FormattingScope): BookFormattingOverrides {
        val sourceId = sourceRepository.getActive()?.id ?: return BookFormattingOverrides()
        val entity = dao.getByItemId(sourceId, itemId, scope.name) ?: return BookFormattingOverrides()
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

    override suspend fun save(itemId: String, scope: FormattingScope, overrides: BookFormattingOverrides) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        if (overrides.isEmpty) {
            dao.deleteByItemId(sourceId, itemId, scope.name)
            return
        }
        dao.upsert(
            BookFormattingPreferencesEntity(
                sourceId = sourceId,
                itemId = itemId,
                scope = scope.name,
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

    override suspend fun clear(itemId: String, scope: FormattingScope) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        dao.deleteByItemId(sourceId, itemId, scope.name)
    }
}
