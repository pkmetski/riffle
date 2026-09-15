package com.riffle.feature.reader

/**
 * Heuristic that interleaves a highlight's [textSnippet] with its embedded figures for the
 * annotations panel's inline-figure rendering (fix #2, 2026-07-09).
 *
 * Both functions are pure string operations — no Android or Readium dependency — and live here
 * so iOS can reuse the same split logic in its annotations panel.
 */

fun splitSnippetForFiguresAt(snippet: String, offsets: List<Long?>): List<String> {
    if (offsets.isEmpty()) return listOf(snippet)
    if (offsets.all { it == null }) return splitSnippetForFigures(snippet, offsets.size)
    val chunks = mutableListOf<String>()
    var cursor = 0
    val maxLen = snippet.length
    var lastOffset = 0
    for (offset in offsets) {
        val clamped = when (offset) {
            null -> maxLen
            else -> offset.toInt().coerceIn(lastOffset, maxLen)
        }
        chunks += snippet.substring(cursor, clamped)
        cursor = clamped
        lastOffset = clamped
    }
    chunks += snippet.substring(cursor, maxLen)
    return chunks
}

fun splitSnippetForFigures(snippet: String, figureCount: Int): List<String> {
    if (figureCount <= 0) return listOf(snippet)
    val parts = snippet.split('\n').filter { it.isNotBlank() }
    return when {
        parts.size >= figureCount + 1 -> {
            // More paragraphs than needed splits — merge the trailing extras into the last chunk.
            parts.take(figureCount) + listOf(parts.drop(figureCount).joinToString("\n"))
        }
        parts.isEmpty() -> listOf(snippet) + List(figureCount) { "" }
        else -> parts + List(figureCount + 1 - parts.size) { "" }
    }
}
