package com.riffle.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.ConnectivityObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    // syncNow() pokes this; the callbackFlow collector reconciles the tracker against the live
    // ConnectivityManager snapshot. Buffered + DROP_OLDEST so a caller storm doesn't wedge the
    // emitter but the next reconciliation still happens.
    private val syncSignal = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val isOnline: StateFlow<Boolean> = callbackFlow {
        // Online-state is derived from callback events rather than re-queried on each transition.
        // Re-querying ConnectivityManager.activeNetwork inside onLost is racy: at the instant the
        // callback fires, the just-lost network can still appear active and validated, which would
        // emit `true` and — because our NetworkRequest filters on VALIDATED — never produce another
        // event for that network. Result: airplane mode never flipped the offline banner.
        val tracker = ValidatedNetworkTracker<Network>()
        trySend(currentOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(tracker.onAvailable(network))
            }

            override fun onLost(network: Network) {
                trySend(tracker.onLost(network))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(tracker.onCapabilitiesChanged(network, hasInternet))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        // Doze/wake on Android 13 can coalesce or drop NetworkCallback events, leaving the tracker
        // holding stale state (banner sticks after resume). syncNow() sweeps allNetworks() and
        // reseeds the tracker so state matches reality on the next lifecycle-resume hop.
        val syncJob = launch {
            syncSignal.collect {
                val fresh = mutableSetOf<Network>()
                for (network in connectivityManager.allNetworks) {
                    val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ) {
                        fresh += network
                    }
                }
                trySend(tracker.reset(fresh))
            }
        }

        awaitClose {
            syncJob.cancel()
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentOnline())

    override fun syncNow() {
        syncSignal.tryEmit(Unit)
    }

    private fun currentOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun isMetered(): Boolean = connectivityManager.isActiveNetworkMetered
}
