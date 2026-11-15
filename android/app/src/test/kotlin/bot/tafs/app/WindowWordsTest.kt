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
}
