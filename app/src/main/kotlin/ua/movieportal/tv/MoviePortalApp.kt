package ua.movieportal.tv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import ua.movieportal.tv.data.AppSettings
import ua.movieportal.tv.jellyfin.JellyfinClient
import ua.movieportal.tv.util.RedactingTree
import androidx.media3.common.util.Log as MediaLog

/**
 * Application entry point and a tiny service locator (the app is too small for a DI framework).
 */
@OptIn(UnstableApi::class)
class MoviePortalApp : Application() {
	lateinit var settings: AppSettings
		private set
	lateinit var jellyfin: JellyfinClient
		private set

	/** Scope for fire-and-forget work that must outlive a screen (e.g. logout requests). */
	val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	/** Number of started activities; > 0 means the app has a visible window. */
	@Volatile
	var startedActivities = 0
		private set

	override fun onCreate() {
		super.onCreate()
		instance = this

		// Logcat only, credentials are masked; release builds keep warnings and errors only
		Timber.plant(RedactingTree.forBuild(BuildConfig.DEBUG))
		if (!BuildConfig.DEBUG) MediaLog.setLogLevel(MediaLog.LOG_LEVEL_ERROR)

		settings = AppSettings(this)
		jellyfin = JellyfinClient(this, settings)

		registerActivityLifecycleCallbacks(object : SimpleActivityCallbacks() {
			override fun onActivityStarted(activity: Activity) {
				startedActivities++
			}

			override fun onActivityStopped(activity: Activity) {
				startedActivities--
			}
		})
	}

	companion object {
		lateinit var instance: MoviePortalApp
			private set
	}
}

private open class SimpleActivityCallbacks : Application.ActivityLifecycleCallbacks {
	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
	override fun onActivityStarted(activity: Activity) = Unit
	override fun onActivityResumed(activity: Activity) = Unit
	override fun onActivityPaused(activity: Activity) = Unit
	override fun onActivityStopped(activity: Activity) = Unit
	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
	override fun onActivityDestroyed(activity: Activity) = Unit
}
