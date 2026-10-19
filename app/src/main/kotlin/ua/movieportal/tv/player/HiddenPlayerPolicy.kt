package ua.movieportal.tv.player

/**
 * When a player that is not in front (stopped activity) gives up and closes.
 *
 * Started by the portal while the TV is in standby, the player is created while the screen is still off and stays
 * stopped until the TV has woken up; some TVs (Sony Bravia) need well over 10 s for that. A player that was already
 * visible and then left (Home, another app, TV switched off) closes quickly.
 */
object HiddenPlayerPolicy {
	/** The player was in front before: leaving it ends playback after this time. */
	const val STOP_GRACE_MS = 2_500L

	/** The player never came to the front although the screen is on: wait this long for it. */
	const val HIDDEN_START_GRACE_MS = 20_000L

	/**
	 * @param hasBeenResumed the player has been in front at least once
	 * @param reachedReady the stream has been ready at least once (before that onStop is always transient)
	 * @param screenInteractive the screen is on and not locked
	 * @return delay before closing the hidden player, null: do not start the countdown now
	 */
	fun closeDelayMs(hasBeenResumed: Boolean, reachedReady: Boolean, screenInteractive: Boolean): Long? = when {
		!reachedReady -> null
		hasBeenResumed -> STOP_GRACE_MS
		// Still waking up (standby, screen off, keyguard): the countdown starts once the screen is on
		!screenInteractive -> null
		else -> HIDDEN_START_GRACE_MS
	}
}
