package bot.tafs.app

import bot.tafs.app.model.CardCheck
import bot.tafs.app.model.Decision
import bot.tafs.app.model.Money
import bot.tafs.app.model.ProposedAction
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafTemplates
import bot.tafs.app.model.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardCheckTest {
  private fun taf(key: String) = TafTemplates.template(key)!!.let {
    Taf(name = it.name, job = it.job, tools = it.tools, colorIndex = 0, card = Card(it.rules, "You"))
  }

  @Test fun withinTheCardIsAllowed() {
    val d = CardCheck.decide(ProposedAction("refund", "Refund order #4471", Money.usd(40.0)), taf("refunds"))
    assertEquals(Decision.Allowed, d)
  }

  @Test fun overTheLimitForOneCallIsHeld() {
    val d = CardCheck.decide(ProposedAction("refund", "Refund order #4502", Money.usd(480.0)), taf("refunds"))
    assertTrue(d is Decision.Held)
    assertTrue((d as Decision.Held).reason.startsWith("Over"))
  }

  @Test fun aNeverRuleIsRefusedWithoutAsking() {
    val d = CardCheck.decide(ProposedAction("payout", "Pay out", Money.usd(900.0)), taf("refunds"))
    assertEquals(Decision.Refused("Payouts: never allowed"), d)
  }

  @Test fun anActionNotOnTheCardIsRefused() {
    val d = CardCheck.decide(ProposedAction("wire", "Wire"), taf("refunds"))
    assertEquals(Decision.Refused("Not on this taf's Card"), d)
  }

  @Test fun alwaysAsksIsHeld() {
    val d = CardCheck.decide(ProposedAction("migration", "Run migration"), taf("deploys"))
    assertTrue(d is Decision.Held)
  }

  @Test fun theWindowTotalCountsWhatIsUsed() {
    var t = taf("refunds")
    // 2 000 a day, 150 each: thirteen at 150 use 1 950, so the next 100 passes the total.
    repeat(13) { t = CardCheck.draw(ProposedAction("refund", "r", Money.usd(150.0)), t) }
    val d = CardCheck.decide(ProposedAction("refund", "r", Money.usd(100.0)), t)
    assertTrue(d is Decision.Held)
    assertTrue((d as Decision.Held).reason.contains("a day"))
  }

  @Test fun aCountLimitHoldsTheOneOverIt() {
    var t = taf("deploys")
    repeat(10) { t = CardCheck.draw(ProposedAction("deploy", "d"), t) }
    assertTrue(CardCheck.decide(ProposedAction("deploy", "d"), t) is Decision.Held)
  }

  @Test fun aRevokedCardRefusesEverything() {
    val t = taf("refunds").let { it.copy(card = it.card.copy(revoked = true)) }
    assertEquals(Decision.Refused("Card revoked"), CardCheck.decide(ProposedAction("refund", "r", Money.usd(1.0)), t))
  }
}
