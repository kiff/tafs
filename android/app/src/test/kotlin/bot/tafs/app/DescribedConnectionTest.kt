package bot.tafs.app

import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.KiffClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A connection as planque describes it ("Your connections"): its title, who uses it, its health and the actions that can work for
 * it. The app draws only these; it reads them leniently, so a shape this build doesn't know is none, never a list that can't be read.
 */
class DescribedConnectionTest {
  private fun grant(extra: String) = KiffClient.json.decodeFromString<Grant>("""{"id":"g1","status":"needs_reconnect","request":{"server":"s"}$extra}""")

  @Test fun planquesTitleUsersHealthAndActionsAreRead() {
    val g = grant(""","title":"Gmail · name@example.com","used_by":["Mail"],"health":{"state":"signed_out","ok":false,"words":"Gmail's sign-in expired on 14 Nov","at":"2026-11-14T00:12:00Z"},""" +
      """"actions":[{"id":"sign_in_again","words":"Sign in again","how":"approve"},{"id":"remove","words":"Remove access","how":"delete","stops":"Mail will stop using Gmail."}]""")
    assertEquals("Gmail · name@example.com", g.title)
    assertEquals(listOf("Mail"), g.usedBy)
    assertEquals("Gmail's sign-in expired on 14 Nov", g.health?.words)
    assertFalse(g.health!!.ok)
    assertEquals(listOf("sign_in_again", "remove"), g.actions?.map { it.id })
    assertEquals("Mail will stop using Gmail.", g.actions?.last()?.stops)
  }

  @Test fun anActionThisBuildCantDoOrWithNoWordsIsLeftOutKeepingTheOrder() {
    val g = grant(""","actions":[{"id":"x","words":"Teleport","how":"teleport"},{"id":"remove","words":"Remove access","how":"delete"},{"id":"y","words":"","how":"approve"},"oops"]""")
    assertEquals(listOf("remove"), g.actions?.map { it.id })
  }

  @Test fun aPlanqueBeforeItSendsNoneAndAnUnknownShapeIsNone() {
    val before = grant("")
    assertNull("no actions: the app's own row stands in", before.actions)
    assertNull(before.health)
    val odd = grant(""","health":"fine","actions":{"a":1}""")
    assertNull(odd.health)
    assertNull(odd.actions)
    // An empty list is planque saying nothing can be done.
    assertTrue(grant(""","actions":[]""").actions!!.isEmpty())
  }

  /** planque's row needs its health words and its actions, as iOS reads it; else the app's own row, with Cancel or Disconnect. */
  @Test fun aRequestNobodyAnsweredWithNoHealthIsTheAppsRowWithItsCancel() {
    val health = ""","health":{"state":"signed_out","ok":false,"words":"Gmail's sign-in expired"}"""
    assertTrue(grant(health + ""","actions":[]""").rowFromPlanque)
    val waiting = KiffClient.json.decodeFromString<Grant>("""{"id":"g2","status":"pending","request":{"server":"s"},"actions":[]}""")
    assertFalse("no health words: the app's own row", waiting.rowFromPlanque)
    assertTrue("and it ends with Cancel", waiting.cancellable)
    assertFalse(grant(""","health":{"state":"ok","ok":true,"words":""},"actions":[]""").rowFromPlanque)
    assertFalse(grant(health).rowFromPlanque)
  }

  /** A connection in the owner's Chrome says which one, by planque's name for it; nothing for any other connection. */
  @Test fun aConnectionInTheOwnersChromeSaysWhichOne() {
    fun g(browser: String) = bot.tafs.app.kiff.KiffClient.json.decodeFromString(bot.tafs.app.kiff.Grant.serializer(),
      """{"id":"g1","status":"connected","text":"","digest":"d","request":{"server":"s","agent":"a","tools":[]$browser}}""")
    val inChrome = g(""","browser":{"driver":"extension","chrome":"chr_1","chrome_name":"Work Mac","sites":["wikipedia.org"]}""")
    assertEquals("Work Mac", inChrome.request.browser?.chromeName)
    assertEquals(bot.tafs.app.model.Words.Chrome.heldIn(name = "Work Mac"), bot.tafs.app.views.connectionChromeLine(inChrome))
    assertNull("planque's hosted browser", bot.tafs.app.views.connectionChromeLine(g(""","browser":{"sites":["wikipedia.org"]}""")))
    assertNull("no name sent", bot.tafs.app.views.connectionChromeLine(g(""","browser":{"driver":"extension","chrome":"chr_1","chrome_name":"  "}""")))
    assertNull("not a browser connection", bot.tafs.app.views.connectionChromeLine(g("")))
  }

  /** Use another Chrome lists every paired Chrome but the one the connection runs in, in planque's order. */
  @Test fun anotherChromeIsAnyPairedChromeButItsOwn() {
    val g = bot.tafs.app.kiff.KiffClient.json.decodeFromString(bot.tafs.app.kiff.Grant.serializer(),
      """{"id":"g1","status":"connected","text":"","digest":"d","request":{"server":"s","agent":"a","tools":[],"browser":{"driver":"extension","chrome":"chr_b","sites":["wikipedia.org"]}}}""")
    val chromes = listOf(bot.tafs.app.kiff.Chrome("chr_a", "A"), bot.tafs.app.kiff.Chrome("chr_b", "B"), bot.tafs.app.kiff.Chrome("chr_c", "C"))
    assertEquals(listOf("chr_a", "chr_c"), bot.tafs.app.views.chromesToMoveTo(g, chromes).map { it.id })
    assertEquals(emptyList<bot.tafs.app.kiff.Chrome>(), bot.tafs.app.views.chromesToMoveTo(g, null))
  }
}
