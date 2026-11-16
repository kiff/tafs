package bot.tafs.app

import bot.tafs.app.kiff.Home
import bot.tafs.app.kiff.HomeLabelMission
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.MissionLogEvidence
import bot.tafs.app.kiff.MissionLogItem
import bot.tafs.app.kiff.MissionLogPage
import bot.tafs.app.kiff.MissionLogs
import bot.tafs.app.kiff.TafsCloud
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A mission's log on Android (Missions v2.1 §6): read only, drawn as tafs cloud words it, a page at a time. */
class MissionLogTest {
  private val page = """{"items":[
    {"id":"log_3","at":"2026-11-14T06:10:00Z","by":{"kind":"lead"},"kind":"stuck","kind_words":"stuck","words":"Payouts didn't run at 07:00: its bank sign-in needs you.","evidence":[{"kind":"run","id":"run_9"},{"kind":"hold","id":"h_2","words":"hold"}]},
    {"id":"log_2","at":"2026-11-14T06:02:00Z","by":{"kind":"taf","name":"Bills in"},"kind":"delivered","kind_words":"delivered","words":"12 bills sorted, 2 didn't match their order.","evidence":[{"kind":"item","id":"rec_1","words":"12 record lines"}],"value":"€4,120 sorted"},
    {"id":"log_1","at":"2026-11-13T08:12:00Z","by":{"kind":"taf"},"kind":"delivered","words":"3 quotes drafted for new clients.","evidence":[],"removed":true},
    {"id":"log_0","at":"2026-11-13T08:00:00Z","by":{"kind":"taf","name":"Collect"},"kind":"note","words":"  "},
    {"oddly":"shaped"}
  ],"before":"log_0","newer_field":1}"""

  @Test fun aPageDecodesLenientlyAndOnlyWhatSaysSomethingIsDrawn() {
    val read = KiffClient.json.decodeFromString(MissionLogPage.serializer(), page)
    assertEquals("log_0", read.before)
    val drawn = MissionLogs.drawn(listOf(read, read))
    assertEquals("blank words and the empty entry left out, a page read twice shown once", listOf("log_3", "log_2", "log_1"), drawn.map { it.id })
    assertEquals("Bills in", MissionLogs.byName(drawn[1]))
    assertNull("the lead and a removed taf carry no name of their own", MissionLogs.byName(drawn[0]))
    assertTrue(drawn[2].removed)
    assertEquals("€4,120 sorted", drawn[1].value)
    assertEquals("delivered", drawn[1].kindWords)
    assertNull(drawn[2].kindWords)
  }

  @Test fun evidenceIsTafsCloudsWordsElseItsKind() {
    assertEquals("12 record lines", MissionLogs.evidenceWords(MissionLogEvidence("item", "rec_1", "12 record lines")))
    assertEquals("run", MissionLogs.evidenceWords(MissionLogEvidence("run", "run_9")))
    assertEquals("run", MissionLogs.evidenceWords(MissionLogEvidence("run", "run_9", " ")))
  }

  @Test fun anEntrysTimeIsThePhonesOwn() {
    assertEquals("14 Nov, 07:10", MissionLogs.shownAt("2026-11-14T06:10:00Z", ZoneId.of("Europe/Paris"), Locale.UK))
    assertEquals("not a time", MissionLogs.shownAt("not a time", ZoneId.of("Europe/Paris"), Locale.UK))
  }

  @Test fun aLabelCarriesItsMissionWhenItIsOne() {
    val home = KiffClient.json.decodeFromString<Home>(
      """{"tafs":[],"others":[],"removing":[],"labels":[
        {"id":"l_money","name":"Money","count":5,"mission":{"id":"m_1","name":"Money","goal":"Run the money side"}},
        {"id":"l_clients","name":"Clients","count":2}]}""",
    )
    assertEquals(HomeLabelMission("m_1", "Money", "Run the money side"), home.labels!![0].mission)
    assertNull(home.labels!![1].mission)
  }

  @Test fun theLogIsReadByItsRouteAPageAtATime() = runTest {
    val transport = FakeTransport()
    transport.answer("GET /v1/missions/m_1/log", 200, page)
    val cloud = TafsCloud(transport).apply { token = "at_1" }
    assertEquals(4, cloud.missionLog("m_1").items.count { it.id.isNotEmpty() })
    transport.answer("GET /v1/missions/m_1/log", 200, """{"items":[]}""")
    cloud.missionLog("m_1", before = "log 0")
    val urls = transport.requests.map { it.url }
    assertEquals("https://cloud.tafs.kiff.dev/v1/missions/m_1/log", urls[0])
    assertEquals("https://cloud.tafs.kiff.dev/v1/missions/m_1/log?before=log%200", urls[1])
  }

  @Test fun theSampleHasNoMissionLog() = runTest {
    assertNull(testModel().missionLog("m_1"))
  }

  @Test fun nothingIsDrawnForAnEmptyLog() {
    assertEquals(emptyList<MissionLogItem>(), MissionLogs.drawn(listOf(MissionLogPage())))
  }
}
