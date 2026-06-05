package com.riffle.app.feature.reader

// The resolved body of a footnote: the visible text plus the spans within it
// that should render as tappable links. [links] is empty for plain-text notes.
data class FootnoteContent(
    val text: String,
    val links: List<FootnoteLink> = emptyList(),
)

// A clickable span within a [FootnoteContent.text]. [end] is exclusive.
data class FootnoteLink(
    val start: Int,
    val end: Int,
    val url: String,
)

data class FootnotePopupState(
    val content: FootnoteContent,
)
