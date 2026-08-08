package me.rerere.rikkahub.voiceagent.debug

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationNetwork

internal interface VoiceAutomationConnectivitySource {
    fun current(): VoiceAutomationConnectivity

    fun updates(): Flow<VoiceAutomationConnectivity>
}

internal class VoiceAutomationConnectivityReader(
    private val source: VoiceAutomationConnectivitySource,
    private val timeoutMillis: Long,
) {
    init {
        require(timeoutMillis > 0)
    }

    suspend fun read(): VoiceAutomationConnectivity {
        val immediate = source.current()
        if (immediate.validated) return immediate
        var latest = immediate
        val observed = withTimeoutOrNull(timeoutMillis) {
            source.updates().first { observed ->
                latest = observed
                observed.validated
            }
        }
        if (observed != null) return observed

        val refreshed = source.current()
        return if (refreshed.validated || refreshed.network != VoiceAutomationNetwork.NONE) {
            refreshed
        } else {
            latest
        }
    }
}

internal class AndroidVoiceAutomationConnectivitySource(
    private val connectivityManager: ConnectivityManager,
) : VoiceAutomationConnectivitySource {
    override fun current(): VoiceAutomationConnectivity =
        connectivityManager.activeNetwork
            ?.let(connectivityManager::getNetworkCapabilities)
            .toAutomationConnectivity()

    override fun updates(): Flow<VoiceAutomationConnectivity> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(networkCapabilities.toAutomationConnectivity())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}

private fun NetworkCapabilities?.toAutomationConnectivity(): VoiceAutomationConnectivity {
    val network = when {
        this?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
            VoiceAutomationNetwork.WIFI
        this?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
            VoiceAutomationNetwork.CELLULAR
        else -> VoiceAutomationNetwork.NONE
    }
    return VoiceAutomationConnectivity(
        network = network,
        validated = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
    )
}
