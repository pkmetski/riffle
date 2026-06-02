package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SmilOverlayParserTest {

    @Test
    fun `parses a single par into one clip`() {
        val smil = """
            <?xml version="1.0" encoding="UTF-8"?>
            <smil xmlns="http://www.w3.org/ns/SMIL"
                  xmlns:epub="http://www.idpf.org/2007/ops" version="3.0">
              <body>
                <par id="p1">
                  <text src="chapter1.xhtml#sent1"/>
                  <audio src="audio/chapter1.mp3" clipBegin="0:00:00.000" clipEnd="0:00:03.500"/>
                </par>
              </body>
            </smil>
        """.trimIndent()

        val clips = SmilOverlayParser.parse(smil)

        assertEquals(
            listOf(
                MediaOverlayClip(
                    textFragmentRef = "chapter1.xhtml#sent1",
                    audioSrc = "audio/chapter1.mp3",
                    clipBeginSec = 0.0,
                    clipEndSec = 3.5,
                ),
            ),
            clips,
        )
    }

    @Test
    fun `preserves document order across multiple pars in a seq`() {
        val smil = """
            <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
              <body>
                <seq epub:textref="c1.xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <par><text src="c1.xhtml#s1"/><audio src="c1.mp3" clipBegin="0s" clipEnd="2s"/></par>
                  <par><text src="c1.xhtml#s2"/><audio src="c1.mp3" clipBegin="2s" clipEnd="5s"/></par>
                  <par><text src="c1.xhtml#s3"/><audio src="c1.mp3" clipBegin="5s" clipEnd="9s"/></par>
                </seq>
              </body>
            </smil>
        """.trimIndent()

        val refs = SmilOverlayParser.parse(smil).map { it.textFragmentRef }

        assertEquals(listOf("c1.xhtml#s1", "c1.xhtml#s2", "c1.xhtml#s3"), refs)
    }

    @Test
    fun `parses the range of SMIL clock-value syntaxes`() {
        val smil = """
            <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
              <body>
                <par><text src="a#1"/><audio src="a.mp3" clipBegin="0:00:12.500" clipEnd="0:01:00"/></par>
                <par><text src="a#2"/><audio src="a.mp3" clipBegin="90.25" clipEnd="2min"/></par>
                <par><text src="a#3"/><audio src="a.mp3" clipBegin="300ms" clipEnd="1.5h"/></par>
              </body>
            </smil>
        """.trimIndent()

        val clips = SmilOverlayParser.parse(smil)

        assertEquals(12.5, clips[0].clipBeginSec, 0.0001)
        assertEquals(60.0, clips[0].clipEndSec, 0.0001)
        assertEquals(90.25, clips[1].clipBeginSec, 0.0001)
        assertEquals(120.0, clips[1].clipEndSec, 0.0001)
        assertEquals(0.3, clips[2].clipBeginSec, 0.0001)
        assertEquals(5400.0, clips[2].clipEndSec, 0.0001)
    }

    @Test
    fun `returns empty list for a SMIL with no pars`() {
        val smil = """
            <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body><seq/></body></smil>
        """.trimIndent()

        assertEquals(emptyList<MediaOverlayClip>(), SmilOverlayParser.parse(smil))
    }
}
