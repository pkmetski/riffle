package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import javax.inject.Inject

class BookFormattingPreferencesStoreImpl @Inject constructor(
    private val dao: BookFormattingPreferencesDao,
) : BookFormattingPreferencesStore {

    override suspend fun load(itemId: String): FormattingPreferences? {
        val entity = dao.getByItemId(itemId) ?: return null
        return FormattingPreferences(
            fontSize = entity.fontSize,
            theme = runCatching { ReaderTheme.valueOf(entity.theme) }.getOrDefault(ReaderTheme.Light),
            fontFamily = runCatching { ReaderFontFamily.valueOf(entity.fontFamily) }.getOrDefault(ReaderFontFamily.Serif),
            lineSpacing = entity.lineSpacing,
            margins = entity.margins,
            orientation = runCatching { ReaderOrientation.valueOf(entity.orientation) }.getOrDefault(ReaderOrientation.Paginated),
        )
    }

    override suspend fun save(itemId: String, preferences: FormattingPreferences) {
        dao.upsert(
            BookFormattingPreferencesEntity(
                itemId = itemId,
                fontSize = preferences.fontSize,
                theme = preferences.theme.name,
                fontFamily = preferences.fontFamily.name,
                lineSpacing = preferences.lineSpacing,
                margins = preferences.margins,
                orientation = preferences.orientation.name,
            )
        )
    }

    override suspend fun clear(itemId: String) {
        dao.deleteByItemId(itemId)
    }
}
