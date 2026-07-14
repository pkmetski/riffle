package com.riffle.core.domain

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity check that a fresh [EmbeddedFigure] with `charOffset = 0L` encodes with the field
 * present in JSON. Would flip red if kotlinx.serialization surprisingly treated 0L as the
 * `null` default and dropped the field — which would break [EpubReaderViewModel]'s
 * caption-highlight interleaver, which discriminates "figure inline" from "figure appended
 * after text" by whether `charOffset` decodes as null.
 */
class EmbeddedFigureSerializationTest {

    @Test
    fun `charOffset 0L encodes into JSON with encodeDefaults false`() {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(EmbeddedFigure.serializer())
        val fig = EmbeddedFigure(
            href = "x.png", svg = null, caption = "", order = 0,
            imageBytes = null, charOffset = 0L,
        )
        val encoded = json.encodeToString(serializer, listOf(fig))
        assertTrue("charOffset must appear in JSON but was: $encoded", encoded.contains("charOffset"))
    }
}
