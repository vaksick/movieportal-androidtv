package ua.movieportal.tv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ua.movieportal.tv.remote.PlayerCommand
import ua.movieportal.tv.remote.RemoteCommand
import ua.movieportal.tv.remote.RemoteMessageParser

class RemoteMessageParserTest : FunSpec({
	test("Play uses the item at StartIndex and all stream options") {
		val message = """
			{"MessageType":"Play","MessageId":"1","Data":{"ItemIds":["aaa","0123456789abcdef0123456789abcdef"],"StartIndex":1,
			"PlayCommand":"PlayNow","StartPositionTicks":123450000,"AudioStreamIndex":2,
			"SubtitleStreamIndex":-1,"MediaSourceId":"fedcba9876543210fedcba9876543210"}}
		""".trimIndent()

		RemoteMessageParser.parse(message) shouldBe RemoteCommand.Play(
			itemId = "0123456789abcdef0123456789abcdef",
			playCommand = "PlayNow",
			startPositionTicks = 123450000,
			audioStreamIndex = 2,
			subtitleStreamIndex = -1,
			mediaSourceId = "fedcba9876543210fedcba9876543210",
		)
	}

	test("Play without optional fields") {
		RemoteMessageParser.parse("""{"MessageType":"Play","Data":{"ItemIds":["01234567-89ab-cdef-0123-456789abcdef"],"PlayCommand":"PlayNext"}}""") shouldBe
			RemoteCommand.Play("01234567-89ab-cdef-0123-456789abcdef", "PlayNext", null, null, null, null)
	}

	test("Playstate commands") {
		RemoteMessageParser.parse("""{"MessageType":"Playstate","Data":{"Command":"PlayPause"}}""") shouldBe
			RemoteCommand.Player(PlayerCommand.PlayPause)
		RemoteMessageParser.parse("""{"MessageType":"Playstate","Data":{"Command":"Seek","SeekPositionTicks":72000000000}}""") shouldBe
			RemoteCommand.Player(PlayerCommand.Seek(72_000_000_000))
		RemoteMessageParser.parse("""{"MessageType":"Playstate","Data":{"Command":"NextTrack"}}""") shouldBe
			RemoteCommand.Ignored("Playstate", "NextTrack")
	}

	test("General commands with string arguments") {
		RemoteMessageParser.parse("""{"MessageType":"GeneralCommand","Data":{"Name":"SetSubtitleStreamIndex","Arguments":{"Index":"-1"}}}""") shouldBe
			RemoteCommand.Player(PlayerCommand.SetSubtitleStream(-1))
		RemoteMessageParser.parse("""{"MessageType":"GeneralCommand","Data":{"Name":"SetVolume","Arguments":{"Volume":"150"}}}""") shouldBe
			RemoteCommand.SetVolume(100)
		RemoteMessageParser.parse("""{"MessageType":"GeneralCommand","Data":{"Name":"DisplayMessage","Arguments":{"Header":"H","Text":"T"}}}""") shouldBe
			RemoteCommand.DisplayMessage("H", "T")
	}

	test("Malformed or hostile values are rejected") {
		RemoteMessageParser.parse("""{"MessageType":"Play","Data":{"ItemIds":["../../etc"]}}""") shouldBe
			RemoteCommand.Ignored("Play", "invalid item id")
		RemoteMessageParser.parse("""{"MessageType":"Playstate","Data":{"Command":"Seek","SeekPositionTicks":-5}}""") shouldBe
			RemoteCommand.Ignored("Playstate", "invalid seek position")
		RemoteMessageParser.parse("""{"MessageType":"GeneralCommand","Data":{"Name":"SetAudioStreamIndex","Arguments":{"Index":"99999999999"}}}""") shouldBe
			RemoteCommand.Ignored("SetAudioStreamIndex", "no index")
		RemoteMessageParser.parse("""{"MessageType":"Play","Data":"oops"}""") shouldBe RemoteCommand.Ignored("Play", "no data")
		RemoteMessageParser.parse("""{"MessageType":"GeneralCommand","Data":{"Name":"DisplayMessage","Arguments":{"Text":"${"x".repeat(2000)}"}}}""") shouldBe
			RemoteCommand.DisplayMessage(null, "x".repeat(500))
		RemoteMessageParser.parse("""[1,2,3]""") shouldBe null
	}

	test("Keep-alive handling and garbage") {
		RemoteMessageParser.parse("""{"MessageType":"ForceKeepAlive","Data":60}""") shouldBe RemoteCommand.ForceKeepAlive(60)
		RemoteMessageParser.parse("""{"MessageType":"KeepAlive"}""") shouldBe RemoteCommand.KeepAlive
		RemoteMessageParser.parse("not json") shouldBe null
	}
})
