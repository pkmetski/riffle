package com.riffle.core.domain

/**
 * Resolves an EPUB-internal href, collapsing `.`/`..` segments, optionally against a [base] folder,
 * and preserving any `#fragment`. The single home for the path normalisation the readaloud machinery
 * needs in several places — so a SMIL clip ref written relative to its `.smil` folder
 * (`../text/x.html#id`), the player's resolved ref (`text/x.html#id`), and a spine href all reconcile
 * to the same string. A leading `/` is treated as root-relative (the base is ignored); `..` segments
 * that would escape the root are dropped.
 */
fun resolveEpubHref(href: String, base: String = ""): String {
    val hash = href.indexOf('#')
    val path = if (hash >= 0) href.substring(0, hash) else href
    val fragment = if (hash >= 0) href.substring(hash + 1) else null

    val segments = ArrayDeque<String>()
    if (!path.startsWith('/')) base.split('/').forEach { if (it.isNotEmpty()) segments.addLast(it) }
    for (segment in path.split('/')) when (segment) {
        "", "." -> {}
        ".." -> if (segments.isNotEmpty()) segments.removeLast()
        else -> segments.addLast(segment)
    }
    val resolved = segments.joinToString("/")
    return if (fragment != null) "$resolved#$fragment" else resolved
}
