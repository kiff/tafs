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
    // An empty list is planque saying nothing can be done: drawn as no action, not as the app's own row.
    assertTrue(grant(""","actions":[]""").actions!!.isEmpty())
  }
}
