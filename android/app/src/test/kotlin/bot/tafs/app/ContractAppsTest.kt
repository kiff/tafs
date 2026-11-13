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
