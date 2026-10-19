package ua.movieportal.tv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ua.movieportal.tv.player.HiddenPlayerPolicy

class HiddenPlayerPolicyTest : FunSpec({
	test("before the stream is ready a stop is always transient") {
		HiddenPlayerPolicy.closeDelayMs(hasBeenResumed = false, reachedReady = false, screenInteractive = true) shouldBe null
		HiddenPlayerPolicy.closeDelayMs(hasBeenResumed = true, reachedReady = false, screenInteractive = true) shouldBe null
	}

	test("TV still waking from standby: no countdown until the screen is on") {
		HiddenPlayerPolicy.closeDelayMs(hasBeenResumed = false, reachedReady = true, screenInteractive = false) shouldBe null
	}

	test("screen on but the player never came to the front: generous wait") {
		HiddenPlayerPolicy.closeDelayMs(hasBeenResumed = false, reachedReady = true, screenInteractive = true) shouldBe
			HiddenPlayerPolicy.HIDDEN_START_GRACE_MS
	}

	test("a player that was visible and was left (Home, TV off) closes quickly") {
		HiddenPlayerPolicy.closeDelayMs(hasBeenResumed = true, reachedReady = true, screenInteractive = true) shouldBe
			HiddenPlayerPolicy.STOP_GRACE_MS
		HiddenPlayerPolicy.closeDelayMs(hasBeenResumed = true, reachedReady = true, screenInteractive = false) shouldBe
			HiddenPlayerPolicy.STOP_GRACE_MS
	}
})
