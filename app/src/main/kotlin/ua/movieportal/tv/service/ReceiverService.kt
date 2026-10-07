package ua.movieportal.tv.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.ClientCapabilitiesDto
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaType
import timber.log.Timber
import ua.movieportal.tv.MoviePortalApp
import ua.movieportal.tv.R
import ua.movieportal.tv.data.Credentials
import ua.movieportal.tv.data.LogoutReason
import ua.movieportal.tv.jellyfin.JellyfinClient.Companion.isUnauthorized
import ua.movieportal.tv.remote.ConnectionState
import ua.movieportal.tv.remote.RemoteCommand
import ua.movieportal.tv.remote.RemoteHub
import ua.movieportal.tv.util.AndroidVersion
import ua.movieportal.tv.util.VolumeController

/**
 * Foreground service that keeps the session WebSocket open, registers the session capabilities and executes the
 * commands sent by the portal through the Jellyfin server.
 */
class ReceiverService : Service() {
	private val app get() = application as MoviePortalApp
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	private lateinit var networkMonitor: NetworkMonitor
	private lateinit var volume: VolumeController

	private var credentials: Credentials? = null
	private var api: ApiClient? = null
	private var socket: SessionSocket? = null
	private var heartbeatJob: Job? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		Notifications.createChannels(this)
		enterForeground(getString(R.string.notification_connecting))

		volume = VolumeController(this)
		networkMonitor = NetworkMonitor(this) { socket?.reconnectNow() }
		networkMonitor.start()

		scope.launch {
			app.settings.credentials.collect { current ->
				when {
					current == null -> {
						Timber.i("No credentials, stopping service")
						stopSelf()
					}

					current != credentials -> connect(current)
				}
			}
		}

		scope.launch {
			RemoteHub.connectionState.collect { state -> updateNotification(state) }
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		// Called again for every start request and after a sticky restart (intent == null)
		enterForeground(getString(R.string.notification_connecting))
		return START_STICKY
	}

	override fun onDestroy() {
		socket?.stop()
		heartbeatJob?.cancel()
		networkMonitor.stop()
		scope.cancel()
		RemoteHub.setConnectionState(ConnectionState.Stopped)
		super.onDestroy()
	}

	private fun connect(credentials: Credentials) {
		socket?.stop()
		heartbeatJob?.cancel()

		this.credentials = credentials
		val api = app.jellyfin.createApi(credentials)
		this.api = api

		socket = SessionSocket(
			baseClient = app.jellyfin.httpClient,
			serverUrl = credentials.serverUrl,
			accessToken = credentials.accessToken,
			deviceId = app.settings.deviceId,
			authorizationHeader = { app.jellyfin.authorizationHeader(credentials.accessToken) },
			scope = scope,
			callbacks = object : SessionSocket.Callbacks {
				override fun onStateChanged(state: ConnectionState) = RemoteHub.setConnectionState(state)

				override suspend fun onConnected() {
					registerCapabilities(api)
					startHeartbeat(api)
				}

				override suspend fun onCommand(command: RemoteCommand) = handleCommand(command)

				override suspend fun isTokenRevoked(): Boolean = checkTokenRevoked(api)

				override fun isNetworkAvailable(): Boolean = networkMonitor.isNetworkAvailable
			},
		).also { it.start() }
	}

	/** `POST /Sessions/Capabilities/Full`; makes the session remote-controllable and visible to the portal. */
	private suspend fun registerCapabilities(api: ApiClient) {
		val commands = GeneralCommandType.entries.filter { it.serialName in RemoteCommand.supportedGeneralCommands }
		try {
			withContext(Dispatchers.IO) {
				api.sessionApi.postFullCapabilities(
					data = ClientCapabilitiesDto(
						playableMediaTypes = listOf(MediaType.VIDEO),
						supportedCommands = commands,
						supportsMediaControl = true,
						supportsPersistentIdentifier = true,
					),
				)
			}
			Timber.i("Capabilities registered: ${commands.joinToString { it.serialName }}")
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to register capabilities")
			if (err.isUnauthorized()) onTokenRevoked()
		}
	}

