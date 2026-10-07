package ua.movieportal.tv.ui

import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ua.movieportal.tv.R
import ua.movieportal.tv.data.AppSettings
import ua.movieportal.tv.remote.ConnectionState
import ua.movieportal.tv.remote.RemoteHub

/**
 * Step 3: idle screen showing the device, server, account and connection state.
 */
class StatusScreen(
	root: View,
	private val scope: CoroutineScope,
	private val settings: AppSettings,
	private val onDisconnect: () -> Unit,
) {
	private val context = root.context
	private val connection = root.findViewById<TextView>(R.id.status_connection)
	private val device = root.findViewById<TextView>(R.id.status_device)
	private val server = root.findViewById<TextView>(R.id.status_server)
	private val account = root.findViewById<TextView>(R.id.status_account)
	private val disconnect = root.findViewById<Button>(R.id.status_disconnect)

	private var job: Job? = null

	init {
		disconnect.setOnClickListener { confirmDisconnect() }
	}

	fun start() {
		val credentials = settings.credentials.value
		device.text = context.getString(R.string.status_device, settings.deviceName)
		server.text = context.getString(R.string.status_server, credentials?.serverUrl.orEmpty())
		account.text = context.getString(R.string.status_account, credentials?.userName ?: credentials?.userId.orEmpty())
		disconnect.requestFocus()

		job?.cancel()
		job = scope.launch {
			RemoteHub.connectionState.collect(::render)
		}
	}

	fun stop() {
		job?.cancel()
		job = null
	}

	/** Shows the disconnect confirmation (button or the remote's menu key). */
	fun confirmDisconnect() {
		AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
			.setMessage(R.string.status_disconnect_confirm)
			.setPositiveButton(R.string.status_disconnect) { _, _ -> onDisconnect() }
			.setNegativeButton(R.string.cancel, null)
			.show()
	}

	private fun render(state: ConnectionState) {
		val (text, color) = when (state) {
			ConnectionState.Connected -> context.getString(R.string.status_connected) to R.color.ok
			ConnectionState.Connecting -> context.getString(R.string.status_connecting) to R.color.accent
			is ConnectionState.Reconnecting -> context.getString(R.string.status_reconnecting, state.delaySeconds) to R.color.accent
			ConnectionState.Offline -> context.getString(R.string.status_offline) to R.color.error
			ConnectionState.Stopped -> context.getString(R.string.status_stopped) to R.color.error
		}
		connection.text = context.getString(R.string.status_line, text)
		connection.setTextColor(ContextCompat.getColor(context, color))
	}
}
