package com.riffle.app.feature.reader

import com.riffle.core.models.TocEntry
import org.readium.r2.shared.publication.Link

fun List<Link>.toTocEntries(): List<TocEntry> = map { link ->
    TocEntry(
        title = link.title.orEmpty(),
        href = link.href.toString(),
        children = link.children.toTocEntries(),
    )
}
