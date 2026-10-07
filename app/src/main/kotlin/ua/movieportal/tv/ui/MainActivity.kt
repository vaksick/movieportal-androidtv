package ua.movieportal.tv.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.sessionApi
import timber.log.Timber
import ua.movieportal.tv.MoviePortalApp
import ua.movieportal.tv.R
import ua.movieportal.tv.data.LogoutReason
import ua.movieportal.tv.service.ReceiverService

/**
 * The only non-player screen. Shows one of three steps: setup -> Quick Connect -> status.
 */
class MainActivity : ComponentActivity() {
	private enum class Screen { SETUP, QUICK_CONNECT, STATUS }

	private val app get() = application as MoviePortalApp

	private lateinit var setupView: View
	private lateinit var quickConnectView: View
	private lateinit var statusView: View

	private lateinit var setupScreen: SetupScreen
	private lateinit var quickConnectScreen: QuickConnectScreen
	private lateinit var statusScreen: StatusScreen
	private lateinit var setupPermissions: PermissionsPanel
	private lateinit var statusPermissions: PermissionsPanel

	private var screen: Screen? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		setupView = findViewById(R.id.screen_setup)
		quickConnectView = findViewById(R.id.screen_quick_connect)
		statusView = findViewById(R.id.screen_status)

		setupScreen = SetupScreen(setupView, lifecycleScope, app.settings, app.jellyfin) {
			show(Screen.QUICK_CONNECT)
		}
		quickConnectScreen = QuickConnectScreen(
			root = quickConnectView,
			scope = lifecycleScope,
			settings = app.settings,
			jellyfin = app.jellyfin,
			onAuthenticated = { show(Screen.STATUS) },
			onChangeServer = { show(Screen.SETUP) },
		)
		statusScreen = StatusScreen(statusView, lifecycleScope, app.settings, ::disconnect)
		setupPermissions = PermissionsPanel(this, findViewById(R.id.setup_permissions))
		statusPermissions = PermissionsPanel(this, findViewById(R.id.status_permissions))

		// The TV keyboard stays open when D-pad focus moves from a text field to a button: close it explicitly
		window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
			if (oldFocus is EditText && newFocus !is EditText) {
				getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
			}
		}

		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				if (screen == Screen.QUICK_CONNECT) show(Screen.SETUP) else finish()
			}
		})

		// Token revoked by the server while this screen is open: go back to Quick Connect
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				app.settings.credentials.collect { credentials ->
					if (credentials == null && screen == Screen.STATUS) show(Screen.QUICK_CONNECT)
				}
			}
		}
	}

	override fun onStart() {
		super.onStart()
		show(initialScreen())
	}

	override fun onResume() {
		super.onResume()
		// Permissions may have been changed in the system settings
		setupPermissions.refresh()
		statusPermissions.refresh()
	}

	override fun onStop() {
		super.onStop()
		// Stop polling while invisible; onStart restarts the current step
		quickConnectScreen.stop()
		statusScreen.stop()
		setupScreen.hide()
	}

	override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
		if (keyCode == KeyEvent.KEYCODE_MENU && screen == Screen.STATUS) {
			statusScreen.confirmDisconnect()
			return true
		}
		return super.onKeyUp(keyCode, event)
	}

	private fun initialScreen(): Screen = when {
		app.settings.credentials.value != null -> Screen.STATUS
		app.settings.serverUrl == null -> Screen.SETUP
		screen == Screen.SETUP -> Screen.SETUP
		else -> Screen.QUICK_CONNECT
	}

	private fun show(target: Screen) {
		quickConnectScreen.stop()
		statusScreen.stop()
		setupScreen.hide()

		screen = target
		setupView.isVisible = target == Screen.SETUP
		quickConnectView.isVisible = target == Screen.QUICK_CONNECT
		statusView.isVisible = target == Screen.STATUS

		when (target) {
			Screen.SETUP -> setupScreen.show()
			Screen.QUICK_CONNECT -> quickConnectScreen.start()
			Screen.STATUS -> {
				// Started from a visible activity, so the foreground service start is always allowed
				ReceiverService.start(this)
				statusScreen.start()
			}
		}
	}

	private fun disconnect() {
		val credentials = app.settings.credentials.value
		ReceiverService.stop(this)
		app.settings.clearCredentials(LogoutReason.USER)

		// Revoke the token on the server too (POST /Sessions/Logout), best effort
		if (credentials != null) {
			val api = app.jellyfin.createApi(credentials)
			app.appScope.launch {
				runCatching { api.sessionApi.reportSessionEnded() }
					.onFailure { Timber.w(it, "Logout request failed") }
			}
		}
		show(Screen.SETUP)
	}
}
