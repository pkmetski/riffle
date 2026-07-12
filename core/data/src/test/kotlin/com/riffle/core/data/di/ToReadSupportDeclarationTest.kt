package com.riffle.core.data.di

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ToReadListCapability
import com.riffle.core.catalog.abs.AbsCatalog
import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.catalog.gutenberg.GutenbergCatalog
import com.riffle.core.catalog.komga.KomgaCatalog
import com.riffle.core.data.localfiles.LocalFilesCatalog
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.ToReadSupport
import com.riffle.core.domain.WebSourceDescriptors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Guarantees the invariant "the descriptor's declared To Read support MATCHES the Catalog's
 * actual capability set". Without this test, a source can declare [ToReadListCapability] (which
 * makes the tab visible) without implementing [PlaylistsCapability] — exactly what shipped for
 * Komga before this test existed: the To Read tab rendered, the toggle appeared to work, but
 * every add silently fell through to [com.riffle.core.data.LocalToReadStore] because
 * [com.riffle.core.data.ToReadRepositoryImpl.activePlaylistsCap] came back null. Users thought
 * their list was syncing to the server; it wasn't.
 *
 * The invariant is checked by cross-referencing:
 *   * [WebSourceDescriptor.toReadSupport] — the explicit declaration in `:core:domain`
 *   * The Catalog class's supertype set — the actual runtime behaviour
 *
 * A new [SourceType] appears in the [CATALOG_CLASS_BY_TYPE] manifest below (compile error if
 * missing), and every descriptor's declaration is checked against its Catalog's `implements`
 * list. Mismatch => test failure => the merge is blocked until either the declaration or the
 * Catalog is fixed.
 */
class ToReadSupportDeclarationTest {

    // Manifest of the Catalog class backing each SourceType. Kept next to the assertions so a
    // new source is visible in one place; the corresponding descriptor comes from the
    // WebSourceDescriptors registry.
    private val catalogClassByType: Map<SourceType, KClass<out Catalog>> = mapOf(
        SourceType.ABS to AbsCatalog::class,
        SourceType.LOCAL_FILES to LocalFilesCatalog::class,
        SourceType.CHITANKA to ChitankaCatalog::class,
        SourceType.GUTENBERG to GutenbergCatalog::class,
        SourceType.KOMGA to KomgaCatalog::class,
    )

    @Test
    fun `every SourceType has a Catalog class in the manifest`() {
        val missing = SourceType.values().filter { it !in catalogClassByType }
        assertTrue(
            "SourceType(s) missing from CATALOG_CLASS_BY_TYPE in ToReadSupportDeclarationTest: " +
                "$missing — add the entry so the To Read declaration is auto-checked.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `descriptor toReadSupport = ServerBacked iff Catalog implements PlaylistsCapability`() {
        val mismatches = mutableListOf<String>()
        catalogClassByType.forEach { (type, catalogClass) ->
            val declared = WebSourceDescriptors.forTypeOrError(type).toReadSupport
            val implementsPlaylists = PlaylistsCapability::class.java.isAssignableFrom(catalogClass.java)
            when (declared) {
                ToReadSupport.ServerBacked -> if (!implementsPlaylists) mismatches +=
                    "$type: descriptor says ServerBacked but ${catalogClass.simpleName} does NOT implement PlaylistsCapability " +
                        "→ every add will silently fall through to LocalToReadStore (the Komga bug)."
                ToReadSupport.LocalOnly,
                ToReadSupport.Unsupported -> if (implementsPlaylists) mismatches +=
                    "$type: descriptor says $declared but ${catalogClass.simpleName} implements PlaylistsCapability " +
                        "→ writes will hit the server the descriptor claims we don't sync to."
            }
        }
        assertEquals(
            "descriptor.toReadSupport ↔ Catalog capability mismatches:\n" + mismatches.joinToString("\n"),
            0, mismatches.size,
        )
    }

    @Test
    fun `descriptor toReadSupport != Unsupported iff Catalog implements ToReadListCapability`() {
        // ToReadListCapability is the UI marker that makes the tab visible. It must be present
        // when the descriptor claims either ServerBacked or LocalOnly support, and absent when
        // the descriptor claims Unsupported. This locks the UI-visibility decision to the
        // descriptor so a source can't silently show a broken tab.
        val mismatches = mutableListOf<String>()
        catalogClassByType.forEach { (type, catalogClass) ->
            val declared = WebSourceDescriptors.forTypeOrError(type).toReadSupport
            val implementsMarker = ToReadListCapability::class.java.isAssignableFrom(catalogClass.java)
            when (declared) {
                ToReadSupport.ServerBacked,
                ToReadSupport.LocalOnly -> if (!implementsMarker) mismatches +=
                    "$type: descriptor says $declared but ${catalogClass.simpleName} does NOT implement " +
                        "ToReadListCapability → the To Read tab will be hidden despite the descriptor promising support."
                ToReadSupport.Unsupported -> if (implementsMarker) mismatches +=
                    "$type: descriptor says Unsupported but ${catalogClass.simpleName} implements " +
                        "ToReadListCapability → the tab renders even though the descriptor claims we don't support it."
            }
        }
        assertEquals(
            "descriptor.toReadSupport ↔ ToReadListCapability marker mismatches:\n" + mismatches.joinToString("\n"),
            0, mismatches.size,
        )
    }
}
