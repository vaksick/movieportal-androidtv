package ua.movieportal.tv.ui

import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
	private val status = root.findViewById<TextView>(R.id.qc_status)
	private val server = root.findViewById<TextView>(R.id.qc_server)
	private val changeServer = root.findViewById<Button>(R.id.qc_change_server)

	private var job: Job? = null

	init {
		changeServer.setOnClickListener { onChangeServer() }
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

	private suspend fun run(serverUrl: String) {
		val api = jellyfin.createApi(serverUrl)
		val revoked = settings.lastLogoutReason == LogoutReason.REVOKED

		// Runs until authenticated or the job is cancelled (delay() is the cancellation point)
		while (true) {
			code.text = ""
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

			code.text = request.code
			setStatus(R.string.qc_waiting, revoked)

			if (pollUntilAuthorized(api, request)) {
				authenticate(api, serverUrl, request.secret)?.let { credentials ->
					settings.saveCredentials(credentials)
					onAuthenticated()
					return
				}
			}
			// Code expired, Quick Connect was disabled meanwhile or authentication failed: get a new code
		}
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
		const val POLL_INTERVAL_MS = 5_000L
		const val RETRY_MS = 5_000L
		const val DISABLED_RETRY_MS = 15_000L

		// Jellyfin drops pending Quick Connect requests after 10 minutes
		const val CODE_LIFETIME_MS = 10 * 60 * 1000L
	}
}
