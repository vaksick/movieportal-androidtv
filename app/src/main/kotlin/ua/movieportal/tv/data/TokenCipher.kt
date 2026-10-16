package ua.movieportal.tv.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the access token with an AES-256-GCM key that lives in the Android Keystore (non-exportable).
 *
 * Stored format: `v1:<iv>:<ciphertext>` (Base64). Some TV firmwares ship a broken Keystore; then the token is kept as
 * `plain:<token>` in the app-private preferences so the receiver keeps working (backups are disabled anyway).
 */
internal object TokenCipher {
	private const val KEYSTORE = "AndroidKeyStore"
	private const val KEY_ALIAS = "receiver_access_token"
	private const val TRANSFORMATION = "AES/GCM/NoPadding"
	private const val TAG_BITS = 128
	private const val PREFIX_ENCRYPTED = "v1:"
	private const val PREFIX_PLAIN = "plain:"

	fun encrypt(token: String): String = try {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
		val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
		PREFIX_ENCRYPTED + cipher.iv.toBase64() + ":" + ciphertext.toBase64()
	} catch (err: GeneralSecurityException) {
		Timber.w(err, "Keystore unavailable, storing the token unencrypted")
		PREFIX_PLAIN + token
	} catch (err: ProviderException) {
		Timber.w(err, "Keystore unavailable, storing the token unencrypted")
		PREFIX_PLAIN + token
	}

	/** @return the token, or null when it can not be decrypted (e.g. the Keystore key was wiped). */
	fun decrypt(stored: String): String? = when {
		stored.startsWith(PREFIX_PLAIN) -> stored.removePrefix(PREFIX_PLAIN)
		stored.startsWith(PREFIX_ENCRYPTED) -> decryptV1(stored.removePrefix(PREFIX_ENCRYPTED))

		// Versions before 1.0.0 stored the token as is; AppSettings re-saves it encrypted
		else -> stored
	}

	fun isEncrypted(stored: String) = stored.startsWith(PREFIX_ENCRYPTED)

	/**
	 * `<iv>:<ciphertext>`. Any failure (truncated value, bad Base64, wiped Keystore key, broken Keystore provider)
	 * means "not paired": this runs in Application.onCreate, so it must never throw.
	 */
	private fun decryptV1(value: String): String? {
		val parts = value.split(':', limit = 2)
		if (parts.size != 2 || parts.any { it.isEmpty() }) {
			Timber.w("Corrupted access token")
			return null
		}
		return try {
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, parts[0].fromBase64()))
			String(cipher.doFinal(parts[1].fromBase64()), Charsets.UTF_8)
		} catch (err: Exception) {
			Timber.w(err, "Unable to decrypt the access token")
			null
		}
	}

	private fun getOrCreateKey(): SecretKey {
		val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
		(keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

		val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
			.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
			.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
			.setKeySize(256)
			.build()
		return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
			init(spec)
			generateKey()
		}
	}

	private fun ByteArray.toBase64() = Base64.encodeToString(this, Base64.NO_WRAP)
	private fun String.fromBase64() = Base64.decode(this, Base64.NO_WRAP)
}
