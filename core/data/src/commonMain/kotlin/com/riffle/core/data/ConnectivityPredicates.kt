package com.riffle.core.data

/**
 * The reconciliation predicate that gates every `isOnline` emit. Both the
 * `NetworkCallback`-driven `ValidatedNetworkTracker` AND the platform's active-network indicator
 * must agree that we're online — either signal saying "no" wins.
 *
 * This is deliberately a pure function so the four truth-table cases can be exhaustively
 * unit-tested. Each corner encodes a real, previously-shipped regression:
 *
 *   * `(true, false)` — the fourth-time-around regression: on Android 13 the OS silently drops
 *     one of the `onLost` callbacks when airplane mode turns multiple qualifying networks off at
 *     once, so the tracker retains a stale network and thinks we're online. `activeNetwork ==
 *     null` vetoes it → offline.
 *   * `(false, true)` — the airplane-mode/#294 regression: during teardown `activeNetwork` can
 *     still briefly report the just-lost network. The tracker (driven by `onLost`) is correct
 *     → offline.
 *   * `(true, true)` — healthy online state.
 *   * `(false, false)` — clean disconnect, both signals agree → offline.
 */
internal fun reconcileOnline(trackerOnline: Boolean, hasActiveNetwork: Boolean): Boolean =
    trackerOnline && hasActiveNetwork

/**
 * Whether a network qualifies as "we have connectivity" for Riffle's purposes. The
 * observer/tracker only counts networks that satisfy this predicate; the ON_START sweep only
 * merges networks that satisfy this predicate.
 *
 * Rules:
 * - `NET_CAPABILITY_INTERNET` is required.
 * - `NET_CAPABILITY_VALIDATED` is deliberately **not** required. VALIDATED is set by Android's
 *   `NetworkMonitor` after a successful probe to `connectivitycheck.gstatic.com/generate_204`.
 *   That probe host is unreachable on Huawei devices without GMS and on any network that firewalls
 *   Google endpoints, so the OS marks the WiFi as `INTERNET` without `VALIDATED` and Riffle would
 *   report the user permanently offline. Riffle only ever talks to the user's Audiobookshelf
 *   server, their WebDAV endpoint, and an optional Storyteller peer — none of which route through
 *   Google. Server-reachability is separately tracked by `LibraryItemsViewModel._refreshFailed`.
 * - VPN networks are excluded. VPN tunnels (e.g. Tailscale) remain active at the kernel level
 *   even in airplane mode — the tunnel interface keeps its link addresses, capabilities, and
 *   routes unchanged whether or not the VPN can actually relay traffic. Including VPNs means
 *   `onLost` is never fired for the VPN handle (Samsung / Android 13+ drops it), permanently
 *   blocking offline detection. Physical networks (WiFi, cellular) correctly fire `onLost` in
 *   airplane mode, so they are the reliable signal.
 *
 * Pure so a commonTest suite can fence off regressions without needing a platform harness.
 * Do not fold `hasValidated` back into the return value.
 */
@Suppress("UNUSED_PARAMETER")
internal fun isQualifyingNetwork(hasInternet: Boolean, hasValidated: Boolean, isVpn: Boolean): Boolean =
    hasInternet && !isVpn
