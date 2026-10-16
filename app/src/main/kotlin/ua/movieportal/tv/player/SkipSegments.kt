package ua.movieportal.tv.player

/**
 * Jellyfin media segment types the receiver offers to skip (Unknown segments are ignored).
 */
enum class SegmentKind { INTRO, RECAP, PREVIEW, COMMERCIAL, OUTRO }

/** A media segment in player milliseconds. */
data class Segment(val kind: SegmentKind, val startMs: Long, val endMs: Long)

/** What the skip button says. */
enum class SkipLabel { SKIP_INTRO, SKIP_RECAP, SKIP, SKIP_CREDITS, NEXT_EPISODE }

/** What OK on the skip button does. */
sealed interface SkipAction {
	/** Jump to the end of the segment. */
	data class SeekTo(val positionMs: Long) : SkipAction

	/** Start the next episode / queued item right away (credits with something after them). */
	data object PlayNext : SkipAction

	/** The segment runs (almost) to the end of the item: let the item end, as if it had played out. */
	data object EndItem : SkipAction
}

data class SkipOffer(val segment: Segment, val label: SkipLabel, val action: SkipAction)

/**
 * Pure skip-intro/credits logic, used by the player's skip button.
 */
object SkipSegments {
	/** A segment ending this close to the end of the item counts as "runs to the end". */
	const val NEAR_END_MS = 5_000L

	// Shorter segments (rounding artefacts of segment providers) are not worth a button
	private const val MIN_LENGTH_MS = 1_000L

	/** Segments worth offering, sorted by start. */
	fun usable(segments: List<Segment>): List<Segment> =
		segments.filter { it.endMs - it.startMs >= MIN_LENGTH_MS }.sortedBy { it.startMs }

	/** The segment playing at [positionMs]; with overlapping segments the one that started last wins. */
	fun active(segments: List<Segment>, positionMs: Long): Segment? =
		segments.lastOrNull { positionMs >= it.startMs && positionMs < it.endMs }

	/**
	 * Button text and action for [segment].
	 * @param hasNext an episode / queued item follows the current one
	 * @param durationMs item duration, null when not known yet
	 */
	fun offer(segment: Segment, hasNext: Boolean, durationMs: Long?): SkipOffer {
		val runsToEnd = durationMs != null && segment.endMs >= durationMs - NEAR_END_MS
		val (label, action) = when {
			segment.kind == SegmentKind.OUTRO && hasNext -> SkipLabel.NEXT_EPISODE to SkipAction.PlayNext
			else -> labelOf(segment.kind) to (if (runsToEnd) SkipAction.EndItem else SkipAction.SeekTo(segment.endMs))
		}
		return SkipOffer(segment, label, action)
	}

	private fun labelOf(kind: SegmentKind) = when (kind) {
		SegmentKind.INTRO -> SkipLabel.SKIP_INTRO
		SegmentKind.RECAP -> SkipLabel.SKIP_RECAP
		SegmentKind.OUTRO -> SkipLabel.SKIP_CREDITS
		SegmentKind.PREVIEW, SegmentKind.COMMERCIAL -> SkipLabel.SKIP
	}
}
