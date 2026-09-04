package com.riffle.core.domain

import com.riffle.core.models.ReadaloudLink

/**
 * Narrow seam for scheduling a cross-EPUB index build for a matched book, so callers depend on
 * the *intent* — not the I/O-heavy Android service that fulfils it (ADR 0023/0031). Keeping the
 * surface this small makes callers trivially unit-testable.
 *
 * The Android implementation is [com.riffle.core.data.CrossEpubIndexBuilderService].
 */
interface CrossEpubIndexBuildTrigger {
    /** Schedule an idempotent background build for [link]; returns immediately. */
    fun enqueueBuild(link: ReadaloudLink)
}
