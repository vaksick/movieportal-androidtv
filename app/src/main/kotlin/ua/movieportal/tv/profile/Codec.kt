package ua.movieportal.tv.profile

/**
 * Codec and container names as used by the Jellyfin server (ffmpeg naming).
 */
object Codec {
	object Container {
		const val ASF = "asf"
		const val HLS = "hls"
		const val M4V = "m4v"
		const val MKV = "mkv"
		const val MOV = "mov"
		const val MP4 = "mp4"
		const val OGM = "ogm"
		const val OGV = "ogv"
		const val TS = "ts"
		const val VOB = "vob"
		const val WEBM = "webm"
		const val WMV = "wmv"
		const val XVID = "xvid"
	}

	object Audio {
		const val AAC = "aac"
		const val AAC_LATM = "aac_latm"
		const val AC3 = "ac3"
		const val ALAC = "alac"
		const val DCA = "dca"
		const val DTS = "dts"
		const val EAC3 = "eac3"
		const val FLAC = "flac"
		const val MLP = "mlp"
		const val MP2 = "mp2"
		const val MP3 = "mp3"
		const val OPUS = "opus"
		const val PCM_ALAW = "pcm_alaw"
		const val PCM_MULAW = "pcm_mulaw"
		const val PCM_S16LE = "pcm_s16le"
		const val PCM_S20LE = "pcm_s20le"
		const val PCM_S24LE = "pcm_s24le"
		const val TRUEHD = "truehd"
		const val VORBIS = "vorbis"
	}

	object Video {
		const val H264 = "h264"
		const val HEVC = "hevc"
		const val MPEG = "mpeg"
		const val MPEG2VIDEO = "mpeg2video"
		const val VP8 = "vp8"
		const val VP9 = "vp9"
		const val AV1 = "av1"
		const val VC1 = "vc1"
	}

	object Subtitle {
		const val ASS = "ass"
		const val DVBSUB = "dvbsub"
		const val DVDSUB = "dvdsub"
		const val IDX = "idx"
		const val PGS = "pgs"
		const val PGSSUB = "pgssub"
		const val SRT = "srt"
		const val SSA = "ssa"
		const val SUBRIP = "subrip"
		const val VTT = "vtt"
		const val TTML = "ttml"
		const val WEBVTT = "webvtt"
	}
}
