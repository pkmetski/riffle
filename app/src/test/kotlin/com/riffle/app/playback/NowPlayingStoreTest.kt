package com.riffle.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingStoreTest {

    @Test
    fun `starts empty`() {
        assertNull(NowPlayingStore().current)
    }

    @Test
    fun `set records the active session`() {
        val store = NowPlayingStore()
        store.set(NowPlaying.Audiobook("item-1"))
        assertEquals(NowPlaying.Audiobook("item-1"), store.current)
    }

    @Test
    fun `set overwrites the previous session`() {
        val store = NowPlayingStore()
        store.set(NowPlaying.Audiobook("item-1"))
        store.set(NowPlaying.Readaloud("item-2"))
        assertEquals(NowPlaying.Readaloud("item-2"), store.current)
    }

    @Test
    fun `clearIf clears when the predicate matches`() {
        val store = NowPlayingStore()
        store.set(NowPlaying.Audiobook("item-1"))
        store.clearIf { it is NowPlaying.Audiobook && it.itemId == "item-1" }
        assertNull(store.current)
    }

    @Test
    fun `clearIf leaves a non-matching session intact`() {
        val store = NowPlayingStore()
        store.set(NowPlaying.Audiobook("item-1"))
        // A readaloud teardown for a different item must not wipe the audiobook entry.
        store.clearIf { it is NowPlaying.Readaloud && it.itemId == "item-1" }
        assertEquals(NowPlaying.Audiobook("item-1"), store.current)
    }

    @Test
    fun `clearIf on an empty store is a no-op`() {
        val store = NowPlayingStore()
        store.clearIf { true }
        assertNull(store.current)
    }
}
