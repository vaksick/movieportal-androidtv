package ua.movieportal.tv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import ua.movieportal.tv.player.NextItemTracks

class NextItemTracksTest : FunSpec({
	fun stream(type: MediaStreamType, index: Int, language: String?, forced: Boolean = false) = MediaStream(
		type = type,
		index = index,
		language = language,
		isForced = forced,
		isInterlaced = false,
		isDefault = false,
		isHearingImpaired = false,
		isExternal = false,
		isTextSubtitleStream = type == MediaStreamType.SUBTITLE,
		supportsExternalStream = false,
	)

	val next = listOf(
		stream(MediaStreamType.VIDEO, 0, null),
		stream(MediaStreamType.AUDIO, 1, "eng"),
		stream(MediaStreamType.AUDIO, 2, "ukr"),
		stream(MediaStreamType.SUBTITLE, 3, "eng", forced = true),
		stream(MediaStreamType.SUBTITLE, 4, "eng"),
	)

	test("same audio and subtitle languages are kept although the indexes differ") {
		NextItemTracks.choose(
			previousAudio = stream(MediaStreamType.AUDIO, 1, "UKR"),
			previousSubtitle = stream(MediaStreamType.SUBTITLE, 2, "eng"),
			subtitlesOff = false,
			nextStreams = next,
		) shouldBe NextItemTracks.Choice(audioStreamIndex = 2, subtitleStreamIndex = 4)
	}

	test("subtitles stay off, a missing language falls back to the server default") {
		NextItemTracks.choose(
			previousAudio = stream(MediaStreamType.AUDIO, 1, "pol"),
			previousSubtitle = null,
			subtitlesOff = true,
			nextStreams = next,
		) shouldBe NextItemTracks.Choice(audioStreamIndex = null, subtitleStreamIndex = -1)
	}

	test("forced subtitles prefer a forced track of the same language") {
		NextItemTracks.choose(
			previousAudio = null,
			previousSubtitle = stream(MediaStreamType.SUBTITLE, 7, "eng", forced = true),
			subtitlesOff = false,
			nextStreams = next,
		) shouldBe NextItemTracks.Choice(audioStreamIndex = null, subtitleStreamIndex = 3)
	}
})
