package bot.tafs.app

import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import org.junit.Assert.assertEquals
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
}
