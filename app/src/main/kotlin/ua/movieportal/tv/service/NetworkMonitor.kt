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

	private val callback = object : ConnectivityManager.NetworkCallback() {
		override fun onAvailable(network: Network) {
			val previous = currentNetwork
			currentNetwork = network
			if (previous != network) {
				Timber.i("Network available: $network (was $previous)")
				onNetworkChanged()
			}
		}

		override fun onLost(network: Network) {
			if (currentNetwork == network) currentNetwork = null
			Timber.i("Network lost: $network")
			onNetworkChanged()
		}
	}

	val isNetworkAvailable: Boolean
		get() {
			val network = connectivityManager.activeNetwork ?: return false
			val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
			return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
		}

	fun start() {
		currentNetwork = connectivityManager.activeNetwork
		connectivityManager.registerDefaultNetworkCallback(callback)
	}

	fun stop() {
		runCatching { connectivityManager.unregisterNetworkCallback(callback) }
	}
}
