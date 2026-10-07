package ua.movieportal.tv.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
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
 */
object PlayerLauncher {
	private const val VERIFY_DELAY_MS = 3_000L

	private val handler = Handler(Looper.getMainLooper())

	fun launch(context: Context, requestId: Long) {
		// A running player picks the new request up from RemoteHub
		if (RemoteHub.playerActive) return

		val app = MoviePortalApp.instance
		val intent = PlayerActivity.intent(context)
		val visible = app.startedActivities > 0
		val overlay = PermissionsPanel.canDrawOverlays(context)
		val allowed = visible || overlay || !AndroidVersion.isAtLeastQ

		Timber.i("Launching player (visible=$visible, overlay=$overlay, api=${android.os.Build.VERSION.SDK_INT})")
		if (allowed) {
			try {
				context.startActivity(intent)
			} catch (err: RuntimeException) {
				Timber.e(err, "Unable to start player")
			}
		}

		// Background starts can be dropped without an error, verify and fall back to the notification
		handler.postDelayed({
			val pending = RemoteHub.playRequest.value?.id == requestId && RemoteHub.hasPendingPlayRequest
			if (pending) showNotification(context, intent)
		}, if (allowed) VERIFY_DELAY_MS else 0)
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
