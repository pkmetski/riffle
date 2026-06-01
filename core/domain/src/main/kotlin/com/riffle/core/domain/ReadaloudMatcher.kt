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

data class Confirmed(val absServerUuid: String, val absLibraryItemId: String)

object ReadaloudMatcher {

    /**
     * Returns every ABS candidate the readaloud should link to. Tier 1 (identifier match)
     * takes precedence: if any candidate matches by ISBN/ASIN, only those candidates are
     * returned and Tier 2 is skipped. Tier 2 (normalised title + author with subset
     * overlap on author tokens) is the fallback when no identifier matches.
     *
     * Multiple matches are expected and valid — `readaloud_links` is keyed by ABS item,
     * so a single readaloud legitimately links to both an ebook entry in a Books library
     * and an audiobook stub in an Audiobooks library when they share the same metadata.
     */
    fun match(book: MatchableStorytellerBook, candidates: List<MatchableAbsItem>): List<Confirmed> {
        val identifierHits = candidates.filter { identifierMatches(book, it) }
        if (identifierHits.isNotEmpty()) return identifierHits.map { it.confirmed() }

        val bookTitle = normaliseTitle(book.title)
        val bookAuthorTokens = authorTokens(book.author)
        if (bookTitle.isEmpty() || bookAuthorTokens.isEmpty()) return emptyList()

        return candidates
            .filter { normaliseTitle(it.title) == bookTitle && authorsOverlap(bookAuthorTokens, authorTokens(it.author)) }
            .map { it.confirmed() }
    }

    /**
     * "Author A matches Author B" iff one side's token set is a non-empty subset of the
     * other's. Reason: Storyteller's OPF-derived `authors` array indiscriminately tags
     * narrators and co-authors with `role:"aut"`, so the Storyteller side typically lists
     * more contributors than the curated ABS side. Subset captures "ABS primary author
     * appears in the Storyteller author list" without rejecting on cardinality drift.
     */
    private fun authorsOverlap(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a.containsAll(b) || b.containsAll(a)
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

    private fun MatchableAbsItem.confirmed() = Confirmed(serverUuid, libraryItemId)

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

    private fun normaliseTitle(raw: String): String {
        // Strip subtitle: anything after the first `:` or em-dash. Storyteller derives titles
        // from the EPUB OPF and keeps the "A Novel"-style subtitle; ABS users curate it out.
        // Comparing only the head pairs the empirical reality without sacrificing safety —
        // when the head alone collides across distinct ABS items, the collision rule
        // demotes to Unmatched.
        val head = raw.split(':', '—').first()
        val tokens = head.lowercase()
            .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        // Drop a leading article ("the", "a", "an"). Library cataloguing convention drops
        // it; OPF metadata usually keeps it. Only the very first token is touched so that
        // "A Brief History of Time" doesn't become "Brief History of Time" by mistake when
        // the indefinite article is title-internal — it isn't, here, but the token-level
        // restriction guards against false trims like "The The" → "" pathologies.
        val deArticled = if (tokens.firstOrNull() in LEADING_ARTICLES) tokens.drop(1) else tokens
        return deArticled.joinToString(" ")
    }

    private val LEADING_ARTICLES = setOf("the", "a", "an")

    /**
     * Token set for an author string, used by [authorsOverlap]. "Smith, John" and "John Smith"
     * produce the same set, which is how the spec's name-order equivalence is satisfied. Any
     * non-alphanumeric character is treated as a separator so OPF-junk like "Andy Weir;"
     * collapses to {andy, weir}.
     */
    private fun authorTokens(raw: String): Set<String> =
        raw.lowercase()
            .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .toSet()
}
