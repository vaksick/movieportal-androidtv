package ua.movieportal.tv.player

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import ua.movieportal.tv.R
import java.util.Locale

/**
 * Bottom panel with the title, timeline and the audio/subtitle buttons.
 *
 * - "info" mode: visible without focus (after seeking or while paused), the remote keeps controlling playback;
 * - "interactive" mode (key up): focus on the buttons, D-pad navigates the panel.
 */
@UnstableApi
class PlayerPanel(
	root: View,
	private val player: Player,
	onAudio: () -> Unit,
	onSubtitles: () -> Unit,
) {
	private val panel = root.findViewById<View>(R.id.player_panel)
	private val title = root.findViewById<TextView>(R.id.player_title)
	private val position = root.findViewById<TextView>(R.id.player_position)
	private val duration = root.findViewById<TextView>(R.id.player_duration)
	private val timeBar = root.findViewById<DefaultTimeBar>(R.id.player_timebar)
	private val state = root.findViewById<TextView>(R.id.player_state)
	private val audioButton = root.findViewById<Button>(R.id.player_audio)
	private val subtitlesButton = root.findViewById<Button>(R.id.player_subtitles)

	private val handler = Handler(Looper.getMainLooper())
	private val hideRunnable = Runnable { hide() }
	private val tickRunnable = object : Runnable {
		override fun run() {
			render()
			if (panel.isVisible) handler.postDelayed(this, TICK_MS)
		}
	}

	private var previewPositionMs: Long? = null

	var isInteractive = false
		private set

	val isVisible get() = panel.isVisible

	init {
		audioButton.setOnClickListener { onAudio() }
		subtitlesButton.setOnClickListener { onSubtitles() }
	}

	fun setTitle(text: String?) {
		title.text = text.orEmpty()
	}

	/** Show without taking focus; [preview] shows a pending seek target instead of the player position. */
	fun showInfo(preview: Long? = null) {
		previewPositionMs = preview
		if (!isInteractive) {
			show()
			scheduleHide(INFO_TIMEOUT_MS)
		} else {
			render()
		}
	}

	fun showInteractive() {
		isInteractive = true
		previewPositionMs = null
		show()
		audioButton.requestFocus()
		scheduleHide(INTERACTIVE_TIMEOUT_MS)
	}

	/** Any key press while the panel is interactive keeps it open. */
	fun onUserInteraction() {
		if (isInteractive) scheduleHide(INTERACTIVE_TIMEOUT_MS)
	}

	/** Paused playback keeps the panel visible; resuming hides it after the usual delay. */
	fun onPlayingStateChanged() {
		render()
		if (!player.playWhenReady) {
			show()
			handler.removeCallbacks(hideRunnable)
		} else if (panel.isVisible) {
			scheduleHide(if (isInteractive) INTERACTIVE_TIMEOUT_MS else INFO_TIMEOUT_MS)
		}
	}

	fun hide() {
		handler.removeCallbacks(hideRunnable)
		handler.removeCallbacks(tickRunnable)
		isInteractive = false
		previewPositionMs = null
		panel.clearFocus()
		panel.isVisible = false
	}

	fun release() {
		handler.removeCallbacksAndMessages(null)
	}

	private fun show() {
		panel.isVisible = true
		handler.removeCallbacks(tickRunnable)
		tickRunnable.run()
	}

	private fun scheduleHide(delayMs: Long) {
		handler.removeCallbacks(hideRunnable)
		// Paused playback keeps the timeline visible
		if (player.playWhenReady) handler.postDelayed(hideRunnable, delayMs)
	}

	private fun render() {
		val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
		val positionMs = previewPositionMs ?: player.currentPosition
		timeBar.setDuration(durationMs)
		timeBar.setPosition(positionMs)
		timeBar.setBufferedPosition(player.bufferedPosition)
		position.text = formatTime(positionMs)
		duration.text = formatTime(durationMs)
		state.text = if (player.playWhenReady) "▶" else "❚❚"
	}

	private companion object {
		const val TICK_MS = 500L
		const val INFO_TIMEOUT_MS = 3_000L
		const val INTERACTIVE_TIMEOUT_MS = 8_000L

		fun formatTime(ms: Long): String {
			val totalSeconds = (ms / 1000).coerceAtLeast(0)
			val hours = totalSeconds / 3600
			val minutes = (totalSeconds % 3600) / 60
			val seconds = totalSeconds % 60
			return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
			else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
		}
	}
}
