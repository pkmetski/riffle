package com.riffle.core.data

import com.riffle.core.domain.ReadaloudLink

/**
 * Narrow seam for scheduling a cross-EPUB index build for a matched book, so callers (the library
 * detail ViewModel's download-complete trigger, the reader-sync factory's on-open self-heal) depend
 * on the *intent* — not the I/O-heavy [CrossEpubIndexBuilderService] that fulfils it (ADR 0019/0031).
 * Keeping the surface this small also makes those callers trivially unit-testable.
 */
interface CrossEpubIndexBuildTrigger {
    /** Schedule an idempotent background build for [link]; returns immediately. */
    fun enqueueBuild(link: ReadaloudLink)
}
