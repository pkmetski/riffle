package com.riffle.core.data

import com.riffle.core.models.AnnotationDeviceMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationDeviceMetaCodecTest {

    @Test
    fun `encode then decode round-trips every field`() {
        val meta = AnnotationDeviceMeta(
            deviceId = "dev-1",
            label = "Phone A",
            lastSyncedAt = "2026-06-27T12:00:00Z",
            username = "alice",
        )
        val encoded = AnnotationDeviceMetaCodec.encode(meta)
        assertEquals(meta, AnnotationDeviceMetaCodec.decode(encoded))
    }

    @Test
    fun `username is omitted when null and parses back as null`() {
        val meta = AnnotationDeviceMeta("dev-1", "Phone A", "2026-06-27T12:00:00Z", username = null)
        val encoded = AnnotationDeviceMetaCodec.encode(meta)
        assertTrue("username field must not appear in body when null", !encoded.contains("username"))
        assertNull(AnnotationDeviceMetaCodec.decode(encoded)?.username)
    }

    @Test
    fun `blank username is treated as absent`() {
        val meta = AnnotationDeviceMeta("dev-1", "Phone A", "2026-06-27T12:00:00Z", username = "")
        val encoded = AnnotationDeviceMetaCodec.encode(meta)
        assertTrue(!encoded.contains("username"))
        assertNull(AnnotationDeviceMetaCodec.decode(encoded)?.username)
    }

    @Test
    fun `encode carries the discriminator type so the file is self-describing`() {
        val meta = AnnotationDeviceMeta("dev-1", "Phone A", "2026-06-27T12:00:00Z")
        assertTrue(AnnotationDeviceMetaCodec.encode(meta).contains("\"type\":\"riffle:DeviceSyncMeta\""))
    }

    @Test
    fun `decode rejects bodies carrying a different type discriminator`() {
        // A foreign-shape object — same field names but the type belongs to the per-file header.
        // The codec must not return data here; cross-parsing would silently corrupt Maintenance.
        val foreign = """{"type":"riffle:FileHeader","deviceId":"x","label":"y","lastSyncedAt":"z"}"""
        assertNull(AnnotationDeviceMetaCodec.decode(foreign))
    }

    @Test
    fun `decode returns null on malformed input`() {
        assertNull(AnnotationDeviceMetaCodec.decode("not json {{"))
    }

    @Test
    fun `decode tolerates unknown fields (forward-compat)`() {
        val withExtra = """
            {"type":"riffle:DeviceSyncMeta","deviceId":"dev-1","label":"Phone A","lastSyncedAt":"2026-06-27T12:00:00Z","futureField":42}
        """.trimIndent()
        val decoded = AnnotationDeviceMetaCodec.decode(withExtra)!!
        assertEquals("dev-1", decoded.deviceId)
    }
}
