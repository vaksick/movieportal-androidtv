package ua.movieportal.tv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import ua.movieportal.tv.player.TranscodeLimits

class TranscodeLimitsTest : FunSpec({
	test("a 4K transcode for a 1080p screen is limited to 1080p and 20 Mbit/s") {
		val url = TranscodeLimits.apply("/videos/1/master.m3u8?VideoCodec=h264&VideoBitrate=33993583&api_key=x", 1920, 1080)
		url shouldContain "MaxWidth=1920"
		url shouldContain "MaxHeight=1080"
		url shouldContain "VideoBitrate=20000000"
		url shouldContain "api_key=x"
		url shouldNotContain "33993583"
	}

	test("a 4K screen keeps the planned bitrate, a smaller server limit wins") {
		val url = TranscodeLimits.apply("/master.m3u8?maxwidth=1280&VideoBitrate=33993583", 3840, 2160)
		url shouldContain "MaxWidth=1280"
		url shouldContain "MaxHeight=2160"
		url shouldContain "VideoBitrate=33993583"
	}

	test("url without a query") {
		TranscodeLimits.apply("/master.m3u8", 1280, 720) shouldBe "/master.m3u8?MaxWidth=1280&MaxHeight=720"
	}
})
