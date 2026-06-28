package com.riffle.core.pdfium.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the parasitic JNI bridge end-to-end:
 *
 * 1. `dlopen("libmodpdfium.so", RTLD_NOLOAD)` returns a non-null handle —
 *    proves the .so loaded by barteksc's PdfiumCore is reachable from our
 *    shared library.
 * 2. Every `FPDFText_*` symbol we expect resolves via `dlsym`.
 * 3. We can open a fixture PDF, walk to a page, open its text page, and call
 *    each new API without crashing.
 *
 * Doesn't assert specific char content (the fixture is minimal). A richer
 * fixture and content-level assertions land with the feature work.
 */
@RunWith(AndroidJUnit4::class)
class PdfiumTextApiSmokeTest {

    private lateinit var fixturePath: String
    private var docPtr: Long = 0L
    private var pagePtr: Long = 0L
    private var textPagePtr: Long = 0L

    @Before
    fun copyFixtureToDisk() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val target = File(ctx.cacheDir, "pdfium_text_smoke_fixture.pdf")
        ctx.assets.open("test.pdf").use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        fixturePath = target.absolutePath
    }

    @After
    fun cleanup() {
        if (textPagePtr != 0L) PdfiumTextApi.closeTextPage(textPagePtr)
        if (pagePtr != 0L) PdfiumTextApi.closePage(pagePtr)
        if (docPtr != 0L) PdfiumTextApi.closeDocument(docPtr)
    }

    @Test
    fun symbol_resolution_succeeds() {
        assertTrue(
            "ensureResolved must return true — libmodpdfium.so should be loaded " +
                "by PdfiumCore.<clinit> and dlsym should find every FPDFText_* symbol",
            PdfiumTextApi.ensureResolved(),
        )
    }

    @Test
    fun document_lifecycle_round_trip() {
        docPtr = PdfiumTextApi.openDocument(fixturePath)
        assertNotEquals("openDocument returned 0", 0L, docPtr)

        val pageCount = PdfiumTextApi.getPageCount(docPtr)
        assertTrue("getPageCount must be >= 1, was $pageCount", pageCount >= 1)
    }

    @Test
    fun page_and_text_page_open() {
        docPtr = PdfiumTextApi.openDocument(fixturePath)
        assertNotEquals(0L, docPtr)

        pagePtr = PdfiumTextApi.openPage(docPtr, 0)
        assertNotEquals("openPage returned 0 for page 0", 0L, pagePtr)

        val width = PdfiumTextApi.getPageWidth(pagePtr)
        val height = PdfiumTextApi.getPageHeight(pagePtr)
        assertTrue("page width must be positive, was $width", width > 0.0)
        assertTrue("page height must be positive, was $height", height > 0.0)

        textPagePtr = PdfiumTextApi.openTextPage(pagePtr)
        assertNotEquals("openTextPage returned 0", 0L, textPagePtr)
    }

    @Test
    fun char_count_and_text_apis_callable() {
        docPtr = PdfiumTextApi.openDocument(fixturePath)
        pagePtr = PdfiumTextApi.openPage(docPtr, 0)
        textPagePtr = PdfiumTextApi.openTextPage(pagePtr)

        // We don't assert a specific count — the fixture may have 0 or more
        // chars depending on what test.pdf contains. We only assert that the
        // API call returns and the bounded-text query handles the edge case.
        val count = PdfiumTextApi.countChars(textPagePtr)
        assertTrue("countChars must be >= 0, was $count", count >= 0)

        val text = PdfiumTextApi.getText(textPagePtr, 0, count)
        assertNotNull(text)
        assertEquals("getText length should equal countChars", count, text.length)

        // Rect / char-box APIs must be callable without crashing even on
        // empty ranges. If the fixture is non-empty, exercise one.
        if (count > 0) {
            val box = PdfiumTextApi.getCharBox(textPagePtr, 0)
            assertNotNull("getCharBox on char 0 should return non-null", box)
            val rects = PdfiumTextApi.rectsForRange(textPagePtr, 0, count)
            assertFalse("rectsForRange should produce ≥1 rect", rects.isEmpty())
        }
    }
}
