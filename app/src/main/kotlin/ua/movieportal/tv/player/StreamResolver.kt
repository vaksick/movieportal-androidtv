package ua.movieportal.tv.player

import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.UUID

/**
 * A subtitle file loaded next to the main stream (SubtitleDeliveryMethod.External).
 */
data class SidecarSubtitle(
	val index: Int,
	val url: String,
	val mimeType: String,
	val language: String?,
	val label: String,
) {
	/** Track id given to Media3, used to find the track again. */
	val trackId: String get() = "$SIDECAR_PREFIX$index"

	companion object {
		const val SIDECAR_PREFIX = "jf-ext-"
	}
}

/**
 * Everything needed to play one item and to report it to the server.
 */
data class ResolvedStream(
	val itemId: UUID,
	val mediaSource: MediaSourceInfo,
	val playSessionId: String?,
	val playMethod: PlayMethod,
	val url: String,
	val mimeType: String?,
	val audioStreams: List<MediaStream>,
	val subtitleStreams: List<MediaStream>,
	val sidecars: List<SidecarSubtitle>,
	/** Jellyfin index of the audio stream requested from the server (null: server default). */
	val audioStreamIndex: Int?,
	/** Jellyfin index of the subtitle stream requested from the server (-1: off). */
	val subtitleStreamIndex: Int,
) {
	val runTimeTicks: Long? get() = mediaSource.runTimeTicks

	/** The server burns this subtitle into the video (transcoding with SubtitleDeliveryMethod.Encode). */
	val burnedInSubtitleIndex: Int?
		get() = subtitleStreams.firstOrNull { it.index == subtitleStreamIndex }
			?.takeIf { it.deliveryMethod == SubtitleDeliveryMethod.ENCODE && playMethod == PlayMethod.TRANSCODE }
			?.index
}

/**
 * `POST /Items/{id}/PlaybackInfo` and selection of direct play / direct stream / transcoding.
 */
class StreamResolver(
	private val api: ApiClient,
	private val userId: UUID,
	private val deviceProfile: () -> DeviceProfile,
) {
	class StreamException(message: String) : Exception(message)

	suspend fun resolve(
		itemId: UUID,
		startPositionTicks: Long?,
		audioStreamIndex: Int?,
		subtitleStreamIndex: Int?,
		mediaSourceId: String?,
		allowDirectPlay: Boolean = true,
	): ResolvedStream = withContext(Dispatchers.IO) {
		val response = api.mediaInfoApi.getPostedPlaybackInfo(
			itemId = itemId,
			data = PlaybackInfoDto(
				userId = userId,
				maxStreamingBitrate = null,
				startTimeTicks = startPositionTicks,
				audioStreamIndex = audioStreamIndex?.takeIf { it >= 0 },
				subtitleStreamIndex = subtitleStreamIndex,
				mediaSourceId = mediaSourceId,
				deviceProfile = deviceProfile(),
				enableDirectPlay = allowDirectPlay,
				enableDirectStream = true,
				enableTranscoding = true,
				allowVideoStreamCopy = true,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = true,
			),
		).content

		response.errorCode?.let { throw StreamException("PlaybackInfo error: ${it.serialName}") }

		val source = response.mediaSources.firstOrNull { mediaSourceId != null && it.id == mediaSourceId }
			?: response.mediaSources.firstOrNull()
			?: throw StreamException("No media source")

		val (playMethod, url, mimeType) = when {
			allowDirectPlay && source.supportsDirectPlay -> Triple(
				PlayMethod.DIRECT_PLAY,
				if (source.isRemote && source.protocol == MediaProtocol.HTTP && source.path != null) source.path!!
				else api.videosApi.getVideoStreamUrl(
					itemId = itemId,
					container = source.container,
					mediaSourceId = source.id,
					static = true,
					tag = source.eTag,
					liveStreamId = source.liveStreamId,
					playSessionId = response.playSessionId,
				),
				null,
			)

			source.supportsDirectStream && source.transcodingUrl != null -> Triple(
				PlayMethod.DIRECT_STREAM,
				api.createUrl(source.transcodingUrl!!, ignorePathParameters = true),
				hlsMimeType(source),
			)

			source.supportsTranscoding && source.transcodingUrl != null -> Triple(
				PlayMethod.TRANSCODE,
				api.createUrl(source.transcodingUrl!!, ignorePathParameters = true),
				hlsMimeType(source),
			)

			else -> throw StreamException("No compatible stream")
		}

		val streams = source.mediaStreams.orEmpty()
		val subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE }

		ResolvedStream(
			itemId = itemId,
			mediaSource = source,
			playSessionId = response.playSessionId,
			playMethod = playMethod,
			url = url,
			mimeType = mimeType,
			audioStreams = streams.filter { it.type == MediaStreamType.AUDIO },
			subtitleStreams = subtitles,
			sidecars = subtitles.mapNotNull { it.toSidecar() },
			audioStreamIndex = audioStreamIndex?.takeIf { it >= 0 } ?: source.defaultAudioStreamIndex,
			subtitleStreamIndex = subtitleStreamIndex ?: source.defaultSubtitleStreamIndex ?: -1,
		)
	}

	private fun hlsMimeType(source: MediaSourceInfo): String? =
		if (source.transcodingSubProtocol == MediaStreamProtocol.HLS || source.transcodingUrl?.contains(".m3u8") == true) MimeTypes.APPLICATION_M3U8
		else null

	private fun MediaStream.toSidecar(): SidecarSubtitle? {
		if (deliveryMethod != SubtitleDeliveryMethod.EXTERNAL) return null
		val deliveryUrl = deliveryUrl ?: return null
		val url = if (isExternalUrl == true) deliveryUrl else api.createUrl(deliveryUrl, ignorePathParameters = true)

		val extension = deliveryUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
		val mimeType = when (extension) {
			"srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
			"vtt", "webvtt" -> MimeTypes.TEXT_VTT
			"ass", "ssa" -> MimeTypes.TEXT_SSA
			"ttml" -> MimeTypes.APPLICATION_TTML
			else -> when (codec?.lowercase()) {
				"srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
				"vtt", "webvtt" -> MimeTypes.TEXT_VTT
				"ass", "ssa" -> MimeTypes.TEXT_SSA
				else -> return null
			}
		}

		return SidecarSubtitle(
			index = index,
			url = url,
			mimeType = mimeType,
			language = language,
			label = displayTitle ?: title ?: language ?: "#$index",
		)
	}
}
