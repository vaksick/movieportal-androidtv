package ua.movieportal.tv.player

/**
 * Seeking with the remote.
 */
object SeekPolicy {
	/** Seeking never lands closer than this to the end: reaching the end would start the next episode. */
	const val END_MARGIN_MS = 1_000L

	/**
	 * New position after a seek step, between 0 and [END_MARGIN_MS] before the end ([durationMs] null: unknown).
	 */
	fun target(positionMs: Long, deltaMs: Long, durationMs: Long?): Long {
		val max = durationMs?.let { (it - END_MARGIN_MS).coerceAtLeast(0) } ?: Long.MAX_VALUE
		return (positionMs + deltaMs).coerceIn(0, max)
	}
}
