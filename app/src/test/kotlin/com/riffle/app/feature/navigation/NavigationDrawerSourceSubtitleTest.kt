package com.riffle.app.feature.navigation

import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import org.junit.Assert.assertEquals
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
