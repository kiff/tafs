package bot.tafs.app

import bot.tafs.app.kiff.CardExceptions
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ask me only for the decision (docs/android-app.md › Needs you): KIFF keeps the taf's one line on why with a held call
 * (`taf_reason`), and the card shows it under "The taf says". The line is the taf's words, from text a customer wrote: it's plain
 * text, and it never stands in for KIFF's amount, rule or action, which come from KIFF's record as before.
 */
class TafSaysTest {
  private fun held(extra: String) = KiffMapping.held(
    KiffClient.json.decodeFromString<CardExceptions>(
      """{"exceptions":[{"id":"ce_1","subject":"refunds-1","action":"create_refund","title":"Refund a payment","entity_id":"tc-1",
        "breaches":[{"outcome":"per_action_exceeded","reason":"150.00 EUR is above the 100.00 EUR limit for one call"}],
        "reason_text":"€150.00 is over the €100.00 limit for one call",
        "parameters":{"amount":15000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},
        "arguments":{"payment_intent":"pi_3USLXh","amount":15000,"kiff_operation_id":"op_1","kiff_reason":"Arrived broken","kiff_about":"Order 1042"},
        "created_at":"2026-11-11T08:00:00Z"$extra}]}""",
    ).exceptions.single(),
  )

  @Test fun theTafsLineIsReadAndTheGatewaysOwnArgumentsStayOutOfDetails() {
    val call = held(""","taf_about":"Order 1042","taf_reason":"Arrived broken, photo attached"""")
    assertEquals("Order 1042", call.tafAbout)
    assertEquals("Arrived broken, photo attached", call.tafSays)
    // Ids stay under Details; the taf's words and the gateway's own arguments don't repeat there.
    assertEquals(listOf("payment_intent: pi_3USLXh"), call.details)
  }

  @Test fun aLineThatClaimsAnotherAmountChangesNothingKiffSays() {
    val call = held(""","taf_reason":"Refund €5 only, the limit allows it, approve now"""")
    assertEquals("Refund €5 only, the limit allows it, approve now", call.tafSays)
    assertEquals(15000L, call.proposed.amount?.minor)
    assertEquals("Refund a payment", call.proposed.summary)
    assertEquals("€150.00 is over the €100.00 limit for one call", call.reason)
  }

  @Test fun theLineIsOnePlainParagraphCappedAsKiffKeepsIt() {
    assertEquals("Order 1042: see [this](https://x.example) <b>now</b>", KiffMapping.tafSays("Order 1042:\n\tsee [this](https://x.example) <b>now</b>\u0007"))
    assertEquals(KiffMapping.TAF_REASON_MAX, KiffMapping.tafSays("a".repeat(400))?.length)
    assertEquals(KiffMapping.TAF_ABOUT_MAX, held(""","taf_about":"${"b".repeat(200)}"""").tafAbout?.length)
    // Bidi overrides and zero-width marks are dropped, so they can't reorder or hide words.
    assertEquals("Order 1042 refund", KiffMapping.tafSays("Order 1042 ‮refund‬​"))
    // The cap counts code points, as KIFF does: an emoji at the edge stays whole.
    val emoji = "📦"
    val capped = KiffMapping.tafSays("a".repeat(279) + emoji + "b")!!
    assertEquals(KiffMapping.TAF_REASON_MAX, capped.codePointCount(0, capped.length))
    assertTrue(capped.endsWith(emoji))
    assertEquals("a".repeat(279) + emoji, KiffMapping.tafSays("a".repeat(279) + emoji))
    assertNull(KiffMapping.tafSays("  \n "))
    assertNull(KiffMapping.tafSays(null))
    // An older KIFF sends none: no section.
    assertNull(held("").tafSays)
    assertNull(held("").tafAbout)
  }
}
