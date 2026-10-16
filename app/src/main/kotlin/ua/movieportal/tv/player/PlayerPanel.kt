package ua.movieportal.tv.player

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
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
 * - "interactive" mode (key up / down): focus on the buttons or the timeline, D-pad navigates the panel.
 */
@UnstableApi
class PlayerPanel(
	root: View,
	private val player: Player,
	onPlayPause: () -> Unit,
	onRewind: () -> Unit,
	onForward: () -> Unit,
	onNext: () -> Unit,
	onTracks: () -> Unit,
	onTimeBarKey: (KeyEvent) -> Boolean,
) {
	/** Element focused when the interactive panel opens. */
	enum class Focus { TIMELINE, PLAY_PAUSE, TRACKS }

	private val panel = root.findViewById<View>(R.id.player_panel)
	private val title = root.findViewById<TextView>(R.id.player_title)
	private val time = root.findViewById<TextView>(R.id.player_time)
	private val timeBar = root.findViewById<DefaultTimeBar>(R.id.player_timebar)
	private val playPauseButton = root.findViewById<ImageButton>(R.id.player_play_pause)
	private val rewindButton = root.findViewById<ImageButton>(R.id.player_rewind)
	private val forwardButton = root.findViewById<ImageButton>(R.id.player_forward)
	private val nextButton = root.findViewById<ImageButton>(R.id.player_next)
	private val tracksButton = root.findViewById<ImageButton>(R.id.player_tracks_button)
	private val tracks = TrackPanel(root)

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
		playPauseButton.setOnClickListener { onPlayPause() }
		rewindButton.setOnClickListener { onRewind() }
		forwardButton.setOnClickListener { onForward() }
		nextButton.setOnClickListener { onNext() }
		tracksButton.setOnClickListener { onTracks() }
		// The activity drives seeking (with the pending-seek preview) instead of DefaultTimeBar's own scrubbing
		timeBar.setOnKeyListener { _, _, event -> onTimeBarKey(event) }
	}

	val isTracksVisible get() = tracks.isVisible

	/** Opens the audio/subtitle columns above the panel content (the panel itself stays open). */
	fun showTracks(
		audio: List<TrackPanel.Row>,
		selectedAudio: Int?,
		subtitles: List<TrackPanel.Row>,
		selectedSubtitle: Int?,
		onAudio: (Int) -> Unit,
		onSubtitle: (Int) -> Unit,
	) {
		showInteractive(Focus.TRACKS)
		val rootView = panel.rootView
		val gap = (TRACKS_GAP_DP * rootView.resources.displayMetrics.density).toInt()
		// Bottom of the track panel just above the title: the timeline and the buttons stay visible
		val titleTop = IntArray(2).also(title::getLocationInWindow)[1]
		val windowHeight = rootView.height
		val bottom = windowHeight - titleTop + gap
		tracks.show(audio, selectedAudio, subtitles, selectedSubtitle, bottom, titleTop - gap * 2, onAudio, onSubtitle)
	}

	fun updateTracks(selectedAudio: Int?, selectedSubtitle: Int?) = tracks.updateSelection(selectedAudio, selectedSubtitle)

	/** Back in the track panel: close it and return to the button that opened it. */
	fun closeTracks() {
		tracks.hide()
		showInteractive(Focus.TRACKS)
	}

	/** Label of the "next" button, or null to hide it (nothing follows the current item). */
	fun setNext(label: String?) {
		if (label == null && nextButton.hasFocus()) playPauseButton.requestFocus()
		nextButton.contentDescription = label
		nextButton.isVisible = label != null
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

	fun showInteractive(focus: Focus = Focus.PLAY_PAUSE) {
		isInteractive = true
		previewPositionMs = null
		show()
		when (focus) {
			Focus.TIMELINE -> timeBar.requestFocus()
			Focus.PLAY_PAUSE -> playPauseButton.requestFocus()
			Focus.TRACKS -> tracksButton.requestFocus()
		}
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
		tracks.hide()
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
		time.text = time.context.getString(R.string.player_time, formatTime(positionMs), formatTime(durationMs))
		// The button shows the action it performs
		val playing = player.playWhenReady
		playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
		playPauseButton.contentDescription = playPauseButton.context.getString(if (playing) R.string.player_pause else R.string.player_play)
	}

	private companion object {
		const val TICK_MS = 500L
		const val INFO_TIMEOUT_MS = 3_000L
		const val INTERACTIVE_TIMEOUT_MS = 8_000L
		const val TRACKS_GAP_DP = 16

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
