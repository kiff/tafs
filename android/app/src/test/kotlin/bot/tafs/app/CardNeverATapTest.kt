package bot.tafs.app

import bot.tafs.app.kiff.SetupComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import bot.tafs.app.model.CoachWords
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A card whose step confirms a draft grants a Card: its Approve always names the fingerprint, never a tap in a window. */
class CardNeverATapTest {
  private val words = "No fingerprint for the next 4 minutes"

  private fun signs(json: String): JsonElement = Json.parseToJsonElement(json)

  @Test fun aRoutineTapCardReadsAsATapWhileAWindowIsHeld() {
    val c = SetupComponent(type = "approval", id = "ap-1", signsRead = signs("""{"takes":"tap"}"""))
    assertTrue(CoachWords.approvesAsTap(c, words))
    assertFalse("no window, no tap", CoachWords.approvesAsTap(c, null))
  }

  @Test fun aCardThatConfirmsADraftIsNeverATapEvenMarkedOne() {
    val c = SetupComponent(type = "approval", id = "ap-2", signsRead = signs("""{"card":true,"takes":"tap"}"""))
    assertFalse(CoachWords.approvesAsTap(c, words))
  }
}
