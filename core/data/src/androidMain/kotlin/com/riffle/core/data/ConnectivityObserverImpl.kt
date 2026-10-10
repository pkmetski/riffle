package com.riffle.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.ConnectivityObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectivityObserverImpl constructor(
    context: Context,
    applicationScope: ApplicationScope,
) : ConnectivityObserver {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = applicationScope.coroutineScope

    override val isOnline: StateFlow<Boolean> = callbackFlow {
        // Online-state is derived from three signals, treating every radio identically — airplane
        // mode is just "all radios off" and isn't special-cased:
        //   * NetworkCallback — primary, event-driven. onAvailable/onLost/onCapabilitiesChanged
        //     fire for every transport (wifi, cellular, ethernet, VPN) on every toggle.
        //   * ProcessLifecycleOwner ON_START — heals doze/wake drift on Android 13+ where
        //     NetworkCallback events can be dropped or coalesced. `activeNetwork` is the coarse
        //     ground-truth: null → offline, non-null → union any newly-qualifying networks in.
        //     The sweep never removes networks the callbacks have already reported, so a stale
        //     `getAllNetworks()` during teardown cannot revert a correct offline emit.
        //   * Foreground poll ticker — every POLL_INTERVAL_MS re-emits with the tracker's current
        //     state cross-checked against a FRESH `activeNetwork` read. This closes the
        //     Android 13 gap where the OS drops the `onLost` for a network going away entirely
        //     (airplane on, network vanishes) so no callback ever fires and neither the
        //     `activeNetwork == null` veto nor the ON_START sweep can rescue us until the user
        //     backgrounds+foregrounds the process or navigates enough to force a fresh
        //     ViewModel-driven refresh. The tick is READ-ONLY against the tracker — it never
        //     merges, never clears — so it cannot re-add a stale network the way the removed
        //     PR #392 `syncNow()` could. It only lets the `activeNetwork == null` veto fire on a
        //     bounded schedule instead of exclusively piggybacking on callback events.
        //
        // "Qualifying" here means `NET_CAPABILITY_INTERNET` only — we deliberately do NOT require
        // `NET_CAPABILITY_VALIDATED`. That flag is set by Android's built-in probe to
        // `connectivitycheck.gstatic.com/generate_204`, which is unreachable on Huawei devices
        // without GMS and on any network that firewalls Google endpoints. Riffle never talks to
        // Google — it talks to the user's Audiobookshelf server, WebDAV, and optional Storyteller
        // peer — so making the banner depend on a Google reachability probe misreports offline for
        // every affected user. See `isQualifyingNetwork` below.
        //
        // Tradeoff: on a network where INTERNET is set but the server is actually unreachable
        // (captive portal that hasn't been signed into, away-from-LAN with a LAN-only server),
        // `isOnline` will now report true. On the main library screens the shim is
        // `LibraryItemsViewModel._refreshFailed` (and the analogous `_refreshFailed` in
        // `SeriesDetailViewModel` / `CollectionDetailViewModel`), which flips the banner when a
        // library refresh returns `NetworkError`. Other consumers of `isOnline` —
        // `LibraryItemDetailViewModel`, `FilteredBooksViewModel`, `ReadaloudSession`'s
        // download-prompt guard — do NOT have this shim, so on unvalidated-server-unreachable
        // networks they will show the online state and fail at request time. That is a strictly
        // narrower failure mode than the Huawei bug (which broke the primary use case entirely)
        // but is a real UX regression on captive portals; treated as follow-up work.
        val tracker = ValidatedNetworkTracker<Network>()

        // See `reconcileOnline` — every callback emit is cross-checked against `activeNetwork`
        // so a dropped `onLost` cannot keep the banner hidden.
        fun emitReconciled(callbackOnline: Boolean) {
            trySend(reconcileOnline(callbackOnline, connectivityManager.activeNetwork != null))
        }

        emitReconciled(currentOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // VPN networks (e.g. Tailscale) are excluded from the tracker because the OS
                // keeps the VPN tunnel alive in airplane mode — the tun interface, link
                // addresses, capabilities, and routes are identical whether or not the VPN can
                // actually relay traffic. Including VPNs in the tracker means onLost never fires
                // for them (Samsung/Android 13+ drops it), permanently blocking offline detection.
                // Physical networks (WiFi, cellular) DO correctly fire onLost in airplane mode.
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return
                emitReconciled(tracker.onAvailable(network))
            }

            override fun onLost(network: Network) {
                // Safe even if the network was never added (VPN filtered in onAvailable) — the
                // tracker treats onLost for an unknown key as a no-op.
                emitReconciled(tracker.onLost(network))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val qualifies = isQualifyingNetwork(hasInternet = hasInternet, hasValidated = hasValidated, isVpn = isVpn)
                emitReconciled(tracker.onCapabilitiesChanged(network, qualifies))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        fun reconcile() {
            if (connectivityManager.activeNetwork == null) {
                emitReconciled(tracker.clear())
                return
            }
            val fresh = mutableSetOf<Network>()
            for (network in connectivityManager.allNetworks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (isQualifyingNetwork(
                        hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    )
                ) {
                    fresh += network
                }
            }
            emitReconciled(tracker.mergeIn(fresh))
        }

        // Foreground-gated poll — see the block comment at the top of this callbackFlow. Started
        // on ON_START (foreground) and cancelled on ON_STOP (background) so we only pay the poll
        // cost while the banner is actually observable to the user; the ON_START sweep still
        // provides a one-shot heal at foregrounding for the "missed onAvailable during doze"
        // direction. `distinctUntilChanged` downstream suppresses no-op emits.
        var pollJob: Job? = null
        val lifecycleOwner = ProcessLifecycleOwner.get()
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    reconcile()
                    pollJob?.cancel()
                    pollJob = launch {
                        while (isActive) {
                            delay(POLL_INTERVAL_MS)
                            // `tracker.isOnline()` can be stale when onLost is dropped (Android
                            // 13+ / Samsung One UI). Cross-check against the active network's own
                            // capabilities: when the OS removes NET_CAPABILITY_INTERNET from the
                            // handle (even if the handle itself persists), currentOnline() returns
                            // false and we authoritatively clear the tracker.
                            if (!currentOnline()) {
                                emitReconciled(tracker.clear())
                            } else {
                                emitReconciled(tracker.isOnline())
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    pollJob?.cancel()
                    pollJob = null
                }
                else -> Unit
            }
        }
        // LifecycleRegistry requires main-thread registration. Adding an observer while the
        // lifecycle is already in the STARTED state replays ON_CREATE + ON_START to bring the
        // observer up to date, so a foreground subscribe immediately runs the sweep and starts
        // the poll — no separate priming path needed.
        withContext(Dispatchers.Main.immediate) {
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        }

        awaitClose {
            pollJob?.cancel()
            connectivityManager.unregisterNetworkCallback(callback)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentOnline())

    // Checks if ANY qualifying physical network currently exists. Using `allNetworks` (not just
    // `activeNetwork`) handles the Tailscale / split-tunnel VPN case: on Samsung the `activeNetwork`
    // is the VPN handle, which is a VPN and therefore excluded by `isQualifyingNetwork`. Iterating
    // all networks finds the underlying WiFi or cellular network independently of which one the OS
    // calls "active". Returns false if only VPN networks are present (e.g. airplane mode with
    // Tailscale still running its tun interface).
    private fun currentOnline(): Boolean =
        connectivityManager.allNetworks.any { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
            isQualifyingNetwork(
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            )
        }

    override fun isMetered(): Boolean = connectivityManager.isActiveNetworkMetered

    private companion object {
        // 5s balances "banner appears quickly after going offline" and "low foreground cost."
        // The poll runs only while the app is in the foreground (ON_START/ON_STOP gated).
        // Reduced from 15s: on Samsung One UI (Android 17) and other OEM variants, both onLost
        // and activeNetwork cleanup can be delayed far beyond 15s, leaving isOnline stuck at true.
        const val POLL_INTERVAL_MS = 5_000L
    }
}

// reconcileOnline and isQualifyingNetwork live in commonMain/ConnectivityPredicates.kt
