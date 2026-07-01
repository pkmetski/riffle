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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
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
        // Online-state is derived from two signals, treating every radio identically — airplane
        // mode is just "all radios off" and isn't special-cased:
        //   * NetworkCallback — primary, event-driven. onAvailable/onLost/onCapabilitiesChanged
        //     fire for every transport (wifi, cellular, ethernet, VPN) on every toggle.
        //   * ProcessLifecycleOwner ON_START — heals doze/wake drift on Android 13+ where
        //     NetworkCallback events can be dropped or coalesced. `activeNetwork` is the coarse
        //     ground-truth: null → offline, non-null → union any newly-validated networks in.
        //     The sweep never removes networks the callbacks have already reported, so a stale
        //     `getAllNetworks()` during teardown cannot revert a correct offline emit.
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
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                emitReconciled(tracker.onCapabilitiesChanged(network, hasInternet))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentOnline())

    private fun currentOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun isMetered(): Boolean = connectivityManager.isActiveNetworkMetered
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
 *     one of the `onLost` callbacks when airplane mode turns multiple validated networks off at
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
