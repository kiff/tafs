package bot.tafs.app

import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.views.windowWordsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The owner on Android 95: "No fingerprint for the next 4 minutes" showed on a card whose Approve took the fingerprint (the
 * tools and their Card, signed together). The window's words go only on a card tafs cloud marks a tap.
 */
class WindowWordsTest {
  private val words = "No fingerprint for the next 4 minutes"
  private fun windowWordsFor(c: SetupComponent, words: String?) = windowWordsFor(c, words, "s1", "s1")
  private fun card(signs: String?) = KiffClient.json.decodeFromString<SetupComponent>(
    """{"type":"approval","id":"a1","grant":"g1"${signs?.let { ",\"signs\":$it" } ?: ""}}""")

  @Test fun theWindowsWordsShowOnlyOnACardMarkedATap() {
    assertEquals(words, windowWordsFor(card("""{"card":false,"takes":"tap"}"""), words))
    assertNull(windowWordsFor(card("""{"card":true,"takes":"fingerprint"}"""), words))
  }

  // tafs cloud says nothing of the key (no mark, or a Card whose draft it couldn't read): the card promises no tap.
  @Test fun aCardWithNoKeySaidPromisesNoTap() {
    assertNull(windowWordsFor(card(null), words))
    assertNull(windowWordsFor(card("""{"card":true}"""), words))
  }

  @Test fun noWindowNoWords() {
    assertNull(windowWordsFor(card("""{"card":false,"takes":"tap"}"""), null))
  }

  // A window is one setup's: another setup's card asks for the fingerprint, and one opened outside a setup covers none.
  @Test fun theWordsShowOnlyForTheSetupWhoseStepOpenedTheWindow() {
    val tap = card("""{"card":true,"takes":"tap"}""")
    assertEquals(words, windowWordsFor(tap, words, "s1", "s1"))
    assertNull(windowWordsFor(tap, words, "s1", "s2"))
    assertNull(windowWordsFor(tap, words, null, "s1"))
  }

  // A card's buttons are held by its own approval, or by another card's still on its vendor's page; one the owner came back
  // from with no link holds nothing (it gives way, AppModel.giveWayToNewApproval).
  @Test fun anApprovalHoldsOtherCardsOnlyWhileItsVendorsPageIsOpen() {
    val open = bot.tafs.app.app.AppState(connecting = "g1")
    assertEquals(true, bot.tafs.app.views.approvalHeld(open, "g1"))
    assertEquals(true, bot.tafs.app.views.approvalHeld(open, "g2"))
    val left = open.copy(vendorPageLeft = true)
    assertEquals(true, bot.tafs.app.views.approvalHeld(left, "g1"))
    assertEquals(false, bot.tafs.app.views.approvalHeld(left, "g2"))
    assertEquals(false, bot.tafs.app.views.approvalHeld(bot.tafs.app.app.AppState(), "g2"))
  }
}
