package com.riffle.core.domain

/**
 * A narrated sentence located as a Readium text quote: the sentence itself ([highlight]) plus a
 * little surrounding prose ([before]/[after]) to disambiguate when the same words recur on a page.
 */
data class SentenceQuote(
    val before: String,
    val highlight: String,
    val after: String,
)
