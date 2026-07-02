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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityObserverImpl @Inject constructor(
    @ApplicationContext context: Context,
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
        // every affected user. Server-reachability is separately tracked by `_refreshFailed` in
        // the library view model, which is the correct signal for "we can see the LAN but not
        // your server." See `isQualifyingNetwork` below.
        val tracker = ValidatedNetworkTracker<Network>()

        // See `reconcileOnline` — every callback emit is cross-checked against `activeNetwork`
        // so a dropped `onLost` cannot keep the banner hidden.
        fun emitReconciled(callbackOnline: Boolean) {
            trySend(reconcileOnline(callbackOnline, connectivityManager.activeNetwork != null))
        }

        emitReconciled(currentOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                emitReconciled(tracker.onAvailable(network))
            }

            override fun onLost(network: Network) {
                emitReconciled(tracker.onLost(network))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val qualifies = isQualifyingNetwork(
                    hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
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
                    )
                ) {
                    fresh += network
                }
            }
            emitReconciled(tracker.mergeIn(fresh))
        }

        val lifecycleOwner = ProcessLifecycleOwner.get()
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) reconcile()
        }
        // LifecycleRegistry requires main-thread registration.
        withContext(Dispatchers.Main.immediate) {
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        }

        // See the block comment at the top of this callbackFlow — read-only re-emit on a bounded
        // schedule so the `activeNetwork == null` veto in `reconcileOnline` fires without needing
        // a callback event that Android 13 may have silently dropped. `distinctUntilChanged`
        // downstream suppresses no-op emits, so a healthy foreground app pays only the cost of
        // one `activeNetwork` read per interval.
        val pollJob = launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                emitReconciled(tracker.isOnline())
            }
        }

        awaitClose {
            pollJob.cancel()
            connectivityManager.unregisterNetworkCallback(callback)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentOnline())

    private fun currentOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return isQualifyingNetwork(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    override fun isMetered(): Boolean = connectivityManager.isActiveNetworkMetered

    private companion object {
        // 15s is a compromise between "banner appears reasonably soon after airplane on" and
        // "we don't pay noticeable cost polling `activeNetwork` all day." The Android 13 dropped-
        // `onLost` case is the whole reason this exists — see the callbackFlow block comment.
        const val POLL_INTERVAL_MS = 15_000L
    }
}

/**
 * The reconciliation predicate that gates every `isOnline` emit. Both the
 * `NetworkCallback`-driven `ValidatedNetworkTracker` AND
 * `ConnectivityManager.activeNetwork` must agree that we're online — either signal saying "no"
 * wins.
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
 * The rule is: `NET_CAPABILITY_INTERNET` is required; `NET_CAPABILITY_VALIDATED` is deliberately
 * **not** required. VALIDATED is set by Android's `NetworkMonitor` after a successful probe to
 * `connectivitycheck.gstatic.com/generate_204`. That probe host is unreachable on Huawei devices
 * without GMS and on any network that firewalls Google endpoints, so the OS marks the WiFi as
 * `INTERNET` without `VALIDATED` and Riffle would report the user permanently offline. Riffle
 * only ever talks to the user's Audiobookshelf server, their WebDAV endpoint, and an optional
 * Storyteller peer — none of which route through Google. Server-reachability is separately
 * tracked by `LibraryItemsViewModel._refreshFailed`, which is the correct signal for "we can see
 * the LAN but not your server."
 *
 * Pure so a JVM test can fence off the "someone re-adds && hasValidated" regression without
 * needing an Android instrumentation harness. Do not fold `hasValidated` back into the return
 * value.
 */
@Suppress("UNUSED_PARAMETER")
internal fun isQualifyingNetwork(hasInternet: Boolean, hasValidated: Boolean): Boolean = hasInternet
