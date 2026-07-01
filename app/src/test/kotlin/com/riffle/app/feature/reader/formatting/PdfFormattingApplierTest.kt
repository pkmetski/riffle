package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.logging.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeOps : PdfWebViewOps {
    val evals = mutableListOf<String>()
    val scrolls = mutableListOf<Pair<Int, Int>>()
    override fun evaluateJavascript(js: String) { evals += js }
    override fun scrollByPx(dx: Int, dy: Int) { scrolls += (dx to dy) }
}

class PdfFormattingApplierTest {
    @Test
    fun `apply dispatches css to webview`() {
        val ops = FakeOps()
        val applier = PdfFormattingApplier(ops, RecordingLogger())
        applier.apply(FormattingPreferences())
        assertEquals(1, ops.evals.size)
        assertTrue(ops.evals[0].contains("--pdf-bg"))
    }

    @Test
    fun `applyScrollDelta scrolls webview vertically`() {
        val ops = FakeOps()
        val applier = PdfFormattingApplier(ops, RecordingLogger())
        applier.applyScrollDelta(12)
        assertEquals(listOf(0 to 12), ops.scrolls)
    }

    @Test
    fun `capabilities are PDF`() {
        val applier = PdfFormattingApplier(FakeOps(), RecordingLogger())
        assertEquals(RenderCapabilities.PDF, applier.capabilities)
    }
}
