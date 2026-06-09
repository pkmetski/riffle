package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadaloudTrackTest {

    private val clips = listOf(
        MediaOverlayClip("c1#s1", "c1.mp3", 0.0, 2.0),
        MediaOverlayClip("c1#s2", "c1.mp3", 2.0, 5.0),
        MediaOverlayClip("c1#s3", "c1.mp3", 5.0, 9.0),
        MediaOverlayClip("c2#s1", "c2.mp3", 0.0, 4.0),
    )
    private val track = ReadaloudTrack(clips)

    @Test
    fun `active clip is the one whose half-open range contains the position`() {
        assertEquals(clips[1], track.activeClipAt("c1.mp3", 2.0))
        assertEquals(clips[1], track.activeClipAt("c1.mp3", 4.999))
        assertEquals(clips[2], track.activeClipAt("c1.mp3", 5.0))
    }

    @Test
    fun `active clip is scoped to the matching audio file`() {
        assertEquals(clips[3], track.activeClipAt("c2.mp3", 1.0))
    }

    @Test
    fun `no active clip when position falls outside every range`() {
        assertNull(track.activeClipAt("c1.mp3", 100.0))
        assertNull(track.activeClipAt("missing.mp3", 1.0))
    }

    @Test
    fun `clipForFragment finds the entry point for play-from-here`() {
        assertEquals(clips[2], track.clipForFragment("c1#s3"))
        assertNull(track.clipForFragment("nope#x"))
    }

    private val chapterClips = listOf(
        MediaOverlayClip("text/c1.html#s1", "c1.mp3", 0.0, 2.0),
        MediaOverlayClip("text/c1.html#s2", "c1.mp3", 2.0, 5.0),
        MediaOverlayClip("text/c2.html#s1", "c2.mp3", 0.0, 4.0),
    )
    private val chapterTrack = ReadaloudTrack(chapterClips)

    @Test
    fun `resolveStartClip prefers an exact fragment match`() {
        assertEquals(chapterClips[1], chapterTrack.resolveStartClip("text/c1.html", "s2"))
    }

    @Test
    fun `resolveStartClip ignores leading slash differences`() {
        assertEquals(chapterClips[1], chapterTrack.resolveStartClip("/text/c1.html", "s2"))
    }

    // Regression for "Play from here started at the beginning of the chapter": when the selection's
    // sentence-span id is supplied, narration must begin at THAT sentence's clip — not silently fall
    // back to the chapter's first clip. (The fix that surfaces the span id lives in EpubReaderScreen;
    // this locks the resolver contract that fix depends on.)
    @Test
    fun `resolveStartClip on a mid-chapter sentence starts there, not at the chapter's first clip`() {
        val resolved = chapterTrack.resolveStartClip("text/c1.html", "s2")
        assertEquals(chapterClips[1], resolved)
        assertNotEquals(chapterClips[0], resolved)
    }

    @Test
    fun `resolveStartClip falls back to first clip of the chapter when fragment is absent or unmatched`() {
        assertEquals(chapterClips[0], chapterTrack.resolveStartClip("text/c1.html", null))
        assertEquals(chapterClips[2], chapterTrack.resolveStartClip("text/c2.html", "unknown"))
    }

    @Test
    fun `resolveStartClip returns null for an unknown chapter`() {
        assertEquals(null, chapterTrack.resolveStartClip("text/c9.html", null))
    }

    // A matched-ABS book renders the publisher's ABS EPUB (ADR 0026), whose chapter hrefs differ from
    // the Storyteller bundle the SMIL clips come from — here the rendered href ("xhtml/chapter1.xhtml")
    // shares nothing with the clips' "OEBPS/text/part0001.xhtml". But Storyteller's sentence-span ids
    // are unique within a book, and that span id is exactly what "Play from here" / the page-top probe
    // resolves. So resolveStartClip must find the clip by the bare span id when the href portion can't
    // match. Regression for "Play from here / session start jumps to the chapter start on matched-ABS
    // books".
    private val bundleHrefClips = listOf(
        MediaOverlayClip("OEBPS/text/part0001.xhtml#c001-s0", "a.mp3", 0.0, 2.0),
        MediaOverlayClip("OEBPS/text/part0001.xhtml#c001-s1", "a.mp3", 2.0, 5.0),
        MediaOverlayClip("OEBPS/text/part0002.xhtml#c002-s0", "b.mp3", 5.0, 9.0),
    )
    private val bundleHrefTrack = ReadaloudTrack(bundleHrefClips)

    @Test
    fun `resolveStartClip matches by bare span id when the rendered href differs from the bundle href`() {
        assertEquals(bundleHrefClips[1], bundleHrefTrack.resolveStartClip("xhtml/chapter1.xhtml", "c001-s1"))
        assertEquals(bundleHrefClips[2], bundleHrefTrack.resolveStartClip("xhtml/chapter2.xhtml", "c002-s0"))
    }

    @Test
    fun `resolveStartClip still prefers the exact href#id match over a bare-id match`() {
        // Two clips could share a span id across chapters only by collision; the exact href#id match
        // must win when the rendered href DOES line up with the bundle, so a matching book is unaffected.
        assertEquals(chapterClips[1], chapterTrack.resolveStartClip("text/c1.html", "s2"))
    }

    // Storyteller sentence-span ids are per-document and recur across chapters (the split fixture below
    // reuses "s1" in four chapters). On a matched-ABS book the rendered ABS href never equals the bundle
    // href, so resolveStartClip can't use the exact match — and a plain bare-id match returns the
    // EARLIEST clip carrying that id, jumping "Play from here" to an identically-id'd sentence in an
    // earlier chapter ("play-from-here reset my progress"). Given the reader's chapter (the caller maps
    // the ABS href to the bundle href via ReaderPositionBridge), it must resolve to THAT chapter's clip.
    private val collidingIdClips = listOf(
        MediaOverlayClip("OEBPS/text/part0003.xhtml#s5", "a.mp3", 0.0, 2.0),    // earlier chapter (e.g. SOL 34)
        MediaOverlayClip("OEBPS/text/part0050.xhtml#s5", "z.mp3", 900.0, 903.0), // the chapter the reader is on
    )
    private val collidingIdTrack = ReadaloudTrack(collidingIdClips)

    @Test
    fun `resolveStartClip with a span id shared across chapters resolves to the reader's chapter`() {
        // The bundle href the bridge supplies is OPF-relative ("text/…"); the clips' hrefs are full
        // zip paths ("OEBPS/text/…"). The chapter match must tolerate that scheme difference and still
        // pick the reader's chapter rather than the first book-wide occurrence of the id.
        assertEquals(collidingIdClips[1], collidingIdTrack.resolveStartClip("text/part0050.xhtml", "s5"))
        assertEquals(collidingIdClips[0], collidingIdTrack.resolveStartClip("text/part0003.xhtml", "s5"))
    }

    @Test
    fun `resolveStartClip falls back to a bare-id match when the chapter href cannot be matched`() {
        // Degrade gracefully: when the target href shares nothing with the bundle, still resolve a
        // uniquely-id'd sentence by its bare id so a book whose hrefs don't line up keeps working.
        assertEquals(bundleHrefClips[2], bundleHrefTrack.resolveStartClip("xhtml/chapter2.xhtml", "c002-s0"))
    }

    // Storyteller splits each chapter into an un-narrated heading page (…_split_000) plus narrated
    // body pages (…_split_001+). Navigating via the TOC lands the reader on the heading page, which
    // has no Media Overlay; starting narration must jump forward to the chapter's first narrated
    // clip — NOT fall back to the start of the book.
    private val splitClips = listOf(
        MediaOverlayClip("text/part0006_split_001.html#s1", "a.mp3", 0.0, 2.0),
        MediaOverlayClip("text/part0010_split_001.html#s1", "a.mp3", 100.0, 102.0),
        MediaOverlayClip("text/part0010_split_002.html#s1", "a.mp3", 102.0, 104.0),
        MediaOverlayClip("text/part0011_split_000.html#s1", "a.mp3", 200.0, 202.0),
    )
    private val splitTrack = ReadaloudTrack(splitClips)

    @Test
    fun `resolveStartClip on an un-narrated heading page jumps to the next narrated clip`() {
        assertEquals(splitClips[1], splitTrack.resolveStartClip("text/part0010_split_000.html", null))
    }

    @Test
    fun `resolveStartClip past all narrated content returns null`() {
        assertEquals(null, splitTrack.resolveStartClip("text/part0099_split_000.html", null))
    }
}
