package ua.movieportal.tv.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import ua.movieportal.tv.MoviePortalApp
import ua.movieportal.tv.R
import ua.movieportal.tv.jellyfin.ServerAuthInterceptor
import ua.movieportal.tv.profile.createDeviceProfile
import ua.movieportal.tv.remote.PlayRequest
import ua.movieportal.tv.remote.PlayerCommand
import ua.movieportal.tv.remote.RemoteHub
import ua.movieportal.tv.service.PlayerLauncher
import ua.movieportal.tv.ui.MainActivity
import ua.movieportal.tv.util.VolumeController
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Full-screen Media3 player driven by the portal (through the Jellyfin session) and by the TV remote.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
	private val app get() = application as MoviePortalApp
	private val handler = Handler(Looper.getMainLooper())

	private lateinit var player: ExoPlayer
	private lateinit var playerView: PlayerView
	private lateinit var loading: View
	private lateinit var panel: PlayerPanel
	private lateinit var volume: VolumeController

	private lateinit var api: ApiClient
	private lateinit var deviceId: String
	private lateinit var resolver: StreamResolver
	private lateinit var reporter: PlaybackReporter

	// Device capabilities are queried once per player instance (on a background thread)
	private val deviceProfile by lazy { createDeviceProfile(applicationContext) }

	// Current item
	private var request: PlayRequest? = null
	private var stream: ResolvedStream? = null
	private var selectedAudio: Int? = null
	private var selectedSubtitle: Int? = null
	private var started = false
	private var restarting = false
	private var applyTrackSelection = false
	private var triedTranscodeFallback = false
	private var wantPlaying = true
	private var volumeBeforeMute = 1f

	// Identifies this instance in RemoteHub; an old instance being destroyed must not unregister a new one
	private val playerToken = Any()

	private var loadJob: Job? = null
	private var progressJob: Job? = null
	private var closing = false

	// Seeking with the remote: steps accumulate while the key is held and are committed once
	private var seekTargetMs: Long? = null
	private val commitSeek = Runnable {
		seekTargetMs?.let { player.seekTo(it) }
		seekTargetMs = null
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		setContentView(R.layout.activity_player)

		val credentials = app.settings.credentials.value
		if (credentials == null || !RemoteHub.hasPendingPlayRequest) {
			finish()
			return
		}

		volume = VolumeController(this)
		deviceId = app.settings.deviceId
		api = app.jellyfin.createApi(credentials)
		resolver = StreamResolver(api, requireNotNull(credentials.userId.toUUIDOrNull())) { deviceProfile }
		reporter = PlaybackReporter(api, app.appScope, deviceId)

		player = buildPlayer(credentials.serverUrl, credentials.accessToken)
		player.addListener(playerListener)
		playerView = findViewById(R.id.player_view)
		playerView.player = player
		loading = findViewById(R.id.player_loading)
		panel = PlayerPanel(findViewById(android.R.id.content), player, ::showAudioDialog, ::showSubtitleDialog)

		RemoteHub.activePlayer = playerToken
		PlayerLauncher.cancelNotification(this)

		// Back closes the panel first, then stops playback and returns to the idle screen
		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				if (panel.isVisible && panel.isInteractive) panel.hide()
				else close(failed = false, returnToMain = true)
			}
		})

		lifecycleScope.launch {
			RemoteHub.playRequest.collect { request ->
				// A closing player leaves new requests to the next player instance
				if (request != null && !closing && RemoteHub.claimPlayRequest(request)) play(request)
			}
		}
		lifecycleScope.launch {
			RemoteHub.playerCommands.collect(::onRemoteCommand)
		}
	}

	override fun onStop() {
		super.onStop()
		// Leaving the player (Home key, another app on top) ends the playback
		if (!isChangingConfigurations && !closing && ::player.isInitialized) close(failed = false, returnToMain = false)
	}

	override fun onDestroy() {
		handler.removeCallbacksAndMessages(null)
		if (::player.isInitialized) {
			stopCurrent(failed = false)
			panel.release()
			player.release()
			reporter.close()
			releaseHub()
		}
		super.onDestroy()
	}

	// region Loading

	private fun buildPlayer(serverUrl: String, accessToken: String): ExoPlayer {
		// The token is only sent to the paired server, never to foreign media/subtitle hosts
		val mediaClient = app.jellyfin.httpClient.newBuilder()
			.addInterceptor(ServerAuthInterceptor(serverUrl) { app.jellyfin.authorizationHeader(accessToken) })
			.build()
		val httpFactory = OkHttpDataSource.Factory(mediaClient)
		val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

		val extractorsFactory = DefaultExtractorsFactory()
			.setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3)
			.setConstantBitrateSeekingEnabled(true)
			.setConstantBitrateSeekingAlwaysEnabled(true)

		// Platform decoders first, FFmpeg (audio) as fallback, then the next decoder if one fails
		val renderersFactory = DefaultRenderersFactory(this)
			.setEnableDecoderFallback(true)
			.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

		return ExoPlayer.Builder(this)
			.setRenderersFactory(renderersFactory)
			.setTrackSelector(DefaultTrackSelector(this))
			.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
			.setAudioAttributes(
				AudioAttributes.Builder()
					.setUsage(C.USAGE_MEDIA)
					.setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
					.build(),
				true,
			)
			.build()
	}

	private fun play(request: PlayRequest) {
		Timber.i("Play request: $request")
		// A new "play now" replaces the current item
		stopCurrent(failed = false)

		this.request = request
		selectedAudio = request.audioStreamIndex
		selectedSubtitle = request.subtitleStreamIndex
		triedTranscodeFallback = false
		wantPlaying = true
		panel.setTitle(null)

		val itemId = request.itemId.toUUIDOrNull()
		if (itemId == null) {
			fail("invalid item id")
			return
		}

		lifecycleScope.launch { loadTitle(itemId) }
		load(itemId, request.startPositionTicks ?: 0, allowDirectPlay = true)
	}

	private fun load(itemId: UUID, startTicks: Long, allowDirectPlay: Boolean) {
		val request = request ?: return
		loadJob?.cancel()
		loading.isVisible = true

		loadJob = lifecycleScope.launch {
			val resolved = try {
				resolver.resolve(
					itemId = itemId,
					startPositionTicks = startTicks,
					audioStreamIndex = selectedAudio,
					subtitleStreamIndex = selectedSubtitle,
					mediaSourceId = request.mediaSourceId,
					allowDirectPlay = allowDirectPlay,
				)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.e(err, "Unable to resolve stream")
				fail(err.message ?: err.javaClass.simpleName)
				return@launch
			}

			val previous = stream
			stream = resolved
			selectedAudio = resolved.audioStreamIndex
			selectedSubtitle = resolved.subtitleStreamIndex
			Timber.i(
				"Stream: ${resolved.playMethod.serialName} session=${resolved.playSessionId} " +
					"audio=${resolved.audioStreamIndex} sub=${resolved.subtitleStreamIndex} sidecars=${resolved.sidecars.size}"
			)

			// The old transcode is not needed anymore (audio/subtitle switch, fallback to transcoding)
			val oldSession = previous?.playSessionId
			if (previous != null && previous.playMethod != PlayMethod.DIRECT_PLAY && oldSession != null &&
				oldSession != resolved.playSessionId
			) {
				reporter.stopEncoding(oldSession)
			}

			applyTrackSelection = true
			player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
				.clearOverrides()
				.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
				.build()
			player.setMediaItem(buildMediaItem(resolved), startTicks / PlaybackReporter.TICKS_PER_MS)
			player.playWhenReady = wantPlaying
			player.prepare()
		}
	}

	private fun buildMediaItem(stream: ResolvedStream): MediaItem = MediaItem.Builder()
		.setUri(stream.url)
		.apply { stream.mimeType?.let(::setMimeType) }
		.setSubtitleConfigurations(stream.sidecars.map { sidecar ->
			MediaItem.SubtitleConfiguration.Builder(sidecar.url.toUri())
				.setId(sidecar.trackId)
				.setMimeType(sidecar.mimeType)
				.setLanguage(sidecar.language)
				.setLabel(sidecar.label)
				.build()
		})
		.build()

	private suspend fun loadTitle(itemId: UUID) {
		val item = try {
			withContext(Dispatchers.IO) { api.userLibraryApi.getItem(itemId = itemId).content }
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to load item details")
			return
		}
		val title = listOfNotNull(item.seriesName, item.name).joinToString(" — ")
		if (request?.itemId?.toUUIDOrNull() == itemId) panel.setTitle(title)
	}

	/** Re-request the stream from the server at the current position (transcoded audio/subtitle switch). */
	private fun restartStream(allowDirectPlay: Boolean) {
		val itemId = stream?.itemId ?: return
		restarting = true
		wantPlaying = player.playWhenReady
		val positionTicks = player.currentPosition * PlaybackReporter.TICKS_PER_MS
		Timber.i("Restarting stream at ${positionTicks / PlaybackReporter.TICKS_PER_SECOND}s")
		load(itemId, positionTicks, allowDirectPlay)
	}

	// endregion

	// region Player events and reporting

	private val playerListener = object : Player.Listener {
		override fun onPlaybackStateChanged(playbackState: Int) {
			loading.isVisible = playbackState == Player.STATE_BUFFERING || (playbackState == Player.STATE_IDLE && loadJob?.isActive == true)

			when (playbackState) {
				Player.STATE_READY -> {
					if (!started) {
						started = true
						reporter.start(snapshot())
						startProgressLoop()
					} else if (restarting) {
						reportProgress()
					}
					restarting = false
				}

				Player.STATE_ENDED -> close(failed = false, returnToMain = true)
				else -> Unit
			}
		}

		override fun onTracksChanged(tracks: Tracks) {
			if (applyTrackSelection && !tracks.isEmpty) {
				applyTrackSelection = false
				selectedAudio?.let { setAudio(it, allowRestart = false) }
				selectedSubtitle?.let { setSubtitle(it, allowRestart = false) }
			}
		}

		override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
			panel.onPlayingStateChanged()
			if (started) reportProgress()
		}

		override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
			if (started && reason == Player.DISCONTINUITY_REASON_SEEK) reportProgress()
		}

		override fun onPlayerError(error: PlaybackException) {
			Timber.e(error, "Player error")
			val current = stream
			if (current != null && current.playMethod == PlayMethod.DIRECT_PLAY && !triedTranscodeFallback) {
				// Direct play failed (unsupported codec/container): let the server transcode instead
				triedTranscodeFallback = true
				Timber.i("Falling back to transcoding")
				restartStream(allowDirectPlay = false)
			} else {
				fail(error.errorCodeName)
			}
		}
	}

	private fun startProgressLoop() {
		progressJob?.cancel()
		progressJob = lifecycleScope.launch {
			while (isActive) {
				delay(PROGRESS_INTERVAL_MS)
				if (started && !restarting) reporter.progress(snapshot())
			}
		}
	}

	private fun reportProgress() {
		if (started) reporter.progress(snapshot())
	}

	private fun snapshot(): PlaybackSnapshot {
		val current = requireNotNull(stream)
		val fixedVolume = volume.isFixed
		return PlaybackSnapshot(
			itemId = current.itemId,
			mediaSourceId = current.mediaSource.id,
			liveStreamId = current.mediaSource.liveStreamId,
			playSessionId = current.playSessionId,
			playMethod = current.playMethod,
			positionTicks = player.currentPosition * PlaybackReporter.TICKS_PER_MS,
			isPaused = !player.playWhenReady,
			canSeek = (current.runTimeTicks ?: 0) > 0,
			audioStreamIndex = selectedAudio,
			subtitleStreamIndex = selectedSubtitle ?: -1,
			volumeLevel = if (fixedVolume) (player.volume * 100).roundToInt() else volume.level,
			isMuted = if (fixedVolume) player.volume == 0f else volume.isMuted,
		)
	}

	/** Report "stopped" for the current item and reset the player. */
	private fun stopCurrent(failed: Boolean) {
		loadJob?.cancel()
		progressJob?.cancel()
		if (started && stream != null) reporter.stopped(snapshot(), failed)
		started = false
		restarting = false
		stream = null
		player.stop()
		player.clearMediaItems()
	}

	private fun fail(reason: String) {
		Toast.makeText(this, getString(R.string.player_error, reason), Toast.LENGTH_LONG).show()
		close(failed = true, returnToMain = true)
	}

	/** Stop playback, report it and leave the player (back to the idle screen when [returnToMain]). */
	private fun close(failed: Boolean, returnToMain: Boolean) {
		if (closing) return
		closing = true
		releaseHub()
		stopCurrent(failed)
		// Launched from the background the player is alone in its task: show the idle screen behind it
		if (returnToMain && isTaskRoot) {
			startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
		}
		finish()
	}

	private fun releaseHub() {
		if (RemoteHub.activePlayer === playerToken) RemoteHub.activePlayer = null
	}

	// endregion

	// region Commands

	private fun onRemoteCommand(command: PlayerCommand) {
		Timber.i("Player command: $command")
		when (command) {
			PlayerCommand.Stop -> close(failed = false, returnToMain = true)
			PlayerCommand.Pause -> setPlaying(false)
			PlayerCommand.Unpause -> setPlaying(true)
			PlayerCommand.PlayPause -> setPlaying(!player.playWhenReady)
			is PlayerCommand.Seek -> {
				player.seekTo(command.positionTicks / PlaybackReporter.TICKS_PER_MS)
				panel.showInfo()
			}

			is PlayerCommand.SetAudioStream -> setAudio(command.index, allowRestart = true)
			is PlayerCommand.SetSubtitleStream -> setSubtitle(command.index, allowRestart = true)
			is PlayerCommand.SetVolume -> {
				player.volume = command.level.coerceIn(0, 100) / 100f
				reportProgress()
			}

			is PlayerCommand.SetMuted -> {
				val mute = command.muted ?: (player.volume > 0f)
				if (mute && player.volume > 0f) {
					volumeBeforeMute = player.volume
					player.volume = 0f
				} else if (!mute && player.volume == 0f) {
					player.volume = volumeBeforeMute
				}
				reportProgress()
			}
		}
	}

	private fun setPlaying(playing: Boolean) {
		wantPlaying = playing
		player.playWhenReady = playing
	}

	private fun setAudio(index: Int, allowRestart: Boolean) {
		val current = stream ?: return
		when (val action = TrackMapper.audio(current, player.currentTracks, index)) {
			is TrackMapper.Action.Apply -> {
				val changed = selectedAudio != index
				selectedAudio = index
				player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().also(action.update).build()
				if (changed) reportProgress()
			}

			TrackMapper.Action.Restart -> if (allowRestart) {
				selectedAudio = index
				restartStream(allowDirectPlay = !triedTranscodeFallback)
			}

			TrackMapper.Action.Unavailable -> Timber.w("Audio stream $index not available")
		}
	}

	private fun setSubtitle(index: Int, allowRestart: Boolean) {
		val current = stream ?: return
		when (val action = TrackMapper.subtitle(current, player.currentTracks, index)) {
			is TrackMapper.Action.Apply -> {
				val changed = selectedSubtitle != index
				selectedSubtitle = index
				player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().also(action.update).build()
				if (changed) reportProgress()
			}

			TrackMapper.Action.Restart -> if (allowRestart) {
				selectedSubtitle = index
				restartStream(allowDirectPlay = !triedTranscodeFallback)
			}

			TrackMapper.Action.Unavailable -> Timber.w("Subtitle stream $index not available")
		}
	}

	private fun showAudioDialog() {
		val current = stream ?: return
		val streams = current.audioStreams
		if (streams.isEmpty()) return
		showStreamDialog(R.string.player_audio, streams.map(::streamLabel), streams.indexOfFirst { it.index == selectedAudio }) { which ->
			setAudio(streams[which].index, allowRestart = true)
		}
	}

	private fun showSubtitleDialog() {
		val current = stream ?: return
		val streams = current.subtitleStreams
		val labels = listOf(getString(R.string.player_subtitles_off)) + streams.map(::streamLabel)
		val checked = streams.indexOfFirst { it.index == selectedSubtitle }.let { if (it < 0) 0 else it + 1 }
		showStreamDialog(R.string.player_subtitles, labels, checked) { which ->
			setSubtitle(if (which == 0) -1 else streams[which - 1].index, allowRestart = true)
		}
	}

	private fun showStreamDialog(title: Int, labels: List<String>, checked: Int, onSelected: (Int) -> Unit) {
		AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
			.setTitle(title)
			.setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
				dialog.dismiss()
				onSelected(which)
			}
			.show()
	}

	private fun streamLabel(stream: MediaStream): String {
		val base = stream.displayTitle ?: stream.title ?: stream.language ?: getString(R.string.player_stream_unknown, stream.index)
		return if (stream.isExternal) "$base (${getString(R.string.player_external)})" else base
	}

	// endregion

	// region Remote control keys

	// Every key press keeps an open panel visible
	override fun onUserInteraction() {
		super.onUserInteraction()
		if (::panel.isInitialized) panel.onUserInteraction()
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)

	override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyUp(keyCode, event)

	/** Keys not consumed by a focused view; inside the interactive panel only media keys control playback. */
	private fun handleKey(event: KeyEvent): Boolean {
		if (!::panel.isInitialized) return false
		if (panel.isInteractive && !isMediaKey(event.keyCode)) return false
		return handlePlaybackKey(event)
	}

	@Suppress("CyclomaticComplexMethod")
	private fun handlePlaybackKey(event: KeyEvent): Boolean {
		val down = event.action == KeyEvent.ACTION_DOWN
		val up = event.action == KeyEvent.ACTION_UP

		when (event.keyCode) {
			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
			KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
				if (down && event.repeatCount == 0) setPlaying(!player.playWhenReady)
			}

			KeyEvent.KEYCODE_MEDIA_PLAY -> if (down) setPlaying(true)
			KeyEvent.KEYCODE_MEDIA_PAUSE -> if (down) setPlaying(false)

			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
				val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
				if (down) seekBy(direction * seekStepMs(event.repeatCount)) else if (up) commitSeekNow()
			}

			KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
				val direction = if (event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 1 else -1
				if (down) seekBy(direction * MEDIA_KEY_SEEK_MS) else if (up) commitSeekNow()
			}

			KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU -> if (down && event.repeatCount == 0) panel.showInteractive()
			KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> if (down) panel.showInfo()

			KeyEvent.KEYCODE_MEDIA_STOP -> if (up) close(failed = false, returnToMain = true)

			// No queue: next/previous are ignored
			KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Unit

			else -> return false
		}
		return true
	}

	private fun isMediaKey(keyCode: Int) = keyCode in setOf(
		KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
		KeyEvent.KEYCODE_MEDIA_PLAY,
		KeyEvent.KEYCODE_MEDIA_PAUSE,
		KeyEvent.KEYCODE_MEDIA_STOP,
		KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
		KeyEvent.KEYCODE_MEDIA_REWIND,
		KeyEvent.KEYCODE_MEDIA_NEXT,
		KeyEvent.KEYCODE_MEDIA_PREVIOUS,
	)

	/** 10 s per press; holding the key accelerates to 30 s and then 60 s steps. */
	private fun seekStepMs(repeatCount: Int): Long = when {
		repeatCount < 8 -> 10_000L
		repeatCount < 24 -> 30_000L
		else -> 60_000L
	}

	private fun seekBy(deltaMs: Long) {
		if (stream == null) return
		val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
		val target = ((seekTargetMs ?: player.currentPosition) + deltaMs).coerceIn(0, duration)
		seekTargetMs = target
		panel.showInfo(preview = target)
		handler.removeCallbacks(commitSeek)
		handler.postDelayed(commitSeek, SEEK_COMMIT_DELAY_MS)
	}

	private fun commitSeekNow() {
		handler.removeCallbacks(commitSeek)
		commitSeek.run()
	}

	// endregion

	companion object {
		private const val PROGRESS_INTERVAL_MS = 10_000L
		private const val SEEK_COMMIT_DELAY_MS = 700L
		private const val MEDIA_KEY_SEEK_MS = 30_000L

		fun intent(context: Context): Intent = Intent(context, PlayerActivity::class.java)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
	}
}
