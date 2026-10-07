package ua.movieportal.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.movieportal.tv.R
import ua.movieportal.tv.data.AppSettings
import ua.movieportal.tv.discovery.DiscoveredServer
import ua.movieportal.tv.discovery.DiscoveryDecision
import ua.movieportal.tv.discovery.DiscoveryProtocol
import ua.movieportal.tv.discovery.ServerDiscovery

/**
 * Step 1: finds Jellyfin servers on the LAN.
 * - one reachable https server (or http already confirmed) is selected automatically;
 * - one reachable http-only server shows a warning first ("connect anyway" / manual entry);
 * - several servers are offered as a D-pad list (http-only ones marked "незахищений");
 * - none keeps scanning and offers manual address entry.
 */
class DiscoveryScreen(
	root: View,
	private val scope: CoroutineScope,
	private val settings: AppSettings,
	private val discovery: ServerDiscovery,
	private val onSelected: () -> Unit,
	onManual: () -> Unit,
) {
	private val context = root.context
	private val progress = root.findViewById<View>(R.id.discovery_progress)
	private val status = root.findViewById<TextView>(R.id.discovery_status)
	private val list = root.findViewById<LinearLayout>(R.id.discovery_list)
	private val warning = root.findViewById<View>(R.id.discovery_warning)
	private val warningText = root.findViewById<TextView>(R.id.discovery_warning_text)
	private val connectAnyway = root.findViewById<Button>(R.id.discovery_insecure_connect)
	private val hint = root.findViewById<TextView>(R.id.discovery_hint)
	private val manualButton = root.findViewById<Button>(R.id.discovery_manual)

	private var job: Job? = null
	private var shown: List<DiscoveredServer>? = null

	/** False after the user came back from a selected server to pick another one. */
	var allowAutoSelect = true

	init {
		manualButton.setOnClickListener { onManual() }
		// ScrollView makes itself focusable and would steal the initial D-pad focus from the buttons
		root.findViewById<View>(R.id.discovery_scroll).isFocusable = false
	}

	fun start() {
		shown = null
		list.removeAllViews()
		warning.isVisible = false
		hint.isVisible = false
		status.setText(R.string.discovery_searching)
		// post: the window may assign initial focus after this call
		manualButton.post { if (!list.hasFocus()) manualButton.requestFocus() }

		job?.cancel()
		job = scope.launch {
			// Keep rescanning while the screen is visible: the server may start later or another one may appear
			while (true) {
				progress.isVisible = true
				val servers = discovery.scan()
				progress.isVisible = false

				when (val decision = DiscoveryDecision.of(servers, allowAutoSelect, settings.confirmedInsecureServers)) {
					is DiscoveryDecision.AutoSelect -> {
						select(decision.server)
						return@launch
					}

					is DiscoveryDecision.ConfirmInsecure -> {
						// Scanning pauses while the warning is shown, so it does not flicker or nag
						showInsecureWarning(decision.server)
						return@launch
					}

					is DiscoveryDecision.Choose -> render(decision.servers)
					DiscoveryDecision.NothingFound -> render(emptyList())
				}
				delay(RESCAN_DELAY_MS)
			}
		}
	}

	fun stop() {
		job?.cancel()
		job = null
	}

	private fun select(server: DiscoveredServer.Reachable) {
		stop()
		settings.serverUrl = server.url
		settings.serverId = server.id
		onSelected()
	}

	/** A server picked from the list still gets the http warning unless it was confirmed before. */
	private fun choose(server: DiscoveredServer.Reachable) {
		val confirmed = settings.confirmedInsecureServers.any { DiscoveryProtocol.sameId(it, server.id) }
		if (server.insecure && !confirmed) {
			stop()
			showInsecureWarning(server)
		} else {
			select(server)
		}
	}

	private fun showInsecureWarning(server: DiscoveredServer.Reachable) {
		progress.isVisible = false
		list.removeAllViews()
		shown = null
		hint.isVisible = false
		status.text = context.getString(R.string.discovery_insecure_title, server.name, server.url)
		warningText.setText(R.string.insecure_warning)
		warning.isVisible = true
		connectAnyway.setOnClickListener {
			// Remembered per server id until "Змінити сервер" / "Відключити"
			settings.confirmInsecureServer(server.id)
			select(server)
		}
		// post: the button only becomes focusable after the layout pass that shows the warning
		connectAnyway.post { connectAnyway.requestFocus() }
	}

	private fun render(servers: List<DiscoveredServer>) {
		warning.isVisible = false
		val reachable = servers.count { it is DiscoveredServer.Reachable }
		status.setText(
			when {
				servers.isEmpty() -> R.string.discovery_none
				reachable == 0 -> R.string.discovery_all_unreachable
				servers.size == 1 -> R.string.discovery_one
				else -> R.string.discovery_choose
			}
		)

		val anyUnreachable = servers.any { it is DiscoveredServer.Unreachable }
		hint.isVisible = servers.isEmpty() || anyUnreachable
		hint.setText(if (anyUnreachable) R.string.discovery_unreachable_hint else R.string.discovery_none_hint)

		// Rebuilding the list would move the D-pad focus, so only do it when the result changed
		if (servers == shown) return
		val firstResult = shown.isNullOrEmpty()
		shown = servers
		val hadFocus = list.hasFocus()
		list.removeAllViews()

		val inflater = LayoutInflater.from(context)
		servers.forEach { server ->
			val button = inflater.inflate(R.layout.item_server, list, false) as Button
			when (server) {
				is DiscoveredServer.Reachable -> {
					val label = if (server.insecure) R.string.discovery_server_item_insecure else R.string.discovery_server_item
					button.text = context.getString(label, server.name, server.url)
					button.setOnClickListener { choose(server) }
				}

				is DiscoveredServer.Unreachable -> {
					button.text = context.getString(
						R.string.discovery_unreachable,
						server.name,
						server.reportedAddress ?: context.getString(R.string.discovery_address_unknown),
					)
					button.isEnabled = false
					button.isFocusable = false
				}
			}
			list.addView(button)
		}

		// Focus the first server when the list appears (or was focused before the rebuild)
		if (hadFocus || firstResult) list.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus()
	}

	private companion object {
		const val RESCAN_DELAY_MS = 5_000L
	}
}
