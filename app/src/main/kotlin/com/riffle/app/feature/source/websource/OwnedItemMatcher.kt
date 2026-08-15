package com.riffle.app.feature.source.websource

import com.riffle.core.catalog.CatalogItem
import com.riffle.core.models.LibraryItem
import java.text.Normalizer

private const val SEP = "|||"

/**
 * Pre-built index of a user's server-source library items for the "Unowned" catalog filter.
 *
 * Matching strategies (applied in order, first hit wins):
 *   1. ISBN — strongest signal; skips title matching entirely.
 *   2. Exact title+author key — normalized, then O(1) lookup.
 *   3. Title-only key — for ABS items stored without an author field.
 *   4. Fuzzy-author — exact normalized title, but spaceless+containment author overlap.
 *      Handles ABS items where the author field concatenates role prefix with no spaces
 *      (e.g. "реж.ЛилянаТодорова ШарлПеро" vs candidate "Шарл Перо").
 *   5. Prefix match — ABS title starts with candidate title + space (≥8 char guard).
 *      Handles ABS items uploaded with extra metadata appended
 *      (e.g. "Винету и Поразяващата ръка :К.Май :БалканТон").
 *   6. Fuzzy title — Levenshtein ≤3 edits / 10% threshold, with author confirmation.
 *      Handles OCR errors and minor Unicode spelling variations.
 *
 * Ported from https://github.com/pkmetski/chitanka-to-audiobookshelf/blob/main/lib/abs/matching.ts
 */
internal class OwnedItemIndex(
    private val isbns: Set<String>,
    private val exactKeys: Set<String>,
    private val scanList: List<AbsEntry>,
    // Normalized base titles extracted from ABS entries that have no author and use the
    // " :Author :Publisher" suffix format (e.g. "Клан, клан-недоклан :НароднаПриказка :БалканТон").
    // Stored separately so strategy 5b can do an O(1) lookup without touching series volumes.
    private val titlesWithMeta: Set<String> = emptySet(),
) {
    internal data class AbsEntry(val normTitle: String, val normAuthor: String)

    fun isOwned(item: CatalogItem): Boolean {
        // 1. ISBN
        val normIsbn = item.isbn?.normalizeIsbn()
        if (!normIsbn.isNullOrEmpty() && normIsbn in isbns) return true

        val normTitle = normalizeTitle(item.title)
        if (normTitle.length < 3) return false
        val normAuthor = normalizeTitle(item.author)

        // 2. Exact combined key
        if (normAuthor.isNotEmpty() && "$normTitle$SEP$normAuthor" in exactKeys) return true

        // 3. Title-only key
        if (normTitle in exactKeys) return true

        if (scanList.isEmpty()) return false

        // 4. Fuzzy-author: exact title, spaceless/containment author overlap
        if (normAuthor.isNotEmpty()) {
            for (entry in scanList) {
                if (entry.normTitle == normTitle && authorsOverlap(normAuthor, entry.normAuthor)) return true
            }
        }

        // 5. Prefix: ABS title starts with candidate title + space (≥8 chars, author required)
        if (normTitle.length >= 8 && normAuthor.isNotEmpty()) {
            val prefix = "$normTitle "
            for (entry in scanList) {
                if (entry.normTitle.startsWith(prefix) && authorsOverlap(normAuthor, entry.normAuthor)) return true
            }
        }

        // 5b. Title-only metadata prefix — ABS stored with " :Author :Publisher" appended to the
        //     title and no separate author field. `titlesWithMeta` was built from the raw title
        //     (" :" guard), so series volumes ("Title Книга 7") are never included.
        if (normTitle.length >= 8 && normTitle in titlesWithMeta) return true

        // 6. Fuzzy title: Levenshtein ≤3 / 10%, author confirmation required
        if (normAuthor.isNotEmpty()) {
            for (entry in scanList) {
                if (titlesSimilar(normTitle, entry.normTitle) && authorsOverlap(normAuthor, entry.normAuthor)) return true
            }
        }

        return false
    }
}

internal fun buildOwnedItemIndex(items: List<LibraryItem>): OwnedItemIndex {
    val isbns = mutableSetOf<String>()
    val exactKeys = mutableSetOf<String>()
    val scanList = mutableListOf<OwnedItemIndex.AbsEntry>()
    val titlesWithMeta = mutableSetOf<String>()

    for (item in items) {
        val normIsbn = item.isbn?.normalizeIsbn()
        if (!normIsbn.isNullOrEmpty()) isbns.add(normIsbn)

        val normTitle = normalizeTitle(item.title)
        if (normTitle.isEmpty()) continue
        val normAuthor = normalizeTitle(item.author)

        if (normAuthor.isEmpty()) {
            exactKeys.add(normTitle)
            // If the raw title uses " :Metadata" suffix (chitanka-to-abs upload format), index
            // the normalized base title so strategy 5b can match without a prefix scan.
            if (item.title.contains(" :")) {
                val baseNorm = normalizeTitle(item.title.substringBefore(" :"))
                if (baseNorm.isNotEmpty()) titlesWithMeta.add(baseNorm)
            }
        } else {
            exactKeys.add("$normTitle$SEP$normAuthor")
        }
        scanList.add(OwnedItemIndex.AbsEntry(normTitle, normAuthor))
    }

    return OwnedItemIndex(isbns, exactKeys, scanList, titlesWithMeta)
}

internal fun normalizeTitle(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "")
        .replace(Regex("[-–—]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.normalizeIsbn(): String = filter { it.isLetterOrDigit() }.lowercase()

private fun spaceless(s: String): String = s.replace(Regex("\\s+"), "")

private fun authorsOverlap(a: String, b: String): Boolean {
    if (a == b) return true
    val sa = spaceless(a)
    val sb = spaceless(b)
    if (sa == sb) return true
    if (sa.length >= 4 && sb.contains(sa)) return true
    if (sb.length >= 4 && sa.contains(sb)) return true
    // Gramofonche emits "Author, реж. Director"; the chitanka-to-abs upload script stores
    // "реж.Director Author" (role prefix first, no spaces). The full spaceless strings land in
    // different order so containment fails. Extract just the author token (part before " реж")
    // from each and retry — title equality is already confirmed by the caller, so the 4-char
    // author prefix check is sufficient to avoid false positives.
    val aAuthor = spaceless(a.substringBefore(" реж").trim())
    if (aAuthor.length >= 4 && sb.contains(aAuthor)) return true
    val bAuthor = spaceless(b.substringBefore(" реж").trim())
    if (bAuthor.length >= 4 && sa.contains(bAuthor)) return true
    return false
}

private fun titlesSimilar(a: String, b: String): Boolean {
    if (a == b) return true
    if (Math.abs(a.length - b.length) > 3) return false
    val maxLen = maxOf(a.length, b.length)
    val threshold = minOf(3, Math.ceil(maxLen * 0.10).toInt())
    return levenshtein(a, b) <= threshold
}

private fun levenshtein(a: String, b: String): Int {
    val m = a.length
    val n = b.length
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 0..m) dp[i][0] = i
    for (j in 0..n) dp[0][j] = j
    for (i in 1..m) {
        for (j in 1..n) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
    }
    return dp[m][n]
}
