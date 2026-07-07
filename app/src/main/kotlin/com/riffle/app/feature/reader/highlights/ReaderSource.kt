package com.riffle.app.feature.reader.highlights

/**
 * Which content the reader is currently displaying.
 *
 * [FullBook] is the ordinary reading experience backed by the book's own EPUB container.
 * [Highlights] is the elided reader (ADR 0041): a synthesised [org.readium.r2.shared.publication.Publication]
 * built by [HighlightsPublicationFactory] containing only the chapters that have at least one
 * highlight, each rendered down to its highlighted passages and notes.
 */
enum class ReaderSource { FullBook, Highlights }
