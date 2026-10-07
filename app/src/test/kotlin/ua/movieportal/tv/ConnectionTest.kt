package ua.movieportal.tv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okhttp3.HttpUrl.Companion.toHttpUrl
import ua.movieportal.tv.jellyfin.JellyfinClient
import ua.movieportal.tv.jellyfin.ServerAuthInterceptor
import ua.movieportal.tv.service.SessionSocket

class ConnectionTest : FunSpec({
	test("reconnect backoff grows from 1 s to 60 s") {
		(0..8).map(SessionSocket::backoffSeconds) shouldBe listOf(1, 2, 4, 8, 16, 32, 60, 60, 60)
	}

	test("any plain http address is flagged, LAN included") {
		listOf("http://192.168.1.10:8096", "http://jellyfin:8096", "HTTP://jf.example.org").forEach {
			JellyfinClient.isInsecureUrl(it) shouldBe true
		}
		JellyfinClient.isInsecureUrl("https://jf.example.org") shouldBe false
	}

	test("server address normalization") {
		JellyfinClient.normalizeServerUrl("192.168.1.10:8096") shouldBe "http://192.168.1.10:8096"
		JellyfinClient.normalizeServerUrl(" https://jf.example.org/ ") shouldBe "https://jf.example.org"
		JellyfinClient.normalizeServerUrl("http://host:8096/jellyfin/") shouldBe "http://host:8096/jellyfin"
		JellyfinClient.normalizeServerUrl("") shouldBe null
		JellyfinClient.normalizeServerUrl("ftp://host") shouldBe null
		JellyfinClient.normalizeServerUrl("http://") shouldBe null
	}
})

class ServerOriginTest : FunSpec({
	test("token header only for the paired server origin") {
		val server = "https://jf.example.org/jellyfin".toHttpUrl()
		ServerAuthInterceptor.isSameOrigin("https://jf.example.org/Videos/1/stream".toHttpUrl(), server) shouldBe true
		ServerAuthInterceptor.isSameOrigin("https://JF.example.org:443/x".toHttpUrl(), server) shouldBe true
		ServerAuthInterceptor.isSameOrigin("http://jf.example.org/x".toHttpUrl(), server) shouldBe false
		ServerAuthInterceptor.isSameOrigin("https://jf.example.org:8920/x".toHttpUrl(), server) shouldBe false
		ServerAuthInterceptor.isSameOrigin("https://subs.evil.example/x.srt".toHttpUrl(), server) shouldBe false
	}
})
