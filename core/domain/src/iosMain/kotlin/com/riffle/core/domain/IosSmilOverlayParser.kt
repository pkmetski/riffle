package com.riffle.core.domain

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser

/**
 * iOS port of jvmMain's [SmilOverlayParser] (ADR 0027). Same contract — pure SMIL XML in,
 * [MediaOverlayClip]s out in document order, which is the playback order the rest of the
 * readaloud machinery relies on.
 *
 * The JVM version uses `javax.xml.parsers.DocumentBuilderFactory`, which has no Kotlin/Native
 * equivalent; this uses ksoup's XML parser (already a core:domain iOS dependency for the CFI
 * translator). Clip timing goes through the shared [parseSmilClockValue] so the two parsers
 * cannot drift on clock arithmetic — a divergence there would desynchronise the highlight.
 */
object IosSmilOverlayParser {

    fun parse(smilXml: String): List<MediaOverlayClip> {
        val doc = Ksoup.parse(html = smilXml, parser = Parser.xmlParser())
        val clips = mutableListOf<MediaOverlayClip>()

        for (par in doc.getElementsByTag("par")) {
            // Direct children only, matching the JVM parser's firstChildElement().
            val text = par.children().firstOrNull { it.tagName().substringAfter(':') == "text" } ?: continue
            val audio = par.children().firstOrNull { it.tagName().substringAfter(':') == "audio" } ?: continue
            val textRef = text.attr("src").takeIf { it.isNotEmpty() } ?: continue
            val audioSrc = audio.attr("src").takeIf { it.isNotEmpty() } ?: continue
            clips += MediaOverlayClip(
                textFragmentRef = textRef,
                audioSrc = audioSrc,
                clipBeginSec = parseSmilClockValue(audio.attr("clipBegin")),
                clipEndSec = parseSmilClockValue(audio.attr("clipEnd")),
            )
        }
        return clips
    }
}
