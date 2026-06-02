package com.riffle.core.domain

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One `<par>` of an EPUB 3 Media Overlay (SMIL): a text fragment paired with the
 * slice of audio that narrates it. [clipBeginSec]/[clipEndSec] are normalised to
 * absolute seconds from any SMIL clock-value syntax.
 */
data class MediaOverlayClip(
    val textFragmentRef: String,
    val audioSrc: String,
    val clipBeginSec: Double,
    val clipEndSec: Double,
)

/**
 * Pure SMIL → [MediaOverlayClip] parser. No I/O — callers read the `.smil` entries out
 * of the EPUB bundle and hand the XML text in. Document order is preserved, which is the
 * playback order the rest of the readaloud machinery relies on.
 */
object SmilOverlayParser {

    fun parse(smilXml: String): List<MediaOverlayClip> {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(smilXml.toByteArray(Charsets.UTF_8)))

        val pars = doc.getElementsByTagName("par")
        val clips = ArrayList<MediaOverlayClip>(pars.length)
        for (i in 0 until pars.length) {
            val par = pars.item(i) as? Element ?: continue
            val text = par.firstChildElement("text") ?: continue
            val audio = par.firstChildElement("audio") ?: continue
            val textRef = text.getAttribute("src").takeIf { it.isNotEmpty() } ?: continue
            val audioSrc = audio.getAttribute("src").takeIf { it.isNotEmpty() } ?: continue
            clips += MediaOverlayClip(
                textFragmentRef = textRef,
                audioSrc = audioSrc,
                clipBeginSec = parseClockValue(audio.getAttribute("clipBegin")),
                clipEndSec = parseClockValue(audio.getAttribute("clipEnd")),
            )
        }
        return clips
    }

    private fun Element.firstChildElement(localName: String): Element? {
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as Element
                if (el.tagName == localName || el.localName == localName) return el
            }
        }
        return null
    }

    /**
     * SMIL clock values come in several shapes:
     *   - full clock: `1:02:03.500` (h:mm:ss) or `02:03.5` (mm:ss)
     *   - timecount with unit: `12.5s`, `300ms`, `1.5min`, `2h`
     *   - bare seconds: `12.5`
     * Returns 0.0 for blank/unparseable input — a missing clip bound degrades to "no offset"
     * rather than crashing playback.
     */
    private fun parseClockValue(raw: String): Double {
        val v = raw.trim()
        if (v.isEmpty()) return 0.0

        if (v.contains(':')) {
            val parts = v.split(':')
            var seconds = 0.0
            for (part in parts) {
                seconds = seconds * 60.0 + (part.toDoubleOrNull() ?: return 0.0)
            }
            return seconds
        }

        return when {
            v.endsWith("ms") -> v.dropLast(2).toDoubleOrNull()?.div(1000.0) ?: 0.0
            v.endsWith("min") -> v.dropLast(3).toDoubleOrNull()?.times(60.0) ?: 0.0
            v.endsWith("h") -> v.dropLast(1).toDoubleOrNull()?.times(3600.0) ?: 0.0
            v.endsWith("s") -> v.dropLast(1).toDoubleOrNull() ?: 0.0
            else -> v.toDoubleOrNull() ?: 0.0
        }
    }
}
