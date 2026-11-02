package bot.tafs.app

import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.ProfileWords
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KIFF's words for a connection's profile, against KIFF's reference vectors: the phone's digests match KIFF's byte for byte, and
 * words that don't hash to their digest are never shown.
 *
 * The files in `test/resources/profile` are KIFF's `kiff.profile.v1` / `kiff.words.v1` vectors: each `<name>.json` is a profile,
 * and `<name>.expected.json` is KIFF's answer for it, its words and both digests. They're synthetic, and kept exactly as KIFF
 * publishes them.
 */
class ProfileWordsTest {
  private val vectors = listOf("gmail-drafts", "holded-invoices", "notion-pages", "slack-messages", "stripe-refunds")

  private fun read(name: String): JsonObject =
    KiffClient.json.parseToJsonElement(javaClass.classLoader!!.getResource("profile/$name.json")!!.readText()).jsonObject

  @Test fun everyVectorsDigestsAreTheOnesKiffComputed() {
    vectors.forEach { name ->
      val profile = read(name)
      val expected = read("$name.expected")
      assertEquals("$name: profile_sha256", expected["profile_sha256"]!!.jsonPrimitive.content, ProfileWords.digest(profile))
      assertEquals("$name: words_sha256", expected["words_sha256"]!!.jsonPrimitive.content, ProfileWords.digest(expected["words"]!!))
    }
  }

  @Test fun kiffsAnswerIsCheckedAndItsLinesKeptVerbatimInOrder() {
    vectors.forEach { name ->
      val expected = read("$name.expected")
      val words = ProfileWords.checked(read(name), expected)
      val lines = expected["words"]!!.jsonObject["lines"]!!.jsonArray.map { it.jsonObject }
      assertEquals(name, lines.map { it["text"]!!.jsonPrimitive.content }, words.lines.map { it.text })
      assertEquals(name, lines.map { it["kind"]!!.jsonPrimitive.content }, words.lines.map { it.kind })
      assertEquals(name, expected["words_sha256"]!!.jsonPrimitive.content, words.wordsSha256)
    }
  }

  @Test fun theApprovalCarriesBothDigests() {
    val expected = read("stripe-refunds.expected")
    val approval = ProfileWords.checked(read("stripe-refunds"), expected).approval()
    assertEquals(expected["profile_sha256"], approval["profile_sha256"])
    assertEquals(expected["words_sha256"], approval["words_sha256"])
  }

  @Test fun wordsThatDontMatchTheirDigestAreRefused() {
    val profile = read("stripe-refunds")
    val expected = read("stripe-refunds.expected")
    val words = expected["words"]!!.jsonObject
    val lines = words["lines"]!!.jsonArray
    // One word changed in transit: "pays out" softened.
    val first = lines[2].jsonObject
    val softened = JsonObject(first + ("text" to JsonPrimitive("Refund a payment: records a refund in Stripe.")))
    val changed = JsonObject(expected + ("words" to JsonObject(words + ("lines" to kotlinx.serialization.json.JsonArray(lines.toMutableList().also { it[2] = softened })))))
    assertThrows(ProfileWords.Mismatch::class.java) { ProfileWords.checked(profile, changed) }
  }

  @Test fun wordsForAnotherProfileAreRefused() {
    // The words KIFF gave for Slack, offered for Stripe's profile.
    assertThrows(ProfileWords.Mismatch::class.java) { ProfileWords.checked(read("stripe-refunds"), read("slack-messages.expected")) }
  }

  @Test fun wordsOfAnotherVersionAreRefused() {
    val expected = read("gmail-drafts.expected")
    val words = expected["words"]!!.jsonObject
    val v2 = JsonObject(expected + ("words" to JsonObject(words + ("v" to JsonPrimitive("kiff.words.v2")))))
    val e = assertThrows(ProfileWords.Mismatch::class.java) { ProfileWords.checked(read("gmail-drafts"), v2) }
    assertTrue(e.message!!.contains("Nothing was approved"))
  }
}
