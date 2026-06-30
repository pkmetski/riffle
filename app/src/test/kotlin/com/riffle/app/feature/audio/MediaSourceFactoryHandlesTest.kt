package com.riffle.app.feature.audio

import com.riffle.core.logging.RecordingLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each `MediaSourceFactory` is keyed by URI scheme — adding a new audio backend is just a new
 * factory + scheme. Locks the dispatch contract for the three production factories so a typo
 * (`"http://"` vs `"http"`) doesn't silently route an item to the wrong source. See issue #333.
 */
class MediaSourceFactoryHandlesTest {

    @Test
    fun `http factory handles http and https`() {
        val f = HttpAudioSourceFactory()
        assertTrue(f.handles("http"))
        assertTrue(f.handles("https"))
        assertFalse(f.handles("file"))
        assertFalse(f.handles("zipaudio"))
        assertFalse(f.handles(null))
    }

    @Test
    fun `file factory handles file only`() {
        val f = FileAudioSourceFactory()
        assertTrue(f.handles("file"))
        assertFalse(f.handles("http"))
        assertFalse(f.handles("https"))
        assertFalse(f.handles("zipaudio"))
        assertFalse(f.handles(null))
    }

    @Test
    fun `bundle factory handles zipaudio only`() {
        val f = BundleAudioSourceFactory(RecordingLogger())
        assertTrue(f.handles("zipaudio"))
        assertFalse(f.handles("http"))
        assertFalse(f.handles("https"))
        assertFalse(f.handles("file"))
        assertFalse(f.handles(null))
    }
}
