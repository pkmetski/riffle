package com.riffle.app.feature.reader

import com.riffle.feature.reader.splitSnippetForFiguresAt as splitSnippetForFiguresAtShared

/** Android-side thin wrapper over the shared [splitSnippetForFiguresAtShared]. */
internal fun splitSnippetForFiguresAt(snippet: String, offsets: List<Long?>): List<String> =
    splitSnippetForFiguresAtShared(snippet, offsets)
