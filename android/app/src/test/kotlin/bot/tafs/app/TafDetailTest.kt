package bot.tafs.app

import bot.tafs.app.model.Card
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.Money
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafCard
import bot.tafs.app.model.TafState
import bot.tafs.app.model.TafTemplates
import bot.tafs.app.model.Window
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TafDetailTest {
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private fun model() = testModel(clock = { t0 })

  private val refunds = TafTemplates.refunds

  // MARK: The Card's face

  @Test fun theFaceShowsWhatIsLeftOfTheMoneyTotal() {
    val card = TafCard.merged("c", "Refunds", refunds.rules, null, used = mapOf("refund" to Money.usd(40.0)))
    val hero = card.hero!!
    assertEquals(Money.usd(1_960.0).formatted, hero.figure)
    assertEquals("left today of ${Money.usd(2_000.0).formatted}", hero.words)
  }

  @Test fun aCardWithOnlyACountShowsCallsLeft() {
    val card = TafCard.merged("c", "Deploys", TafTemplates.deploys.rules, null, counted = mapOf("deploy" to 3))
    assertEquals("7", card.hero!!.figure)
    assertEquals("calls left today of 10", card.hero!!.words)
    assertEquals("7 calls left", card.leftLine)
  }

  @Test fun useKiffCouldNotReadIsNeverShownAsZero() {
    val card = TafCard.merged("c", "Refunds", refunds.rules, null, unknown = setOf("refund"))
    assertEquals("a day · use unavailable", card.hero!!.words)
    assertEquals(Money.usd(2_000.0).formatted, card.hero!!.figure)
  }

  @Test fun anAmountWithNoCurrencySaysSoOnceAfterTheWords() {
    val rule = CardRule(action = "send", label = "Sends", perWindow = Money.bare(30_000), window = Window.day)
    val card = TafCard.merged("c", "Sends", listOf(rule), null)
    assertTrue(card.hero!!.words.endsWith(" · no currency"))
    assertEquals("30,000".replace(",", java.text.DecimalFormatSymbols.getInstance().groupingSeparator.toString()), card.hero!!.figure)
  }

  @Test fun aCardWithNoLimitsShowsItsFirstLinesInsteadOfAFigure() {
    val rules = TafTemplates.billPay.rules.filter { it.never || it.alwaysAsk }
    val card = TafCard.merged("c", "Bill pay", rules, null)
    assertNull(card.hero)
    assertEquals(listOf("wires always asks you", "bank detail changes never", "1 more"), card.facts)
  }

  @Test fun theFaceNamesTheLimitForOneCallAndHowManyTools() {
    val card = TafCard.merged("c", "Refunds", refunds.rules, null)
    assertEquals(listOf("up to ${Money.usd(150.0).formatted} each; more asks you first", "3 tools"), card.facts)
  }

  @Test fun aTafWithoutKiffCardsDrawsItsLinesAsOneCard() {
    val taf = Taf(name = "Refunds", job = "", tools = emptyList(), colorIndex = 0, card = Card(refunds.rules, "You"))
    assertEquals(listOf("merged"), taf.shownCards.map { it.id })
  }

  @Test fun liveCardsComeFirstAndOneThatCountsMoneyBeforeOneThatDoesNot() {
    val calls = TafCard("calls", "Calls", rules = emptyList(), calls = bot.tafs.app.model.CardTotal(10, 0, Window.day))
    val money = TafCard("money", "Money", rules = emptyList(), total = bot.tafs.app.model.CardTotal(Money.usd(1.0), Money.ZERO, Window.day))
    val test = TafCard("test", "Test", environment = "test", live = false, rules = emptyList(), total = bot.tafs.app.model.CardTotal(Money.usd(1.0), Money.ZERO, Window.day))
    val taf = Taf(name = "T", job = "", tools = emptyList(), colorIndex = 0, card = Card(emptyList(), "You"), kiffCards = listOf(test, calls, money))
    assertEquals(listOf("money", "calls", "test"), taf.shownCards.map { it.id })
  }

  @Test fun aTafThatHasUsedNothingReadsZeroInTheLimitsUnit() {
    val taf = Taf(name = "Refunds", job = "", tools = emptyList(), colorIndex = 0, card = Card(refunds.rules, "You"))
    val used = taf.usedOf(refunds.rules.first { it.action == "refund" })
    assertEquals(Money.usd(0.0), used)
    assertEquals("USD", used.currency)
    assertTrue(!used.formatted.contains("no currency"))
  }

  @Test fun whatIsUsedReadsAsTheCardCountedIt() {
    val taf = Taf(name = "Refunds", job = "", tools = emptyList(), colorIndex = 0, card = Card(refunds.rules, "You"),
      used = mapOf("refund" to Money.usd(40.0)))
    assertEquals(Money.usd(40.0), taf.usedOf(refunds.rules.first { it.action == "refund" }))
  }

  // MARK: Drafting and starting a taf

  @Test fun theJobIsDraftedFromItsWords() {
    val draft = TafTemplates.draft("Please settle refund requests from the queue")
    assertEquals("Refunds", draft.name)
    assertEquals(refunds.rules, draft.rules)
    assertEquals("refunds", draft.templateKey)
  }

  @Test fun aJobNoTemplateMatchesGetsAnEmptyDraft() {
    val draft = TafTemplates.draft("Water the plants")
    assertEquals("New taf", draft.name)
    assertTrue(draft.rules.isEmpty())
    assertNull(draft.templateKey)
  }

  @Test fun startingATafIssuesItsCardAndLogsIt() = runTest {
    val m = model()
    val before = m.state.value.tafs.size
    val taf = m.start(TafTemplates.draft("deploy to production"))!!
    val s = m.state.value
    assertEquals(before + 1, s.tafs.size)
    assertEquals(taf.id, s.tafs.first().id)
    assertEquals(TafState.working, s.taf(taf.id)!!.state)
    assertEquals(before, s.taf(taf.id)!!.colorIndex)
    assertEquals("You", s.taf(taf.id)!!.card.issuedBy)
    assertTrue(s.record.any { it.tafId == taf.id && it.kind == RecordEntry.Kind.started })
  }

  @Test fun aNewCardEndsThreeMonthsOn() = runTest {
    val m = model()
    val taf = m.start(TafTemplates.draft("refund"))!!
    val ends = taf.card.expires!!
    assertTrue(ends > t0.plusSeconds(88L * 86_400))
    assertTrue(ends < t0.plusSeconds(93L * 86_400))
  }

  // MARK: Pausing and stopping

  @Test fun pausingAndResumingTogglesTheTaf() {
    val m = model()
    val id = m.state.value.tafs.first { it.state == TafState.working }.id
    m.togglePause(id)
    assertEquals(TafState.paused, m.state.value.taf(id)!!.state)
    assertEquals("Paused by you", m.state.value.taf(id)!!.now)
    m.togglePause(id)
    assertEquals(TafState.working, m.state.value.taf(id)!!.state)
    assertEquals("Back to work", m.state.value.taf(id)!!.now)
  }

  @Test fun revokingStopsTheTafAndRefusesWhatWaited() {
    val m = model()
    val held = m.state.value.held.first()
    val id = held.tafId
    val waiting = m.state.value.heldCalls(id).size
    m.revoke(id)
    val s = m.state.value
    assertEquals(TafState.revoked, s.taf(id)!!.state)
    assertTrue(s.taf(id)!!.card.revoked)
    assertTrue(s.heldCalls(id).isEmpty())
    val line = s.record.first { it.kind == RecordEntry.Kind.revoked }
    assertTrue(line.detail.contains("$waiting waiting action(s) refused"))
  }

  @Test fun aRevokedTafCannotBePausedOrResumed() {
    val m = model()
    val id = m.state.value.tafs.first().id
    m.revoke(id)
    m.togglePause(id)
    assertEquals(TafState.revoked, m.state.value.taf(id)!!.state)
  }

  // MARK: What a taf remembers

  @Test fun aTafsNotesLoadOnceAndForgettingRemovesOnlyThatNote() {
    val m = model()
    val id = m.state.value.tafs.first().id
    m.loadMemory(id)
    val notes = m.state.value.memories[id]!!
    assertEquals(3, notes.size)
    m.loadMemory(id)
    m.forget(notes[0], id)
    assertEquals(notes.drop(1), m.state.value.memories[id])
    m.loadMemory(id)
    assertEquals(2, m.state.value.memories[id]!!.size)
  }

  @Test fun aNotesTitleIsReadFromItsPath() {
    val note = bot.tafs.app.model.MemoryNote("n", "/customers/regular-ones.md", "x", t0)
    assertEquals("customers · regular ones", note.title)
  }
}
