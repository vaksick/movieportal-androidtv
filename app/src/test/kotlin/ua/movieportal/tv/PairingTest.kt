package ua.movieportal.tv

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ua.movieportal.tv.discovery.DiscoveredServer
import ua.movieportal.tv.discovery.DiscoveryDecision
import ua.movieportal.tv.discovery.DiscoveryProtocol
import ua.movieportal.tv.discovery.DiscoveryResponse
import ua.movieportal.tv.pairing.PairingQr

class DiscoveryTest : FunSpec({
	test("discovery reply is parsed; only the reported address is kept") {
		DiscoveryProtocol.parse(
			"""{"Address":"http://192.168.1.10:8096","Id":"6d3f1c2a9b8e4f7a8c1d2e3f4a5b6c7d","Name":"Home","EndpointAddress":null}""",
		) shouldBe DiscoveryResponse("6d3f1c2a9b8e4f7a8c1d2e3f4a5b6c7d", "Home", "http://192.168.1.10:8096")

		DiscoveryProtocol.parse("""{"Name":"no id"}""") shouldBe null
		DiscoveryProtocol.parse("garbage") shouldBe null
	}

	test("http address gets https upgrades on 443 and 8920, same host and path, no other fallbacks") {
		DiscoveryProtocol.candidateUrls(DiscoveryResponse("id", "A", "http://jf.lan:8096/jellyfin/")) shouldContainExactly
			listOf("http://jf.lan:8096/jellyfin", "https://jf.lan/jellyfin", "https://jf.lan:8920/jellyfin")
		DiscoveryProtocol.candidateUrls(DiscoveryResponse("id", "Docker", "http://172.17.0.2:8096")) shouldContainExactly
			listOf("http://172.17.0.2:8096", "https://172.17.0.2", "https://172.17.0.2:8920")
	}

	test("https address is used as is; missing or invalid address gives no candidates") {
		DiscoveryProtocol.candidateUrls(DiscoveryResponse("id", "A", "https://jf.example.org/")) shouldContainExactly
			listOf("https://jf.example.org")
		DiscoveryProtocol.candidateUrls(DiscoveryResponse("id", "A", null)) shouldBe emptyList()
		DiscoveryProtocol.candidateUrls(DiscoveryResponse("id", "A", "ftp://x")) shouldBe emptyList()
	}

	test("any working https candidate wins over http, http only as a last resort") {
		val candidates = listOf("http://jf.lan:8096", "https://jf.lan", "https://jf.lan:8920")
		DiscoveryProtocol.pickBest(candidates, setOf("http://jf.lan:8096", "https://jf.lan:8920")) shouldBe "https://jf.lan:8920"
		DiscoveryProtocol.pickBest(candidates, candidates.toSet()) shouldBe "https://jf.lan"
		DiscoveryProtocol.pickBest(candidates, setOf("http://jf.lan:8096")) shouldBe "http://jf.lan:8096"
		DiscoveryProtocol.pickBest(candidates, emptySet()) shouldBe null
	}

	test("ids are compared without dashes and case") {
		DiscoveryProtocol.sameId("6D3F1C2A-9B8E-4F7A-8C1D-2E3F4A5B6C7D", "6d3f1c2a9b8e4f7a8c1d2e3f4a5b6c7d") shouldBe true
		DiscoveryProtocol.sameId("a", null) shouldBe false
	}

	test("one reachable https server is selected automatically, unless the user wants to choose") {
		val home = DiscoveredServer.Reachable("1", "Home", "https://jf.lan", "10.10.7")
		val docker = DiscoveredServer.Unreachable("2", "Docker", "http://172.17.0.2:8096")

		DiscoveryDecision.of(listOf(docker, home), allowAutoSelect = true) shouldBe DiscoveryDecision.AutoSelect(home)
		DiscoveryDecision.of(listOf(docker, home), allowAutoSelect = false) shouldBe DiscoveryDecision.Choose(listOf(home, docker))
		DiscoveryDecision.of(listOf(docker), allowAutoSelect = true).shouldBeInstanceOf<DiscoveryDecision.Choose>()
		DiscoveryDecision.of(emptyList(), allowAutoSelect = true) shouldBe DiscoveryDecision.NothingFound

		val office = DiscoveredServer.Reachable("3", "Office", "https://office.lan", null)
		DiscoveryDecision.of(listOf(office, home), allowAutoSelect = true) shouldBe DiscoveryDecision.Choose(listOf(home, office))
	}

	test("an http-only server is never selected silently") {
		val plain = DiscoveredServer.Reachable("6d3f1c2a-9b8e-4f7a-8c1d-2e3f4a5b6c7d", "Home", "http://192.168.1.10:8096", null)
		plain.insecure shouldBe true

		DiscoveryDecision.of(listOf(plain), allowAutoSelect = true) shouldBe DiscoveryDecision.ConfirmInsecure(plain)
		// Confirmed earlier for this server id (any GUID format): no nagging on rescans
		DiscoveryDecision.of(listOf(plain), allowAutoSelect = true, confirmedInsecure = setOf("6d3f1c2a9b8e4f7a8c1d2e3f4a5b6c7d")) shouldBe
			DiscoveryDecision.AutoSelect(plain)
		// Confirmation of another server does not count
		DiscoveryDecision.of(listOf(plain), allowAutoSelect = true, confirmedInsecure = setOf("other")) shouldBe
			DiscoveryDecision.ConfirmInsecure(plain)
		// In the list the choice is the user's; the screen still warns on click
		DiscoveryDecision.of(listOf(plain), allowAutoSelect = false) shouldBe DiscoveryDecision.Choose(listOf(plain))
	}
})

