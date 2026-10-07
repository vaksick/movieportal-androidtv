package ua.movieportal.tv.ui

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import ua.movieportal.tv.R
import ua.movieportal.tv.data.AppSettings
import ua.movieportal.tv.jellyfin.ConnectionError
import ua.movieportal.tv.jellyfin.JellyfinClient
import ua.movieportal.tv.jellyfin.JellyfinClient.Companion.describe

/**
 * Step 1: server address and device name, validated with `GET /System/Info/Public`.
 */
class SetupScreen(
	private val root: View,
	private val scope: CoroutineScope,
	private val settings: AppSettings,
	private val jellyfin: JellyfinClient,
	private val onConfigured: () -> Unit,
) {
	private val context: Context = root.context
	private val serverInput = root.findViewById<EditText>(R.id.setup_server)
	private val message = root.findViewById<TextView>(R.id.setup_message)
	private val connectButton = root.findViewById<Button>(R.id.setup_connect)

	private var checkJob: Job? = null

	// Plain http is allowed only after a second press of "Connect"
	private var confirmedInsecureUrl: String? = null

	init {
		connectButton.setOnClickListener { connect() }
		serverInput.setOnEditorActionListener { _, actionId, event ->
			val done = actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO ||
				(event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
			if (done) {
				hideKeyboard()
				connectButton.requestFocus()
			}
			done
		}
	}

	fun show() {
		serverInput.setText(settings.serverUrl.orEmpty())
		message.text = ""
		connectButton.isEnabled = true
		// Start on the first empty field
		if (serverInput.text.isNullOrBlank()) serverInput.requestFocus() else connectButton.requestFocus()
	}

	fun hide() {
		checkJob?.cancel()
	}

	private fun hideKeyboard() {
		root.context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(root.windowToken, 0)
	}

	private fun connect() {
		hideKeyboard()
		val rawAddress = serverInput.text.toString()
		if (rawAddress.isBlank()) return showError(context.getString(R.string.error_empty_address))
		val serverUrl = JellyfinClient.normalizeServerUrl(rawAddress)
			?: return showError(context.getString(R.string.error_invalid_address))

		// Same rule and text as discovery: any plain http address needs an explicit second press
		if (JellyfinClient.isInsecureUrl(serverUrl) && confirmedInsecureUrl != serverUrl) {
			confirmedInsecureUrl = serverUrl
			serverInput.setText(serverUrl)
			message.setTextColor(ContextCompat.getColor(context, R.color.accent))
			message.text = context.getString(R.string.insecure_press_again, context.getString(R.string.insecure_warning))
			connectButton.requestFocus()
			return
		}

		serverInput.setText(serverUrl)

		checkJob?.cancel()
		checkJob = scope.launch {
			connectButton.isEnabled = false
			message.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
			message.setText(R.string.setup_checking)

			try {
				val info = withTimeout(CHECK_TIMEOUT_MS) { jellyfin.getPublicSystemInfo(serverUrl) }
				// A Jellyfin server always reports its id; anything else is not a Jellyfin server
				if (info.id.isNullOrBlank()) {
					showError(context.getString(R.string.error_not_jellyfin))
					return@launch
				}

				Timber.i("Server ${info.serverName} ${info.version} at $serverUrl")
				message.setTextColor(ContextCompat.getColor(context, R.color.ok))
				message.text = context.getString(R.string.setup_server_ok, info.serverName.orEmpty(), info.version.orEmpty())
				settings.serverUrl = serverUrl
				settings.serverId = info.id
				if (JellyfinClient.isInsecureUrl(serverUrl)) info.id?.let(settings::confirmInsecureServer)
				onConfigured()
			} catch (_: TimeoutCancellationException) {
				showError(context.getString(R.string.error_timeout))
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Server check failed for $serverUrl")
				showError(errorText(context, err.describe()))
			} finally {
				connectButton.isEnabled = true
			}
		}
	}

	private fun showError(text: String) {
		message.setTextColor(ContextCompat.getColor(context, R.color.error))
		message.text = text
		serverInput.requestFocus()
	}

	companion object {
		private const val CHECK_TIMEOUT_MS = 15_000L

		fun errorText(context: Context, error: ConnectionError): String = when (error) {
			ConnectionError.UnknownHost -> context.getString(R.string.error_unknown_host)
			ConnectionError.Refused -> context.getString(R.string.error_refused)
			ConnectionError.Timeout -> context.getString(R.string.error_timeout)
			ConnectionError.Tls -> context.getString(R.string.error_tls)
			ConnectionError.Network -> context.getString(R.string.error_network)
			ConnectionError.NotJellyfin -> context.getString(R.string.error_not_jellyfin)
			is ConnectionError.HttpStatus -> context.getString(R.string.error_http_status, error.status)
			is ConnectionError.Other -> context.getString(R.string.error_other, error.message)
		}
	}
}
