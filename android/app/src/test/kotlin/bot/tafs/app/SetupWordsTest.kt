package bot.tafs.app

import bot.tafs.app.app.AppState
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KnobWire
import bot.tafs.app.model.Card
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.SetupWords
import bot.tafs.app.model.Taf
import bot.tafs.app.views.resultsSummary
import bot.tafs.app.views.spentOn
import bot.tafs.app.views.startsSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The taf screen's summaries, drawn from what tafs cloud and KIFF send: what starts it, where results go, a day's spend. */
class SetupWordsTest {
  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1, card = Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "taf_1")
  private val knob = KiffClient.json.decodeFromString<KnobWire>(
    """{"wakes":[{"id":"w","kind":"schedule","label":"Weekdays","words":"Weekdays at 09:00"},{"id":"d","kind":"door","label":"Email","words":"An email to refunds@"},
        {"id":"r","kind":"run_now","label":"Run now"},{"id":"a","kind":"add_door","label":"Add a door"}],
        "results":[{"id":"r1","place":"door_reply","label":"Sender","on":true},{"id":"r2","place":"app","label":"You","on":false},
                   {"id":"r3","place":"action","label":"Notion · Support log","on":true}],"rim":{}}""",
  )

  @Test fun whatStartsItIsEachStartInTafsCloudsWordsLeavingOutItsButtons() {
    assertEquals(listOf("Weekdays at 09:00", "An email to refunds@"), startsSummary(taf, knob))
    assertEquals("works from its chat, nothing listed", listOf(SetupWords.NO_START), startsSummary(taf.copy(worksFromChat = true), null))
  }

  @Test fun whereResultsGoIsThePlacesOnByTheirTitles() {
    assertEquals(listOf("Reply to the sender", "Notion · Support log"), resultsSummary(knob, AppState()))
  }

  private fun call(kind: RecordEntry.Kind, value: String?, unit: String?) =
    RecordEntry(tafId = "refunds-1", kind = kind, title = "Refund", detail = "", at = Instant.parse("2026-11-03T09:00:00Z"), amountValue = value, amountUnit = unit, amountText = value?.let { "$it ${unit ?: ""}" })

  @Test fun aDaysSpendIsWhatItsMoneyMovedCameToByUnit() {
    val day = listOf(call(RecordEntry.Kind.done, "100.00", "EUR"), call(RecordEntry.Kind.done, "20.50", "EUR"), call(RecordEntry.Kind.refused, "500.00", "EUR"))
    val spent = spentOn(day)!!
    assertEquals("refused money didn't move", true, spent.contains("120") && !spent.contains("620"))
    assertNull("nothing moved", spentOn(listOf(call(RecordEntry.Kind.refused, "5.00", "EUR"))))
    assertEquals("two units, both said", 2, spentOn(day + call(RecordEntry.Kind.done, "30", "USD"))!!.split(" · ").size)
  }
}
