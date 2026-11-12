package bot.tafs.app

import bot.tafs.app.kiff.Home
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.kiff.TafRunsPage
import bot.tafs.app.model.Chat
import bot.tafs.app.model.TafState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What a taf's row says, in tafs cloud's words: its next run with its day ("Next run Mon 08:00"), and what went wrong first in
 * place of "Working". Its runs show the firings that started no run. The app formats none of it itself.
 */
class CloudWordsTest {
  private fun taf(extra: String) = KiffMapping.taf(
    KiffClient.json.decodeFromString<Home>(
      """{"tafs":[{"id":"taf_1","name":"Disputes","kiff_agent":"disputes-1","status":"active","next_run":"2026-11-16T07:00:00Z","color":1,"state":"working",""" +
        """"card":{"ids":["c1"],"active":true}$extra}]}""",
    ).tafs.single(),
    "You",
  )

  @Test fun theNextRunIsTafsCloudsWordsWithItsDay() {
    val t = taf(""","next_run_words":"Next run Mon 08:00"""")
    assertEquals(TafState.working, t.state)
    assertEquals("Next run Mon 08:00", t.now)
    assertEquals("Next run Mon 08:00", t.nextRunWords)
  }

  @Test fun anOlderTafsCloudWithNoWordsKeepsTheAppsOwnFormat() {
    val t = taf("")
    assertEquals(null, t.nextRunWords)
    assertNotEquals("", t.now)
  }

  @Test fun whatWentWrongFirstShowsInPlaceOfWorking() {
    val t = taf(""","next_run_words":"Next run Mon 08:00","work":{"status":"idle","line":"Its check couldn't read the work list at 23:29.","needs_you":0,"attention":true}""")
    assertNotEquals(TafState.working, t.state)
    assertEquals("Its check couldn't read the work list at 23:29.", t.now)
  }

  @Test fun itsRunsShowAFiringThatStartedNoRunInItsPlace() {
    val page = KiffClient.json.decodeFromString<TafRunsPage>(
      """{"requests":[{"id":"r1","at":"2026-11-11T21:00:00Z","state":"done","line":"Done at 22:00"},{"id":"r2","at":"2026-11-11T19:00:00Z","state":"done","line":"Done at 20:00"}],""" +
        """"skipped":[{"due":"2026-11-11T20:00:00Z","why":"check_failed","words":"21:00 · its check couldn't read the work list"}]}""",
    )
    val rows = Chat.runRows(page)
    assertEquals(3, rows.size)
    assertEquals("21:00 · its check couldn't read the work list", (rows[1] as Chat.WorkRow.Skipped).firing.words)
  }
}
