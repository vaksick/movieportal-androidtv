package ua.movieportal.tv.jellyfin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.PublicSystemInfo
import ua.movieportal.tv.BuildConfig
import ua.movieportal.tv.data.AppSettings
import ua.movieportal.tv.data.Credentials
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Thin wrapper around the Jellyfin SDK. Every request (REST, WebSocket and media) carries the same client name,
 * device id and device name so the server keeps a single session for this TV.
 *
 * SDK requests must run on Dispatchers.IO: the SDK reads response bodies on the calling dispatcher.
 */
class JellyfinClient(context: Context, private val settings: AppSettings) {
	private val okHttpFactory = OkHttpFactory()
	private val clientInfo = ClientInfo(CLIENT_NAME, BuildConfig.VERSION_NAME)

	private val jellyfin = createJellyfin {
		this.context = context.applicationContext
		clientInfo = this@JellyfinClient.clientInfo
		deviceInfo = deviceInfo()
		apiClientFactory = okHttpFactory
		socketConnectionFactory = okHttpFactory
	}

	/** Shared OkHttp client for the WebSocket and the media data source. */
	val httpClient: OkHttpClient by lazy { okHttpFactory.createClient(HttpClientOptions()) }

	private fun deviceInfo() = DeviceInfo(id = settings.deviceId, name = settings.deviceName)

	/** Create an API client; the device name is read every time so renames apply immediately. */
	fun createApi(baseUrl: String, accessToken: String? = null): ApiClient =
		jellyfin.createApi(
			baseUrl = baseUrl,
			accessToken = accessToken,
			clientInfo = clientInfo,
			deviceInfo = deviceInfo(),
		)

	fun createApi(credentials: Credentials): ApiClient = createApi(credentials.serverUrl, credentials.accessToken)

	/** `Authorization: MediaBrowser Client=…, Device=…, DeviceId=…, Version=…, Token=…` */
	fun authorizationHeader(accessToken: String?): String = AuthorizationHeaderBuilder.buildHeader(
		clientName = clientInfo.name,
		clientVersion = clientInfo.version,
		deviceId = settings.deviceId,
		deviceName = settings.deviceName,
		accessToken = accessToken,
	)

	/** `GET /System/Info/Public`, used to validate the server address. */
	suspend fun getPublicSystemInfo(baseUrl: String): PublicSystemInfo = withContext(Dispatchers.IO) {
		createApi(baseUrl).systemApi.getPublicSystemInfo().content
	}

	companion object {
		const val CLIENT_NAME = "Movie Portal TV"

		/**
		 * Normalize user input to a base URL: adds `http://` when no scheme is given and strips trailing slashes.
		 * Returns `null` when the input can not be a valid http(s) URL.
		 */
		fun normalizeServerUrl(input: String): String? {
			var url = input.trim()
			if (url.isEmpty()) return null
			if (!url.contains("://")) url = "http://$url"
			url = url.trimEnd('/')

			val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return null
			if (parsed.scheme?.lowercase() !in setOf("http", "https")) return null
			if (parsed.host.isNullOrBlank()) return null
			return url
		}

		/** Plain http: the pairing code and the access token travel unencrypted (warned in discovery and setup). */
		fun isInsecureUrl(url: String): Boolean = url.startsWith("http://", ignoreCase = true)

		/** HTTP status of an SDK error, if the server answered at all. */
		fun Throwable.httpStatus(): Int? = causeChain().filterIsInstance<InvalidStatusException>().firstOrNull()?.status

		/** True when the error means the token is no longer valid. */
		fun Throwable.isUnauthorized(): Boolean = httpStatus() == 401

		/** Classify an error into a user-facing category. */
		fun Throwable.describe(): ConnectionError {
			val chain = causeChain().toList()
			val status = httpStatus()
			return when {
				status != null -> ConnectionError.HttpStatus(status)
				chain.any { it is UnknownHostException } -> ConnectionError.UnknownHost
				chain.any { it is SSLException || it is SecureConnectionException } -> ConnectionError.Tls
				chain.any { it is SocketTimeoutException || it is TimeoutException } -> ConnectionError.Timeout
				chain.any { it is ConnectException } -> ConnectionError.Refused
				chain.any { it is SerializationException || it is InvalidContentException || it is IllegalArgumentException } ->
					ConnectionError.NotJellyfin
				chain.any { it is IOException } -> ConnectionError.Network
				else -> ConnectionError.Other(message ?: javaClass.simpleName)
			}
		}

		private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
	}
}

/**
 * User-facing connection error categories.
 */
sealed interface ConnectionError {
	data object UnknownHost : ConnectionError
	data object Refused : ConnectionError
	data object Timeout : ConnectionError
	data object Tls : ConnectionError
	data object Network : ConnectionError
	data object NotJellyfin : ConnectionError
	data class HttpStatus(val status: Int) : ConnectionError
	data class Other(val message: String) : ConnectionError
}
