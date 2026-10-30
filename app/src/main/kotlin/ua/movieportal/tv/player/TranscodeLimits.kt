package ua.movieportal.tv.player

/**
 * Keeps a server transcode no larger than the screen: Jellyfin otherwise transcodes a 4K file to 4K H.264 at
 * ~34 Mbit/s even for a 1080p TV (or the emulator), which the network and the decoder then struggle with.
 * Only applied to transcodes; direct play / direct stream of a larger file is left to the TV, which scales it down.
 */
object TranscodeLimits {
	private const val FULL_HD_HEIGHT = 1080
	private const val HD_HEIGHT = 720
	private const val FULL_HD_MAX_BITRATE = 20_000_000L
	private const val HD_MAX_BITRATE = 8_000_000L

	/** [url] with MaxWidth/MaxHeight set to the screen and VideoBitrate capped to what that resolution needs. */
	fun apply(url: String, maxWidth: Int, maxHeight: Int): String {
		val queryStart = url.indexOf('?')
		if (queryStart < 0) return "$url?MaxWidth=$maxWidth&MaxHeight=$maxHeight"

		val params = url.substring(queryStart + 1).split('&').filter { it.isNotEmpty() }.toMutableList()
		fun value(key: String) = params.firstOrNull { it.substringBefore('=').equals(key, ignoreCase = true) }
			?.substringAfter('=', "")
		fun set(key: String, value: Any) {
			params.removeAll { it.substringBefore('=').equals(key, ignoreCase = true) }
			params += "$key=$value"
		}

		// Never ask for more than the server already planned
		value("MaxWidth")?.toIntOrNull().let { set("MaxWidth", if (it == null) maxWidth else minOf(it, maxWidth)) }
		value("MaxHeight")?.toIntOrNull().let { set("MaxHeight", if (it == null) maxHeight else minOf(it, maxHeight)) }

		val bitrateCap = when {
			maxHeight <= HD_HEIGHT -> HD_MAX_BITRATE
			maxHeight <= FULL_HD_HEIGHT -> FULL_HD_MAX_BITRATE
			else -> null
		}
		val bitrate = value("VideoBitrate")?.toLongOrNull()
		if (bitrateCap != null && bitrate != null && bitrate > bitrateCap) set("VideoBitrate", bitrateCap)

		return url.substring(0, queryStart + 1) + params.joinToString("&")
	}
}
