package ua.movieportal.tv.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Commands for the active player.
 */
sealed interface PlayerCommand {
	data object Stop : PlayerCommand
	data object Pause : PlayerCommand
	data object Unpause : PlayerCommand
	data object PlayPause : PlayerCommand
	data class Seek(val positionTicks: Long) : PlayerCommand
	data class SetAudioStream(val index: Int) : PlayerCommand
	data class SetSubtitleStream(val index: Int) : PlayerCommand

	/** Volume changes applied to the player itself when the system volume is fixed (HDMI-CEC / passthrough). */
	data class SetVolume(val level: Int) : PlayerCommand
	data class SetMuted(val muted: Boolean?) : PlayerCommand
}

/**
 * Messages received from the Jellyfin session WebSocket that the receiver cares about.
 */
sealed interface RemoteCommand {
	data class Play(
		val itemId: String,
		val playCommand: String,
		val startPositionTicks: Long?,
		val audioStreamIndex: Int?,
		val subtitleStreamIndex: Int?,
		val mediaSourceId: String?,
	) : RemoteCommand

	data class Player(val command: PlayerCommand) : RemoteCommand

	data class SetVolume(val level: Int) : RemoteCommand
	data object VolumeUp : RemoteCommand
	data object VolumeDown : RemoteCommand
	data object Mute : RemoteCommand
	data object Unmute : RemoteCommand
	data object ToggleMute : RemoteCommand

	data class DisplayMessage(val header: String?, val text: String) : RemoteCommand

	/** Server asks the client to send KeepAlive messages at least every [timeoutSeconds]. */
	data class ForceKeepAlive(val timeoutSeconds: Int) : RemoteCommand
	data object KeepAlive : RemoteCommand

	data class Ignored(val messageType: String, val detail: String? = null) : RemoteCommand

	companion object {
		/** Commands advertised in the session capabilities. */
		val supportedGeneralCommands = listOf(
			"SetAudioStreamIndex",
			"SetSubtitleStreamIndex",
			"SetVolume",
			"VolumeUp",
			"VolumeDown",
			"Mute",
			"Unmute",
			"ToggleMute",
			"DisplayMessage",
		)
	}
}

/**
 * Parser for Jellyfin WebSocket messages (`{"MessageType": "...", "Data": ...}`).
 * Works on raw JSON, so additions to the server schema do not break it.
 */
object RemoteMessageParser {
	private val json = Json { ignoreUnknownKeys = true }

