package com.riffle.core.catalog

/**
 * Returns whether [destinationItems] contains the same logical item as [sourceItem].
 *
 * Stable identifiers win over the display fields. When no shared identifier is available, the
 * normalized title/author pair is the fallback used by the upload preflight. This function is
 * intentionally independent of a particular catalog or UI so overwrite safety checks can reuse it.
 */
fun doesDestinationItemExist(
    sourceItem: CatalogItem,
    destinationItems: Iterable<CatalogItem>,
): Boolean = destinationItems.any { destinationItemMatches(sourceItem, it) }

internal fun destinationItemMatches(sourceItem: CatalogItem, destinationItem: CatalogItem): Boolean {
    val sourceIsbn = sourceItem.isbn.normalizedIdentifier()
    val destinationIsbn = destinationItem.isbn.normalizedIdentifier()
    if (sourceIsbn != null && destinationIsbn != null) return sourceIsbn == destinationIsbn

    val sourceAsin = sourceItem.asin.normalizedIdentifier()
    val destinationAsin = destinationItem.asin.normalizedIdentifier()
    if (sourceAsin != null && destinationAsin != null) return sourceAsin == destinationAsin

    // ABS may parse embedded audio tags into title/author while retaining the clean directory
    // requested by the upload. The folder is the stronger identity in that case: AUTHOR/TITLE.
    if (destinationItem.pathIdentityMatches(sourceItem)) return true

    return sourceItem.title.normalizedText() == destinationItem.title.normalizedText() &&
        sourceItem.author.normalizedText() == destinationItem.author.normalizedText()
}

private fun CatalogItem.pathIdentityMatches(sourceItem: CatalogItem): Boolean {
    val expectedAuthor = sourceItem.author.pathSegment()
    val expectedTitle = sourceItem.title.pathSegment()
    if (expectedAuthor.isEmpty() || expectedTitle.isEmpty()) return false
    return listOfNotNull(path, relPath).any { candidatePath ->
        val segments = candidatePath.trim('/').split('/').filter(String::isNotBlank).map { it.pathSegment() }
        val titleIndex = segments.indexOfLast { it.pathSegmentMatches(expectedTitle) }
        titleIndex >= 1 && segments[titleIndex - 1].pathSegmentMatches(expectedAuthor)
    }
}

private fun String.pathSegment(): String = filter(Char::isLetterOrDigit).lowercase()

private fun String.pathSegmentMatches(expected: String): Boolean =
    this == expected || startsWith(expected) || expected.startsWith(this)

private fun String?.normalizedIdentifier(): String? =
    this?.filter(Char::isLetterOrDigit)?.lowercase()?.takeIf(String::isNotEmpty)

private fun String.normalizedText(): String =
    trim().replace(Regex("\\s+"), " ").lowercase()
