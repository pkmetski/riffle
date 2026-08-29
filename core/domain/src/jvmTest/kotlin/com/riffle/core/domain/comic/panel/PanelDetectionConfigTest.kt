package com.riffle.core.domain.comic.panel

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PanelDetectionConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `default config round-trips through JSON`() {
        val original = PanelDetectionConfig()
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PanelDetectionConfig>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `JSON with partial fields deserializes with correct defaults`() {
        val minimal = """{"localAdaptiveConstant":15}"""
        val config = json.decodeFromString<PanelDetectionConfig>(minimal)
        assertEquals(15L, config.localAdaptiveConstant)
        // All other fields retain defaults
        assertEquals(PanelDetectionConfig().projectionGutterFraction, config.projectionGutterFraction, 1e-10)
        assertEquals(PanelDetectionConfig().minPanelDimensionFraction, config.minPanelDimensionFraction, 1e-10)
    }

    @Test
    fun `minInterpreterVersion defaults to CURRENT`() {
        val config = PanelDetectionConfig()
        assertEquals(PanelEngineVersion.CURRENT, config.minInterpreterVersion)
    }

    @Test
    fun `JSON with minInterpreterVersion round-trips correctly`() {
        val fullSpec = """
            {
              "minInterpreterVersion": "2.0.0",
              "projectionGutterFraction": 0.20,
              "textureStdDevThreshold": 15.0,
              "backgroundContrastThreshold": 60
            }
        """.trimIndent()
        val config = json.decodeFromString<PanelDetectionConfig>(fullSpec)
        assertEquals("2.0.0", config.minInterpreterVersion)
        assertEquals(0.20, config.projectionGutterFraction, 1e-10)
        assertEquals(15.0, config.textureStdDevThreshold, 1e-10)
        assertEquals(60, config.backgroundContrastThreshold)
        assertNotNull(config)
    }
}
