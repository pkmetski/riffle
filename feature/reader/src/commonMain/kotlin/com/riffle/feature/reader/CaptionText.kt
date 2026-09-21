package com.riffle.feature.reader

/**
 * Caption-identity normalizer — squashes internal whitespace runs so different renderers of
 * "the same" caption (`textSnippet` captured with newlines, a DOM `text()` walk that collapsed
 * them) compare equal.
 *
 * Shared because both the figure-border decoration builder and Android's caption-highlight
 * dedup pass key on it, and the two platforms must agree on what counts as the same caption or
 * the same figure grows a duplicate annotation on one device and not the other.
 */
fun normalizeCaptionText(text: String): String = text.replace(WHITESPACE_RUN, " ").trim()

private val WHITESPACE_RUN = Regex("\\s+")
