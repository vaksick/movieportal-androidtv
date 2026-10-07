package ua.movieportal.tv.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/**
 * Maps Jellyfin stream indexes (MediaStream.Index) to Media3 track groups.
 *
 * - Sidecar subtitles carry their Jellyfin index in the track id ([SidecarSubtitle.trackId]).
 * - Embedded tracks (direct play) are matched by their order in the container: the n-th Jellyfin audio stream is
 *   the n-th Media3 audio group, the same for embedded subtitles.
 */
object TrackMapper {
	/** What has to happen to apply a stream selection. */
	sealed interface Action {
		/** Selection can be applied to the running player. */
		data class Apply(val update: (TrackSelectionParameters.Builder) -> Unit) : Action

		/** The server has to produce a new stream (transcoding / burn-in). */
		data object Restart : Action

		/** Nothing found to select (e.g. tracks not loaded yet or unknown index). */
		data object Unavailable : Action
	}

	fun audio(stream: ResolvedStream, tracks: Tracks, index: Int): Action {
		// A transcoded/remuxed stream only contains the audio track that was requested from the server
		if (stream.playMethod != PlayMethod.DIRECT_PLAY) {
			return if (index == stream.audioStreamIndex) Action.Apply { } else Action.Restart
		}

		val position = stream.audioStreams.filter { !it.isExternal }.indexOfFirst { it.index == index }
		val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
		val group = groups.getOrNull(position) ?: return Action.Unavailable

		return Action.Apply { builder ->
			builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
		}
	}

	fun subtitle(stream: ResolvedStream, tracks: Tracks, index: Int): Action {
		val burnedIn = stream.burnedInSubtitleIndex
		// Removing or replacing burned-in subtitles needs a new transcode
		if (burnedIn != null && burnedIn != index) return Action.Restart

		val disable = Action.Apply { builder -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) }
		if (index < 0 || burnedIn == index) return disable

		val target = stream.subtitleStreams.firstOrNull { it.index == index } ?: return Action.Unavailable
		val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
		val sidecar = stream.sidecars.firstOrNull { it.index == index }
		val group = when {
			sidecar != null -> textGroups.firstOrNull { group -> group.getTrackFormat(0).id.matchesTrackId(sidecar.trackId) }

			target.isEmbedded() && stream.playMethod == PlayMethod.DIRECT_PLAY -> {
				val embedded = stream.subtitleStreams.filter { !it.isExternal }
				val position = embedded.indexOfFirst { it.index == index }
				textGroups.filterNot { it.isSidecar() }.getOrNull(position)
			}

			// Encode (burn-in) or a delivery method not available in the current stream
			else -> return Action.Restart
		} ?: return Action.Unavailable

		return Action.Apply { builder ->
			builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
			builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
		}
	}

	private fun MediaStream.isEmbedded() =
		deliveryMethod == SubtitleDeliveryMethod.EMBED || (deliveryMethod == null && !isExternal)

	private fun Tracks.Group.isSidecar() = getTrackFormat(0).id?.contains(SidecarSubtitle.SIDECAR_PREFIX) == true

	// Media3 prefixes the ids of merged sources ("1:jf-ext-3")
	private fun String?.matchesTrackId(trackId: String) = this != null && (this == trackId || endsWith(":$trackId"))
}
