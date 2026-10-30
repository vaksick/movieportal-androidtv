package ua.movieportal.tv.player

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
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
import androidx.media3.exoplayer.DefaultLoadControl
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
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import ua.movieportal.tv.MoviePortalApp
import ua.movieportal.tv.R
import ua.movieportal.tv.jellyfin.ServerAuthInterceptor
import ua.movieportal.tv.profile.createDeviceProfile
import ua.movieportal.tv.profile.maxDisplaySize
import ua.movieportal.tv.remote.PlayRequest
import ua.movieportal.tv.remote.PlayerCommand
import ua.movieportal.tv.remote.RemoteHub
import ua.movieportal.tv.service.PlayerLauncher
import ua.movieportal.tv.ui.ConfirmDialog
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
	private lateinit var skipButton: SkipButton
	private lateinit var volume: VolumeController

	private lateinit var api: ApiClient
	private lateinit var userId: UUID
	private lateinit var deviceId: String
	private lateinit var resolver: StreamResolver
	private lateinit var reporter: PlaybackReporter

	// Device capabilities are queried once per player instance (on a background thread)
	private val deviceProfile by lazy { createDeviceProfile(applicationContext) }

	// Current item
	private var request: PlayRequest? = null
	// What follows the current item (looked up when it starts); null: nothing or not known yet
	private var upNext: UpNext? = null
	private var upNextJob: Job? = null
	private var stream: ResolvedStream? = null
	private var selectedAudio: Int? = null
	private var selectedSubtitle: Int? = null
	private var started = false
	private var restarting = false
	private var applyTrackSelection = false
	private var triedTranscodeFallback = false
	private var networkRetries = 0
	private var wantPlaying = true
	private var volumeBeforeMute = 1f

	// Identifies this instance in RemoteHub; an old instance being destroyed must not unregister a new one
	private val playerToken = Any()

	private var loadJob: Job? = null
	private var progressJob: Job? = null
	private var closing = false

	/** The idle screen was on the TV when the portal started the player: return to it after the video. */
	private var returnToIdleScreen = false

	// Media segments (skip intro/credits) of the current item and the position check that shows the button
	private var segmentsJob: Job? = null
	private var skipTickJob: Job? = null

	// The remote's Back was held: the matching key up must not act as a short press
	private var backLongPressed = false

	// Selection to go back to when a track switch needs a new stream and that request fails
	private var restoreOnRestartFailure: Pair<Int?, Int?>? = null
	// A track was chosen while the stream was loading: it may need a stream restart once the tracks are known
	private var pendingTrackRestart = false

	// onStop handling: a transient stop (screensaver fading out, a system window briefly on top) must not end playback
	private var hasBeenResumed = false
	private var reachedReady = false
	private var closeAfterStopPending = false
	private var closeAfterStopReason = ""
	private val closeAfterStop = Runnable {
		closeAfterStopPending = false
		if (closing) return@Runnable
		Timber.w("Closing the hidden player: $closeAfterStopReason")
		close(failed = false, returnToMain = false)
	}

	// Screen on/off and unlock: the hidden-start countdown only runs while the screen is on (TV woken from standby)
	private var screenReceiverRegistered = false
	private val screenReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			Timber.i("Screen event ${intent.action} (player resumed before: $hasBeenResumed, ready: $reachedReady)")
			when (intent.action) {
				// Screen went off before the player ever came to the front: wait for the next screen on
				Intent.ACTION_SCREEN_OFF -> if (!hasBeenResumed) cancelCloseAfterStop()
				else -> if (!isStartedNow() && !closeAfterStopPending) scheduleHiddenClose("screen on, player still hidden")
			}
		}
	}

	// Seeking with the remote: steps accumulate while the key is held and are committed once
	private var seekTargetMs: Long? = null

	// Track/stop dialog currently shown; dismissed when the player closes
	private var activeDialog: AlertDialog? = null
	private val commitSeek = Runnable {
		seekTargetMs?.let { player.seekTo(it) }
		seekTargetMs = null
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		// Started by the portal while the TV shows the screensaver or is locked: come to the front and turn the screen on
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			setShowWhenLocked(true)
			setTurnScreenOn(true)
		} else {
			@Suppress("DEPRECATION")
			window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
		}
		setContentView(R.layout.activity_player)
		// Read before the idle screen below the player gets onStop
		returnToIdleScreen = MainActivity.isOnScreen

		val credentials = app.settings.credentials.value
		if (credentials == null || !RemoteHub.hasPendingPlayRequest) {
			finish()
			return
		}

		volume = VolumeController(this)
		deviceId = app.settings.deviceId
		api = app.jellyfin.createApi(credentials)
		userId = requireNotNull(credentials.userId.toUUIDOrNull())
		resolver = StreamResolver(api, userId, { deviceProfile }, maxDisplaySize(this))
		reporter = PlaybackReporter(api, app.appScope, deviceId)

		player = buildPlayer(credentials.serverUrl, credentials.accessToken)
		player.addListener(playerListener)
		playerView = findViewById(R.id.player_view)
		playerView.player = player
		loading = findViewById(R.id.player_loading)
		panel = PlayerPanel(
			findViewById(android.R.id.content),
			player,
			onPlayPause = { togglePlaying() },
			onRewind = { seekBy(-BUTTON_SEEK_MS) },
			onForward = { seekBy(BUTTON_SEEK_MS) },
			onNext = ::skipToNext,
			onTracks = ::showTracks,
			onTimeBarKey = ::handleTimeBarKey,
		)
		skipButton = SkipButton(findViewById(R.id.player_skip), ::onSkip)

		RemoteHub.activePlayer = playerToken
		Timber.i("Player created (screen on: ${isScreenInteractive()})")
		ContextCompat.registerReceiver(
			this,
			screenReceiver,
			IntentFilter().apply {
				addAction(Intent.ACTION_SCREEN_ON)
				addAction(Intent.ACTION_SCREEN_OFF)
				addAction(Intent.ACTION_USER_PRESENT)
			},
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		screenReceiverRegistered = true
		PlayerLauncher.cancelNotification(this)

		// Back hides a visible panel first; on plain video it asks before stopping (a stray press must not end playback)
		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				when {
					panel.isTracksVisible -> panel.closeTracks()
					panel.isVisible -> panel.hide()
					// Not interested in skipping this segment: hide the button, keep playing
					skipButton.isShown -> skipButton.dismiss()
					else -> confirmStop()
				}
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

	// An open panel must keep a focused control (touch mode, a dialog that took the focus with it)
	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus && ::panel.isInitialized && panel.isInteractive && !panel.isTracksVisible && currentFocus?.isShown != true) {
			panel.showInteractive(PlayerPanel.Focus.PLAY_PAUSE)
		}
	}

	override fun onStart() {
		super.onStart()
		cancelCloseAfterStop()
	}

	override fun onResume() {
		super.onResume()
		if (!hasBeenResumed) {
			hasBeenResumed = true
			Timber.i("Player in front for the first time (wanted playing: $wantPlaying)")
			// Nothing plays before the player is visible; now apply what the portal / the remote asked for
			if (::player.isInitialized) player.playWhenReady = wantPlaying
		}
		cancelCloseAfterStop()
	}

	override fun onStop() {
		super.onStop()
		if (isChangingConfigurations || closing || !::player.isInitialized) return
		// Leaving the player (Home key, another app on top) ends the playback, but only if the player does not come
		// back within a short grace period. Until the stream is ready this is ignored: the player is often started
		// behind the fading screensaver, or while the TV is still waking up from standby (see HiddenPlayerPolicy).
		scheduleHiddenClose("player stopped")
	}

	/** Starts the close countdown for a player that is not in front, if [HiddenPlayerPolicy] says so. */
	private fun scheduleHiddenClose(reason: String) {
		val interactive = isScreenInteractive()
		val delay = HiddenPlayerPolicy.closeDelayMs(hasBeenResumed, reachedReady, interactive)
		if (delay == null) {
			Timber.i("Hidden player kept open ($reason; resumed: $hasBeenResumed, ready: $reachedReady, screen on: $interactive)")
			return
		}
		scheduleCloseAfterStop(delay, "$reason, not back within ${delay / 1000.0}s (resumed before: $hasBeenResumed)")
	}

	private fun scheduleCloseAfterStop(delayMs: Long, reason: String) {
		handler.removeCallbacks(closeAfterStop)
		handler.postDelayed(closeAfterStop, delayMs)
		closeAfterStopPending = true
		closeAfterStopReason = reason
	}

	private fun isStartedNow() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

	/** Screen on and not locked (standby, screen off and keyguard do not count). */
	private fun isScreenInteractive(): Boolean {
		val interactive = getSystemService<PowerManager>()?.isInteractive != false
		val locked = getSystemService<KeyguardManager>()?.isKeyguardLocked == true
		return interactive && !locked
	}

	private fun cancelCloseAfterStop() {
		handler.removeCallbacks(closeAfterStop)
		closeAfterStopPending = false
	}

	override fun onDestroy() {
		handler.removeCallbacksAndMessages(null)
		if (screenReceiverRegistered) {
			unregisterReceiver(screenReceiver)
			screenReceiverRegistered = false
		}
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
			.setLoadControl(createLoadControl())
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

	/**
	 * Media3's default buffer may hold ~140 MB of video in the Java heap; low-memory TVs (Xiaomi Mi TV / Mi Box give
	 * apps 128–192 MB) ran out of memory a few minutes into high-bitrate files. The buffer is capped at a quarter of
	 * the heap the app may use, and the byte limit wins over the time targets.
	 */
	private fun createLoadControl(): DefaultLoadControl {
		val heapMb = getSystemService<ActivityManager>()?.largeMemoryClass ?: FALLBACK_HEAP_MB
		val bufferBytes = (heapMb.toLong() * BYTES_PER_MB / HEAP_SHARE_FOR_BUFFER)
			.coerceIn(MIN_BUFFER_BYTES, MAX_BUFFER_BYTES)
			.toInt()
		Timber.i("Player buffer: ${bufferBytes / BYTES_PER_MB} MB (heap $heapMb MB)")
		return DefaultLoadControl.Builder()
			.setTargetBufferBytes(bufferBytes)
			.setPrioritizeTimeOverSizeThresholds(false)
			.build()
	}

	private fun play(request: PlayRequest) {
		if (closing) return
		Timber.i("Play request: $request")
		// A new "play now" replaces the current item
		stopCurrent(failed = false)

		this.request = request
		// Columns of the previous item would list its stream indexes
		if (panel.isTracksVisible) panel.hide()
		upNext = null
		panel.setNext(null)
		selectedAudio = request.audioStreamIndex
		selectedSubtitle = request.subtitleStreamIndex
		triedTranscodeFallback = false
		networkRetries = 0
		wantPlaying = true
		panel.setTitle(null)

		val itemId = request.itemId.toUUIDOrNull()
		if (itemId == null) {
			fail("invalid item id")
			return
		}

		lifecycleScope.launch { loadTitle(itemId) }
		findUpNext(request, itemId)
		loadSegments(request, itemId)
		load(itemId, request.startPositionTicks ?: 0, transcodeOnly = false)
	}

	private fun load(itemId: UUID, startTicks: Long, transcodeOnly: Boolean) {
		val request = request ?: return
		loadJob?.cancel()
		loading.isVisible = true
		// Selection the stream is requested with; a different value later means the user changed it meanwhile
		val requestedAudio = selectedAudio
		val requestedSubtitle = selectedSubtitle

		loadJob = lifecycleScope.launch {
			val resolved = try {
				resolver.resolve(
					itemId = itemId,
					startPositionTicks = startTicks,
					audioStreamIndex = selectedAudio,
					subtitleStreamIndex = selectedSubtitle,
					mediaSourceId = request.mediaSourceId,
					transcodeOnly = transcodeOnly,
				)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.e(err, "Unable to resolve stream")
				val restore = restoreOnRestartFailure
				if (restarting && stream != null && restore != null) {
					// Track switch failed: the old stream is still playing, keep it and its tracks
					restartFailed(restore)
				} else {
					fail(err.message ?: err.javaClass.simpleName)
				}
				return@launch
			}

			val previous = stream
			stream = resolved
			restoreOnRestartFailure = null
			// A track chosen while this request was running wins over the server's choice
			val audioChanged = selectedAudio != requestedAudio
			val subtitleChanged = selectedSubtitle != requestedSubtitle
			if (!audioChanged) selectedAudio = resolved.audioStreamIndex
			if (!subtitleChanged) selectedSubtitle = resolved.subtitleStreamIndex
			pendingTrackRestart = audioChanged || subtitleChanged
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
			// Not before the player has been in front: a player still behind the waking screen must not play invisibly
			player.playWhenReady = wantPlaying && hasBeenResumed
			player.prepare()
		}
	}

	private fun restartFailed(restore: Pair<Int?, Int?>) {
		restarting = false
		restoreOnRestartFailure = null
		selectedAudio = restore.first
		selectedSubtitle = restore.second
		loading.isVisible = player.playbackState == Player.STATE_BUFFERING
		if (panel.isTracksVisible) panel.updateTracks(selectedAudio, selectedSubtitle ?: -1)
		Toast.makeText(this, R.string.player_track_change_failed, Toast.LENGTH_LONG).show()
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

	/** Looks up what follows [itemId] so the panel can offer "next" (and the end of the item does not wait for it). */
	private fun findUpNext(request: PlayRequest, itemId: UUID) {
		upNextJob?.cancel()
		upNextJob = lifecycleScope.launch {
			val next = try {
				lookUpNext(request, itemId)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to find the next item")
				null
			}
			if (this@PlayerActivity.request !== request) return@launch
			upNext = next
			panel.setNext(next?.let { getString(if (it.isEpisode) R.string.player_next_episode else R.string.player_next_item) })
		}
	}

	private suspend fun lookUpNext(request: PlayRequest, itemId: UUID): UpNext? {
		if (request.queue.isNotEmpty()) {
			return UpNextQueue.fromQueue(request.queue) { queued ->
				withContext(Dispatchers.IO) { api.userLibraryApi.getItem(itemId = queued).content }.type
			}
		}

		val item = withContext(Dispatchers.IO) { api.userLibraryApi.getItem(itemId = itemId).content }
		val seriesId = item.seriesId
		if (item.type != BaseItemKind.EPISODE || seriesId == null) return null

		// Episodes in series order starting with the current one; the second one is next (also across seasons)
		val episodes = withContext(Dispatchers.IO) {
			api.tvShowsApi.getEpisodes(
				seriesId = seriesId,
				userId = userId,
				isMissing = false,
				startItemId = itemId,
				limit = 2,
			).content
		}.items
		return episodes.getOrNull(1)?.id?.takeIf { it != itemId }?.let { UpNext(it, emptyList(), isEpisode = true) }
	}

	/**
	 * Media segments (`GET /MediaSegments/{itemId}`, Jellyfin 10.10+ with a segment provider plugin) for the skip
	 * button. Older servers, no plugin or any error: no segments and no button, silently.
	 */
	private fun loadSegments(request: PlayRequest, itemId: UUID) {
		segmentsJob?.cancel()
		segmentsJob = lifecycleScope.launch {
			val segments = try {
				withContext(Dispatchers.IO) {
					api.mediaSegmentsApi.getItemSegments(itemId = itemId, includeSegmentTypes = SKIPPABLE_SEGMENTS).content.items
				}.mapNotNull(::toSegment)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.d("No media segments for $itemId: ${err.message}")
				emptyList()
			}
			if (this@PlayerActivity.request !== request) return@launch
			skipButton.setSegments(segments)
			if (skipButton.segments.isNotEmpty()) startSkipTicker()
		}
	}

	private fun toSegment(dto: MediaSegmentDto): Segment? {
		val kind = when (dto.type) {
			MediaSegmentType.INTRO -> SegmentKind.INTRO
			MediaSegmentType.RECAP -> SegmentKind.RECAP
			MediaSegmentType.PREVIEW -> SegmentKind.PREVIEW
			MediaSegmentType.COMMERCIAL -> SegmentKind.COMMERCIAL
			MediaSegmentType.OUTRO -> SegmentKind.OUTRO
			MediaSegmentType.UNKNOWN -> return null
		}
		return Segment(kind, dto.startTicks / PlaybackReporter.TICKS_PER_MS, dto.endTicks / PlaybackReporter.TICKS_PER_MS)
	}

	/** Shows/hides the skip button as playback enters and leaves segments (only items with segments). */
	private fun startSkipTicker() {
		skipTickJob?.cancel()
		skipTickJob = lifecycleScope.launch {
			while (isActive) {
				if (started && !restarting) {
					skipButton.update(
						positionMs = player.currentPosition,
						durationMs = player.duration.takeIf { it != C.TIME_UNSET },
						hasNext = upNext != null,
						panelBusy = panel.isVisible,
					)
				}
				delay(SKIP_CHECK_INTERVAL_MS)
			}
		}
	}

	private fun onSkip(offer: SkipOffer) {
		skipButton.hide()
		when (val action = offer.action) {
			is SkipAction.SeekTo -> seekFromSkip(action.positionMs)
			SkipAction.PlayNext -> skipToNext()
			// Credits run to the end: end the item (next item or back to the idle screen, as after playing out)
			SkipAction.EndItem -> player.duration.takeIf { it != C.TIME_UNSET }?.let(::seekFromSkip)
		}
		panel.showInfo()
	}

	// Seek reported to the server like every other seek (onPositionDiscontinuity)
	private fun seekFromSkip(positionMs: Long) {
		handler.removeCallbacks(commitSeek)
		seekTargetMs = null
		player.seekTo(positionMs)
	}

	/** End of the item: the next episode / queued item, otherwise back to the idle screen. */
	private fun playNextOrClose() {
		if (closing) return
		val ended = request
		lifecycleScope.launch {
			// The lookup started with the item; on a very short item it may still be running
			upNextJob?.join()
			// Meanwhile the portal may have started something else (or stopped): that must not be closed or replaced
			if (closing || request !== ended || player.playbackState != Player.STATE_ENDED) return@launch
			if (upNext != null) skipToNext() else close(failed = false, returnToMain = true)
		}
	}

	/** "Next" button, the remote's next key and the end of the item: same audio/subtitle languages as now. */
	private fun skipToNext() {
		val current = request ?: return
		val ended = stream ?: return
		val next = upNext ?: return
		if (closing) return
		commitSeekNow()
		upNext = null
		loadJob?.cancel()
		loading.isVisible = true

		// In loadJob: Stop / a new item from the portal (stopCurrent) cancels the switch
		loadJob = lifecycleScope.launch {
			val tracks = try {
				val streams = withContext(Dispatchers.IO) { api.userLibraryApi.getItem(itemId = next.itemId).content }.mediaStreams
				NextItemTracks.choose(
					previousAudio = ended.audioStreams.firstOrNull { it.index == selectedAudio },
					previousSubtitle = ended.subtitleStreams.firstOrNull { it.index == selectedSubtitle },
					subtitlesOff = selectedSubtitle == -1,
					nextStreams = streams.orEmpty(),
				)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load the next item's streams, using server defaults")
				NextItemTracks.Choice(audioStreamIndex = null, subtitleStreamIndex = null)
			}
			if (closing || request !== current) return@launch

			Timber.i("Playing next item ${next.itemId} (queued after it: ${next.queue.size})")
			play(
				current.copy(
					itemId = next.itemId.toString(),
					startPositionTicks = 0,
					audioStreamIndex = tracks.audioStreamIndex,
					subtitleStreamIndex = tracks.subtitleStreamIndex,
					mediaSourceId = null,
					queue = next.queue,
				),
			)
			panel.showInfo()
		}
	}

	/** Re-request the stream from the server at the current position (transcoded audio/subtitle switch). */
	private fun restartStream(transcodeOnly: Boolean, restore: Pair<Int?, Int?>? = null) {
		val itemId = stream?.itemId ?: return
		restarting = true
		restoreOnRestartFailure = restore
		if (hasBeenResumed) wantPlaying = player.playWhenReady
		val positionTicks = player.currentPosition * PlaybackReporter.TICKS_PER_MS
		Timber.i("Restarting stream at ${positionTicks / PlaybackReporter.TICKS_PER_SECOND}s")
		load(itemId, positionTicks, transcodeOnly)
	}

	// endregion

	// region Player events and reporting

	private val playerListener = object : Player.Listener {
		override fun onPlaybackStateChanged(playbackState: Int) {
			loading.isVisible = playbackState == Player.STATE_BUFFERING || (playbackState == Player.STATE_IDLE && loadJob?.isActive == true)

			when (playbackState) {
				Player.STATE_READY -> {
					// Playing again: a later network error gets a fresh set of retries
					networkRetries = 0
					reachedReady = true
					// Ready while hidden and no close scheduled yet (onStop came before READY): do not play invisibly for long
					if (!isStartedNow() && !closeAfterStopPending) scheduleHiddenClose("ready while hidden")
					if (!started) {
						started = true
						reporter.start(snapshot())
						startProgressLoop()
					} else if (restarting) {
						reportProgress()
					}
					restarting = false
				}

				Player.STATE_ENDED -> playNextOrClose()
				else -> Unit
			}
		}

		override fun onTracksChanged(tracks: Tracks) {
			if (applyTrackSelection && !tracks.isEmpty) {
				applyTrackSelection = false
				// A choice made during loading may need a new stream; the server's own choice never does
				val allowRestart = pendingTrackRestart
				pendingTrackRestart = false
				selectedAudio?.let { setAudio(it, allowRestart) }
				selectedSubtitle?.let { setSubtitle(it, allowRestart) }
			}
		}

		override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
			// Keep the wish in sync with changes the player makes itself (e.g. audio focus loss)
			if (hasBeenResumed) wantPlaying = playWhenReady
			panel.onPlayingStateChanged()
			if (started) reportProgress()
		}

		override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
			if (started && reason == Player.DISCONTINUITY_REASON_SEEK) reportProgress()
		}

		override fun onPlayerError(error: PlaybackException) {
			Timber.e(error, "Player error")
			val current = stream
			// Network hiccup (Wi-Fi drop, server/transcoder timeout): resume from the same place instead of closing
			if (current != null && error.isNetworkError() && networkRetries < MAX_NETWORK_RETRIES) {
				networkRetries++
				Timber.w("Network error ${error.errorCodeName}, retry $networkRetries/$MAX_NETWORK_RETRIES")
				handler.postDelayed(
					{ if (!closing) restartStream(transcodeOnly = triedTranscodeFallback) },
					NETWORK_RETRY_DELAY_MS * networkRetries,
				)
				return
			}
			if (current != null && current.playMethod != PlayMethod.TRANSCODE && !triedTranscodeFallback) {
				// Direct play / remux failed (codec the TV can not decode after all): let the server transcode instead
				triedTranscodeFallback = true
				Timber.i("Falling back to transcoding")
				restartStream(transcodeOnly = true)
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
		segmentsJob?.cancel()
		skipTickJob?.cancel()
		if (::skipButton.isInitialized) skipButton.clear()
		// A remote-key seek still pending belongs to this item
		handler.removeCallbacks(commitSeek)
		seekTargetMs = null
		val current = stream
		if (started && current != null) {
			reporter.stopped(snapshot(), failed)
		} else if (current != null && current.playMethod != PlayMethod.DIRECT_PLAY) {
			// Stopped before playback started: no "stopped" report would end the server's transcode, so stop it directly
			current.playSessionId?.let(reporter::stopEncoding)
		}
		started = false
		restarting = false
		restoreOnRestartFailure = null
		pendingTrackRestart = false
		stream = null
		player.stop()
		player.clearMediaItems()
	}

	private fun fail(reason: String) {
		Toast.makeText(this, getString(R.string.player_error, reason), Toast.LENGTH_LONG).show()
		close(failed = true, returnToMain = true)
	}

	/**
	 * Stop playback, report it and leave the player. Back to the idle screen only when [returnToMain] and the idle
	 * screen was open when the portal started the video; otherwise the TV returns to what it showed before (the app
	 * was closed: the launcher or another app), also when the idle screen is still in the task after Home.
	 */
	private fun close(failed: Boolean, returnToMain: Boolean) {
		if (closing) return
		closing = true
		activeDialog?.dismiss()
		activeDialog = null
		releaseHub()
		stopCurrent(failed)
		if (returnToMain && returnToIdleScreen) finish()
		else finishAndRemoveTask()
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
			PlayerCommand.PlayPause -> togglePlaying()
			is PlayerCommand.Seek -> {
				// The portal's position wins over a remote-key seek that is still pending
				handler.removeCallbacks(commitSeek)
				seekTargetMs = null
				player.seekTo(command.positionTicks / PlaybackReporter.TICKS_PER_MS)
				panel.showInfo()
			}

			// Track changed from the portal: keep the check marks of open columns in sync
			is PlayerCommand.SetAudioStream -> {
				setAudio(command.index, allowRestart = true)
				if (panel.isTracksVisible) panel.updateTracks(selectedAudio, selectedSubtitle ?: -1)
			}

			is PlayerCommand.SetSubtitleStream -> {
				setSubtitle(command.index, allowRestart = true)
				if (panel.isTracksVisible) panel.updateTracks(selectedAudio, selectedSubtitle ?: -1)
			}
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

	/** Play/pause toggle; before the player was ever in front it toggles the wanted state. */
	private fun togglePlaying() = setPlaying(!(if (hasBeenResumed) player.playWhenReady else wantPlaying))

	private fun setPlaying(playing: Boolean) {
		wantPlaying = playing
		// Before the first onResume only the wish is stored (Pause/Unpause from the portal while the TV wakes up)
		player.playWhenReady = playing && hasBeenResumed
	}

	private fun setAudio(index: Int, allowRestart: Boolean) {
		val current = stream ?: run {
			// Still loading: remember the choice, load() keeps it and applies it once the tracks are known
			if (loadJob?.isActive == true) selectedAudio = index
			return
		}
		when (val action = TrackMapper.audio(current, player.currentTracks, index)) {
			is TrackMapper.Action.Apply -> {
				val changed = selectedAudio != index
				selectedAudio = index
				player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().also(action.update).build()
				if (changed) reportProgress()
			}

			TrackMapper.Action.Restart -> if (allowRestart) {
				val previous = selectedAudio to selectedSubtitle
				selectedAudio = index
				restartStream(transcodeOnly = triedTranscodeFallback, restore = previous)
			}

			TrackMapper.Action.Unavailable -> Timber.w("Audio stream $index not available")
		}
	}

	private fun setSubtitle(index: Int, allowRestart: Boolean) {
		val current = stream ?: run {
			if (loadJob?.isActive == true) selectedSubtitle = index
			return
		}
		when (val action = TrackMapper.subtitle(current, player.currentTracks, index)) {
			is TrackMapper.Action.Apply -> {
				val changed = selectedSubtitle != index
				selectedSubtitle = index
				player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().also(action.update).build()
				if (changed) reportProgress()
			}

			TrackMapper.Action.Restart -> if (allowRestart) {
				val previous = selectedAudio to selectedSubtitle
				selectedSubtitle = index
				restartStream(transcodeOnly = triedTranscodeFallback, restore = previous)
			}

			TrackMapper.Action.Unavailable -> Timber.w("Subtitle stream $index not available")
		}
	}

	/** "Audio and subtitles" button: both lists side by side; a choice applies at once and the panel stays open. */
	private fun showTracks() {
		val current = stream ?: return
		// Nothing to choose: an empty panel would leave the focus on a disabled control
		if (current.audioStreams.isEmpty() && current.subtitleStreams.isEmpty()) return
		val audio = current.audioStreams.map { TrackPanel.Row(it.index, streamLabel(it)) }
		// No subtitle tracks: no subtitle column at all (an "off"-only list is noise)
		val subtitles = if (current.subtitleStreams.isEmpty()) emptyList() else
			listOf(TrackPanel.Row(-1, getString(R.string.player_subtitles_off))) +
				current.subtitleStreams.map { TrackPanel.Row(it.index, streamLabel(it)) }
		panel.showTracks(
			audio = audio,
			selectedAudio = selectedAudio,
			subtitles = subtitles,
			selectedSubtitle = selectedSubtitle ?: -1,
			onAudio = { index ->
				setAudio(index, allowRestart = true)
				panel.updateTracks(selectedAudio, selectedSubtitle ?: -1)
			},
			onSubtitle = { index ->
				setSubtitle(index, allowRestart = true)
				panel.updateTracks(selectedAudio, selectedSubtitle ?: -1)
			},
		)
	}

	/** Back on plain video: confirm before stopping, "continue" is focused so an accidental OK keeps playing. */
	private fun confirmStop() {
		// Left alone for a minute (remote put down, accidental Back) the dialog closes and playback just goes on
		val dialog = ConfirmDialog.create(
			this,
			message = R.string.player_stop_confirm,
			confirm = R.string.player_stop,
			cancel = R.string.player_continue,
			autoCancelSeconds = STOP_CONFIRM_TIMEOUT_S,
		) {
			close(failed = false, returnToMain = true)
		}
		showDialog(dialog)
	}

	// Android 16+ delivers Back to the dialog through OnBackInvokedDispatcher and never sends the key; the key listener
	// below only matters on older TVs, where some remotes close the dialog on Back down and the matching Back up then
	// reached the activity (and stopped playback)
	// Holding Back in the dialog leaves the app like everywhere else in the player (the first key repeat is the long press).
	@SuppressLint("GestureBackNavigation")
	private fun showDialog(dialog: AlertDialog) {
		var longPressed = false
		dialog.setOnKeyListener { _, keyCode, event ->
			if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
			when {
				event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && !longPressed -> {
					longPressed = true
					dialog.dismiss()
					exitToLauncher()
				}

				event.action == KeyEvent.ACTION_UP && !event.isCanceled && !longPressed -> dialog.cancel()
			}
			true
		}
		activeDialog?.dismiss()
		activeDialog = dialog
		dialog.show()
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

	// Back: short press = the usual steps (tracks → panel → skip button → stop dialog) through the dispatcher on key up,
	// long press = stop and leave to the TV launcher. Back key events need enableOnBackInvokedCallback="false" on
	// Android 16+ (see the manifest); TVs have no back gesture, so predictive back has nothing to animate here.
	@SuppressLint("GestureBackNavigation")
	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			event.startTracking()
			return true
		}
		return handleKey(event) || super.onKeyDown(keyCode, event)
	}

	@SuppressLint("GestureBackNavigation")
	override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
		if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyLongPress(keyCode, event)
		backLongPressed = true
		exitToLauncher()
		return true
	}

	@SuppressLint("GestureBackNavigation")
	override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			if (!backLongPressed && event.isTracking && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
			backLongPressed = false
			return true
		}
		return handleKey(event) || super.onKeyUp(keyCode, event)
	}

	/** Long Back: stop (normal "stopped" report) and go to the TV launcher, not to the idle screen. */
	private fun exitToLauncher() {
		close(failed = false, returnToMain = false)
	}

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
				if (down && event.repeatCount == 0) togglePlaying()
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

			KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU -> if (down && event.repeatCount == 0) showPanel(PlayerPanel.Focus.PLAY_PAUSE)
			KeyEvent.KEYCODE_DPAD_DOWN -> if (down && event.repeatCount == 0) showPanel(PlayerPanel.Focus.TIMELINE)
			KeyEvent.KEYCODE_INFO -> if (down) panel.showInfo()

			KeyEvent.KEYCODE_MEDIA_STOP -> if (up) close(failed = false, returnToMain = true)

			KeyEvent.KEYCODE_MEDIA_NEXT -> if (down && event.repeatCount == 0) skipToNext()
			// No history to go back to
			KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Unit

			else -> return false
		}
		return true
	}

	/** The control panel takes over: the skip button gets out of the way (it comes back after the panel closes). */
	private fun showPanel(focus: PlayerPanel.Focus) {
		skipButton.hide()
		panel.showInteractive(focus)
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

	/** Keys on the focused timeline: ←/→ seek, OK play/pause, ↑ hides the panel (the timeline is its top element). */
	private fun handleTimeBarKey(event: KeyEvent): Boolean {
		val down = event.action == KeyEvent.ACTION_DOWN
		val up = event.action == KeyEvent.ACTION_UP
		when (event.keyCode) {
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
				val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
				if (down) seekBy(direction * seekStepMs(event.repeatCount)) else if (up) commitSeekNow()
			}

			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
				if (down && event.repeatCount == 0) {
					commitSeekNow()
					togglePlaying()
				}
			}

			KeyEvent.KEYCODE_DPAD_UP -> if (down && event.repeatCount == 0) panel.hide()

			else -> return false
		}
		return true
	}

	/** 10 s per press; holding the key accelerates to 30 s and then 60 s steps. */
	private fun seekStepMs(repeatCount: Int): Long = when {
		repeatCount < 8 -> 10_000L
		repeatCount < 24 -> 30_000L
		else -> 60_000L
	}

	private fun seekBy(deltaMs: Long) {
		if (stream == null) return
		val duration = player.duration.takeIf { it != C.TIME_UNSET }
		val target = SeekPolicy.target(seekTargetMs ?: player.currentPosition, deltaMs, duration)
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

	private fun PlaybackException.isNetworkError() =
		errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
			errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
			errorCode == PlaybackException.ERROR_CODE_TIMEOUT

	companion object {
		private const val PROGRESS_INTERVAL_MS = 10_000L
		private const val SEEK_COMMIT_DELAY_MS = 700L
		private const val MEDIA_KEY_SEEK_MS = 30_000L
		private const val BUTTON_SEEK_MS = 10_000L
		private const val STOP_CONFIRM_TIMEOUT_S = 60

		private const val MAX_NETWORK_RETRIES = 3
		private const val NETWORK_RETRY_DELAY_MS = 2_000L
		private const val FALLBACK_HEAP_MB = 128
		private const val BYTES_PER_MB = 1024L * 1024
		private const val HEAP_SHARE_FOR_BUFFER = 4
		private const val MIN_BUFFER_BYTES = 16L * 1024 * 1024
		private const val MAX_BUFFER_BYTES = 96L * 1024 * 1024

		private const val SKIP_CHECK_INTERVAL_MS = 500L
		private val SKIPPABLE_SEGMENTS = listOf(
			MediaSegmentType.INTRO,
			MediaSegmentType.RECAP,
			MediaSegmentType.PREVIEW,
			MediaSegmentType.COMMERCIAL,
			MediaSegmentType.OUTRO,
		)

		fun intent(context: Context): Intent = Intent(context, PlayerActivity::class.java)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
	}
}
