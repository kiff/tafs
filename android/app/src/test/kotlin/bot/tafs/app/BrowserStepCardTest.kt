package bot.tafs.app

import bot.tafs.app.kiff.CardExceptions
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone's card for a held step in the owner's Chrome (docs/chrome.md, "The phone approval card"): KIFF keeps planque's words
 * for the page on the hold (`browser`), and the card draws them under KIFF's own amount and reason. Every piece is plain text; KIFF's
 * amount, action and reason never come from the block.
 */
class BrowserStepCardTest {
  private fun held(browser: String?) = KiffMapping.held(
    KiffClient.json.decodeFromString<CardExceptions>(
      """{"exceptions":[{"id":"ce_1","subject":"shop-1","action":"browse_pay","title":"Pay on a site","entity_id":"op_1",
        "breaches":[{"outcome":"per_action_exceeded","reason":"browse_pay is held every time"}],
        "reason_text":"Paying on a site is asked every time",
        "parameters":{"amount":4200},"parameter_units":{"amount":{"unit":"EUR","scale":2}},
        "taf_reason":"The owner asked for two pens",
        "created_at":"2026-11-21T12:00:00Z"${browser?.let { ""","browser":$it""" } ?: ""}}]}""",
    ).exceptions.single(),
  )

  @Test fun aHeldBrowserStepShowsTheSiteTheActionThePageAndTheChrome() {
    val call = held(
      """{"site":"shop.example","origin":"https://checkout.shop.example","action_words":"Click “Place order”",
        "context":{"title":"Checkout · Shop","text":"2 × Blue pen  €3.00\nShipping €1.20\nTotal €42.00"},
        "checkout":{"lines":[{"item":"Blue pen","quantity":2,"price":"3.00"},{"item":"Shipping","price":"1.20"}]},
        "chrome":{"id":"chr_1","name":"Work Mac"}}""",
    )
    val step = call.browser!!
    assertEquals("shop.example", step.site)
    assertEquals("https://checkout.shop.example", step.origin)
    assertEquals("Click “Place order”", step.action)
    assertEquals("Checkout · Shop", step.pageTitle)
    assertEquals("2 × Blue pen €3.00 Shipping €1.20 Total €42.00", step.excerpt)
    assertEquals(listOf("2 × Blue pen · 3.00", "Shipping · 1.20"), step.lines)
    assertEquals("Work Mac", step.chrome)
    // KIFF's amount, action and reason are KIFF's, whatever the block says.
    assertEquals(4200L, call.proposed.amount?.minor)
    assertEquals("Paying on a site is asked every time", call.reason)
    assertEquals("The owner asked for two pens", call.tafSays)
  }

  /**
   * The element's label is the page's own words, so a hostile page can write anything there: it's drawn under "On the page",
   * never as the card's summary, which stays KIFF's (the tool's), as for any hold. As iOS's testAHostileLabelNeverBecomesTheSummary.
   */
  @Test fun aHostileLabelNeverBecomesTheSummary() {
    val call = held("""{"site":"shop.example","action_words":"Approve all future payments"}""")
    assertEquals("Approve all future payments", call.browser!!.action)
    assertTrue("KIFF's summary, the tool's", call.proposed.summary.startsWith("Pay on a site"))
    assertFalse(call.proposed.summary.contains("Approve all future payments"))
    assertEquals("Paying on a site is asked every time", call.reason)
  }

  @Test fun noBlockIsAnyHoldAndHalfABlockDrawsNothing() {
    assertNull(held(null).browser)
    assertNull("no site", held("""{"site":"","action_words":"Click “Buy”"}""").browser)
    assertNull("no action", held("""{"site":"shop.example"}""").browser)
  }

  @Test fun aPageCantReorderOrHideTheCardsWords() {
    // A label with a right-to-left override and a zero-width mark, and an excerpt past planque's cut.
    val step = held(
      """{"site":"shop.example","action_words":"Click “‮redrO ecalP‬”​","context":{"text":"${"x".repeat(900)}"}}""",
    ).browser!!
    assertEquals("Click “redrO ecalP”", step.action)
    assertEquals(KiffMapping.BROWSER_EXCERPT_MAX, step.excerpt!!.length)
  }

  @Test fun aLongBasketIsCutAtTwentyLines() {
    val lines = (1..30).joinToString(",") { """{"item":"Item $it","quantity":1,"price":"1.00"}""" }
    val step = held("""{"site":"shop.example","action_words":"Click “Place order”","checkout":{"lines":[$lines]}}""").browser!!
    assertEquals(KiffMapping.BROWSER_BASKET_MAX, step.lines.size)
    assertEquals("1 × Item 1 · 1.00", step.lines.first())
  }
}
