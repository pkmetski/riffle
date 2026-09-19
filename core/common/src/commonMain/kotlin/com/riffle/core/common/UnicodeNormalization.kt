package com.riffle.core.common

/**
 * Unicode NFD (Canonical Decomposition) of [s].
 *
 * Callers use this to strip diacritics: after decomposition every accent is its own combining
 * mark in the `U+0300..U+036F` block, so a single regex pass removes them without a per-character
 * transliteration table.
 *
 * Neither the JVM's `java.text.Normalizer` nor Foundation's
 * `decomposedStringWithCanonicalMapping` exists in `commonMain`, so this is an expect/actual seam.
 * Both actuals implement the same Unicode Standard Annex #15 canonical decomposition, so the
 * output is identical on every target — `UnicodeNormalizationTest` pins that.
 */
expect fun normalizeToNfd(s: String): String
