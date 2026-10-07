package ua.movieportal.tv.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import timber.log.Timber
import ua.movieportal.tv.R
import ua.movieportal.tv.util.AndroidVersion

/**
 * Shows and requests the permissions the receiver needs:
 * - "display over other apps" (SYSTEM_ALERT_WINDOW) to open the player from the background on Android 10+;
 * - notifications (Android 13+) for the foreground service and the "open the player" fallback.
 */
class PermissionsPanel(private val activity: ComponentActivity, root: View) {
	private val overlayStatus = root.findViewById<TextView>(R.id.perm_overlay_status)
	private val overlayButton = root.findViewById<Button>(R.id.perm_overlay_button)
	private val overlayUnavailable = root.findViewById<TextView>(R.id.perm_overlay_unavailable)
	private val notificationsRow = root.findViewById<View>(R.id.perm_notifications_row)
	private val notificationsStatus = root.findViewById<TextView>(R.id.perm_notifications_status)
	private val notificationsButton = root.findViewById<Button>(R.id.perm_notifications_button)

	private var overlayRequested = false

	private val notificationPermissionLauncher =
		activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

	init {
		overlayButton.setOnClickListener { requestOverlay() }
		notificationsButton.setOnClickListener {
			if (AndroidVersion.isAtLeastT) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
		refresh()
	}

	/** Re-read the permission state, call from onResume. */
	fun refresh() {
		val overlay = canDrawOverlays(activity)
		overlayStatus.setText(if (overlay) R.string.setup_overlay_granted else R.string.setup_overlay_missing)
		overlayButton.isVisible = !overlay
		// Show the adb fallback when the system settings screen did not help
		overlayUnavailable.isVisible = !overlay && overlayRequested
		overlayUnavailable.text = activity.getString(R.string.setup_overlay_unavailable, activity.packageName)

		notificationsRow.isVisible = AndroidVersion.isAtLeastT
		val notifications = hasNotificationPermission(activity)
		notificationsStatus.setText(if (notifications) R.string.setup_notifications_granted else R.string.setup_notifications_missing)
		notificationsButton.isVisible = !notifications
	}

	private fun requestOverlay() {
		overlayRequested = true
		val packageUri = "package:${activity.packageName}".toUri()
		val candidates = listOf(
			Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri),
			Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
		)

		val started = candidates.any { intent ->
			try {
				activity.startActivity(intent)
				true
			} catch (err: ActivityNotFoundException) {
				Timber.w(err, "Overlay settings not available: ${intent.action}")
				false
			}
		}
		if (!started) refresh()
	}

	companion object {
		fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

		fun hasNotificationPermission(context: Context): Boolean =
			!AndroidVersion.isAtLeastT ||
				ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
	}
}
