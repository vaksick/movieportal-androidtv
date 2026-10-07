package ua.movieportal.tv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ua.movieportal.tv.util.RedactingTree

class RedactingTreeTest : FunSpec({
	test("credentials are masked") {
		RedactingTree.redact("GET http://h/socket?api_key=abc123&deviceId=x") shouldBe
			"GET http://h/socket?api_key=<redacted>&deviceId=x"
		RedactingTree.redact("/master.m3u8?ApiKey=Zz9&PlaySessionId=1") shouldBe "/master.m3u8?ApiKey=<redacted>&PlaySessionId=1"
		RedactingTree.redact("""MediaBrowser Client="a", Token="t0k3n"""") shouldBe """MediaBrowser Client="a", Token="<redacted>""""
		RedactingTree.redact("/QuickConnect/Connect?secret=deadbeef") shouldBe "/QuickConnect/Connect?secret=<redacted>"
	}
})
