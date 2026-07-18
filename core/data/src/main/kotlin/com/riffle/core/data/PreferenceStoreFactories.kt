package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.EmphasisPreferencesStore
import com.riffle.core.domain.EmphasisStyle
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.HighlightColorPreferencesStore
import com.riffle.core.domain.HighlightsResumeStore
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReadingSpeedTracker
import com.riffle.core.domain.WakeLockPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single-key plain `DataStore<Preferences>` wrappers — one factory per typed store.
 * The interface name is reused (Kotlin function-with-type-name idiom, c.f.
 * `mutableListOf`, `Channel`) so callers and tests look like `AppThemeStore(ds)`.
 *
 * Multi-key stores (Volume, Listening, Formatting, …) and stores with custom
 * logic stay as hand-written classes — they don't fit the single-codec shape.
 */

fun AppThemeStore(dataStore: DataStore<Preferences>): AppThemeStore {
    val store = preferenceStore(
        dataStore,
        PrefCodecs.enum("app_theme", AppTheme.System, AppTheme.entries.toTypedArray()),
    )
    return object : AppThemeStore {
        override val appTheme: Flow<AppTheme> = store.flow
        override suspend fun setAppTheme(value: AppTheme) = store.update(value)
    }
}

fun CoverGridDensityStore(dataStore: DataStore<Preferences>): CoverGridDensityStore {
    val store = preferenceStore(dataStore, PrefCodecs.float("cover_grid_scale", default = 1f))
    return object : CoverGridDensityStore {
        override val scale: Flow<Float> = store.flow
        override suspend fun setScale(value: Float) = store.update(value)
    }
}

fun ReadingSpeedStore(dataStore: DataStore<Preferences>): ReadingSpeedStore {
    val store = preferenceStore(
        dataStore,
        PrefCodecs.double(
            "reading_speed_secs_per_position",
            default = ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION,
        ),
    )
    return object : ReadingSpeedStore {
        override val speedSecPerPosition: Flow<Double> = store.flow
        override suspend fun updateSpeed(newSecPerPosition: Double) = store.update(newSecPerPosition)
    }
}

fun WakeLockPreferencesStore(dataStore: DataStore<Preferences>): WakeLockPreferencesStore {
    val store = preferenceStore(dataStore, PrefCodecs.boolean("keep_screen_on", default = true))
    return object : WakeLockPreferencesStore {
        override val keepScreenOn: Flow<Boolean> = store.flow
        override suspend fun setKeepScreenOn(value: Boolean) = store.update(value)
    }
}

fun ReadaloudPreferencesStore(dataStore: DataStore<Preferences>): ReadaloudPreferencesStore {
    // Legacy "PINK" / "PURPLE" values written by older builds fall through to BLUE via the
    // codec's unknown-name fallback — acceptable since the user can re-pick from the new palette.
    val store = preferenceStore(
        dataStore,
        PrefCodecs.enum(
            "highlight_color",
            HighlightColor.BLUE,
            HighlightColor.entries.toTypedArray(),
        ),
    )
    return object : ReadaloudPreferencesStore {
        override val preferences: Flow<ReadaloudPreferences> =
            store.flow.map { ReadaloudPreferences(highlightColor = it) }

        override suspend fun update(prefs: ReadaloudPreferences) = store.update(prefs.highlightColor)
    }
}

/**
 * Per-book last-used-highlight-colour DataStore key. Exposed as `internal` so tests can reach it
 * without duplicating the literal — a fixture that hard-codes the key silently diverges when the
 * format changes and the fallback assertions become vacuous. Format is `"<prefix>:$sourceId:$itemId"`.
 */
internal fun highlightColorPrefKey(sourceId: String, itemId: String) =
    stringPreferencesKey("last_used_highlight_color:$sourceId:$itemId")

/**
 * Multi-key store — one string key per (serverId, itemId) pair, unlike the single-codec stores
 * above, so it's constructed directly here rather than via [preferenceStore]/[PrefCodecs].
 */
fun HighlightsResumeStore(dataStore: DataStore<Preferences>): HighlightsResumeStore {
    fun keyFor(serverId: String, itemId: String) = stringPreferencesKey("highlights_resume_${serverId}_$itemId")
    return object : HighlightsResumeStore {
        override suspend fun lastHighlightId(serverId: String, itemId: String): String? =
            dataStore.data.map { it[keyFor(serverId, itemId)] }.first()

        override suspend fun setLastHighlightId(serverId: String, itemId: String, annotationId: String) {
            dataStore.edit { it[keyFor(serverId, itemId)] = annotationId }
        }
    }
}

internal fun emphasisStylesPrefKey(sourceId: String, itemId: String) =
    stringPreferencesKey("last_used_emphasis_styles:$sourceId:$itemId")

fun EmphasisPreferencesStore(dataStore: DataStore<Preferences>): EmphasisPreferencesStore {
    // Per-book last-used emphasis styles set (ADR 0046). Absent → empty set (no emphasis
    // pre-selected on the next annotate gesture). Persisted as the same comma-separated wire
    // form used by `AnnotationEntity.emphasisStyles`, so unknown tokens from a
    // forward-compat peer decode cleanly to the subset we know.
    return object : EmphasisPreferencesStore {
        override fun lastUsedStyles(sourceId: String, itemId: String): kotlinx.coroutines.flow.Flow<Set<EmphasisStyle>> =
            dataStore.data.map { prefs ->
                EmphasisStyle.decode(prefs[emphasisStylesPrefKey(sourceId, itemId)]) ?: emptySet()
            }

        override suspend fun setLastUsedStyles(sourceId: String, itemId: String, value: Set<EmphasisStyle>) {
            dataStore.edit { it[emphasisStylesPrefKey(sourceId, itemId)] = EmphasisStyle.encode(value) ?: "" }
        }
    }
}

internal fun highlightColorIsNonePrefKey(sourceId: String, itemId: String) =
    androidx.datastore.preferences.core.booleanPreferencesKey("last_used_highlight_is_none:$sourceId:$itemId")

fun HighlightColorPreferencesStore(dataStore: DataStore<Preferences>): HighlightColorPreferencesStore {
    // Per-book last-used colour. Unknown/absent → HighlightColor.DEFAULT (first entry in the
    // palette), so a book the user has never picked a colour on opens with the palette default.
    // Legacy names outside the current palette (e.g. "PINK", "PURPLE") also fall back to DEFAULT;
    // the user can re-pick and it persists per-book thereafter.
    return object : HighlightColorPreferencesStore {
        override fun lastUsedColor(sourceId: String, itemId: String): Flow<HighlightColor> =
            dataStore.data.map { prefs ->
                val name = prefs[highlightColorPrefKey(sourceId, itemId)] ?: return@map HighlightColor.DEFAULT
                runCatching { HighlightColor.valueOf(name) }.getOrDefault(HighlightColor.DEFAULT)
            }

        override suspend fun setLastUsedColor(sourceId: String, itemId: String, value: HighlightColor) {
            dataStore.edit { it[highlightColorPrefKey(sourceId, itemId)] = value.name }
        }

        override fun lastUsedIsNone(sourceId: String, itemId: String): Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[highlightColorIsNonePrefKey(sourceId, itemId)] ?: false }

        override suspend fun setLastUsedIsNone(sourceId: String, itemId: String, value: Boolean) {
            dataStore.edit { it[highlightColorIsNonePrefKey(sourceId, itemId)] = value }
        }
    }
}
