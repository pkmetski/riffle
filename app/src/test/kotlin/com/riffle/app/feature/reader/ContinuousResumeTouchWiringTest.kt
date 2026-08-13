package com.riffle.app.feature.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the sleep-resume scroll-bounce bug in Continuous mode.
 *
 * The behavioral state machine is covered by [com.riffle.app.feature.reader.session.ResumeRestorerTest]:
 * after [com.riffle.app.feature.reader.session.ResumeRestorer.onUserInteracted], a backward locator
 * must not be re-fired forward. This test pins the production wiring that Continuous used to miss:
 * the visible [ContinuousReaderView] must emit the same ACTION_DOWN user-touch signal as the
 * Readium-backed [ScrollBoundaryNavigationContainer].
 */
class ContinuousResumeTouchWiringTest {

    @Test
    fun `ContinuousReaderView disarms resume restore on touch down`() {
        val source = resolveSource("ContinuousReaderView.kt").readText()

        assertTrue(
            "ContinuousReaderView must expose an onUserTouch callback",
            source.contains("var onUserTouch: (() -> Unit)? = null"),
        )
        assertTrue(
            "ACTION_DOWN must invoke onUserTouch before backward-scroll locator emissions arrive",
            source.contains("MotionEvent.ACTION_DOWN -> onUserTouch?.invoke()"),
        )
    }

    @Test
    fun `EpubReaderScreen wires continuous touch to ViewModel user interaction`() {
        val source = resolveSource("EpubReaderScreen.kt").readText()

        assertTrue(
            "Continuous AndroidView factory must wire onUserTouch to onUserInteracted",
            source.contains("view.onUserTouch = { onUserInteracted() }"),
        )
        assertTrue(
            "Continuous AndroidView update must refresh onUserTouch for reused views",
            source.contains("it.onUserTouch = { onUserInteracted() }"),
        )
    }

    private fun resolveSource(name: String): File {
        val relative = "src/main/kotlin/com/riffle/app/feature/reader/$name"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() } ?: error(
            "$name not found. Tried: ${candidates.map { it.absolutePath }}",
        )
    }
}
