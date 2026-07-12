package com.riffle.core.data.di

import com.riffle.core.domain.SourceType
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.domain.WebSourceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            "SourceType entries without a WebSourceDescriptor: $missing — add a descriptor " +
                "object in :core:domain/WebSourceDescriptor.kt and register it in WebSourceDescriptors.all",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every SourceType resolves via injected WebSourceRegistry`() {
        val registry = WebSourceRegistry(WebSourceDescriptors.all)
        val missing = SourceType.values().filter { registry.forType(it) == null }
        assertTrue("missing bindings via WebSourceRegistry: $missing", missing.isEmpty())
    }

    @Test
    fun `descriptor set contains no duplicates by type`() {
        val perType = WebSourceDescriptors.all.groupBy { it.type }.mapValues { it.value.size }
        val duplicates = perType.filterValues { it > 1 }
        assertTrue("duplicate descriptors registered per SourceType: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `descriptors registered match SourceType set`() {
        val registered = WebSourceDescriptors.all.map { it.type }.toSet()
        val expected = SourceType.values().toSet()
        assertEquals(expected, registered)
    }
}
