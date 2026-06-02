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

/** An ABS slot the readaloud is auto-linked to (Tier 1/2). */
data class Confirmed(val absServerUuid: String, val absLibraryItemId: String)

/** A Tier 3 fuzzy candidate surfaced for user review, with its combined similarity [score]. */
data class ScoredCandidate(
    val absServerUuid: String,
    val absLibraryItemId: String,
    val score: Double,
)

/**
 * The match state of a single Storyteller readaloud against the configured ABS Library Items,
 * per [ADR 0021]. Exactly one of these is produced for each readaloud per matcher run.
 */
sealed interface MatchOutcome {
    /**
     * Tier 1 (identifier) or Tier 2 (normalised title + author) produced one or more
     * high-confidence ABS slots. Multiplicity is preserved: a readaloud legitimately links to
     * every ABS item sharing its metadata (e.g. an ebook entry plus an audiobook stub).
     */
    data class Confirmed(val links: List<com.riffle.core.domain.Confirmed>) : MatchOutcome

    /** Tier 3: fuzzy candidates above threshold the user must choose between (or reject). */
    data class PendingReview(val candidates: List<ScoredCandidate>) : MatchOutcome

    /** Tier 4: no plausible ABS candidate. Available for manual pairing. */
    data object Unmatched : MatchOutcome
}

object ReadaloudMatcher {

    /** Minimum token-set similarity (on both title and author) for a Tier 3 fuzzy candidate. */
    const val FUZZY_THRESHOLD = 0.85

    /**
     * Classifies a readaloud against [candidates], evaluated strongest tier first:
     *
     *  - **Tier 1 (identifier)** — any candidate matching by normalised ISBN/ASIN. If present,
     *    only identifier hits are returned and weaker tiers are skipped.
     *  - **Tier 2 (normalised title + author)** — exact normalised title with author token
     *    subset overlap. Tiers 1 and 2 both produce [MatchOutcome.Confirmed] and preserve
     *    multiplicity (`readaloud_links` is ABS-keyed, so one readaloud may link to several
     *    ABS items that share its metadata).
     *  - **Tier 3 (fuzzy)** — Sørensen–Dice token-set similarity ≥ [FUZZY_THRESHOLD] on both
     *    title and author. Every above-threshold candidate is surfaced together as
     *    [MatchOutcome.PendingReview] for the user to resolve.
     *  - **Tier 4** — nothing plausible → [MatchOutcome.Unmatched].
     */
    fun match(book: MatchableStorytellerBook, candidates: List<MatchableAbsItem>): MatchOutcome {
        val identifierHits = candidates.filter { identifierMatches(book, it) }
        if (identifierHits.isNotEmpty()) {
            return MatchOutcome.Confirmed(identifierHits.map { it.confirmed() })
        }

        val bookTitle = normaliseTitle(book.title)
        val bookTitleTokens = titleTokens(book.title)
        val bookAuthorTokens = authorTokens(book.author)
        if (bookTitle.isEmpty() || bookAuthorTokens.isEmpty()) return MatchOutcome.Unmatched

        val titleAuthorHits = candidates.filter {
            normaliseTitle(it.title) == bookTitle &&
                authorsOverlap(bookAuthorTokens, authorTokens(it.author))
        }
        if (titleAuthorHits.isNotEmpty()) {
            return MatchOutcome.Confirmed(titleAuthorHits.map { it.confirmed() })
        }

        val fuzzy = candidates.mapNotNull { candidate ->
            val titleSim = diceSimilarity(bookTitleTokens, titleTokens(candidate.title))
            val authorSim = diceSimilarity(bookAuthorTokens, authorTokens(candidate.author))
            if (titleSim >= FUZZY_THRESHOLD && authorSim >= FUZZY_THRESHOLD) {
                ScoredCandidate(candidate.serverUuid, candidate.libraryItemId, (titleSim + authorSim) / 2.0)
            } else {
                null
            }
        }
        return if (fuzzy.isNotEmpty()) MatchOutcome.PendingReview(fuzzy) else MatchOutcome.Unmatched
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

    /**
     * Sørensen–Dice coefficient over two token sets: `2·|A∩B| / (|A|+|B|)`. Chosen over Jaccard
     * because it is more forgiving of a single differing token in a multi-token title/author,
     * which is precisely the imperfect-metadata case Tier 3 exists to surface. Returns 0 when
     * either side is empty so an empty title/author can never clear the threshold.
     */
    private fun diceSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        return (2.0 * intersection) / (a.size + b.size)
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

    /**
     * Ordered, normalised title used for Tier 2 exact comparison. Strips the subtitle (anything
     * after the first `:` or em-dash — Storyteller keeps the OPF "A Novel"-style subtitle that ABS
     * users curate out), lowercases, strips punctuation, and drops a single leading article.
     */
    private fun normaliseTitle(raw: String): String = titleTokenList(raw).joinToString(" ")

    /** Token set form of [normaliseTitle], used for Tier 3 token-set similarity. */
    private fun titleTokens(raw: String): Set<String> = titleTokenList(raw).toSet()

    private fun titleTokenList(raw: String): List<String> {
        val head = raw.split(':', '—').first()
        val tokens = head.lowercase()
            .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        // Drop a leading article only from the very first token, so a title-internal article
        // (or a "The The" pathology) is never trimmed by mistake.
        return if (tokens.firstOrNull() in LEADING_ARTICLES) tokens.drop(1) else tokens
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
