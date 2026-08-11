package com.riffle.app.feature.navigation

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationDrawerSourceSubtitleTest {

    @Test
    fun `Komga switcher subtitle shows configured address`() {
        val source = source(
            type = SourceType.KOMGA,
            url = "https://komga.example.com/books",
        )

        assertEquals("komga.example.com", sourceSwitcherSubtitle(source, version = null))
    }

    @Test
    fun `every network-host source switcher subtitle includes configured address`() {
        WebSourceDescriptors.all
            .filter { it.hasNetworkHost }
            .forEach { descriptor ->
                val source = source(
                    type = descriptor.type,
                    url = "https://${descriptor.type.name.lowercase()}.example.com/root",
                )

                assertEquals(
                    "${descriptor.type} must show its configured address in the source switcher",
                    source.url.authority(),
                    sourceSwitcherSubtitle(source, version = null),
                )
            }
    }

    @Test
    fun `network-host source switcher subtitle keeps version after address`() {
        val source = source(type = SourceType.ABS, url = "https://abs.example.com")

        assertEquals("abs.example.com · v2.21.0", sourceSwitcherSubtitle(source, version = "2.21.0"))
    }

    @Test
    fun `source switcher text does not shrink when it already fits`() {
        assertNull(
            nextOverflowFontSize(
                currentSize = 16.sp,
                minFontSize = 12.sp,
                hasVisualOverflow = false,
            ),
        )
    }

    @Test
    fun `source switcher text shrinks only while overflowing`() {
        assertEquals(
            14.4f.sp,
            nextOverflowFontSize(
                currentSize = 16.sp,
                minFontSize = 12.sp,
                hasVisualOverflow = true,
            ),
        )
    }

    @Test
    fun `source switcher text never shrinks below minimum`() {
        assertEquals(
            12.sp,
            nextOverflowFontSize(
                currentSize = 12.5f.sp,
                minFontSize = 12.sp,
                hasVisualOverflow = true,
            ),
        )
        assertNull(
            nextOverflowFontSize(
                currentSize = 12.sp,
                minFontSize = 12.sp,
                hasVisualOverflow = true,
            ),
        )
    }

    @Test
    fun `source switcher text ignores unspecified font size`() {
        assertNull(
            nextOverflowFontSize(
                currentSize = TextUnit.Unspecified,
                minFontSize = 12.sp,
                hasVisualOverflow = true,
            ),
        )
    }

    private fun source(
        type: SourceType,
        url: String,
    ) = Source(
        id = type.name.lowercase(),
        url = SourceUrl.parse(url) ?: error("invalid test URL: $url"),
        isActive = false,
        insecureConnectionAllowed = false,
        username = "",
        type = type,
    )
}
