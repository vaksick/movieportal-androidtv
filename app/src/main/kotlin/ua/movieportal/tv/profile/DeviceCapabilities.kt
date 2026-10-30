package ua.movieportal.tv.profile

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.view.Display
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import ua.movieportal.tv.R
import ua.movieportal.tv.profile.codec.isPassthroughAudioAvailable

/** One row of the capabilities table: a format group and its formats (name to supported). */
data class CapabilityGroup(val label: String, val items: List<Pair<String, Boolean>>)

/**
 * What this TV can play, for the status screen: helps to see at a glance why the server transcodes (e.g. a Dolby
 * Vision film on a TV without a Dolby Vision decoder). Unsupported formats are kept so the absence is visible too.
 */
@OptIn(UnstableApi::class)
fun deviceCapabilities(context: Context): List<CapabilityGroup> {
	val media = MediaCodecCapabilitiesTest(softwareCodecsEnabled = true)
	val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
		.filter { !it.isEncoder }
		.flatMap { it.supportedTypes.asList() }
		.map { it.lowercase() }
		.toSet()

	val video = listOf(
		"H.264" to media.supportsAVC(),
		(if (media.supportsHevcMain10()) "HEVC 10-bit" else "HEVC") to media.supportsHevc(),
		"VP9" to (MimeTypes.VIDEO_VP9 in decoders),
		(if (media.supportsAV1Main10()) "AV1 10-bit" else "AV1") to media.supportsAV1(),
		"MPEG-2" to (MimeTypes.VIDEO_MPEG2 in decoders),
		"VC-1" to media.supportsVc1(),
	)
	val hdr = listOf(
		"HDR10" to media.supportsHevcHDR10(),
		// No decoder profile for HLG: it is plain 10-bit video, the screen has to show it
		"HLG" to (media.supportsHevcMain10() && displaySupportsHlg(context)),
		"HDR10+" to media.supportsHevcHDR10Plus(),
		"Dolby Vision" to (media.supportsHevcDolbyVision() || media.supportsAV1DolbyVision()),
	)

	// Bitstream audio: passthrough to the TV/receiver or a platform decoder (the bundled FFmpeg is not counted, it can
	// decode most of these anyway)
	fun audio(mime: String) = isPassthroughAudioAvailable(context, mime) || mime in decoders
	val audio = listOf(
		"AC3" to audio(MimeTypes.AUDIO_AC3),
		"E-AC3" to audio(MimeTypes.AUDIO_E_AC3),
		"DTS" to audio(MimeTypes.AUDIO_DTS),
		"TrueHD" to audio(MimeTypes.AUDIO_TRUEHD),
	)

	val screen = maxDisplaySize(context)?.let { "${it.width}×${it.height}" }

	return listOfNotNull(
		CapabilityGroup(context.getString(R.string.caps_video), video),
		CapabilityGroup("HDR", hdr),
		CapabilityGroup(context.getString(R.string.caps_audio), audio),
		screen?.let { CapabilityGroup(context.getString(R.string.caps_screen), listOf(it to true)) },
	)
}

private fun displaySupportsHlg(context: Context): Boolean {
	val display = context.getSystemService<DisplayManager>()?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
	@Suppress("DEPRECATION")
	return display.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HLG) == true
}
