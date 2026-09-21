package com.riffle.app

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android half of "Open in Riffle".
 *
 * Everything after the URI is resolved is shared Kotlin (`SharedOpenInImporter`, covered by
 * `OpenInImporterTest` on both platforms). What only Android does — and therefore what only this
 * test can pin — is picking the URI out of the intent and getting a usable file *name* out of a
 * content provider. The iOS counterpart is `OpenInDocumentStagingTests` in `iosAppUnitTests`,
 * which pins the equivalent Swift step.
 *
 * Neither platform had any of this before: the manifest carried a single LAUNCHER intent-filter.
 */
@RunWith(AndroidJUnit4::class)
class OpenInIntentRoutingTest {

    @Test
    fun anActionViewIntentCarriesTheBookInItsData() {
        val uri = Uri.parse("content://provider/book.epub")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        assertEquals(uri, incomingBookUri(intent))
    }

    @Test
    fun aShareSheetIntentCarriesTheBookInExtraStream() {
        // ACTION_SEND leaves `data` null; reading only `intent.data` would silently ignore every
        // share from a mail client.
        val uri = Uri.parse("content://provider/book.epub")
        val intent = Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uri) }
        assertNull(intent.data)
        assertEquals(uri, incomingBookUri(intent))
    }

    @Test
    fun anOrdinaryLaunchIntentIsNotAnImport() {
        assertNull(incomingBookUri(Intent(Intent.ACTION_MAIN)))
        assertNull(incomingBookUri(Intent(MainActivity.ACTION_OPEN_NOW_PLAYING)))
        assertNull(incomingBookUri(null))
    }

    @Test
    fun theDisplayNameFallsBackToTheLastPathSegmentWhenTheProviderHasNoName() {
        // A provider that answers no DISPLAY_NAME still has to yield something with an
        // extension, because the extension is what decides whether the file is accepted at all.
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        assertEquals("book.epub", resolver.displayNameFor(Uri.parse("file:///tmp/book.epub")))
    }

    @Test
    fun theDisplayNameIsNeverBlank() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        assertEquals("book", resolver.displayNameFor(Uri.parse("content://provider/")))
    }
}
