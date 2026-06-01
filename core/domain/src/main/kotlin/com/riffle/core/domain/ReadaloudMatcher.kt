package com.riffle.core.domain

data class MatchableStorytellerBook(
    val uuid: String,
    val title: String,
    val author: String,
    val isbn: String? = null,
    val asin: String? = null,
)

data class MatchableAbsItem(
    val serverUuid: String,
    val libraryItemId: String,
    val title: String,
    val author: String,
    val isbn: String? = null,
    val asin: String? = null,
)

sealed interface MatchResult {
    data class Confirmed(val absServerUuid: String, val absLibraryItemId: String) : MatchResult
    data object Unmatched : MatchResult
}

object ReadaloudMatcher {

    fun match(book: MatchableStorytellerBook, candidates: List<MatchableAbsItem>): MatchResult {
        val identifierHits = candidates.filter { identifierMatches(book, it) }
        if (identifierHits.isNotEmpty()) {
            return identifierHits.singleOrNull()?.confirmed() ?: MatchResult.Unmatched
        }

        val bookTitle = normaliseTitle(book.title)
        val bookAuthor = normaliseAuthor(book.author)
        if (bookTitle.isEmpty() || bookAuthor.isEmpty()) return MatchResult.Unmatched

        val titleAuthorHits = candidates.filter {
            normaliseTitle(it.title) == bookTitle && normaliseAuthor(it.author) == bookAuthor
        }
        return titleAuthorHits.singleOrNull()?.confirmed() ?: MatchResult.Unmatched
    }

    private fun identifierMatches(book: MatchableStorytellerBook, abs: MatchableAbsItem): Boolean {
        val bookIsbn = normaliseIsbn(book.isbn)
        val absIsbn = normaliseIsbn(abs.isbn)
        if (bookIsbn != null && bookIsbn == absIsbn) return true
        val bookAsin = normaliseAsin(book.asin)
        val absAsin = normaliseAsin(abs.asin)
        if (bookAsin != null && bookAsin == absAsin) return true
        return false
    }

    private fun MatchableAbsItem.confirmed() = MatchResult.Confirmed(serverUuid, libraryItemId)

    private fun normaliseIsbn(raw: String?): String? {
        if (raw == null) return null
        val stripped = buildString {
            for (c in raw) if (c.isLetterOrDigit()) append(c)
        }
        return stripped.ifEmpty { null }?.uppercase()
    }

    private fun normaliseAsin(raw: String?): String? {
        if (raw == null) return null
        val stripped = raw.filter { !it.isWhitespace() }
        return stripped.ifEmpty { null }?.uppercase()
    }

    private fun normaliseTitle(raw: String): String =
        raw.lowercase()
            .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /**
     * Normalises an author string so "Smith, John" and "John Smith" compare equal.
     * Strategy: split on commas/whitespace, drop punctuation, sort the tokens.
     */
    private fun normaliseAuthor(raw: String): String =
        raw.lowercase()
            .map { if (it.isLetterOrDigit() || it.isWhitespace() || it == ',') it else ' ' }
            .joinToString("")
            .split(Regex("[\\s,]+"))
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(" ")
}
