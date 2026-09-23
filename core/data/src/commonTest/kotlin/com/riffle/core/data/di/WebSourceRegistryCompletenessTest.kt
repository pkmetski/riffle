package com.riffle.core.data.di

import com.riffle.core.models.SourceType
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.domain.WebSourceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enforces that every [SourceType] has a [com.riffle.core.domain.WebSourceDescriptor] registered
 * in [WebSourceDescriptors]. Adding a new enum entry without a descriptor turns this test red —
 * the runtime substitute for the compile-time exhaustiveness we lose by replacing
 * `when(sourceType)` with a registry lookup.
 */
class WebSourceRegistryCompletenessTest {

    @Test
    fun `every SourceType resolves to a descriptor via static registry`() {
        val missing = SourceType.values().filter { WebSourceDescriptors.forType(it) == null }
        assertTrue(
            missing.isEmpty(),
            "SourceType entries without a WebSourceDescriptor: $missing — add a descriptor " +
                "object in :core:domain/WebSourceDescriptor.kt and register it in WebSourceDescriptors.all",
        )
    }

    @Test
    fun `every SourceType resolves via injected WebSourceRegistry`() {
        val registry = WebSourceRegistry(WebSourceDescriptors.all)
        val missing = SourceType.values().filter { registry.forType(it) == null }
        assertTrue(missing.isEmpty(), "missing bindings via WebSourceRegistry: $missing")
    }

    @Test
    fun `descriptor set contains no duplicates by type`() {
        val perType = WebSourceDescriptors.all.groupBy { it.type }.mapValues { it.value.size }
        val duplicates = perType.filterValues { it > 1 }
        assertTrue(duplicates.isEmpty(), "duplicate descriptors registered per SourceType: $duplicates")
    }

    @Test
    fun `descriptors registered match SourceType set`() {
        val registered = WebSourceDescriptors.all.map { it.type }.toSet()
        val expected = SourceType.values().toSet()
        assertEquals(expected, registered)
    }

    // Every credentialed source (multi-instance, user-configured URL/creds) MUST ship an
    // AddSourceCopy so the shared AddSourceScreen can render title/labels/help/buttons without a
    // when(sourceType). A new credentialed descriptor without copy would fall through to `null`
    // and crash the form at render time.
    @Test
    fun `every credentialed descriptor ships AddSourceCopy`() {
        val missing = WebSourceDescriptors.all
            .filter { it.hasCredentials }
            .filter { it.addSourceCopy == null }
            .map { it.type }
        assertTrue(
            missing.isEmpty(),
            "credentialed WebSourceDescriptor(s) without addSourceCopy: $missing — populate " +
                "`addSourceCopy = AddSourceCopy(...)` in the descriptor object",
        )
    }

    // Pins the invariant that `MainScreen.libraryEntryRoute` relies on: any SourceType flagged as
    // `isUnboundedCatalog = true` must ship a `browseRoutePrefix`, otherwise drawer library taps
    // silently fall back to the Room-mirrored `library_items` screen and render an empty grid.
    @Test
    fun `every unbounded SourceType descriptor sets a browseRoutePrefix`() {
        val missing = SourceType.values()
            .filter { it.isUnboundedCatalog }
            .filter { WebSourceDescriptors.forTypeOrError(it).browseRoutePrefix == null }
        assertTrue(
            missing.isEmpty(),
            "unbounded SourceType(s) without browseRoutePrefix: $missing — drawer library taps " +
                "would silently route to `library_items` and render empty",
        )
    }
}
