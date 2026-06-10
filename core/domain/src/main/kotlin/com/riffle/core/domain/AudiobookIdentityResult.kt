package com.riffle.core.domain

/**
 * The verdict of the streaming identity check (ADR 0028), persisted on the Readaloud link so the
 * source decision can read it without re-fetching. Only [VERIFIED] permits streaming; everything
 * else falls back to the bundle.
 */
enum class AudiobookIdentityResult {
    /** ABS serves the same recording Storyteller aligned against — streaming is safe. */
    VERIFIED,

    /** ABS's audiobook is a different recording — must not stream; use the bundle. */
    MISMATCH,

    /** One side has no audiobook to compare — not streamable. */
    NO_AUDIOBOOK,

    /** The check could not be completed (e.g. offline) — not yet known, default to the bundle. */
    UNKNOWN,
}
