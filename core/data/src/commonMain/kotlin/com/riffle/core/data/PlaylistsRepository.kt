package com.riffle.core.data

// PlaylistsRepository and related types have moved to core:domain so KMP feature modules can
// depend on the interface without pulling in Android-only core:data.
// These keep existing core:data and app imports compiling unchanged.
val RESERVED_PLAYLIST_NAMES: Set<String> get() = com.riffle.core.domain.RESERVED_PLAYLIST_NAMES
typealias ReservedPlaylistNameException = com.riffle.core.domain.ReservedPlaylistNameException
typealias PlaylistsRepository = com.riffle.core.domain.PlaylistsRepository
