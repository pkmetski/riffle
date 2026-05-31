package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import javax.inject.Inject

class BookFormattingPreferencesStoreImpl @Inject constructor(
    private val dao: BookFormattingPreferencesDao,
) : BookFormattingPreferencesStore {

    override suspend fun load(itemId: String): BookFormattingOverrides {
        val entity = dao.getByItemId(itemId) ?: return BookFormattingOverrides()
        return BookFormattingOverrides(
            fontSize = entity.fontSize,
            theme = entity.theme?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() },
            fontFamily = entity.fontFamily?.let { runCatching { ReaderFontFamily.valueOf(it) }.getOrNull() },
            lineSpacing = entity.lineSpacing,
            margins = entity.margins,
            orientation = entity.orientation?.let { runCatching { ReaderOrientation.valueOf(it) }.getOrNull() },
            showChapterMap = entity.showChapterMap,
            showReadingProgressLabels = entity.showReadingProgressLabels,
            showCurrentChapterLabel = entity.showCurrentChapterLabel,
            doublePageSpread = entity.doublePageSpread,
            justifyText = entity.justifyText,
        )
    }

    override suspend fun save(itemId: String, overrides: BookFormattingOverrides) {
        if (overrides.isEmpty) {
            dao.deleteByItemId(itemId)
            return
        }
        dao.upsert(
            BookFormattingPreferencesEntity(
                itemId = itemId,
                fontSize = overrides.fontSize,
                theme = overrides.theme?.name,
                fontFamily = overrides.fontFamily?.name,
                lineSpacing = overrides.lineSpacing,
                margins = overrides.margins,
                orientation = overrides.orientation?.name,
                showChapterMap = overrides.showChapterMap,
                showReadingProgressLabels = overrides.showReadingProgressLabels,
                showCurrentChapterLabel = overrides.showCurrentChapterLabel,
                doublePageSpread = overrides.doublePageSpread,
                justifyText = overrides.justifyText,
            )
        )
    }

    override suspend fun clear(itemId: String) {
        dao.deleteByItemId(itemId)
    }
}
