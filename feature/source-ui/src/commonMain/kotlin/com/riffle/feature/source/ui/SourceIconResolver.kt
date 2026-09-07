package com.riffle.feature.source.ui

import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ic_source_audiobookshelf
import com.riffle.feature.source.ui.generated.resources.ic_source_chitanka
import com.riffle.feature.source.ui.generated.resources.ic_source_gutenberg
import com.riffle.feature.source.ui.generated.resources.ic_source_komga
import com.riffle.feature.source.ui.generated.resources.ic_source_local_files
import com.riffle.feature.source.ui.generated.resources.ic_source_radio_es
import com.riffle.feature.source.ui.generated.resources.ic_source_storyteller
import org.jetbrains.compose.resources.DrawableResource

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
 * card renders the Folder glyph for the "from the OS" affordance), but the resolver still owns a
 * bundled drawable so drawer/switcher/settings surfaces all have a monogram to fall back to.
 * This keeps the "which sources have an icon?" invariant checkable in one place — the companion
 * test iterates [SourceType.values] and asserts every entry resolves.
 *
 * The drawables live in this module's `composeResources/drawable`, so the same artwork is used
 * by the Android app and the iOS Compose Multiplatform app.
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

    /**
     * Favicon URL for a source type without a configured server URL — only non-null for types
     * whose icon is a fixed CDN URL independent of any server base (e.g. radio.es).
     */
    fun faviconUrlFor(type: SourceType, serverType: ServerType = ServerType.AUDIOBOOKSHELF): String? =
        WebSourceDescriptors.forType(type)
            ?.iconRemoteUrl("", serverType)
            ?.takeIf { it.startsWith("http") }

    /** Fallback drawable for a configured [Source]. */
    fun fallbackDrawableFor(source: Source): DrawableResource =
        fallbackDrawableFor(source.type, source.serverType)

    /**
     * Fallback drawable for a source picked by type (no configured [Source] yet). Exhaustive by
     * design — see the class doc. If you're adding a new [SourceType], add its bundled drawable
     * to `composeResources/drawable/` and its branch below in the same commit.
     */
    fun fallbackDrawableFor(
        type: SourceType,
        serverType: ServerType = ServerType.AUDIOBOOKSHELF,
    ): DrawableResource =
        when (type) {
            SourceType.LOCAL_FILES -> Res.drawable.ic_source_local_files
            SourceType.CHITANKA -> Res.drawable.ic_source_chitanka
            SourceType.GUTENBERG -> Res.drawable.ic_source_gutenberg
            SourceType.KOMGA -> Res.drawable.ic_source_komga
            SourceType.RADIO_ES -> Res.drawable.ic_source_radio_es
            SourceType.OREILLY -> Res.drawable.ic_source_oreilly
            SourceType.ABS -> when (serverType) {
                ServerType.AUDIOBOOKSHELF -> Res.drawable.ic_source_audiobookshelf
                ServerType.STORYTELLER_SERVICE -> Res.drawable.ic_source_storyteller
            }
        }
}
