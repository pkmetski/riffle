package com.riffle.core.data

// ToReadRepository and TO_READ_PLAYLIST_NAME have moved to core:domain so KMP feature modules can
// depend on the interface without pulling in Android-only core:data.
// These keep existing core:data and app imports compiling unchanged.
val TO_READ_PLAYLIST_NAME: String get() = com.riffle.core.domain.TO_READ_PLAYLIST_NAME
typealias ToReadRepository = com.riffle.core.domain.ToReadRepository
