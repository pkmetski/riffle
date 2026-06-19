package com.riffle.app.feature.reader.readaloud

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On-device check (ADR 0028) that the shared data source routes by URI scheme: `zipaudio://` reads
 * out of the on-disk bundle (the unchanged bundle path), while an `http` URI with no streaming
 * context fails cleanly rather than misrouting. Validates the dispatch the player now relies on.
 */
@RunWith(AndroidJUnit4::class)
class ReadaloudDataSourceRoutingTest {

    @After
    fun tearDown() {
        SharedBundle.current = null
        SharedBundle.streaming = null
    }

    @Test
    fun zipaudio_uri_reads_from_the_bundle() {
        val audio = "hello-readaloud-audio".toByteArray()
        val bundle = File.createTempFile("bundle", ".epub").apply {
            ZipOutputStream(outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("Audio/x.mp3")); zos.write(audio); zos.closeEntry()
            }
        }
        SharedBundle.current = bundle
        SharedBundle.streaming = null

        val ds = SharedBundle.dataSourceFactory().createDataSource()
        ds.open(DataSpec.Builder().setUri(ZipAudioDataSource.uriFor("Audio/x.mp3")).build())
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val r = ds.read(buf, 0, buf.size)
            if (r == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, r)
        }
        ds.close()
        bundle.delete()

        assertArrayEquals(audio, out.toByteArray())
    }

    @Test
    fun http_uri_without_streaming_context_fails_cleanly() {
        SharedBundle.current = null
        SharedBundle.streaming = null
        val ds = SharedBundle.dataSourceFactory().createDataSource()
        try {
            ds.open(DataSpec.Builder().setUri(Uri.parse("http://example/track")).build())
            fail("expected IOException when no streaming source is set")
        } catch (e: IOException) {
            // expected
        }
    }
}
