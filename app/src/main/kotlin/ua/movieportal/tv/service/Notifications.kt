package ua.movieportal.tv.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ua.movieportal.tv.R
import ua.movieportal.tv.ui.MainActivity

/**
 * Notification channels and builders.
 */
object Notifications {
	const val CHANNEL_SERVICE = "receiver_service"
	const val CHANNEL_PLAYBACK = "playback_launch"

	const val ID_SERVICE = 1
	const val ID_PLAYBACK = 2
	const val ID_REVOKED = 3

	fun createChannels(context: Context) {
		val manager = NotificationManagerCompat.from(context)
		manager.createNotificationChannel(
			NotificationChannelCompat.Builder(CHANNEL_SERVICE, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(context.getString(R.string.channel_service))
				.setShowBadge(false)
				.build()
		)
		manager.createNotificationChannel(
			NotificationChannelCompat.Builder(CHANNEL_PLAYBACK, NotificationManagerCompat.IMPORTANCE_HIGH)
				.setName(context.getString(R.string.channel_playback))
				.setShowBadge(false)
				.build()
		)
	}

	fun service(context: Context, text: String): Notification =
		NotificationCompat.Builder(context, CHANNEL_SERVICE)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(context.getString(R.string.app_name))
			.setContentText(text)
			.setContentIntent(openAppIntent(context))
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setShowWhen(false)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.build()

	/** Plain informational message that opens the app. */
	fun message(context: Context, text: String): Notification = NotificationCompat.Builder(context, CHANNEL_SERVICE)
		.setSmallIcon(R.drawable.ic_notification)
		.setContentTitle(context.getString(R.string.app_name))
		.setContentText(text)
		.setContentIntent(openAppIntent(context))
		.setAutoCancel(true)
		.build()

	private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
		context,
		0,
		Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
	)

	/**
	 * Fallback when the player can not be started from the background: a high-priority notification that opens the
	 * player. No full-screen intent: Google Play only allows USE_FULL_SCREEN_INTENT for calling and alarm apps.
	 */
	fun playback(context: Context, playerIntent: Intent): Notification {
		val pendingIntent = PendingIntent.getActivity(
			context,
			1,
			playerIntent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)

		return NotificationCompat.Builder(context, CHANNEL_PLAYBACK)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(context.getString(R.string.notification_play_title))
			.setContentText(context.getString(R.string.notification_play_text))
			.setContentIntent(pendingIntent)
			.setAutoCancel(true)
			.setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
			.setPriority(NotificationCompat.PRIORITY_MAX)
			.setTimeoutAfter(PLAYBACK_NOTIFICATION_TIMEOUT_MS)
			.build()
	}

	private const val PLAYBACK_NOTIFICATION_TIMEOUT_MS = 2 * 60 * 1000L
}
