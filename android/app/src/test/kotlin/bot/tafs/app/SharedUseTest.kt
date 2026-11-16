package bot.tafs.app

import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A person a taf is shared with sees the account by its name, never a person: who issued its Card reads as the account. */
class SharedUseTest {
  private val refunds = Taf(id = "taf_1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1, card = Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "taf_1")
  private val acme = Account(tenantId = "t_01", slug = "acme", displayName = "Acme Pay")

  @Test fun onATafThePersonManagesTheCardIsIssuedByThem() {
    val s = AppState(mode = Mode.kiff, kiffAccount = acme, tafs = listOf(refunds), access = mapOf("taf_1" to Access(use = true, manage = true)))
    assertEquals("You", s.issuer(refunds))
  }

  @Test fun onATafSharedWithThemTheCardIsIssuedByTheAccount() {
    val s = AppState(mode = Mode.kiff, kiffAccount = acme, tafs = listOf(refunds), access = mapOf("taf_1" to Access(use = true)))
    assertEquals("Acme Pay", s.issuer(refunds))
    assertEquals("The account", s.copy(kiffAccount = null).issuer(refunds))
  }

  @Test fun theSampleKeepsItsOwnIssuer() {
    assertEquals("You", AppState(mode = Mode.sample, tafs = listOf(refunds)).issuer(refunds))
  }

  private val quotes = refunds.copy(id = "taf_2", name = "Quotes", cloudId = "taf_2")
  private val payouts = refunds.copy(id = "taf_3", name = "Payouts", cloudId = "taf_3")

  // Home lists the person's own tafs, then those shared with them to use, as iPhone's tafSections.
  @Test fun homeListsTheirOwnTafsThenTheOnesSharedWithThem() {
    val s = AppState(
      mode = Mode.kiff, kiffAccount = acme, tafs = listOf(refunds, quotes, payouts),
      access = mapOf("taf_1" to Access(use = true, manage = true), "taf_2" to Access(use = true), "taf_3" to Access(use = true, oversee = true, manage = true)),
    )
    // Home's ordered, filtered list, as HomeView hands it over: each section keeps that order.
    val (own, shared) = s.sections(listOf(payouts, quotes, refunds))
    assertEquals(listOf("taf_3", "taf_1"), own.map { it.id })
    assertEquals(listOf("taf_2"), shared.map { it.id })
    // Filtered to one label, only what the filter kept is split.
    assertEquals(Pair(listOf(refunds), emptyList<Taf>()), s.sections(listOf(refunds)))
    assertTrue(s.sharedWithMe("taf_2"))
    assertFalse(s.sharedWithMe("taf_1"))
  }

  // Before KIFF has answered for a taf, it isn't "shared": it stays among their own rather than jump once the answer comes.
  @Test fun aTafKiffHasntAnsweredForYetIsntShared() {
    val s = AppState(mode = Mode.kiff, kiffAccount = acme, tafs = listOf(refunds, quotes), access = mapOf("taf_2" to Access(use = true)))
    assertFalse(s.sharedWithMe("taf_1"))
    assertEquals(listOf("taf_1"), s.sections(listOf(refunds, quotes)).first.map { it.id })
  }

  // The sample has no KIFF answer and nothing shared.
  @Test fun theSampleSharesNothing() {
    val s = AppState(mode = Mode.sample, tafs = listOf(refunds, quotes), access = mapOf("taf_2" to Access(use = true)))
    assertFalse(s.sharedWithMe("taf_2"))
    assertEquals(emptyList<Taf>(), s.sections(listOf(refunds, quotes)).second)
  }
}
