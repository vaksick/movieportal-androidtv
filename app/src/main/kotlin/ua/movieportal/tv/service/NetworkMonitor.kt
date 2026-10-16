package ua.movieportal.tv.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import timber.log.Timber

/**
 * Watches the default network and reports when it (re)appears or changes, so the WebSocket can reconnect
 * immediately instead of waiting for the backoff delay or a dead-connection timeout.
 */
class NetworkMonitor(context: Context, private val onNetworkChanged: () -> Unit) {
	private val connectivityManager = requireNotNull(context.getSystemService<ConnectivityManager>())

	@Volatile
	private var currentNetwork: Network? = null

	// INTERNET capability of currentNetwork as reported by the callback; ConnectivityManager.activeNetwork may still
	// be empty right after onAvailable, which used to keep the socket "offline" until the next periodic check
	@Volatile
	private var currentHasInternet = false

	private val callback = object : ConnectivityManager.NetworkCallback() {
		override fun onAvailable(network: Network) {
			val previous = currentNetwork
			currentNetwork = network
			if (previous != network) {
				currentHasInternet = false
				Timber.i("Network available: $network (was $previous)")
				onNetworkChanged()
			}
		}

		// Follows onAvailable once the capabilities are known: wake the socket again when the network becomes usable
		override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
			if (network != currentNetwork) return
			val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
			val becameUsable = hasInternet && !currentHasInternet
			currentHasInternet = hasInternet
			if (becameUsable) onNetworkChanged()
		}

		override fun onLost(network: Network) {
			if (currentNetwork == network) {
				currentNetwork = null
				currentHasInternet = false
			}
			Timber.i("Network lost: $network")
			onNetworkChanged()
		}
	}

	val isNetworkAvailable: Boolean
		get() {
			if (currentNetwork != null && currentHasInternet) return true
			val network = connectivityManager.activeNetwork ?: return false
			val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
			return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
		}

	fun start() {
		currentNetwork = connectivityManager.activeNetwork
		// Known state up front: the first onCapabilitiesChanged after registering must not reconnect the socket
		currentHasInternet = currentNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
			?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
		connectivityManager.registerDefaultNetworkCallback(callback)
	}

	fun stop() {
		runCatching { connectivityManager.unregisterNetworkCallback(callback) }
	}
}