	fun parse(text: String): RemoteCommand? {
		val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
		val type = root.string("MessageType") ?: return null
		val data = root.get("Data")

		return when (type) {
			"ForceKeepAlive" -> RemoteCommand.ForceKeepAlive((data as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: DEFAULT_KEEP_ALIVE)
			"KeepAlive" -> RemoteCommand.KeepAlive
			"Play" -> parsePlay(data as? JsonObject)
			"Playstate" -> parsePlaystate(data as? JsonObject)
			"GeneralCommand" -> parseGeneralCommand(data as? JsonObject)
			else -> RemoteCommand.Ignored(type)
		}
	}

	private fun parsePlay(data: JsonObject?): RemoteCommand {
		data ?: return RemoteCommand.Ignored("Play", "no data")
		val itemIds = (data.get("ItemIds") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
		val startIndex = data.int("StartIndex") ?: 0
		val itemId = itemIds.getOrNull(startIndex) ?: itemIds.firstOrNull() ?: return RemoteCommand.Ignored("Play", "no items")
		if (!GUID.matches(itemId)) return RemoteCommand.Ignored("Play", "invalid item id")

		return RemoteCommand.Play(
			itemId = itemId,
			playCommand = data.string("PlayCommand") ?: "PlayNow",
			startPositionTicks = data.ticks("StartPositionTicks"),
			audioStreamIndex = data.int("AudioStreamIndex")?.takeIf { it in STREAM_INDEX_RANGE },
			subtitleStreamIndex = data.int("SubtitleStreamIndex")?.takeIf { it in STREAM_INDEX_RANGE },
			mediaSourceId = data.string("MediaSourceId")?.takeIf { GUID.matches(it) },
		)
	}

	private fun parsePlaystate(data: JsonObject?): RemoteCommand {
		val command = data?.string("Command") ?: return RemoteCommand.Ignored("Playstate", "no command")
		val playerCommand = when (command) {
			"Stop" -> PlayerCommand.Stop
			"Pause" -> PlayerCommand.Pause
			"Unpause" -> PlayerCommand.Unpause
			"PlayPause" -> PlayerCommand.PlayPause
			"Seek" -> PlayerCommand.Seek(data.ticks("SeekPositionTicks") ?: return RemoteCommand.Ignored("Playstate", "invalid seek position"))
			else -> return RemoteCommand.Ignored("Playstate", command)
		}
		return RemoteCommand.Player(playerCommand)
	}

	private fun parseGeneralCommand(data: JsonObject?): RemoteCommand {
		val name = data?.string("Name") ?: return RemoteCommand.Ignored("GeneralCommand", "no name")
		val arguments = data.get("Arguments") as? JsonObject ?: JsonObject(emptyMap())

		return when (name) {
			"SetAudioStreamIndex" -> arguments.int("Index")?.takeIf { it in STREAM_INDEX_RANGE }
				?.let { RemoteCommand.Player(PlayerCommand.SetAudioStream(it)) }
				?: RemoteCommand.Ignored(name, "no index")

			"SetSubtitleStreamIndex" -> arguments.int("Index")?.takeIf { it in STREAM_INDEX_RANGE }
				?.let { RemoteCommand.Player(PlayerCommand.SetSubtitleStream(it)) }
				?: RemoteCommand.Ignored(name, "no index")

			"SetVolume" -> arguments.int("Volume")
				?.let { RemoteCommand.SetVolume(it.coerceIn(0, MAX_VOLUME)) }
				?: RemoteCommand.Ignored(name, "no volume")

			"VolumeUp" -> RemoteCommand.VolumeUp
			"VolumeDown" -> RemoteCommand.VolumeDown
			"Mute" -> RemoteCommand.Mute
			"Unmute" -> RemoteCommand.Unmute
			"ToggleMute" -> RemoteCommand.ToggleMute

			"DisplayMessage", "SendString" -> {
				val text = arguments.string("Text") ?: arguments.string("String")
				if (text.isNullOrBlank()) RemoteCommand.Ignored(name, "empty text")
				// Shown as plain text in a toast; length-limited so a server can not flood the screen
				else RemoteCommand.DisplayMessage(
					header = arguments.string("Header")?.takeIf { it.isNotBlank() }?.take(MAX_HEADER_LENGTH),
					text = text.take(MAX_MESSAGE_LENGTH),
				)
			}

			else -> RemoteCommand.Ignored(name)
		}
	}

	// Jellyfin uses PascalCase keys, but be lenient about the casing
	private fun JsonObject.find(key: String): JsonElement? =
		get(key) ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

	private fun JsonObject.string(key: String): String? = when (val value = find(key)) {
		null, JsonNull -> null
		is JsonPrimitive -> value.contentOrNull
		else -> null
	}

	// Numbers may come as JSON numbers or strings (GeneralCommand arguments are always strings)
	private fun JsonObject.long(key: String): Long? = string(key)?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
	private fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

	// Positions are never negative; anything beyond 100 days is garbage
	private fun JsonObject.ticks(key: String): Long? = long(key)?.takeIf { it in 0..MAX_TICKS }

	// Jellyfin GUIDs, with or without dashes
	private val GUID = Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$")
	private val STREAM_INDEX_RANGE = -1..9_999
	private const val MAX_TICKS = 100L * 24 * 3600 * 10_000_000
	private const val MAX_HEADER_LENGTH = 100
	private const val MAX_MESSAGE_LENGTH = 500

	private const val DEFAULT_KEEP_ALIVE = 60
	private const val MAX_VOLUME = 100
}
