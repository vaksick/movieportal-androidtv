@file:OptIn(ExperimentalCoroutinesApi::class)

package ua.movieportal.tv.service

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import ua.movieportal.tv.remote.ConnectionState
import ua.movieportal.tv.remote.RemoteCommand
import ua.movieportal.tv.remote.RemoteMessageParser
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Jellyfin session WebSocket (`/socket?api_key=…&deviceId=…`) with:
 * - exponential reconnect backoff (1 s -> 60 s), reset after a successful connection;
 * - KeepAlive messages at the interval requested by the server's ForceKeepAlive;
 * - immediate reconnect on network changes ([reconnectNow]);
 * - detection of a rejected token (handshake 401/403, confirmed by [callbacks]).
 */
class SessionSocket(
	baseClient: OkHttpClient,
	private val serverUrl: String,
	private val accessToken: String,
	private val deviceId: String,
	private val authorizationHeader: () -> String,
	private val scope: CoroutineScope,
	private val callbacks: Callbacks,
) {
	interface Callbacks {
		fun onStateChanged(state: ConnectionState)

		/** Called after every successful (re)connection, e.g. to register capabilities. */
		suspend fun onConnected()

		suspend fun onCommand(command: RemoteCommand)

		/** The handshake was rejected; return true when the token is confirmed invalid (stops the socket). */
		suspend fun isTokenRevoked(): Boolean

		fun isNetworkAvailable(): Boolean
	}

	private sealed interface Event {
		data class Opened(val socket: WebSocket) : Event
		data class Message(val text: String) : Event
		data class Closed(val code: Int, val reason: String) : Event
		data class Failed(val error: Throwable, val httpCode: Int?) : Event
	}

	private enum class Outcome { CLOSED_AFTER_CONNECT, FAILED, REJECTED, INTERRUPTED }

	// Ping frames detect half-open TCP connections (e.g. after Wi-Fi drops) much faster than TCP itself
	private val client = baseClient.newBuilder()
		.pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
		.readTimeout(0, TimeUnit.MILLISECONDS)
		.build()

	private val wakeUp = Channel<Unit>(Channel.CONFLATED)
	private var job: Job? = null

	@Volatile
	private var currentSocket: WebSocket? = null

	fun start() {
		if (job?.isActive == true) return
		job = scope.launch { runLoop() }
	}

	fun stop() {
		job?.cancel()
		job = null
		currentSocket?.close(NORMAL_CLOSURE, "client stopped")
		currentSocket = null
	}

	/** Drop the current connection (if any) and reconnect without waiting for the backoff delay. */
	fun reconnectNow() {
		currentSocket?.cancel()
		wakeUp.trySend(Unit)
	}

	private suspend fun runLoop() {
		var attempt = 0
		while (scope.isActive) {
			if (!callbacks.isNetworkAvailable()) {
				callbacks.onStateChanged(ConnectionState.Offline)
				// Wait for the network callback, re-check periodically in case a callback was missed
				select {
					wakeUp.onReceive { }
					onTimeout(OFFLINE_RECHECK_MS) { }
				}
				continue
			}

			callbacks.onStateChanged(ConnectionState.Connecting)
			when (connectOnce()) {
				Outcome.CLOSED_AFTER_CONNECT -> attempt = 0
				Outcome.INTERRUPTED -> {
					attempt = 0
					continue
				}

				Outcome.REJECTED -> if (callbacks.isTokenRevoked()) {
					Timber.w("Access token revoked, stopping socket")
					return
				}

				Outcome.FAILED -> Unit
			}

			val delaySeconds = backoffSeconds(attempt)
			attempt++
			callbacks.onStateChanged(ConnectionState.Reconnecting(delaySeconds))
			Timber.i("Reconnecting in ${delaySeconds}s (attempt $attempt)")
			select {
				wakeUp.onReceive { attempt = 0 }
				onTimeout(delaySeconds * 1000L) { }
			}
		}
	}

	private suspend fun connectOnce(): Outcome {
		// Drop a stale wake-up so it does not interrupt the new connection
		wakeUp.tryReceive()

		val url = socketUrl() ?: run {
			Timber.e("Invalid server URL for WebSocket")
			return Outcome.FAILED
		}
		val request = Request.Builder()
			.url(url)
			.header("Authorization", authorizationHeader())
			.build()

		val events = Channel<Event>(Channel.UNLIMITED)
		val socket = client.newWebSocket(request, object : WebSocketListener() {
			override fun onOpen(webSocket: WebSocket, response: Response) {
				events.trySend(Event.Opened(webSocket))
			}

			override fun onMessage(webSocket: WebSocket, text: String) {
				events.trySend(Event.Message(text))
			}

			override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
				webSocket.close(NORMAL_CLOSURE, null)
				events.trySend(Event.Closed(code, reason))
			}

			override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
				events.trySend(Event.Closed(code, reason))
			}

			override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
				events.trySend(Event.Failed(t, response?.code))
			}
		})
		currentSocket = socket

		var connected = false
		var connectedAt = 0L
		var keepAliveJob: Job? = null
		try {
			while (true) {
				val event = select<Event?> {
					events.onReceive { it }
					// A wake-up while connected means "network changed": drop this connection
					wakeUp.onReceive { null }
				} ?: run {
					Timber.i("Network changed, reconnecting")
					return Outcome.INTERRUPTED
				}

				when (event) {
					is Event.Opened -> {
						connected = true
						connectedAt = SystemClock.elapsedRealtime()
						Timber.i("WebSocket connected")
						callbacks.onStateChanged(ConnectionState.Connected)
						scope.launch { callbacks.onConnected() }
					}

					is Event.Message -> {
						val command = RemoteMessageParser.parse(event.text) ?: continue
						when (command) {
							is RemoteCommand.ForceKeepAlive -> {
								// Reply now and then at half the server timeout, like the official clients
								socket.send(KEEP_ALIVE_MESSAGE)
								keepAliveJob?.cancel()
								val intervalMs = (command.timeoutSeconds.coerceAtLeast(MIN_KEEP_ALIVE_S) * 1000L) / 2
								keepAliveJob = scope.launch {
									while (isActive) {
										delay(intervalMs)
										socket.send(KEEP_ALIVE_MESSAGE)
									}
								}
							}

							RemoteCommand.KeepAlive -> Unit
							else -> scope.launch { callbacks.onCommand(command) }
						}
					}

					is Event.Closed -> {
						Timber.i("WebSocket closed: ${event.code} ${event.reason}")
						// Jellyfin closes the socket when the session/token is removed: verify on the next handshake
						return if (wasStable(connected, connectedAt)) Outcome.CLOSED_AFTER_CONNECT else Outcome.FAILED
					}

					is Event.Failed -> {
						Timber.w("WebSocket failure (http ${event.httpCode}): ${event.error.message}")
						return when {
							event.httpCode == HTTP_UNAUTHORIZED || event.httpCode == HTTP_FORBIDDEN -> Outcome.REJECTED
							wasStable(connected, connectedAt) -> Outcome.CLOSED_AFTER_CONNECT
							else -> Outcome.FAILED
						}
					}
				}
			}
		} finally {
			keepAliveJob?.cancel()
			socket.cancel()
			if (currentSocket === socket) currentSocket = null
		}
	}

	// Only a connection that lasted a while resets the backoff, so an accept-then-close loop still backs off
	private fun wasStable(connected: Boolean, connectedAt: Long) =
		connected && SystemClock.elapsedRealtime() - connectedAt >= STABLE_CONNECTION_MS

	private fun socketUrl(): String? {
		val base = serverUrl.toHttpUrlOrNull() ?: return null
		// OkHttp accepts http(s) URLs for WebSockets and upgrades them to ws(s)
		return base.newBuilder()
			.addPathSegment("socket")
			.addQueryParameter("api_key", accessToken)
			.addQueryParameter("deviceId", deviceId)
			.build()
			.toString()
	}

	companion object {
		private const val KEEP_ALIVE_MESSAGE = """{"MessageType":"KeepAlive"}"""
		private const val NORMAL_CLOSURE = 1000
		private const val HTTP_UNAUTHORIZED = 401
		private const val HTTP_FORBIDDEN = 403
		private const val PING_INTERVAL_S = 30L
		private const val MIN_KEEP_ALIVE_S = 10
		private const val OFFLINE_RECHECK_MS = 30_000L
		private const val MAX_BACKOFF_S = 60
		private const val STABLE_CONNECTION_MS = 10_000L

		/** 1, 2, 4, 8, 16, 32, 60, 60, … seconds. */
		fun backoffSeconds(attempt: Int): Int = min(MAX_BACKOFF_S, 1 shl attempt.coerceIn(0, 6))
	}
}
