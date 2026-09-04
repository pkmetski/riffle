package com.riffle.app.feature.reader

// AbsTargetResolver types have moved to feature:reader jvmMain.
typealias AbsLinkMedia = com.riffle.feature.reader.AbsLinkMedia
typealias ResolvedAbsTargets = com.riffle.feature.reader.ResolvedAbsTargets

fun resolveAbsTargets(
    openedItemId: String,
    items: List<AbsLinkMedia>,
): ResolvedAbsTargets = com.riffle.feature.reader.resolveAbsTargets(openedItemId, items)
