package ua.movieportal.tv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ua.movieportal.tv.player.Segment
import ua.movieportal.tv.player.SegmentKind
import ua.movieportal.tv.player.SkipAction
import ua.movieportal.tv.player.SkipLabel
import ua.movieportal.tv.player.SkipOffer
import ua.movieportal.tv.player.SkipSegments

class SkipSegmentsTest : FunSpec({
	val recap = Segment(SegmentKind.RECAP, 0, 45_000)
	val intro = Segment(SegmentKind.INTRO, 60_000, 150_000)
	val outro = Segment(SegmentKind.OUTRO, 2_500_000, 2_640_000)
	val duration = 2_640_000L

	test("active segment: start inclusive, end exclusive") {
		val segments = SkipSegments.usable(listOf(intro, recap))
		SkipSegments.active(segments, 0) shouldBe recap
		SkipSegments.active(segments, 44_999) shouldBe recap
		SkipSegments.active(segments, 45_000) shouldBe null
		SkipSegments.active(segments, 60_000) shouldBe intro
		SkipSegments.active(segments, 150_000) shouldBe null
	}

	test("overlapping segments: the one that started last wins; tiny segments are dropped") {
		val commercial = Segment(SegmentKind.COMMERCIAL, 100_000, 120_000)
		val tiny = Segment(SegmentKind.PREVIEW, 200_000, 200_500)
		val segments = SkipSegments.usable(listOf(intro, commercial, tiny))
		segments shouldBe listOf(intro, commercial)
		SkipSegments.active(segments, 110_000) shouldBe commercial
		SkipSegments.active(segments, 200_100) shouldBe null
	}

	test("labels and seek targets") {
		SkipSegments.offer(intro, hasNext = true, durationMs = duration) shouldBe
			SkipOffer(intro, SkipLabel.SKIP_INTRO, SkipAction.SeekTo(150_000))
		SkipSegments.offer(recap, hasNext = false, durationMs = duration).label shouldBe SkipLabel.SKIP_RECAP
		SkipSegments.offer(Segment(SegmentKind.PREVIEW, 10, 5_000), false, duration).label shouldBe SkipLabel.SKIP
		SkipSegments.offer(Segment(SegmentKind.COMMERCIAL, 10, 5_000), false, duration).label shouldBe SkipLabel.SKIP
	}

	test("credits with something next: next episode") {
		SkipSegments.offer(outro, hasNext = true, durationMs = duration) shouldBe
			SkipOffer(outro, SkipLabel.NEXT_EPISODE, SkipAction.PlayNext)
	}

	test("credits without next: skip credits; running to the end lets the item end") {
		SkipSegments.offer(outro, hasNext = false, durationMs = duration) shouldBe
			SkipOffer(outro, SkipLabel.SKIP_CREDITS, SkipAction.EndItem)
		// Ends 4 s before the end: still "the end"
		SkipSegments.offer(outro.copy(endMs = duration - 4_000), false, duration).action shouldBe SkipAction.EndItem
		// Post-credits scene after it: jump to the scene
		val midCredits = Segment(SegmentKind.OUTRO, 2_400_000, 2_500_000)
		SkipSegments.offer(midCredits, false, duration) shouldBe
			SkipOffer(midCredits, SkipLabel.SKIP_CREDITS, SkipAction.SeekTo(2_500_000))
		// Duration not known yet: seek to the segment end
		SkipSegments.offer(outro, false, durationMs = null).action shouldBe SkipAction.SeekTo(2_640_000)
	}
})
