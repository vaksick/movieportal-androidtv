package ua.movieportal.tv.ui

import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import timber.log.Timber
import ua.movieportal.tv.R
import ua.movieportal.tv.data.AppSettings
import ua.movieportal.tv.data.Credentials
import ua.movieportal.tv.data.LogoutReason
import ua.movieportal.tv.jellyfin.JellyfinClient
import ua.movieportal.tv.jellyfin.JellyfinClient.Companion.httpStatus
import ua.movieportal.tv.pairing.PairingQr

/**
 * Step 2: show a Quick Connect code, poll until the portal authorizes it and store the access token.
 */
class QuickConnectScreen(
	root: View,
	private val scope: CoroutineScope,
	private val settings: AppSettings,
	private val jellyfin: JellyfinClient,
	private val onAuthenticated: () -> Unit,
	onChangeServer: () -> Unit,
) {
	private val context = root.context
	private val code = root.findViewById<TextView>(R.id.qc_code)
	private val qr = root.findViewById<ImageView>(R.id.qc_qr)
	private val status = root.findViewById<TextView>(R.id.qc_status)
	private val server = root.findViewById<TextView>(R.id.qc_server)
	private val changeServer = root.findViewById<Button>(R.id.qc_change_server)
	private val hint = root.findViewById<TextView>(R.id.qc_hint)

	private var job: Job? = null

	// Code currently on screen; the QR is regenerated when the server id arrives later
	private var currentCode: String? = null

	init {
		changeServer.setOnClickListener { onChangeServer() }
		hint.text = withCameraIcon(context.getString(R.string.qc_hint))
	}

	/** Replaces the camera emoji with a vector icon: many TVs ship without a colour emoji font. */
	private fun withCameraIcon(text: String): CharSequence {
		val index = text.indexOf(CAMERA_EMOJI)
		if (index < 0) return text
		val icon = ContextCompat.getDrawable(context, R.drawable.ic_camera) ?: return text
		val size = (hint.textSize * 1.2f).toInt()
		icon.setBounds(0, 0, size, size)
		return SpannableString(text).apply {
			setSpan(ImageSpan(icon, ImageSpan.ALIGN_BASELINE), index, index + CAMERA_EMOJI.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
	}

	/** Start (or restart) the Quick Connect flow; called whenever the screen becomes visible. */
	fun start() {
		val serverUrl = settings.serverUrl ?: return
		server.text = context.getString(R.string.qc_server, serverUrl)
		changeServer.requestFocus()

		job?.cancel()
		job = scope.launch { run(serverUrl) }
	}

	fun stop() {
		job?.cancel()
		job = null
	}

	private suspend fun run(serverUrl: String) = coroutineScope {
		val api = jellyfin.createApi(serverUrl)
		val revoked = settings.lastLogoutReason == LogoutReason.REVOKED
		// Server name/id load in parallel: the code must not wait for /System/Info/Public
		launch { loadServerInfo(serverUrl) }

		// Runs until authenticated or the job is cancelled (delay() is the cancellation point)
		while (true) {
			showCode(null)
			setStatus(R.string.qc_requesting, revoked)

			val request = try {
				withContext(Dispatchers.IO) { api.quickConnectApi.initiateQuickConnect().content }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Quick Connect initiate failed")
				// Jellyfin answers 401 (older versions 403) when Quick Connect is disabled
				val disabled = err.httpStatus() in setOf(401, 403)
				status.setText(if (disabled) R.string.qc_disabled else R.string.qc_connection_problem)
				delay(if (disabled) DISABLED_RETRY_MS else RETRY_MS)
				continue
			}

			// The server id is part of the QR code; retry (in the background) if the first lookup failed
			if (settings.serverId == null) launch { loadServerInfo(serverUrl) }
			showCode(request.code)
			setStatus(R.string.qc_waiting, revoked)

			if (pollUntilAuthorized(api, request)) {
				val credentials = authenticate(api, serverUrl, request.secret)
				if (credentials != null) {
					settings.saveCredentials(credentials)
					onAuthenticated()
					// Ends the background server info lookup too
					coroutineContext.cancelChildren()
					return@coroutineScope
				}
			}
			// Code expired, Quick Connect was disabled meanwhile or authentication failed: get a new code
		}
	}

	/** Server id for the QR code and the server name for the screen (`GET /System/Info/Public`), time-limited. */
	private suspend fun loadServerInfo(serverUrl: String) {
		val info = try {
			withTimeoutOrNull(SERVER_INFO_TIMEOUT_MS) { jellyfin.getPublicSystemInfo(serverUrl) } ?: return
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to load server info")
			return
		}
		info.serverName?.takeIf { it.isNotBlank() }?.let { name -> server.text = context.getString(R.string.qc_server, name) }

		val newId = info.id?.takeIf { it.isNotBlank() } ?: return
		val changed = newId != settings.serverId
		settings.serverId = newId
		// The QR already on screen lacked (or had a stale) server id
		if (changed) currentCode?.let { showCode(it) }
	}

	/** Shows the digits and the matching QR code; a new code always gets a new QR code. */
	private suspend fun showCode(value: String?) {
		currentCode = value
		code.text = value.orEmpty()
		if (value == null) {
			qr.setImageDrawable(null)
			return
		}

		val payload = PairingQr.payload(
			code = value,
			deviceId = settings.deviceId,
			serverId = settings.serverId,
			deviceName = settings.deviceName,
		)
		val sizePx = qr.width.takeIf { it > 0 } ?: context.resources.getDimensionPixelSize(R.dimen.qr_size)
		val bitmap = withContext(Dispatchers.Default) { PairingQr.toBitmap(PairingQr.encode(payload), sizePx) }
		// A newer code may have been shown while this QR was being rendered
		if (currentCode != value) return
		qr.setImageDrawable(bitmap.toDrawable(context.resources).apply { isFilterBitmap = false })
	}

	/** @return true when the code was authorized, false when a new code is needed. */
	private suspend fun pollUntilAuthorized(api: ApiClient, request: QuickConnectResult): Boolean {
		val startedAt = SystemClock.elapsedRealtime()
		var problem = false

		while (SystemClock.elapsedRealtime() - startedAt < CODE_LIFETIME_MS) {
			delay(POLL_INTERVAL_MS)

			val state = try {
				withContext(Dispatchers.IO) { api.quickConnectApi.getQuickConnectState(request.secret).content }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				when (err.httpStatus()) {
					// Unknown or expired secret
					400, 404 -> return false
					// Quick Connect got disabled
					401, 403 -> {
						status.setText(R.string.qc_disabled)
						delay(DISABLED_RETRY_MS)
						return false
					}

					else -> {
						Timber.w(err, "Quick Connect poll failed")
						status.setText(R.string.qc_connection_problem)
						problem = true
						continue
					}
				}
			}

			if (problem) {
				status.setText(R.string.qc_waiting)
				problem = false
			}
			if (state.authenticated) return true
		}

		Timber.i("Quick Connect code expired, requesting a new one")
		return false
	}

	private suspend fun authenticate(api: ApiClient, serverUrl: String, secret: String): Credentials? {
		status.setText(R.string.qc_authenticating)
		return try {
			val result = withContext(Dispatchers.IO) {
				api.userApi.authenticateWithQuickConnect(QuickConnectDto(secret = secret)).content
			}
			val token = result.accessToken
			val user = result.user
			if (token.isNullOrBlank() || user == null) {
				Timber.w("Quick Connect authentication returned no token")
				null
			} else {
				Timber.i("Paired as user ${user.name} (${user.id})")
				Credentials(serverUrl = serverUrl, accessToken = token, userId = user.id.toString(), userName = user.name)
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Quick Connect authentication failed")
			status.setText(R.string.qc_connection_problem)
			delay(RETRY_MS)
			null
		}
	}

	private fun setStatus(text: Int, revoked: Boolean) {
		status.text = buildString {
			if (revoked) appendLine(context.getString(R.string.qc_revoked))
			append(context.getString(text))
		}
	}

	private companion object {
		const val CAMERA_EMOJI = "📷"
		// The portal waits up to 30 s for the session after authorizing the code
		const val POLL_INTERVAL_MS = 2_000L
		const val RETRY_MS = 5_000L
		const val DISABLED_RETRY_MS = 15_000L
		const val SERVER_INFO_TIMEOUT_MS = 8_000L

		// Jellyfin drops pending Quick Connect requests after 10 minutes
		const val CODE_LIFETIME_MS = 10 * 60 * 1000L
	}
}
