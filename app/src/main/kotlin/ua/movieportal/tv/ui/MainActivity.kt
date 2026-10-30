package ua.movieportal.tv.ui

import android.annotation.SuppressLint
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.sessionApi
import timber.log.Timber
import ua.movieportal.tv.MoviePortalApp
import ua.movieportal.tv.R
import ua.movieportal.tv.data.LogoutReason
import ua.movieportal.tv.discovery.ServerDiscovery
import ua.movieportal.tv.profile.deviceCapabilities
import ua.movieportal.tv.service.ReceiverService

/**
 * The only non-player screen. Shows one of three steps: setup -> Quick Connect -> status.
 */
class MainActivity : ComponentActivity() {
	private enum class Screen { DISCOVERY, SETUP, QUICK_CONNECT, STATUS }

	private val app get() = application as MoviePortalApp

	private lateinit var discoveryView: View
	private lateinit var setupView: View
	private lateinit var quickConnectView: View
	private lateinit var statusView: View

	private lateinit var discoveryScreen: DiscoveryScreen
	private lateinit var setupScreen: SetupScreen
	private lateinit var quickConnectScreen: QuickConnectScreen
	private lateinit var statusScreen: StatusScreen
	private lateinit var setupPermissions: PermissionsPanel
	private lateinit var statusPermissions: PermissionsPanel

	private var screen: Screen? = null

	// The remote's Back was held: the matching key up must not act as a short press
	private var backLongPressed = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		discoveryView = findViewById(R.id.screen_discovery)
		setupView = findViewById(R.id.screen_setup)
		quickConnectView = findViewById(R.id.screen_quick_connect)
		statusView = findViewById(R.id.screen_status)

		discoveryScreen = DiscoveryScreen(
			root = discoveryView,
			scope = lifecycleScope,
			settings = app.settings,
			discovery = ServerDiscovery(this, app.jellyfin),
			onSelected = { show(Screen.QUICK_CONNECT) },
			onManual = { show(Screen.SETUP) },
		)
		setupScreen = SetupScreen(setupView, lifecycleScope, app.settings, app.jellyfin) {
			show(Screen.QUICK_CONNECT)
		}
		quickConnectScreen = QuickConnectScreen(
			root = quickConnectView,
			scope = lifecycleScope,
			settings = app.settings,
			jellyfin = app.jellyfin,
			onAuthenticated = { show(Screen.STATUS) },
			onChangeServer = ::chooseServer,
		)
		statusScreen = StatusScreen(statusView, lifecycleScope, app.settings, ::disconnect, ::rename, ::finish)
		setupPermissions = PermissionsPanel(this, findViewById(R.id.setup_permissions))
		statusPermissions = PermissionsPanel(this, findViewById(R.id.status_permissions))

		lifecycleScope.launch {
			val capabilities = withContext(Dispatchers.Default) { deviceCapabilities(applicationContext) }
			CapabilitiesCard(findViewById(R.id.status_capabilities), findViewById(R.id.status_capabilities_table))
				.show(capabilities)
		}

