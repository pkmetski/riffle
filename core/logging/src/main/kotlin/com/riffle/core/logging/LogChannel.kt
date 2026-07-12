package com.riffle.core.logging

/**
 * Stable log-tag namespace for Riffle's production debug recipes
 * (`adb logcat -d | grep RIFFLE_*`).
 *
 * The string values are part of the contract with AGENTS.md and the debug skills — never
 * rename a tag without updating both places. Add a new channel by adding an enum entry; do
 * not introduce `Log.d("RIFFLE_*", …)` literals elsewhere (enforced by `checkRiffleLogTags`).
 */
enum class LogChannel(val tag: String) {
    Readaloud("RIFFLE_RA"),
    Audiobook("RIFFLE_AB"),
    Handoff("RIFFLE_HANDOFF"),
    HighlightMerge("RIFFLE_HL_MERGE"),
    ReaderDecoration("RIFFLE_DECO"),
    Cadence("RIFFLE_CD"),
    Covers("RIFFLE_COVERS"),
    LocalFiles("RIFFLE_LF"),
    Chitanka("RIFFLE_CHI"),
    WebSourceCache("RIFFLE_WSC"),
    Gutenberg("RIFFLE_GB"),
    ToRead("RIFFLE_TOREAD"),
}
