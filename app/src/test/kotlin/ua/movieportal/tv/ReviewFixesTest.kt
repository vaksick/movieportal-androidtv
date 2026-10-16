package ua.movieportal.tv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemKind
import ua.movieportal.tv.data.TokenCipher
import ua.movieportal.tv.player.SeekPolicy
import ua.movieportal.tv.player.UpNext
import ua.movieportal.tv.player.UpNextQueue
import ua.movieportal.tv.service.ServiceRecovery
import java.util.UUID

class TokenCipherTest : FunSpec({
	test("a corrupted stored token means 'not paired' instead of a crash") {
		listOf("v1:", "v1:onlyiv", "v1::", "v1::cipher", "v1:iv:", "v1:!!:??", "v1:aGVsbG8=:d29ybGQ=").forEach {
			TokenCipher.decrypt(it) shouldBe null
		}
	}

	test("plain and legacy formats are returned as is") {
		TokenCipher.decrypt("plain:abc") shouldBe "abc"
		TokenCipher.decrypt("legacy-token") shouldBe "legacy-token"
	}
})

class SeekPolicyTest : FunSpec({
	test("seeking stops one second before the end, so holding → does not start the next episode") {
		SeekPolicy.target(positionMs = 590_000, deltaMs = 60_000, durationMs = 600_000) shouldBe 599_000
		SeekPolicy.target(positionMs = 100_000, deltaMs = 10_000, durationMs = 600_000) shouldBe 110_000
	}

	test("never before the start; unknown or tiny duration") {
		SeekPolicy.target(positionMs = 5_000, deltaMs = -10_000, durationMs = 600_000) shouldBe 0
		SeekPolicy.target(positionMs = 5_000, deltaMs = 60_000, durationMs = null) shouldBe 65_000
		SeekPolicy.target(positionMs = 0, deltaMs = 10_000, durationMs = 500) shouldBe 0
	}
})

class UpNextQueueTest : FunSpec({
	val first = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
	val queue = listOf(first.toString(), "fedcba9876543210fedcba9876543210")

	test("a failed type lookup keeps the queue (only the button label falls back)") {
		UpNextQueue.fromQueue(queue) { error("network") } shouldBe UpNext(first, queue.drop(1), isEpisode = false)
	}

	test("episodes are labelled as such") {
		UpNextQueue.fromQueue(queue) { BaseItemKind.EPISODE } shouldBe UpNext(first, queue.drop(1), isEpisode = true)
		UpNextQueue.fromQueue(queue) { BaseItemKind.MOVIE }?.isEpisode shouldBe false
	}

	test("empty or invalid queue") {
		UpNextQueue.fromQueue(emptyList()) { BaseItemKind.EPISODE } shouldBe null
		UpNextQueue.fromQueue(listOf("not-a-guid")) { BaseItemKind.EPISODE } shouldBe null
	}
})

class ServiceRecoveryTest : FunSpec({
	test("recovery attempts back off from 1 to 30 minutes") {
		(0..7).map { ServiceRecovery.delayMs(it) / 60_000 } shouldBe listOf(1L, 2L, 5L, 10L, 15L, 30L, 30L, 30L)
		ServiceRecovery.delayMs(-1) shouldBe 60_000L
	}
})
