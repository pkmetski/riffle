package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDeviceLabelResolverTest {

    @Test
    fun `composeBuildLabel drops manufacturer when MODEL already starts with it (case-insensitive)`() {
        assertEquals(
            "Samsung Galaxy S24",
            AndroidDeviceLabelResolver.composeBuildLabel("Samsung", "Samsung Galaxy S24"),
        )
        assertEquals(
            "samsung galaxy s24",
            AndroidDeviceLabelResolver.composeBuildLabel("Samsung", "samsung galaxy s24"),
        )
    }

    @Test
    fun `composeBuildLabel prepends manufacturer when MODEL does not start with it`() {
        assertEquals(
            "Google Pixel 9 Pro",
            AndroidDeviceLabelResolver.composeBuildLabel("Google", "Pixel 9 Pro"),
        )
        assertEquals(
            "Samsung SM-G991B",
            AndroidDeviceLabelResolver.composeBuildLabel("Samsung", "SM-G991B"),
        )
    }

    @Test
    fun `composeBuildLabel handles missing manufacturer`() {
        assertEquals("SM-G991B", AndroidDeviceLabelResolver.composeBuildLabel("", "SM-G991B"))
        assertEquals("SM-G991B", AndroidDeviceLabelResolver.composeBuildLabel(null, "SM-G991B"))
    }

    @Test
    fun `composeBuildLabel handles missing model`() {
        assertEquals("Google", AndroidDeviceLabelResolver.composeBuildLabel("Google", ""))
        assertEquals("Google", AndroidDeviceLabelResolver.composeBuildLabel("Google", null))
    }
}
