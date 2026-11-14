package bot.tafs.app

import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.SetupSigns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** tafs cloud's `signs` mark on an approval card (docs/secure-connection.md): read as sent, and leniently. */
class SignsMarkTest {
  private fun card(signs: String) =
    KiffClient.json.decodeFromString(SetupComponent.serializer(), """{"type":"approval","id":"a1","grant":"g1","draft":"dr_1"$signs}""")

  @Test fun theMarkIsReadAsSent() {
    assertEquals(SetupSigns(card = true), card(""","signs":{"card":true}""").signs)
    assertEquals(SetupSigns(card = false, words = "Its Card: you approve it next"), card(""","signs":{"card":false,"words":"Its Card: you approve it next"}""").signs)
  }

  @Test fun noMarkOrOneOfAnotherShapeIsNoMarkAndTheCardStillReads() {
    assertNull(card("").signs)
    assertNull(card(""","signs":"yes"""").signs)
    assertNull(card(""","signs":[1]""").signs)
    assertEquals("a1", card(""","signs":7""").id)
  }
}