	/**
	 * Re-register the capabilities periodically. Every authenticated request refreshes the session's
	 * LastActivityDate (the portal lists sessions with activeWithinSeconds=600) and detects a revoked token.
	 */
	private fun startHeartbeat(api: ApiClient) {
		heartbeatJob?.cancel()
		heartbeatJob = scope.launch {
			while (isActive) {
				delay(HEARTBEAT_INTERVAL_MS)
				if (RemoteHub.connectionState.value == ConnectionState.Connected) registerCapabilities(api)
			}
		}
	}

	private suspend fun checkTokenRevoked(api: ApiClient): Boolean {
		val revoked = try {
			withContext(Dispatchers.IO) { api.userApi.getCurrentUser() }
			false
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			err.isUnauthorized()
		}
		if (revoked) onTokenRevoked()
		return revoked
	}

	@SuppressLint("MissingPermission")
	private fun onTokenRevoked() {
		if (app.settings.credentials.value == null) return
		Timber.w("Access token was revoked by the server")
		app.settings.clearCredentials(LogoutReason.REVOKED)

		val manager = NotificationManagerCompat.from(this)
		if (manager.areNotificationsEnabled()) {
			manager.notify(
				Notifications.ID_REVOKED,
				Notifications.message(this, getString(R.string.notification_revoked)),
			)
		}
	}

	private fun handleCommand(command: RemoteCommand) {
		Timber.i("Command: $command")
		when (command) {
			// PlayNext/PlayLast are treated as PlayNow: the receiver has no queue
			is RemoteCommand.Play -> {
				val request = RemoteHub.submitPlay(command)
				PlayerLauncher.launch(this, request.id)
			}

			is RemoteCommand.Player -> if (!RemoteHub.sendToPlayer(command.command)) {
				Timber.i("No active player for ${command.command}")
			}

			is RemoteCommand.SetVolume -> volume.setLevel(command.level)
			RemoteCommand.VolumeUp -> volume.up()
			RemoteCommand.VolumeDown -> volume.down()
			RemoteCommand.Mute -> volume.mute()
			RemoteCommand.Unmute -> volume.unmute()
			RemoteCommand.ToggleMute -> volume.toggleMute()

			is RemoteCommand.DisplayMessage -> {
				val text = listOfNotNull(command.header, command.text).joinToString(": ")
				Toast.makeText(this, text, Toast.LENGTH_LONG).show()
			}

			is RemoteCommand.Ignored, is RemoteCommand.ForceKeepAlive, RemoteCommand.KeepAlive -> Unit
		}
	}

	@SuppressLint("MissingPermission")
	private fun updateNotification(state: ConnectionState) {
		val text = when (state) {
			ConnectionState.Connected -> getString(R.string.notification_connected)
			ConnectionState.Connecting -> getString(R.string.notification_connecting)
			is ConnectionState.Reconnecting, ConnectionState.Offline -> getString(R.string.notification_reconnecting)
			ConnectionState.Stopped -> return
		}
		if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
			NotificationManagerCompat.from(this).notify(Notifications.ID_SERVICE, Notifications.service(this, text))
		}
	}

	private fun enterForeground(text: String) {
		val notification = Notifications.service(this, text)
		try {
			if (AndroidVersion.isAtLeastU) {
				startForeground(Notifications.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
			} else {
				startForeground(Notifications.ID_SERVICE, notification)
			}
		} catch (err: IllegalStateException) {
			// ForegroundServiceStartNotAllowedException (Android 12+) when restarted from the background
			Timber.e(err, "Unable to enter the foreground state")
		}
	}

	companion object {
		private const val HEARTBEAT_INTERVAL_MS = 4 * 60 * 1000L

		fun start(context: Context) {
			try {
				ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
			} catch (err: IllegalStateException) {
				Timber.e(err, "Unable to start the receiver service")
			}
		}

		fun stop(context: Context) {
			context.stopService(Intent(context, ReceiverService::class.java))
		}
	}
}
