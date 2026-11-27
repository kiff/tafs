package bot.tafs.app

import bot.tafs.app.kiff.AIConsent
import bot.tafs.app.kiff.AI_CONSENT_REQUIRED
import bot.tafs.app.kiff.KiffClient
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The contract with tafs cloud (contracts/README.md): tafs cloud's answers, as its own handler writes them
 * (runtime/test/contract_apps.test.ts), read here as the app reads them. An answer whose shape changed fails here, never first
 * on a phone.
 */
class ContractAppsTest {
  private fun cases(file: String): List<JsonObject> =
    KiffClient.json.parseToJsonElement(File("../../contracts/$file").readText()).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }

  /** One answer kept as a contract file (`{about, answer}`, runtime/test/contract.ts), as the text the app would read. */
  private fun answer(file: String): String =
    KiffClient.json.parseToJsonElement(File("../../contracts/$file").readText()).jsonObject["answer"]!!.toString()

  /**
   * A held call's arguments under Details, every case of contracts/held-arguments.json: the lines every app writes, exactly.
   * Nothing is ever shortened to "…" (red-team, 27 Nov: a held browse_act's steps showed as "steps: …").
   */
  @Test fun aHeldCallsArgumentsAreTheContractsLinesExactly() {
    cases("held-arguments.json").forEach { case ->
      val name = case["name"]!!.jsonPrimitive.content
      val text = """{"exceptions":[{"id":"ce_c","subject":"a","action":"x","entity_id":"e","created_at":"2026-11-27T16:00:00Z","arguments":${case["arguments"]}}]}"""
      val e = KiffClient.json.decodeFromString(bot.tafs.app.kiff.CardExceptions.serializer(), text).exceptions[0]
      val want = case["lines"]!!.jsonArray.map { it.jsonPrimitive.content }
      assertEquals(name, want, bot.tafs.app.kiff.KiffMapping.details(e))
    }
  }

  @Test fun homesRecipesReadAsTafsCloudSendsThem() {
    val read = KiffClient.json.decodeFromString(bot.tafs.app.kiff.Recipes.serializer(), answer("recipes-home.json"))
    assertTrue("Home leads with the jobs", read.home?.shown == true)
    assertTrue("Home's line", !read.home?.words.isNullOrBlank())
    assertTrue("the sheet's words", read.labels != null)
  }

  @Test fun theSetupAnswersBeforeCoachExistsRead() {
    for (file in listOf("setup-start.json", "setup-start-draft-alone.json", "setup-time-zone.json")) {
      val read = KiffClient.json.decodeFromString(bot.tafs.app.kiff.SetupResponse.serializer(), answer(file))
      assertTrue("$file: no Coach yet", read.setup == null)
    }
  }

  /** A card whose sign-in planque couldn't narrow carries tafs cloud's wider line (contracts/coach-wider.json): the card is where it's said. */
  @Test fun aWiderCardReadsItsLine() {
    val thread = KiffClient.json.decodeFromString(bot.tafs.app.kiff.WireThread.serializer(), answer("coach-wider.json"))
    val cards = thread.components.orEmpty().map { shown ->
      val wire = KiffClient.json.decodeFromJsonElement(bot.tafs.app.kiff.WireShown.serializer(), shown)
      KiffClient.json.decodeFromJsonElement(bot.tafs.app.kiff.SetupComponent.serializer(), wire.component)
    }
    val wider = cards.mapNotNull { it.wider }
    assertFalse("coach-wider.json: no card reads its wider line", wider.isEmpty())
    assertTrue(wider.all { it.isNotBlank() })
  }

  @Test fun theAiConsentReadsAsTafsCloudAnswersIt() {
    val all = cases("ai-consent.json")
    assertTrue("at least five cases", all.size >= 5)
    for (c in all) {
      val name = c["name"]!!.jsonPrimitive.content
      val status = c["status"]!!.jsonPrimitive.int
      val body = c["body"]!!.jsonObject
      val consent = KiffClient.json.decodeFromJsonElement(AIConsent.serializer(), body["consent"]!!)
      assertTrue("$name: the screen has its words", consent.drawable)
      when (status) {
        200 -> assertEquals(name, name == "given", consent.given)
        403 -> assertEquals(name, AI_CONSENT_REQUIRED, body["code"]!!.jsonPrimitive.content)
        409 -> assertFalse(name, consent.given)
        else -> fail("$name: an answer the app doesn't read ($status)")
      }
    }
  }
}
