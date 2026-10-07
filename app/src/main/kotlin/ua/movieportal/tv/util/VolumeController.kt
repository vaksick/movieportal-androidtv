package ua.movieportal.tv.util

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import ua.movieportal.tv.remote.PlayerCommand
import ua.movieportal.tv.remote.RemoteHub
import kotlin.math.roundToInt

/**
 * Applies remote volume commands to the system media volume. When the system volume is fixed (common on TVs that
 * control volume over HDMI-CEC), the player's own volume is changed instead.
 */
class VolumeController(context: Context) {
	private val audioManager = requireNotNull(context.getSystemService<AudioManager>())
	private val maxVolume get() = audioManager.getStreamMaxVolume(STREAM).coerceAtLeast(1)

	val isFixed: Boolean get() = audioManager.isVolumeFixed

	/** System volume in percent. */
	val level: Int get() = (audioManager.getStreamVolume(STREAM) * 100f / maxVolume).roundToInt()

	val isMuted: Boolean get() = audioManager.isStreamMute(STREAM)

	fun setLevel(percent: Int) {
		if (isFixed) {
			RemoteHub.sendToPlayer(PlayerCommand.SetVolume(percent))
			return
		}
		val index = (percent.coerceIn(0, 100) / 100f * maxVolume).roundToInt()
		audioManager.setStreamVolume(STREAM, index, AudioManager.FLAG_SHOW_UI)
	}

	fun up() = adjust(AudioManager.ADJUST_RAISE)

	fun down() = adjust(AudioManager.ADJUST_LOWER)

	fun mute() = if (isFixed) sendMuted(true) else adjust(AudioManager.ADJUST_MUTE)

	fun unmute() = if (isFixed) sendMuted(false) else adjust(AudioManager.ADJUST_UNMUTE)

	fun toggleMute() = if (isFixed) sendMuted(null) else adjust(AudioManager.ADJUST_TOGGLE_MUTE)

	private fun adjust(direction: Int) {
		if (isFixed) return
		audioManager.adjustStreamVolume(STREAM, direction, AudioManager.FLAG_SHOW_UI)
	}

	private fun sendMuted(muted: Boolean?) {
		RemoteHub.sendToPlayer(PlayerCommand.SetMuted(muted))
	}

	private companion object {
		const val STREAM = AudioManager.STREAM_MUSIC
	}
}
