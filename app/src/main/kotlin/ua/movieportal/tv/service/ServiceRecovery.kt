package ua.movieportal.tv.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.content.getSystemService
import timber.log.Timber
import ua.movieportal.tv.MoviePortalApp

/**
 * Brings the receiver service back after Android refused to restart it in the foreground (sticky restart from the
 * background on Android 12+).
 *
 * Uses an inexact `setAndAllowWhileIdle` alarm: exact alarms would need SCHEDULE_EXACT_ALARM (a special permission
 * on Android 14+). An inexact alarm does not itself allow a foreground service start from the background; the start
 * succeeds when another exemption applies, most often the "display over other apps" permission (Android 12–14), on
 * older Android always. Otherwise the next attempt is scheduled with a growing delay; opening the app or a reboot
 * (BOOT_COMPLETED is exempt) also brings the service back. Limits are documented in RECEIVER_PLAN.md.
 */
object ServiceRecovery {
	private const val PREFS_NAME = "service_recovery"
	private const val KEY_ATTEMPT = "attempt"

	// Minutes between attempts; the last value repeats
	private val DELAYS_MINUTES = listOf(1L, 2L, 5L, 10L, 15L, 30L)

	/** Delay before recovery attempt number [attempt] (0-based). */
	internal fun delayMs(attempt: Int): Long =
		DELAYS_MINUTES[attempt.coerceIn(0, DELAYS_MINUTES.lastIndex)] * 60_000L

	fun schedule(context: Context) {
		val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		val attempt = prefs.getInt(KEY_ATTEMPT, 0)
		prefs.edit { putInt(KEY_ATTEMPT, attempt + 1) }

		val delay = delayMs(attempt)
		val alarmManager = context.getSystemService<AlarmManager>() ?: return
		alarmManager.setAndAllowWhileIdle(
			AlarmManager.ELAPSED_REALTIME_WAKEUP,
			SystemClock.elapsedRealtime() + delay,
			pendingIntent(context),
		)
		Timber.w("Receiver service stopped, recovery attempt ${attempt + 1} in ${delay / 1000}s")
	}

	/** The service is running in the foreground again: forget the backoff and any pending attempt. */
	fun reset(context: Context) {
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { remove(KEY_ATTEMPT) }
		context.getSystemService<AlarmManager>()?.cancel(pendingIntent(context))
	}

	private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
		context,
		0,
		Intent(context, ServiceRecoveryReceiver::class.java),
		PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
	)
}

/** Alarm target of [ServiceRecovery]. */
class ServiceRecoveryReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		// Unpaired (or disconnected meanwhile): nothing to recover
		if (MoviePortalApp.instance.settings.credentials.value == null) return
		if (!ReceiverService.start(context)) ServiceRecovery.schedule(context)
	}
}
