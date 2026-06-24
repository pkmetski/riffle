package com.riffle.core.data

import com.riffle.core.domain.DeviceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMetadataCodecTest {

    private val meta = DeviceMetadata(
        deviceId = "dev-1",
        label = "Phone A",
        lastSeenAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun `buildFileBody with no annotations renders just the header`() {
        val body = DeviceMetadataCodec.buildFileBody(meta, annotationJsonStrings = emptyList())
        val extracted = DeviceMetadataCodec.extractHeader(body)
        assertEquals(meta, extracted)
    }

    @Test
    fun `buildFileBody preserves annotation records in order after the header`() {
        val body = DeviceMetadataCodec.buildFileBody(
            meta,
            annotationJsonStrings = listOf("""{"id":"a"}""", """{"id":"b"}"""),
        )
        val extracted = DeviceMetadataCodec.extractHeader(body)
        assertEquals(meta, extracted)
        assertTrue(body.contains("\"id\":\"a\""))
        assertTrue(body.contains("\"id\":\"b\""))
    }

    @Test
    fun `replaceHeader swaps the header without losing annotations`() {
        val original = DeviceMetadataCodec.buildFileBody(
            meta,
            annotationJsonStrings = listOf("""{"id":"x"}"""),
        )
        val newMeta = meta.copy(label = "Renamed", lastSeenAt = "2026-02-02T02:02:02Z")
        val rewritten = DeviceMetadataCodec.replaceHeader(original, newMeta)
        val extracted = DeviceMetadataCodec.extractHeader(rewritten)
        assertEquals(newMeta, extracted)
        assertTrue(rewritten.contains("\"id\":\"x\""))
    }

    @Test
    fun `extractHeader returns null for legacy header-less files`() {
        val legacy = """[{"id":"a"},{"id":"b"}]"""
        assertNull(DeviceMetadataCodec.extractHeader(legacy))
    }

    @Test
    fun `replaceHeader on a legacy file inserts a header`() {
        val legacy = """[{"id":"a"}]"""
        val rewritten = DeviceMetadataCodec.replaceHeader(legacy, meta)
        assertEquals(meta, DeviceMetadataCodec.extractHeader(rewritten))
        assertTrue(rewritten.contains("\"id\":\"a\""))
    }

    @Test
    fun `malformed input is returned unchanged by replaceHeader and null by extractHeader`() {
        val bogus = "not json {{"
        assertNull(DeviceMetadataCodec.extractHeader(bogus))
        assertEquals(bogus, DeviceMetadataCodec.replaceHeader(bogus, meta))
    }
}
