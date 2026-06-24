package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncConfig
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavAnnotationSyncTargetFactoryTest {

    private val factory = WebDavAnnotationSyncTargetFactory(OkHttpClient())

    @Test
    fun `valid config produces a target`() {
        val target = factory.create(
            AnnotationSyncConfig(
                baseUrl = "https://dav.example.org/remote.php/dav/files/me/annotations",
                username = "u",
                password = "p",
            ),
        )

        assertNotNull(target)
    }

    @Test
    fun `malformed base URL yields null`() {
        val target = factory.create(
            AnnotationSyncConfig(
                baseUrl = "::not a url::",
                username = "u",
                password = "p",
            ),
        )

        assertNull(target)
    }

    @Test
    fun `empty base URL yields null`() {
        val target = factory.create(
            AnnotationSyncConfig(baseUrl = "", username = "u", password = "p"),
        )

        assertNull(target)
    }
}
