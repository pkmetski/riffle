package com.riffle.app.ui.source

import androidx.annotation.DrawableRes
import com.riffle.app.R
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType

/**
 * Chooses the icon for a network-backed source: a favicon URL to fetch at runtime, plus a bundled
 * monogram drawable to use as the fallback when the fetch fails (or as the primary when no base
 * URL is known yet, i.e. in the "add source" type picker).
 *
 * Chitanka's favicon is served as `.ico`, which Coil's default decoders can't handle, so we skip
 * the fetch and always show the bundled Ч monogram.
 *
 * LocalFiles is intentionally excluded — see call sites — because the existing Material Folder
 * treatment for local files is left as-is.
 */
object SourceIconResolver {

    /**
     * The URL to attempt for the source's favicon, or null when we don't try (LocalFiles,
     * Chitanka). Callers should always pair the returned URL with [fallbackDrawableFor] so a
     * fetch/decode failure falls back to the monogram.
     */
    fun faviconUrlFor(source: Source): String? = when (source.type) {
        SourceType.LOCAL_FILES -> null
        SourceType.CHITANKA -> null
        SourceType.ABS -> when (source.serverType) {
            // ABS bundles /Logo.png at the web root as its PWA icon — a known PNG path we can
            // decode without a bespoke ICO decoder.
            ServerType.AUDIOBOOKSHELF -> "${source.url.value}/Logo.png"
            // Storyteller is Next.js; the /apple-touch-icon.png convention gives us a large PNG
            // when the deployment ships one, and the bundled monogram covers the miss.
            ServerType.STORYTELLER_SERVICE -> "${source.url.value}/apple-touch-icon.png"
        }
    }

    /**
     * Fallback drawable for a configured [Source]. Not defined for [SourceType.LOCAL_FILES] —
     * that surface keeps the Material Folder icon at its call sites, unchanged.
     */
    @DrawableRes
    fun fallbackDrawableFor(source: Source): Int =
        fallbackDrawableFor(source.type, source.serverType)

    /**
     * Fallback drawable for a source picked by type (no configured [Source] yet). Not defined
     * for [SourceType.LOCAL_FILES] — throws to catch callers that forgot the LocalFiles branch.
     */
    @DrawableRes
    fun fallbackDrawableFor(type: SourceType, serverType: ServerType = ServerType.AUDIOBOOKSHELF): Int =
        when (type) {
            SourceType.LOCAL_FILES -> error(
                "SourceIconResolver has no drawable for LOCAL_FILES — render Icons.Default.Folder at the call site."
            )
            SourceType.CHITANKA -> R.drawable.ic_source_chitanka
            SourceType.ABS -> when (serverType) {
                ServerType.AUDIOBOOKSHELF -> R.drawable.ic_source_audiobookshelf
                ServerType.STORYTELLER_SERVICE -> R.drawable.ic_source_storyteller
            }
        }
}
