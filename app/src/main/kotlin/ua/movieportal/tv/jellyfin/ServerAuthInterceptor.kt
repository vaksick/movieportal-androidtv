package ua.movieportal.tv.jellyfin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the Jellyfin Authorization header (with the access token) only to requests for the paired server's origin.
 * Media from other hosts (remote media sources, external subtitle URLs) is fetched without credentials.
 * OkHttp itself drops the Authorization header when a redirect leaves the original host.
 */
class ServerAuthInterceptor(serverUrl: String, private val authorizationHeader: () -> String) : Interceptor {
	private val server: HttpUrl? = serverUrl.toHttpUrlOrNull()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (server == null || !isSameOrigin(request.url, server)) return chain.proceed(request)

		return chain.proceed(request.newBuilder().header("Authorization", authorizationHeader()).build())
	}

	companion object {
		fun isSameOrigin(url: HttpUrl, server: HttpUrl) =
			url.scheme == server.scheme && url.host.equals(server.host, ignoreCase = true) && url.port == server.port
	}
}