		// The TV keyboard stays open when D-pad focus moves from a text field to a button: close it explicitly
		window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
			if (oldFocus is EditText && newFocus !is EditText) {
				getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
			}
		}

		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				when (screen) {
					Screen.QUICK_CONNECT, Screen.SETUP -> chooseServer()
					else -> finish()
				}
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
		isOnScreen = true
		show(initialScreen())
	}

	override fun onResume() {
		super.onResume()
		// Permissions may have been changed in the system settings
		setupPermissions.refresh()
		statusPermissions.refresh()
	}

	/**
	 * Safety net for a screen without focus (touch mode after a mouse/pointer remote, focus lost to a hidden view):
	 * the D-pad must always have a highlighted control to start from.
	 */
	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus && currentFocus?.isShown != true) focusDefault()
	}

	// Back: short press = the screen's usual step through the dispatcher on key up, long press = close the UI and go to
	// the TV launcher (the receiver service keeps running, the TV stays available in the portal). Back key events need
	// enableOnBackInvokedCallback="false" on Android 16+ (see the manifest).
	@SuppressLint("GestureBackNavigation")
	override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
		if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyLongPress(keyCode, event)
		backLongPressed = true
		finish()
		return true
	}

	// A D-pad key on a screen without focus (left in touch mode) reaches the activity and would do nothing at all
	@SuppressLint("GestureBackNavigation")
	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			event.startTracking()
			return true
		}
		if (keyCode in NAVIGATION_KEYS && currentFocus?.isShown != true) {
			focusDefault()
			return true
		}
		return super.onKeyDown(keyCode, event)
	}

	private fun focusDefault() {
		when (screen) {
			Screen.STATUS -> statusScreen.focusDefault()
			else -> visibleScreenView()?.requestFocusFromTouch()
		}
	}

	private fun visibleScreenView(): View? = listOf(discoveryView, setupView, quickConnectView, statusView).firstOrNull { it.isVisible }

	override fun onStop() {
		super.onStop()
		isOnScreen = false
		// Stop polling while invisible; onStart restarts the current step
		discoveryScreen.stop()
		quickConnectScreen.stop()
		statusScreen.stop()
		setupScreen.hide()
	}

	@SuppressLint("GestureBackNavigation")
	override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			if (!backLongPressed && event.isTracking && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
			backLongPressed = false
			return true
		}
		if (keyCode == KeyEvent.KEYCODE_MENU && screen == Screen.STATUS) {
			statusScreen.confirmDisconnect()
			return true
		}
		return super.onKeyUp(keyCode, event)
	}

	private fun initialScreen(): Screen = when {
		app.settings.credentials.value != null -> Screen.STATUS
		// Keep the step the user was on (e.g. manual entry) when coming back to the app
		screen == Screen.SETUP || screen == Screen.DISCOVERY -> screen!!
		app.settings.serverUrl == null -> Screen.DISCOVERY
		else -> Screen.QUICK_CONNECT
	}

	/** Back to the server search without picking the single found server again automatically. */
	private fun chooseServer() {
		discoveryScreen.allowAutoSelect = false
		// A plain http confirmation only lasts until the user changes or disconnects the server
		app.settings.clearInsecureConfirmations()
		show(Screen.DISCOVERY)
	}

	private fun show(target: Screen) {
		discoveryScreen.stop()
		quickConnectScreen.stop()
		statusScreen.stop()
		setupScreen.hide()

		screen = target
		discoveryView.isVisible = target == Screen.DISCOVERY
		setupView.isVisible = target == Screen.SETUP
		quickConnectView.isVisible = target == Screen.QUICK_CONNECT
		statusView.isVisible = target == Screen.STATUS

		when (target) {
			Screen.DISCOVERY -> discoveryScreen.start()
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
			// SDK requests must run on IO (the SDK reads the response body on the calling dispatcher)
			app.appScope.launch(Dispatchers.IO) {
				runCatching { api.sessionApi.reportSessionEnded() }
					.onFailure { Timber.w(it, "Logout request failed") }
			}
		}
		chooseServer()
	}

	/** New device name; the service observes it and re-registers the session by itself. */
	private fun rename(name: String) {
		app.settings.deviceName = name
		statusScreen.start()
	}

	companion object {
		/**
		 * This screen is visible right now. The player checks it when the portal starts it: only then does it return
		 * here after the video, otherwise it leaves to wherever the TV was (closed app, launcher, another app).
		 */
		@Volatile
		var isOnScreen = false
			private set

		private val NAVIGATION_KEYS = setOf(
			KeyEvent.KEYCODE_DPAD_UP,
			KeyEvent.KEYCODE_DPAD_DOWN,
			KeyEvent.KEYCODE_DPAD_LEFT,
			KeyEvent.KEYCODE_DPAD_RIGHT,
			KeyEvent.KEYCODE_DPAD_CENTER,
			KeyEvent.KEYCODE_ENTER,
		)
	}
}
