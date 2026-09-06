package com.riffle.feature.settings

/** Counts shown in a Storyteller service's expanded "Readaloud matches" summary (gradient order). */
data class ReadaloudMatchSummary(
    val unmatchedCount: Int,
    val suggestedCount: Int,
    val partiallyMatchedCount: Int,
    val matchedCount: Int,
)
