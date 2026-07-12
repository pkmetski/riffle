package com.riffle.app.ui.source

import androidx.annotation.DrawableRes
import com.riffle.app.R
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.WebSourceDescriptors

/**
 * Chooses the icon for a source: a favicon URL to fetch at runtime (for network-backed sources
 * that expose one), plus a bundled monogram drawable to use as the fallback when the fetch
 * fails or as the primary when no host is known yet.
 *
 * ## Icon-per-SourceType is required
 *
 * Every [SourceType] must map to a bundled drawable in [fallbackDrawableFor]. The `when` is
 * intentionally exhaustive with NO `else` branch and NO `error(…)` fallback — adding a new
 * SourceType is a compile error until the new entry ships an icon here, which in turn requires
 * shipping a bundled drawable resource. Do not add an `else` branch to make the compiler quiet.
 *
 * Call sites are free to substitute a Material icon at their own layer (the picker's LocalFiles
 * card renders `Icons.Default.Folder` for the "from the OS" affordance), but the resolver still
 * owns a bundled drawable so drawer/switcher/settings surfaces all have a monogram to fall back
 * to. This keeps the "which sources have an icon?" invariant checkable in one place — the
 * companion test iterates [SourceType.values] and asserts every entry resolves.
 */
object SourceIconResolver {

    /**
     * The URL to attempt for the source's favicon, or null when we don't try. Delegates to the
     * source's [com.riffle.core.domain.WebSourceDescriptor] so a new SourceType ships its own
     * URL pattern without editing this file. Callers should always pair the returned URL with
     * [fallbackDrawableFor] so a fetch/decode failure falls back to the monogram.
     */
    fun faviconUrlFor(source: Source): String? =
        WebSourceDescriptors.forType(source.type)
            ?.iconRemoteUrl(source.url.value, source.serverType)

    /** Fallback drawable for a configured [Source]. */
    @DrawableRes
    fun fallbackDrawableFor(source: Source): Int =
        fallbackDrawableFor(source.type, source.serverType)

    /**
     * Fallback drawable for a source picked by type (no configured [Source] yet). Exhaustive by
     * design — see the class doc. If you're adding a new [SourceType], add its bundled drawable
     * to `res/drawable/` and its branch below in the same commit.
     */
    @DrawableRes
    fun fallbackDrawableFor(type: SourceType, serverType: ServerType = ServerType.AUDIOBOOKSHELF): Int =
        when (type) {
            SourceType.LOCAL_FILES -> R.drawable.ic_source_local_files
            SourceType.CHITANKA -> R.drawable.ic_source_chitanka
            SourceType.GUTENBERG -> R.drawable.ic_source_gutenberg
            SourceType.KOMGA -> R.drawable.ic_source_komga
            SourceType.ABS -> when (serverType) {
                ServerType.AUDIOBOOKSHELF -> R.drawable.ic_source_audiobookshelf
                ServerType.STORYTELLER_SERVICE -> R.drawable.ic_source_storyteller
            }
        }
}
