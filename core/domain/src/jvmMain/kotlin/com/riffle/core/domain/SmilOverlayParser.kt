package com.riffle.core.domain

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

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
                clipBeginSec = parseSmilClockValue(audio.getAttribute("clipBegin")),
                clipEndSec = parseSmilClockValue(audio.getAttribute("clipEnd")),
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

}
