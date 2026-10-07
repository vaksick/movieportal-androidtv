package ua.movieportal.tv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import ua.movieportal.tv.MoviePortalApp

/**
 * Starts the receiver service after the TV boots or the app is updated, if the device is paired.
 */
class BootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action !in ACTIONS) return

		val paired = MoviePortalApp.instance.settings.credentials.value != null
		Timber.i("Received ${intent.action}, paired=$paired")
		if (paired) ReceiverService.start(context)
	}

	private companion object {
		val ACTIONS = setOf(
			Intent.ACTION_BOOT_COMPLETED,
			Intent.ACTION_MY_PACKAGE_REPLACED,
			// Vendor "fast boot" variants
			"android.intent.action.QUICKBOOT_POWERON",
			"com.htc.intent.action.QUICKBOOT_POWERON",
		)
	}
}
