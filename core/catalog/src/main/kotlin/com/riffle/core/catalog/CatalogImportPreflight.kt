package com.riffle.core.catalog

sealed interface CatalogImportDecision {
    data object UploadNewItem : CatalogImportDecision
    data object ConfirmOverwrite : CatalogImportDecision
    data class Blocked(val reason: String) : CatalogImportDecision
}

/**
 * Decides whether an import may proceed before any destination mutation occurs.
 *
 * A replacement is unsafe when the incoming EPUB differs from the existing file and the
 * destination already owns annotations for that item. Progress is deliberately not part of this
 * decision: destinations may not support progress, while file replacement can still be safe.
 */
fun catalogImportDecision(
    itemExists: Boolean,
    destinationHasAnnotations: Boolean,
    replacementDiffers: Boolean,
): CatalogImportDecision = when {
    !itemExists -> CatalogImportDecision.UploadNewItem
    destinationHasAnnotations && replacementDiffers -> CatalogImportDecision.Blocked(
        "The replacement differs and would invalidate existing annotations",
    )
    else -> CatalogImportDecision.ConfirmOverwrite
}
