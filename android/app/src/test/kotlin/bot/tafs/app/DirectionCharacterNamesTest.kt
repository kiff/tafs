package bot.tafs.app

import bot.tafs.app.kiff.Home
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.kiff.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * iOS's #1547, on Android (#1552's half): a vendor's own message quoted into the record, a tool's or vendor's
 * name in words, and a taf's name, job and status lines from tafs cloud carry no direction character either, so every line
 * `KiffMapping` builds from a server value is clean.
 */
class DirectionCharacterNamesTest {
  private fun hasDirection(s: String?) = s != null && s.codePoints().anyMatch(KiffMapping::isDirection)

  @Test fun quotedMessagesAndNamesCarryNone() {
    assertEquals("Card declined", KiffMapping.quotable("Card‮ declined"))
    assertEquals("create refund", KiffMapping.toolWords("stripe.create⁦_refund"))
    assertEquals("Stripe", KiffMapping.vendorWords("mcp.str‮ipe.com"))
    assertEquals("Pay now", KiffMapping.name("x", "Pay‮ now"))
    val (_, words) = KiffMapping.executed(ToolOutcome("not_sent", message = "Bank‮ said no"), approved = false)
    assertFalse(words, hasDirection(words))
  }

  @Test fun aTafsNameJobAndLinesCarryNone() {
    val home = KiffClient.json.decodeFromString<Home>("""
      {"tafs":[
        {"id":"t1","name":"Ra‮e","kiff_agent":"rae-1","color":1,"state":"working","next_run_words":"Next⁦ run at 10:00",
         "job_summary":"Settles‮ refunds","card":{"ids":["c1"],"active":true}},
        {"id":"t2","name":"Desk","kiff_agent":"desk-1","color":1,"state":"working","card":{"ids":["c2"],"active":true},
         "work":{"status":"waiting_on_app","line":"The desk‮ answers","needs_you":0}}],
       "others":[]}
    """.trimIndent())
    val (rae, desk) = home.tafs.map { KiffMapping.taf(it, "You") }
    assertEquals("Rae", rae.name)
    assertEquals("Settles refunds", rae.job)
    assertEquals("Next run at 10:00", rae.now)
    assertEquals("Next run at 10:00", rae.nextRunWords)
    assertEquals("The desk answers", desk.now)
  }
}
