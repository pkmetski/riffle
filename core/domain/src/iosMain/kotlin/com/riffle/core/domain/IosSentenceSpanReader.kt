package com.riffle.core.domain

import com.fleeksoft.ksoup.Ksoup

/** ksoup-backed [SentenceSpanReader] for the iOS readaloud path. */
object IosSentenceSpanReader : SentenceSpanReader {
    override fun read(html: String): List<SentenceSpan> {
        val doc = try { Ksoup.parse(html) } catch (_: Exception) { return emptyList() }
        return doc.select("span[id]").map { SentenceSpan(id = it.id(), text = it.text()) }
    }
}