class PairingQrTest : FunSpec({
	test("payload is compact versioned JSON") {
		PairingQr.payload("123456", "1fa680d4-dbcc-3145-b6f9-b24ba9c4b7d4", "6d3f1c2a9b8e4f7a8c1d2e3f4a5b6c7d") shouldBe
			"""{"t":"mptv","v":1,"code":"123456","did":"1fa680d4-dbcc-3145-b6f9-b24ba9c4b7d4","sid":"6d3f1c2a9b8e4f7a8c1d2e3f4a5b6c7d"}"""
	}

	test("payload carries the device name, trimmed and length-limited") {
		val longName = " BRAVIA 4K VH2 " + "x".repeat(100)
		val payload = PairingQr.payload("123456", "1fa680d4-dbcc-3145-b6f9-b24ba9c4b7d4", null, longName)
		payload shouldBe """{"t":"mptv","v":1,"code":"123456","did":"1fa680d4-dbcc-3145-b6f9-b24ba9c4b7d4","name":"${longName.trim().take(40)}"}"""
		PairingQr.payload("123456", "1fa680d4-dbcc-3145-b6f9-b24ba9c4b7d4", null, "  ") shouldBe
			"""{"t":"mptv","v":1,"code":"123456","did":"1fa680d4-dbcc-3145-b6f9-b24ba9c4b7d4"}"""
	}

	test("QR code round-trips through a decoder") {
		val payload = PairingQr.payload("654321", "1fa680d4-dbcc-3145-b6f9-b24ba9c4b7d4", "6d3f1c2a9b8e4f7a8c1d2e3f4a5b6c7d", "Вітальня Mi TV")
		val matrix = PairingQr.encode(payload)

		// Quiet zone: the outer 4 modules are white
		(0 until matrix.width).none { matrix[it, 0] || matrix[it, 3] || matrix[0, it] } shouldBe true

		val scale = 4
		val size = matrix.width * scale
		val pixels = IntArray(size * size) { i ->
			if (matrix[(i % size) / scale, (i / size) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
		}
		val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))).text

		decoded shouldBe payload
		val json = Json.parseToJsonElement(decoded).jsonObject
		json["t"]!!.jsonPrimitive.content shouldBe "mptv"
		json["code"]!!.jsonPrimitive.content shouldBe "654321"
	}
})
