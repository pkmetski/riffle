package com.riffle.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fence off the Huawei-shaped "banner permanently shows offline while the user's server, WebDAV,
 * and Storyteller peer are all reachable" regression class.
 *
 * The historical bug: `ConnectivityObserverImpl` required both `NET_CAPABILITY_INTERNET` and
 * `NET_CAPABILITY_VALIDATED` on every path (callback registration, capability change, ON_START
 * sweep, initial `currentOnline`). `NET_CAPABILITY_VALIDATED` is set by Android's `NetworkMonitor`
 * after a successful probe to `connectivitycheck.gstatic.com/generate_204`. On Huawei devices
 * without GMS — and on any network that firewalls Google endpoints — that probe never succeeds,
 * so the OS marks the WiFi as INTERNET-only and Riffle reported the user as offline forever,
 * even though the ABS server on the same LAN was reachable the whole time.
 *
 * The fix routed every capability check through [isQualifyingNetwork], which requires INTERNET
 * only. The `hasValidated` argument is retained in the signature — deliberately unused — so
 * future readers see the flag was considered and rejected, and so a well-meaning "let me also
 * require VALIDATED again" edit is a visible, mechanical change that must delete the parameter,
 * flip the return value, and delete these tests all at once. The assertion below on the Huawei
 * case would fail red on any revert.
 *
 * Do NOT collapse these into a parameterised runner — the failure name on a broken build should
 * read like a mini-changelog of the regression.
 */
class QualifyingNetworkTest {

    @Test
    fun `internet plus validated is online`() {
        // Healthy state on a GMS-enabled Android device whose network passed the Google probe.
        assertTrue(isQualifyingNetwork(hasInternet = true, hasValidated = true))
    }

    @Test
    fun `internet without validated is online — Huawei and Google-firewalled networks`() {
        // The load-bearing assertion for this regression class. Huawei-without-GMS, corporate
        // networks that block `connectivitycheck.gstatic.com`, and users in regions where Google
        // is unreachable all end up here. The OS cannot validate, but Riffle does not talk to
        // Google — it talks to the user's ABS server, WebDAV, and Storyteller peer — so we must
        // treat these networks as online. Server-reachability is signalled separately via
        // `LibraryItemsViewModel._refreshFailed`.
        assertTrue(isQualifyingNetwork(hasInternet = true, hasValidated = false))
    }

    @Test
    fun `no internet capability is offline even if validated is set`() {
        // Defensive: a callback that reports VALIDATED without INTERNET is a system-invariant
        // violation we don't expect in practice, but if it ever happens we still treat it as
        // offline because the whole point of the check is "does this network route traffic."
        assertFalse(isQualifyingNetwork(hasInternet = false, hasValidated = true))
    }

    @Test
    fun `neither capability is offline`() {
        // Clean disconnect — nothing to route traffic through.
        assertFalse(isQualifyingNetwork(hasInternet = false, hasValidated = false))
    }
}
