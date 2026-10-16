package ua.movieportal.tv.player

import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

/** The item after the current one and the queue after that. */
data class UpNext(val itemId: UUID, val queue: List<String>, val isEpisode: Boolean)

object UpNextQueue {
	/**
	 * Next item from a queue sent by the portal. The item type only labels the "next" button (episode or not), so a
	 * failed type lookup must not drop the queue.
	 */
	suspend fun fromQueue(queue: List<String>, itemType: suspend (UUID) -> BaseItemKind?): UpNext? {
		val next = queue.firstOrNull()?.toUUIDOrNull() ?: return null
		val type = try {
			itemType(next)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to look up the type of the next queued item")
			null
		}
		// The portal queues the rest of a season as plain items: still label it as the next episode
		return UpNext(next, queue.drop(1), isEpisode = type == BaseItemKind.EPISODE)
	}
}
