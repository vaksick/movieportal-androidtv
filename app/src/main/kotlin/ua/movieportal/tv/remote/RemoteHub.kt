package ua.movieportal.tv.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * State of the connection to the Jellyfin server WebSocket.
 */
sealed interface ConnectionState {
	data object Stopped : ConnectionState
	data object Connecting : ConnectionState
	data object Connected : ConnectionState
	data class Reconnecting(val delaySeconds: Int) : ConnectionState
	data object Offline : ConnectionState
}

/**
 * A "play now" request received from the server.
 */
data class PlayRequest(
	val id: Long,
	val itemId: String,
	val startPositionTicks: Long?,
	val audioStreamIndex: Int?,
	val subtitleStreamIndex: Int?,
	val mediaSourceId: String?,
)

/**
 * In-process bus between the background service and the player activity.
 */
object RemoteHub {
	private val requestIds = AtomicLong(0)
	private val handledRequestId = AtomicLong(0)

	private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
	val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

	private val _playRequest = MutableStateFlow<PlayRequest?>(null)

	/** Latest play request; the player consumes requests with an id greater than the last handled one. */
	val playRequest: StateFlow<PlayRequest?> = _playRequest.asStateFlow()

	private val _playerCommands = MutableSharedFlow<PlayerCommand>(extraBufferCapacity = 32)

	/** Commands for the active player; dropped when no player is running. */
	val playerCommands: SharedFlow<PlayerCommand> = _playerCommands.asSharedFlow()

	/** Token of the player instance that currently accepts commands (null: no player). */
	@Volatile
	var activePlayer: Any? = null

	/** True while a player accepts play requests and commands. */
	val playerActive: Boolean get() = activePlayer != null

	fun setConnectionState(state: ConnectionState) {
		_connectionState.value = state
	}

	fun submitPlay(command: RemoteCommand.Play): PlayRequest {
		val request = PlayRequest(
			id = requestIds.incrementAndGet(),
			itemId = command.itemId,
			startPositionTicks = command.startPositionTicks,
			audioStreamIndex = command.audioStreamIndex,
			subtitleStreamIndex = command.subtitleStreamIndex,
			mediaSourceId = command.mediaSourceId,
		)
		_playRequest.value = request
		return request
	}

	/** Marks the request as handled; returns false when it was already handled (e.g. activity recreated). */
	fun claimPlayRequest(request: PlayRequest): Boolean {
		while (true) {
			val handled = handledRequestId.get()
			if (request.id <= handled) return false
			if (handledRequestId.compareAndSet(handled, request.id)) return true
		}
	}

	/** True when a play request is waiting for the player. */
	val hasPendingPlayRequest: Boolean
		get() = (playRequest.value?.id ?: 0) > handledRequestId.get()

	fun sendToPlayer(command: PlayerCommand): Boolean = playerActive && _playerCommands.tryEmit(command)
}
