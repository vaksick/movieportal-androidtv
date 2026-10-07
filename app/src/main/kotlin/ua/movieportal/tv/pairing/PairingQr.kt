package ua.movieportal.tv.pairing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pairing QR code scanned by the portal ("Налаштування → Пристрої → «+» → «Сканувати QR»").
 *
 * Payload (compact JSON, versioned):
 * `{"t":"mptv","v":1,"code":"<Quick Connect code>","did":"<DeviceId>","sid":"<Jellyfin server id>","name":"<device name>"}`
 *
 * `sid` and `name` are optional; the portal uses `name` as the default TV name.
 */
object PairingQr {
	const val TYPE = "mptv"
	const val VERSION = 1

	// 4 modules of white border, required by the QR specification for reliable scanning
	private const val QUIET_ZONE_MODULES = 4

	// Keeps the QR small enough to scan from across the room
	private const val MAX_NAME_LENGTH = 40

	fun payload(code: String, deviceId: String, serverId: String?, deviceName: String? = null): String = buildJsonObject {
		put("t", TYPE)
		put("v", VERSION)
		put("code", code)
		put("did", deviceId)
		if (!serverId.isNullOrBlank()) put("sid", serverId)
		deviceName?.trim()?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
	}.toString()

	/** QR matrix with the quiet zone included, one bit per module. */
	fun encode(text: String): BitMatrix = QRCodeWriter().encode(
		text,
		BarcodeFormat.QR_CODE,
		0,
		0,
		mapOf(
			EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
			EncodeHintType.CHARACTER_SET to "UTF-8",
			EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
		),
	)

	/**
	 * Black-on-white bitmap with an integer number of pixels per module (no blurry scaling), at most [maxSizePx]
	 * wide. Only when the matrix itself is wider than [maxSizePx] (not the case for our ~50-module payload) the bitmap
	 * is returned at 1 pixel per module and is larger than requested; the view then downscales it.
	 */
	fun toBitmap(matrix: BitMatrix, maxSizePx: Int): Bitmap {
		val scale = maxOf(1, maxSizePx / matrix.width)
		val size = matrix.width * scale
		val pixels = IntArray(size * size)
		for (y in 0 until size) {
			val row = y * size
			for (x in 0 until size) {
				pixels[row + x] = if (matrix[x / scale, y / scale]) Color.BLACK else Color.WHITE
			}
		}
		return createBitmap(size, size).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
	}
}
