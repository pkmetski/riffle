package com.riffle.core.domain

import platform.Foundation.NSURL

/**
 * iOS path extraction, mirroring the JVM `java.net.URI(raw).path` contract: `NSURL.path` returns
 * the decoded path component and drops any `#fragment`/`?query`, and yields `null` for strings
 * `NSURL` cannot parse — so the shared [normalizeEpubHref] falls back to the raw string exactly as
 * it does on the JVM. `file://…!/…` and `http://localhost/…` variants are handled by the `!`
 * fast-path in [normalizeEpubHref] before this is reached.
 */
internal actual fun uriPath(raw: String): String? =
    NSURL.URLWithString(raw)?.path
