package com.riffle.core.data

// ReadaloudSidecarPrefetcher has moved to core:domain so KMP feature modules can depend on
// the interface without pulling in Android-only core:data.
// This keeps existing core:data and app imports compiling unchanged.
typealias ReadaloudSidecarPrefetcher = com.riffle.core.domain.ReadaloudSidecarPrefetcher
