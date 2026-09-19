package com.riffle.core.domain

import org.jsoup.Jsoup

/** jsoup-backed [SentenceSpanReader] for the JVM/Android readaloud path. */
object JvmSentenceSpanReader : SentenceSpanReader {
    override fun read(html: String): List<SentenceSpan> {
        val doc = try { Jsoup.parse(html) } catch (_: Exception) { return emptyList() }
        return doc.select("span[id]").map { SentenceSpan(id = it.id(), text = it.text()) }
    }
}
