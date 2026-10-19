package ua.movieportal.tv.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import timber.log.Timber
import ua.movieportal.tv.MoviePortalApp
import ua.movieportal.tv.player.PlayerActivity
import ua.movieportal.tv.remote.RemoteHub
import ua.movieportal.tv.ui.PermissionsPanel
import ua.movieportal.tv.util.AndroidVersion

/**
 * Opens the player in response to a remote "play" command.
 *
 * Android 10+ blocks activity starts from the background. Exemptions used here, in order:
 * 1. the app already has a visible window (idle screen or player in front);
 * 2. the "display over other apps" permission (SYSTEM_ALERT_WINDOW) is granted;
 * 3. Android 9 and older have no restriction.
 * Otherwise (or when the start was silently blocked) a notification that opens the player is posted.
 *
 * Started while the TV is in standby (screen off) the player is created but stays stopped until the screen is on; the
 * launcher then brings it to the front again and only after that falls back to the notification.
 */
object PlayerLauncher {
	private const val VERIFY_DELAY_MS = 3_000L
	private const val WAKE_DURATION_MS = 5_000L

	// A TV woken from standby (screen off) may take long to turn the screen on (Sony Bravia: > 10 s)
	private const val SCREEN_ON_TIMEOUT_MS = 30_000L

	private val handler = Handler(Looper.getMainLooper())

	// One-shot "screen is on" wait of the latest launch; a new launch replaces it
	private var screenOnWaiter: BroadcastReceiver? = null
	private var screenOnTimeout: Runnable? = null

	fun launch(context: Context, requestId: Long) {
		val app = MoviePortalApp.instance
		val power = context.getSystemService<PowerManager>()
		val interactive = power?.isInteractive != false
		val launchedAt = SystemClock.elapsedRealtime()

		// A running player picks the new request up from RemoteHub; it may still be hidden behind a waking screen
		if (RemoteHub.playerActive) {
			if (!interactive) {
				Timber.i("Player already running, screen off: waking up and bringing it to the front once on")
				wakeScreen(context)
				whenScreenOn(context, launchedAt) { startPlayer(context) }
			} else if (app.startedActivities == 0) {
				Timber.i("Player already running but hidden: bringing it to the front")
				startPlayer(context)
			}
			return
		}

		val visible = app.startedActivities > 0
		val overlay = PermissionsPanel.canDrawOverlays(context)
		val allowed = visible || overlay || !AndroidVersion.isAtLeastQ

		Timber.i(
			"Launching player (visible=$visible, overlay=$overlay, screen on=$interactive, " +
				"api=${android.os.Build.VERSION.SDK_INT})"
		)
		// The screensaver (ambient mode) is a window above every activity: the player would start hidden under it
		wakeScreen(context)
		if (allowed) startPlayer(context)

		if (interactive) {
			scheduleVerify(context, requestId, if (allowed) VERIFY_DELAY_MS else 0)
		} else {
			// Started while the screen is off: the activity stays stopped until the TV is awake. Bring it to the front
			// again when the screen is on and only then judge whether the start worked (a notification posted while
			// the screen is off would never be seen)
			whenScreenOn(context, launchedAt) {
				if (allowed) startPlayer(context)
				scheduleVerify(context, requestId, if (allowed) VERIFY_DELAY_MS else 0)
			}
		}
	}

	/** PlayerActivity is singleTask: a second start only brings the existing player to the front. */
	private fun startPlayer(context: Context) {
		try {
			context.startActivity(PlayerActivity.intent(context))
		} catch (err: RuntimeException) {
			Timber.e(err, "Unable to start player")
		}
	}

	// Background starts can be dropped without an error, verify and fall back to the notification
	private fun scheduleVerify(context: Context, requestId: Long, delayMs: Long) {
		handler.postDelayed({
			val pending = RemoteHub.playRequest.value?.id == requestId && RemoteHub.hasPendingPlayRequest
			if (pending) showNotification(context, PlayerActivity.intent(context))
		}, delayMs)
	}

	/** Runs [action] once the screen is on (or unlocked), at the latest after [SCREEN_ON_TIMEOUT_MS]. */
	private fun whenScreenOn(context: Context, launchedAt: Long, action: () -> Unit) {
		val appContext = context.applicationContext
		cancelScreenOnWait(appContext)

		var done = false
		fun finish(reason: String) {
			if (done) return
			done = true
			cancelScreenOnWait(appContext)
			val waited = SystemClock.elapsedRealtime() - launchedAt
			if (reason == "timeout") Timber.w("Screen did not turn on within ${waited}ms, starting the player anyway")
			else Timber.i("Screen on after ${waited}ms ($reason)")
			action()
		}

		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) = finish(intent.action.orEmpty())
		}
		val timeout = Runnable { finish("timeout") }
		screenOnWaiter = receiver
		screenOnTimeout = timeout
		ContextCompat.registerReceiver(
			appContext,
			receiver,
			IntentFilter().apply {
				addAction(Intent.ACTION_SCREEN_ON)
				addAction(Intent.ACTION_USER_PRESENT)
			},
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		handler.postDelayed(timeout, SCREEN_ON_TIMEOUT_MS)

		// The screen may have come on between the check and the registration
		if (appContext.getSystemService<PowerManager>()?.isInteractive == true) finish("already on")
	}

	private fun cancelScreenOnWait(appContext: Context) {
		screenOnWaiter?.let { receiver -> runCatching { appContext.unregisterReceiver(receiver) } }
		screenOnTimeout?.let(handler::removeCallbacks)
		screenOnWaiter = null
		screenOnTimeout = null
	}

	/**
	 * Ends the screensaver / turns the screen on: a short wake lock with ACQUIRE_CAUSES_WAKEUP counts as user activity.
	 * FULL_WAKE_LOCK is deprecated for keeping the screen on, which is not the point here (the player sets
	 * keepScreenOn itself); it is still the public way to wake the screen up.
	 */
	@Suppress("DEPRECATION")
	private fun wakeScreen(context: Context) {
		val power = context.getSystemService<PowerManager>() ?: return
		try {
			power.newWakeLock(
				PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
				"movieportal:play",
			).acquire(WAKE_DURATION_MS)
		} catch (err: RuntimeException) {
			Timber.w(err, "Unable to wake the screen")
		}
	}

	fun cancelNotification(context: Context) {
		NotificationManagerCompat.from(context).cancel(Notifications.ID_PLAYBACK)
	}

	@SuppressLint("MissingPermission")
	private fun showNotification(context: Context, intent: Intent) {
		Timber.w("Player did not start, posting a notification")
		val manager = NotificationManagerCompat.from(context)
		if (!manager.areNotificationsEnabled()) {
			Timber.w("Notifications are disabled, can not open the player")
			return
		}
		manager.notify(Notifications.ID_PLAYBACK, Notifications.playback(context, intent))
	}
}
