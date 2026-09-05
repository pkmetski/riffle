package com.riffle.core.domain

import platform.Foundation.NSURL

/**
 * iOS path extraction, mirroring the JVM `java.net.URI(raw).path` contract: `NSURL.path` returns
 * the decoded path component and drops any `#fragment`/`?query`, and yields `null` for strings
 * `NSURL` cannot parse — so the shared [normalizeEpubHref] falls back to the raw string exactly as
 * it does on the JVM. `file://…!/…` archive URLs are handled by the `!` fast-path in
 * [normalizeEpubHref] before this is reached.
 *
 * Known divergence from `java.net.URI.path`, deliberately not worked around: for a scheme+authority
 * URL whose path is a bare directory (`http://localhost/OEBPS/`) `NSURL.path` strips the trailing
 * slash where `URI` keeps it, and for a scheme-only URL (`http://localhost`) `NSURL.path` is `null`
 * where `URI` is `""`. These shapes never occur here — every href [normalizeEpubHref] sees is a
 * concrete EPUB *resource* (a spine file or a locator into one), i.e. a non-directory path — so the
 * two actuals agree on every reachable input (see the `EpubHrefTest` cases, green on both targets).
 */
internal actual fun uriPath(raw: String): String? =
    NSURL.URLWithString(raw)?.path
