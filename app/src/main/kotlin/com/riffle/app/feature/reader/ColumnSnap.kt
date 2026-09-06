package com.riffle.app.feature.reader

// Delegate to the shared KMP implementation. All call sites in :app continue to compile because
// Kotlin resolves the name via this typealias; the implementation now lives in :feature:reader
// commonMain so iOS can access it too.
internal typealias ColumnSnap = com.riffle.feature.reader.ColumnSnap
