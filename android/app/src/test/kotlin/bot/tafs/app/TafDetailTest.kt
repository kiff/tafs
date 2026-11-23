package bot.tafs.app

import bot.tafs.app.model.Card
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.Money
import bot.tafs.app.model.Outside
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

  /** A Card with money limits (the sample's jobs count calls since 18 Nov; a real Card can still limit money). */
  private object refunds {
    val rules = listOf(
      CardRule(action = "refund", label = "Refunds", perAction = Money.eur(150.0), perWindow = Money.eur(2_000.0), window = Window.day),
      CardRule(action = "credit", label = "Credits", perAction = Money.eur(100.0), perWindow = Money.eur(1_000.0), window = Window.day),
      CardRule(action = "payout", label = "Payouts", never = true, outside = Outside.refuse),
    )
  }

  /** A Card with no limit at all: only what always asks and what never goes through. */
  private val noLimits = listOf(
    CardRule(action = "wire", label = "Wires", alwaysAsk = true),
    CardRule(action = "bank_details", label = "Bank detail changes", never = true, outside = Outside.refuse),
    CardRule(action = "new_payee", label = "New payees", never = true, outside = Outside.refuse),
  )

  // MARK: The Card's face

  /** A template is read in the app's language each time, and is still the same template: its rules keep their ids. */
  @Test fun aTemplateReadAgainIsTheSameTemplate() {
    for (t in TafTemplates.all) assertEquals(t, TafTemplates.template(t.key))
    assertEquals(TafTemplates.all.flatMap { it.rules }.size, TafTemplates.all.flatMap { it.rules }.map { it.id }.toSet().size)
  }

  @Test fun theFaceShowsWhatIsLeftOfTheMoneyTotal() {
    val card = TafCard.merged("c", "Refunds", refunds.rules, null, used = mapOf("refund" to Money.eur(40.0)))
    val hero = card.hero!!
    assertEquals(Money.eur(1_960.0).formatted, hero.figure)
    assertEquals("left today of ${Money.eur(2_000.0).formatted}", hero.words)
  }

  @Test fun aCardWithOnlyACountShowsCallsLeft() {
    val card = TafCard.merged("c", "Deploys", TafTemplates.deploys.rules, null, counted = mapOf("deploy" to 3))
    assertEquals("7", card.hero!!.figure)
    assertEquals("calls left today of 10", card.hero!!.words)
    assertEquals("7 calls left", card.leftLine)
  }

  /**
   * A Card's row and TalkBack read the calls left as one line in the language's own order (`card.calls_left_line`, as iOS's
   * #1567): English reads as before, one call is singular, and a money Card is its figure then its words, unchanged.
   */
  @Test fun theRowReadsCallsLeftAsOneLine() {
    val seven = TafCard.merged("c", "Deploys", TafTemplates.deploys.rules, null, counted = mapOf("deploy" to 3))
    assertEquals("7 calls left today of 10", seven.heroLine)
    val one = TafCard.merged("c", "Deploys", TafTemplates.deploys.rules, null, counted = mapOf("deploy" to 9))
    assertEquals("1 call left today of 10", one.heroLine)
    val money = TafCard.merged("c", "Refunds", refunds.rules, null, used = mapOf("refund" to Money.eur(40.0)))
    assertEquals("${Money.eur(1_960.0).formatted} left today of ${Money.eur(2_000.0).formatted}", money.heroLine)
    // The limit is written as the language writes numbers, as the count is: never "of 10000".
    val big = TafCard("c", "Reads", rules = emptyList(), calls = bot.tafs.app.model.CardTotal(10_000, 1, Window.day))
    assertEquals("9,999 calls left today of 10,000", big.heroLine)
    assertEquals("calls left today of 10,000", big.hero!!.words)
    assertEquals("9,999", big.hero!!.figure)
  }

  @Test fun useKiffCouldNotReadIsNeverShownAsZero() {
    val card = TafCard.merged("c", "Refunds", refunds.rules, null, unknown = setOf("refund"))
    assertEquals("a day · use unavailable", card.hero!!.words)
    assertEquals(Money.eur(2_000.0).formatted, card.hero!!.figure)
  }

  @Test fun anAmountWithNoCurrencySaysSoOnceAfterTheWords() {
    val rule = CardRule(action = "send", label = "Sends", perWindow = Money.bare(30_000), window = Window.day)
    val card = TafCard.merged("c", "Sends", listOf(rule), null)
    assertTrue(card.hero!!.words.endsWith(" · no currency"))
    // Written in the app's language (English lines here), never the phone's region.
    assertEquals("30,000", card.hero!!.figure)
  }

  @Test fun aCardWithNoLimitsShowsItsFirstLinesInsteadOfAFigure() {
    val rules = noLimits
    val card = TafCard.merged("c", "Bill pay", rules, null)
    assertNull(card.hero)
    assertEquals(listOf("wires always asks you", "bank detail changes never", "1 more"), card.facts)
  }

  @Test fun theFaceNamesTheLimitForOneCallAndHowManyTools() {
    val card = TafCard.merged("c", "Refunds", refunds.rules, null)
    assertEquals(listOf("up to ${Money.eur(150.0).formatted} each; more asks you first", "3 tools"), card.facts)
  }

  @Test fun aTafWithoutKiffCardsDrawsItsLinesAsOneCard() {
    val taf = Taf(name = "Refunds", job = "", tools = emptyList(), colorIndex = 0, card = Card(refunds.rules, "You"))
    assertEquals(listOf("merged"), taf.shownCards.map { it.id })
  }

  @Test fun theCardsKeepTafsCloudsOrderTheAppSortsNothing() {
    val calls = TafCard("calls", "Calls", rules = emptyList(), calls = bot.tafs.app.model.CardTotal(10, 0, Window.day))
    val money = TafCard("money", "Money", rules = emptyList(), total = bot.tafs.app.model.CardTotal(Money.usd(1.0), Money.ZERO, Window.day))
    val test = TafCard("test", "Test", environment = "test", live = false, rules = emptyList(), total = bot.tafs.app.model.CardTotal(Money.usd(1.0), Money.ZERO, Window.day))
    val taf = Taf(name = "T", job = "", tools = emptyList(), colorIndex = 0, card = Card(emptyList(), "You"), kiffCards = listOf(test, calls, money))
    // tafs cloud puts first the Card that has used the most of its limit: the app keeps its order as sent.
    assertEquals(listOf("test", "calls", "money"), taf.shownCards.map { it.id })
  }

  @Test fun aTafThatHasUsedNothingReadsZeroInTheLimitsUnit() {
    val taf = Taf(name = "Refunds", job = "", tools = emptyList(), colorIndex = 0, card = Card(refunds.rules, "You"))
    val used = taf.usedOf(refunds.rules.first { it.action == "refund" })
    assertEquals(Money.eur(0.0), used)
    assertEquals("EUR", used.currency)
    assertTrue(!used.formatted.contains("no currency"))
  }

  @Test fun whatIsUsedReadsAsTheCardCountedIt() {
    val taf = Taf(name = "Refunds", job = "", tools = emptyList(), colorIndex = 0, card = Card(refunds.rules, "You"),
      used = mapOf("refund" to Money.eur(40.0)))
    assertEquals(Money.eur(40.0), taf.usedOf(refunds.rules.first { it.action == "refund" }))
  }

  // MARK: The sample's tafs (a new taf starts in Coach once signed in; the sample has no form for one)

  @Test fun eachSampleTafHasItsJobsCardIssuedByYouAndLoggedAsStarted() {
    val s = model().state.value
    assertEquals(TafTemplates.all.map { it.name }.toSet(), s.tafs.map { it.name }.toSet())
    s.tafs.forEach { taf ->
      assertEquals(TafTemplates.all.first { it.name == taf.name }.rules, taf.card.rules)
      assertEquals("You", taf.card.issuedBy)
      assertTrue("${taf.name} is logged as started", s.record.any { it.tafId == taf.id && it.kind == RecordEntry.Kind.started })
    }
  }

  @Test fun aSampleTafsCardEndsThreeMonthsOn() {
    model().state.value.tafs.forEach { taf ->
      val ends = taf.card.expires!!
      assertTrue(ends > t0.plusSeconds(88L * 86_400))
      assertTrue(ends < t0.plusSeconds(93L * 86_400))
    }
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
    assertTrue(line.detail.contains(if (waiting == 1) "1 waiting action refused" else "$waiting waiting actions refused"))
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
