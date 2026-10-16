package ua.movieportal.tv.util

import android.os.Build

/**
 * Named Android version checks, reads better than inline SDK_INT comparisons.
 */
object AndroidVersion {
	/** Android 10 Q, API 29. */
	val isAtLeastQ: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

	/** Android 11 R, API 30. */
	val isAtLeastR: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

	/** Android 13 Tiramisu, API 33. */
	val isAtLeastT: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

	/** Android 14 Upside Down Cake, API 34. */
	val isAtLeastU: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
