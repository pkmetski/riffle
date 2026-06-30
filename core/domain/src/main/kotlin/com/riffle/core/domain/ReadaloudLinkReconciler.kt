package com.riffle.core.domain

/**
 * Runs the readaloud auto-matcher against the current local library state and persists its verdicts
 * (confirmed links + pending candidates). Side-effecting; safe to call repeatedly.
 */
interface ReadaloudLinkReconciler {
    suspend fun reconcileLinks()
}
