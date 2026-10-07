package ua.movieportal.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
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

	/** Jellyfin server id (from /System/Info/Public), put into the pairing QR code. */
	var serverId: String?
		get() = prefs.getString(KEY_SERVER_ID, null)
		set(value) = prefs.edit { putString(KEY_SERVER_ID, value) }

	private val _deviceName = MutableStateFlow(readOrInitDeviceName())

	/** Name shown in Jellyfin sessions; observed by the service, which re-registers the session on change. */
	val deviceNameFlow: StateFlow<String> = _deviceName.asStateFlow()

	var deviceName: String
		get() = _deviceName.value
		set(value) {
			val name = value.trim().take(MAX_DEVICE_NAME_LENGTH).ifEmpty { return }
			prefs.edit { putString(KEY_DEVICE_NAME, name) }
			_deviceName.value = name
		}

	/** Server ids for which the user accepted a plain http connection (cleared when changing the server). */
	val confirmedInsecureServers: Set<String>
		get() = prefs.getStringSet(KEY_INSECURE_CONFIRMED, null).orEmpty()

	fun confirmInsecureServer(serverId: String) {
		prefs.edit { putStringSet(KEY_INSECURE_CONFIRMED, confirmedInsecureServers + serverId) }
	}

	fun clearInsecureConfirmations() {
		prefs.edit { remove(KEY_INSECURE_CONFIRMED) }
	}

	/**
	 * The stored name, or on first run the TV's own name from the system settings (Settings → Device preferences →
	 * About → Device name), stored once so no settings lookup happens per request.
	 */
	private fun readOrInitDeviceName(): String {
		// Version 1.0 stored the app name as the default; replace it with the TV's own name
		prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() && it != DEFAULT_DEVICE_NAME }?.let { return it }

		val name = systemDeviceName()
		prefs.edit { putString(KEY_DEVICE_NAME, name) }
		return name
	}

	/** Forgets a name entered with "Перейменувати": the next pairing starts with the TV's own name again. */
	private fun resetDeviceName() {
		val name = systemDeviceName()
		prefs.edit { putString(KEY_DEVICE_NAME, name) }
		_deviceName.value = name
	}

	/** "Sony BRAVIA 4K VH2", without repeating the manufacturer when the model already starts with it. */
	private fun modelName(): String {
		val manufacturer = Build.MANUFACTURER.orEmpty().trim()
		val model = Build.MODEL.orEmpty().trim()
		return when {
			manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
			else -> "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
		}
	}

	private fun systemDeviceName(): String {
		val resolver = appContext.contentResolver
		return listOf(
			runCatching { Settings.Global.getString(resolver, DEVICE_NAME) }.getOrNull(),
			runCatching { Settings.Secure.getString(resolver, BLUETOOTH_NAME) }.getOrNull(),
			modelName(),
		).firstOrNull { !it.isNullOrBlank() }?.trim()?.take(MAX_DEVICE_NAME_LENGTH) ?: DEFAULT_DEVICE_NAME
	}

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
		if (reason == LogoutReason.USER) resetDeviceName()
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
		private const val MAX_DEVICE_NAME_LENGTH = 40

		// Settings.Global.DEVICE_NAME (API 25+, same key works as a lookup on older versions) and the Settings.Secure
		// key Android uses for the Bluetooth device name (not exposed as a constant)
		private const val DEVICE_NAME = "device_name"
		private const val BLUETOOTH_NAME = "bluetooth_name"

		private const val PREFS_NAME = "receiver"
		private const val KEY_SERVER_URL = "server_url"
		private const val KEY_SERVER_ID = "server_id"
		private const val KEY_INSECURE_CONFIRMED = "insecure_confirmed"
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
