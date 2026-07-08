package com.riffle.core.domain

// Identifies which reading context the formatting preferences belong to. The full-book reader and
// the annotations-view reader each have their own independent prefs chain (global defaults +
// per-book overrides), so a tweak in one context never leaks into the other. Enum name values are
// used verbatim as the `source` PK column in `book_formatting_preferences`, so renaming is a DB
// migration.
enum class FormattingScope { FullBook, Highlights }
