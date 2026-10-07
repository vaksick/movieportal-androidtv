package ua.movieportal.tv.discovery

import android.content.Context
import android.net.wifi.WifiManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.PublicSystemInfo
import timber.log.Timber
import ua.movieportal.tv.BuildConfig
import ua.movieportal.tv.jellyfin.JellyfinClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * Finds Jellyfin servers on the local network (UDP broadcast to port 7359) and checks which of them are reachable.
 */
class ServerDiscovery(context: Context, private val jellyfin: JellyfinClient) {
	private val wifiManager = context.applicationContext.getSystemService<WifiManager>()

	/** One broadcast round followed by validation of every answer. */
	suspend fun scan(): List<DiscoveredServer> {
		val responses = broadcast()
		return coroutineScope {
			responses.map { response -> async { validate(response) } }.awaitAll()
		}
	}

	private suspend fun broadcast(): List<DiscoveryResponse> = withContext(Dispatchers.IO) {
		// Many TVs filter incoming broadcast/multicast traffic unless a multicast lock is held
		val lock = wifiManager?.createMulticastLock("movieportal-discovery")?.apply {
			setReferenceCounted(false)
			runCatching { acquire() }.onFailure { Timber.w(it, "Unable to acquire multicast lock") }
		}

		try {
			DatagramSocket().use { socket ->
				socket.broadcast = true
				socket.soTimeout = RECEIVE_TIMEOUT_MS

				val message = DiscoveryProtocol.MESSAGE.toByteArray()
				for (address in broadcastAddresses()) {
					runCatching { socket.send(DatagramPacket(message, message.size, address, DiscoveryProtocol.PORT)) }
						.onFailure { Timber.w("Discovery send to $address failed: ${it.message}") }
				}

				val responses = linkedMapOf<String, DiscoveryResponse>()
				val buffer = ByteArray(RECEIVE_BUFFER)
				val deadline = System.currentTimeMillis() + SCAN_DURATION_MS
				while (System.currentTimeMillis() < deadline) {
					coroutineContext.ensureActive()
					val packet = DatagramPacket(buffer, buffer.size)
					try {
						socket.receive(packet)
					} catch (_: SocketTimeoutException) {
						continue
					}
					val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
					val response = DiscoveryProtocol.parse(text) ?: continue
					// Several interfaces may answer for one server: keep the first reply
					if (responses.keys.none { DiscoveryProtocol.sameId(it, response.id) }) responses[response.id] = response
				}
				Timber.i("Discovery found ${responses.size} server(s)")
				responses.values.toList()
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Discovery failed")
			emptyList()
		} finally {
			runCatching { lock?.release() }
		}
	}

	/** Checks all candidate addresses in parallel and prefers https (see [DiscoveryProtocol.pickBest]). */
	private suspend fun validate(response: DiscoveryResponse): DiscoveredServer {
		val candidates = DiscoveryProtocol.candidateUrls(response)
		val results = coroutineScope {
			candidates.map { url -> async { url to probe(url, response.id) } }.awaitAll()
		}
		val working = results.mapNotNull { (url, info) -> info?.let { url } }.toSet()
		val best = DiscoveryProtocol.pickBest(candidates, working)
		if (best != null) {
			val info = results.first { it.first == best }.second!!
			return DiscoveredServer.Reachable(response.id, info.serverName ?: response.name, best, info.version)
		}

		// Do not report servers as unreachable when the scan was only cancelled (screen closed)
		coroutineContext.ensureActive()
		Timber.w("Discovered server ${response.name} is not reachable at ${response.reportedAddress}")
		return DiscoveredServer.Unreachable(response.id, response.name, response.reportedAddress)
	}

	/** `GET /System/Info/Public` with normal TLS validation; null unless the expected server answers. */
	private suspend fun probe(url: String, expectedId: String): PublicSystemInfo? = withTimeoutOrNull(VALIDATE_TIMEOUT_MS) {
		try {
			jellyfin.getPublicSystemInfo(url).takeIf { DiscoveryProtocol.sameId(it.id, expectedId) }
		} catch (err: CancellationException) {
			throw err
		} catch (_: Exception) {
			null
		}
	}

	private fun broadcastAddresses(): Set<InetAddress> = buildSet {
		add(InetAddress.getByName("255.255.255.255"))
		runCatching {
			NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
				.filter { it.isUp && !it.isLoopback }
				.flatMap { it.interfaceAddresses }
				.mapNotNull { it.broadcast }
				.forEach(::add)
		}
		// Emulator NAT drops broadcasts: in debug builds also ask the host loopback (10.0.2.2), where a UDP relay
		// (tools/emulator-discovery/3proxy.cfg) forwards the request to the real server
		if (BuildConfig.DEBUG) add(InetAddress.getByName(EMULATOR_HOST))
	}

	private companion object {
		const val SCAN_DURATION_MS = 3_000L
		const val RECEIVE_TIMEOUT_MS = 500
		const val RECEIVE_BUFFER = 4096
		const val VALIDATE_TIMEOUT_MS = 4_000L
		const val EMULATOR_HOST = "10.0.2.2"
	}
}
