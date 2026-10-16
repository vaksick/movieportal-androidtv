package ua.movieportal.tv.player

import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * Track choice for the next queued item or episode: the same audio/subtitle languages as the item that just ended.
 *
 * Stream indexes differ between files, so they are matched by language; `null` leaves the choice to the server
 * (the user's Jellyfin preferences), -1 keeps subtitles off.
 */
object NextItemTracks {
	data class Choice(val audioStreamIndex: Int?, val subtitleStreamIndex: Int?)

	fun choose(
		previousAudio: MediaStream?,
		previousSubtitle: MediaStream?,
		subtitlesOff: Boolean,
		nextStreams: List<MediaStream>,
	): Choice {
		val audio = previousAudio?.language?.let { language ->
			nextStreams.firstOrNull { it.type == MediaStreamType.AUDIO && it.language.sameLanguage(language) }?.index
		}
		val subtitle = when {
			subtitlesOff -> -1
			previousSubtitle?.language == null -> null
			else -> nextStreams
				.filter { it.type == MediaStreamType.SUBTITLE && it.language.sameLanguage(previousSubtitle.language) }
				// Forced-only tracks carry just the foreign-language lines: keep the same kind as before
				.let { candidates -> candidates.firstOrNull { it.isForced == previousSubtitle.isForced } ?: candidates.firstOrNull() }
				?.index
		}
		return Choice(audio, subtitle)
	}

	private fun String?.sameLanguage(other: String?) = this != null && other != null && equals(other, ignoreCase = true)
}
