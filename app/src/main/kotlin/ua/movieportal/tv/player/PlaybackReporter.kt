package ua.movieportal.tv.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import timber.log.Timber
import java.util.UUID

/**
 * Snapshot of the playback state sent to the server.
 */
data class PlaybackSnapshot(
	val itemId: UUID,
	val mediaSourceId: String?,
	val liveStreamId: String?,
	val playSessionId: String?,
	val playMethod: PlayMethod,
	val positionTicks: Long,
	val isPaused: Boolean,
	val canSeek: Boolean,
	val audioStreamIndex: Int?,
	val subtitleStreamIndex: Int?,
	val volumeLevel: Int,
	val isMuted: Boolean,
)

/**
 * Sends `/Sessions/Playing`, `/Sessions/Playing/Progress` and `/Sessions/Playing/Stopped` in order.
 * The portal reads NowPlayingItem and PlayState from `/Sessions`, which the server only fills from these reports.
 *
 * Reports run in [scope] (the application scope) so the final "stopped" report survives the player activity.
 */
class PlaybackReporter(private val api: ApiClient, scope: CoroutineScope, private val deviceId: String) {
	private sealed interface Report {
		data class Start(val snapshot: PlaybackSnapshot) : Report
		data class Progress(val snapshot: PlaybackSnapshot) : Report
		data class Stop(val snapshot: PlaybackSnapshot, val failed: Boolean) : Report
		data class StopEncoding(val playSessionId: String) : Report
	}

	private val queue = Channel<Report>(Channel.UNLIMITED)

	init {
		scope.launch(Dispatchers.IO) {
			for (report in queue) send(report)
		}
	}

	fun start(snapshot: PlaybackSnapshot) {
		queue.trySend(Report.Start(snapshot))
	}

	fun progress(snapshot: PlaybackSnapshot) {
		queue.trySend(Report.Progress(snapshot))
	}

	fun stopped(snapshot: PlaybackSnapshot, failed: Boolean = false) {
		queue.trySend(Report.Stop(snapshot, failed))
	}

	/** Kill a server-side transcode that is replaced by a new stream (audio/subtitle switch). */
	fun stopEncoding(playSessionId: String) {
		queue.trySend(Report.StopEncoding(playSessionId))
	}

	fun close() {
		queue.close()
	}

	private suspend fun send(report: Report) {
		try {
			when (report) {
				is Report.Start -> {
					Timber.i("Report start: ${report.snapshot.describe()}")
					api.playStateApi.reportPlaybackStart(report.snapshot.toStartInfo())
				}

				is Report.Progress -> {
					Timber.d("Report progress: ${report.snapshot.describe()}")
					api.playStateApi.reportPlaybackProgress(report.snapshot.toProgressInfo())
				}

				is Report.Stop -> {
					Timber.i("Report stopped: ${report.snapshot.describe()} failed=${report.failed}")
					api.playStateApi.reportPlaybackStopped(report.snapshot.toStopInfo(report.failed))
				}

				is Report.StopEncoding -> api.hlsSegmentApi.stopEncodingProcess(
					deviceId = deviceId,
					playSessionId = report.playSessionId,
				)
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Playback report failed: ${report::class.simpleName}")
		}
	}

	private fun PlaybackSnapshot.describe() =
		"item=$itemId pos=${positionTicks / TICKS_PER_SECOND}s paused=$isPaused method=${playMethod.serialName} " +
			"audio=$audioStreamIndex sub=$subtitleStreamIndex session=$playSessionId"

	private fun PlaybackSnapshot.toStartInfo() = PlaybackStartInfo(
		itemId = itemId,
		mediaSourceId = mediaSourceId,
		liveStreamId = liveStreamId,
		playSessionId = playSessionId,
		playMethod = playMethod,
		positionTicks = positionTicks,
		isPaused = isPaused,
		canSeek = canSeek,
		audioStreamIndex = audioStreamIndex,
		subtitleStreamIndex = subtitleStreamIndex,
		volumeLevel = volumeLevel,
		isMuted = isMuted,
		repeatMode = RepeatMode.REPEAT_NONE,
		playbackOrder = PlaybackOrder.DEFAULT,
	)

	private fun PlaybackSnapshot.toProgressInfo() = PlaybackProgressInfo(
		itemId = itemId,
		mediaSourceId = mediaSourceId,
		liveStreamId = liveStreamId,
		playSessionId = playSessionId,
		playMethod = playMethod,
		positionTicks = positionTicks,
		isPaused = isPaused,
		canSeek = canSeek,
		audioStreamIndex = audioStreamIndex,
		subtitleStreamIndex = subtitleStreamIndex,
		volumeLevel = volumeLevel,
		isMuted = isMuted,
		repeatMode = RepeatMode.REPEAT_NONE,
		playbackOrder = PlaybackOrder.DEFAULT,
	)

	private fun PlaybackSnapshot.toStopInfo(failed: Boolean) = PlaybackStopInfo(
		itemId = itemId,
		mediaSourceId = mediaSourceId,
		liveStreamId = liveStreamId,
		playSessionId = playSessionId,
		positionTicks = positionTicks,
		failed = failed,
	)

	companion object {
		const val TICKS_PER_MS = 10_000L
		const val TICKS_PER_SECOND = 10_000_000L
	}
}
