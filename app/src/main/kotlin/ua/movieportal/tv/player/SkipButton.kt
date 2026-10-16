package ua.movieportal.tv.player

import android.os.SystemClock
import android.widget.Button
import androidx.core.view.isVisible
import ua.movieportal.tv.R

/**
 * Skip intro/credits button at the bottom right, outside the control panel.
 *
 * Shown while playback is inside a segment; hidden when leaving it, when the user dismisses it (Back) and after
 * [AUTO_HIDE_MS] if ignored. A dismissed or ignored segment only comes back after leaving it and seeking into it again.
 */
class SkipButton(private val button: Button, private val onSkip: (SkipOffer) -> Unit) {
	var segments: List<Segment> = emptyList()
		private set

	// Segment the current offer belongs to and when it was first shown
	private var offer: SkipOffer? = null
	private var offeredAtMs = 0L
	// Segment the user dismissed (Back) or ignored until the auto-hide; cleared once playback leaves it
	private var dismissed: Segment? = null

	val isShown get() = button.isVisible

	init {
		button.setOnClickListener { offer?.let(onSkip) }
	}

	fun setSegments(value: List<Segment>) {
		segments = SkipSegments.usable(value)
		reset()
	}

	/** New item / stopped: forget everything. */
	fun clear() {
		segments = emptyList()
		reset()
	}

	/**
	 * Called periodically while playing.
	 * @param panelBusy the control panel or the track columns are open: the button stays out of the way
	 */
	fun update(positionMs: Long, durationMs: Long?, hasNext: Boolean, panelBusy: Boolean) {
		val segment = SkipSegments.active(segments, positionMs)
		if (segment != dismissed) dismissed = null
		if (segment == null || segment == dismissed) {
			offer = null
			hide()
			return
		}

		// The auto-hide timer belongs to the segment; the offer itself is recomputed because the next item and the
		// duration may become known while inside the segment
		if (offer?.segment != segment) offeredAtMs = SystemClock.elapsedRealtime()
		offer = SkipSegments.offer(segment, hasNext, durationMs)

		if (SystemClock.elapsedRealtime() - offeredAtMs > AUTO_HIDE_MS) {
			dismiss()
			return
		}
		if (panelBusy) {
			hide()
			return
		}

		val shown = offer ?: return
		button.text = button.context.getString(labelRes(shown.label))
		if (!button.isVisible) {
			button.isVisible = true
			button.requestFocusFromTouch()
		}
	}

	/** Back while the button is shown: not interested in this segment. */
	fun dismiss() {
		dismissed = offer?.segment
		offer = null
		hide()
	}

	/** The control panel is opening: get out of its way at once (the next [update] keeps it hidden). */
	fun hide() {
		if (!button.isVisible) return
		button.clearFocus()
		button.isVisible = false
	}

	private fun reset() {
		offer = null
		dismissed = null
		hide()
	}

	private fun labelRes(label: SkipLabel) = when (label) {
		SkipLabel.SKIP_INTRO -> R.string.player_skip_intro
		SkipLabel.SKIP_RECAP -> R.string.player_skip_recap
		SkipLabel.SKIP -> R.string.player_skip
		SkipLabel.SKIP_CREDITS -> R.string.player_skip_credits
		SkipLabel.NEXT_EPISODE -> R.string.player_next_episode
	}

	companion object {
		const val AUTO_HIDE_MS = 10_000L
	}
}
