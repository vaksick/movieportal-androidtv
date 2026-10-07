package ua.movieportal.tv.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ua.movieportal.tv.jellyfin.JellyfinClient
import java.net.URI

/**
 * A reply to the Jellyfin UDP discovery broadcast.
 *
 * @param reportedAddress the address the server advertises ("Address", Jellyfin's "Published server URIs" / LAN
 * address). It is the only address the receiver uses (plus https upgrades of the same host).
 */
data class DiscoveryResponse(
	val id: String,
	val name: String,
	val reportedAddress: String?,
)

/**
 * Jellyfin LAN discovery: a UDP datagram "who is JellyfinServer?" to port 7359; every server answers with
 * `{"Address":"http://192.168.1.10:8096","Id":"…","Name":"…","EndpointAddress":null}`.
 */
object DiscoveryProtocol {
	const val PORT = 7359
	const val MESSAGE = "who is JellyfinServer?"

	/** Jellyfin's default HTTPS port, tried after the standard 443. */
	private const val JELLYFIN_HTTPS_PORT = 8920

	private val json = Json { ignoreUnknownKeys = true }

	fun parse(payload: String): DiscoveryResponse? {
		val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
		fun string(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

		val id = string("Id") ?: return null
		return DiscoveryResponse(id = id, name = string("Name") ?: id, reportedAddress = string("Address"))
	}

	/**
	 * Base URLs to validate: the reported address (normalized like manual input) and, when it is plain http, the
	 * same host over https on 443 and on Jellyfin's 8920 (same base path). No other fallback addresses.
	 */
	fun candidateUrls(response: DiscoveryResponse): List<String> {
		val reported = response.reportedAddress?.let(JellyfinClient::normalizeServerUrl) ?: return emptyList()
		val uri = URI(reported)
		if (uri.scheme.lowercase() != "http") return listOf(reported)

		val host = uri.host.let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }
		val path = uri.rawPath?.trimEnd('/').orEmpty()
		return listOf(
			reported,
			"https://$host$path",
			"https://$host:$JELLYFIN_HTTPS_PORT$path",
		).distinct()
	}

	fun isSecure(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

	/**
	 * Picks the address to use among the candidates that answered with the right server id: any https address first
	 * (in candidate order), plain http only when no https address works.
	 */
	fun pickBest(candidates: List<String>, working: Set<String>): String? {
		val ok = candidates.filter { it in working }
		return ok.firstOrNull(::isSecure) ?: ok.firstOrNull()
	}

	/** Jellyfin ids are GUIDs, compare them without dashes and case. */
	fun sameId(a: String?, b: String?): Boolean =
		a != null && b != null && a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)
}

/**
 * A discovered server after checking `GET /System/Info/Public` on its candidate addresses.
 */
sealed interface DiscoveredServer {
	val id: String
	val name: String

	data class Reachable(override val id: String, override val name: String, val url: String, val version: String?) :
		DiscoveredServer {
		/** Only plain http works: the pairing code and the access token would travel unencrypted. */
		val insecure: Boolean get() = !DiscoveryProtocol.isSecure(url)
	}

	/** Answered the broadcast, but its reported address does not work from this TV. */
	data class Unreachable(override val id: String, override val name: String, val reportedAddress: String?) :
		DiscoveredServer
}

/** What the discovery screen does with a scan result. */
sealed interface DiscoveryDecision {
	/** Exactly one reachable server with https (or http already confirmed by the user): use it. */
	data class AutoSelect(val server: DiscoveredServer.Reachable) : DiscoveryDecision

	/** Exactly one reachable server, http only and not confirmed yet: warn before using it. */
	data class ConfirmInsecure(val server: DiscoveredServer.Reachable) : DiscoveryDecision

	data class Choose(val servers: List<DiscoveredServer>) : DiscoveryDecision
	data object NothingFound : DiscoveryDecision

	companion object {
		/**
		 * @param allowAutoSelect false after the user came back to pick another server
		 * @param confirmedInsecure server ids for which the user already accepted plain http
		 */
		fun of(
			servers: List<DiscoveredServer>,
			allowAutoSelect: Boolean,
			confirmedInsecure: Set<String> = emptySet(),
		): DiscoveryDecision {
			val reachable = servers.filterIsInstance<DiscoveredServer.Reachable>()
			if (servers.isEmpty()) return NothingFound

			if (allowAutoSelect && reachable.size == 1) {
				val server = reachable.single()
				val confirmed = confirmedInsecure.any { DiscoveryProtocol.sameId(it, server.id) }
				return if (!server.insecure || confirmed) AutoSelect(server) else ConfirmInsecure(server)
			}

			return Choose(servers.sortedWith(compareBy({ it !is DiscoveredServer.Reachable }, { it.name.lowercase() })))
		}
	}
}
