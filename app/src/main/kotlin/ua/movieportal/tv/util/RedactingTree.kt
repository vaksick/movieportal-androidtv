package ua.movieportal.tv.util

import android.util.Log
import timber.log.Timber

/**
 * Logcat tree that never writes credentials: access tokens in query strings (`api_key`, `ApiKey`), the `Token="…"`
 * part of the MediaBrowser Authorization header and Quick Connect secrets are masked. Messages below [minPriority]
 * are dropped (release builds only keep warnings and errors).
 */
class RedactingTree(private val minPriority: Int) : Timber.DebugTree() {
	override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= minPriority

	override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
		// Timber already appended the stack trace (including exception messages) to [message]
		super.log(priority, tag, redact(message), null)
	}

	companion object {
		private val patterns = listOf(
			Regex("""(?i)((?:api_?key|access_?token|secret)=)[^&\s"']+"""),
			Regex("""(?i)(Token=")[^"]*"""),
			Regex("""(?i)(X-Emby-Token:\s*)\S+"""),
		)

		fun redact(text: String): String = patterns.fold(text) { acc, regex -> regex.replace(acc, "$1<redacted>") }

		fun forBuild(debug: Boolean) = RedactingTree(if (debug) Log.VERBOSE else Log.WARN)
	}
}
