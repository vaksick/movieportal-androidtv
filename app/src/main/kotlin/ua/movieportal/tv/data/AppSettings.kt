package ua.movieportal.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Credentials obtained through Quick Connect for the hidden per-device portal account.
 */
data class Credentials(
	val serverUrl: String,
	val accessToken: String,
	val userId: String,
	val userName: String?,
)

/**
 * Why the credentials were removed; shown on the Quick Connect screen.
 */
enum class LogoutReason {
	USER,
	REVOKED,
}

/**
 * Persistent receiver settings. Only the server address, device name, device id and the access token are stored.
 */
class AppSettings(context: Context) {
	private val appContext = context.applicationContext
	private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	private val _credentials = MutableStateFlow(readCredentials())

	/** Current credentials, `null` when the device is not paired. */
	val credentials: StateFlow<Credentials?> = _credentials.asStateFlow()

	var serverUrl: String?
		get() = prefs.getString(KEY_SERVER_URL, null)
		set(value) = prefs.edit { putString(KEY_SERVER_URL, value) }

	var deviceName: String
		get() = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_NAME
		set(value) = prefs.edit { putString(KEY_DEVICE_NAME, value.trim()) }

	/** Last reason the credentials were cleared, consumed by the UI. */
	var lastLogoutReason: LogoutReason?
		get() = prefs.getString(KEY_LOGOUT_REASON, null)?.let { name -> LogoutReason.entries.find { it.name == name } }
		set(value) = prefs.edit { putString(KEY_LOGOUT_REASON, value?.name) }

	/**
	 * Stable device id. Generated once and stored. It is derived from ANDROID_ID (stable per signing key and user since
	 * Android 8), so a reinstall signed with the same key gets the same id. Salting with the package name keeps it
	 * different from the id used by the official Jellyfin app (and from our own debug build) on the same TV.
	 */
	val deviceId: String by lazy {
		prefs.getString(KEY_DEVICE_ID, null) ?: generateDeviceId().also { id ->
			prefs.edit { putString(KEY_DEVICE_ID, id) }
		}
	}

	fun saveCredentials(credentials: Credentials) {
		prefs.edit {
			putString(KEY_SERVER_URL, credentials.serverUrl)
			putString(KEY_ACCESS_TOKEN, TokenCipher.encrypt(credentials.accessToken))
			putString(KEY_USER_ID, credentials.userId)
			putString(KEY_USER_NAME, credentials.userName)
			remove(KEY_LOGOUT_REASON)
		}
		_credentials.value = credentials
	}

	fun clearCredentials(reason: LogoutReason) {
		prefs.edit {
			remove(KEY_ACCESS_TOKEN)
			remove(KEY_USER_ID)
			remove(KEY_USER_NAME)
			putString(KEY_LOGOUT_REASON, reason.name)
		}
		_credentials.value = null
	}

	private fun readCredentials(): Credentials? {
		val serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: return null
		// An undecryptable token (Keystore key lost) means the TV has to be paired again
		val stored = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
		val token = TokenCipher.decrypt(stored) ?: return null
		// Migrate a token saved in plain text by an older version
		if (!TokenCipher.isEncrypted(stored)) prefs.edit { putString(KEY_ACCESS_TOKEN, TokenCipher.encrypt(token)) }
		val userId = prefs.getString(KEY_USER_ID, null) ?: return null
		return Credentials(serverUrl, token, userId, prefs.getString(KEY_USER_NAME, null))
	}

	@SuppressLint("HardwareIds")
	private fun generateDeviceId(): String {
		val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
		return if (androidId.isNullOrBlank() || androidId == BROKEN_ANDROID_ID) UUID.randomUUID().toString()
		else UUID.nameUUIDFromBytes("${appContext.packageName}:$androidId".toByteArray()).toString()
	}

	companion object {
		const val DEFAULT_DEVICE_NAME = "Movie Portal TV"

		private const val PREFS_NAME = "receiver"
		private const val KEY_SERVER_URL = "server_url"
		private const val KEY_DEVICE_NAME = "device_name"
		private const val KEY_DEVICE_ID = "device_id"
		private const val KEY_ACCESS_TOKEN = "access_token"
		private const val KEY_USER_ID = "user_id"
		private const val KEY_USER_NAME = "user_name"
		private const val KEY_LOGOUT_REASON = "logout_reason"

		// Known bogus ANDROID_ID shared by many old devices
		private const val BROKEN_ANDROID_ID = "9774d56d682e549c"
	}
}
