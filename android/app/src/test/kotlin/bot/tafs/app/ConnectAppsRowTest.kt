package bot.tafs.app

import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Words
import bot.tafs.app.views.ConnectAppsWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Connect-apps row's second line and whose list it's in (docs/connect-apps.md): tafs cloud's `line` drawn as sent, else the
 * same shared words put together here; `method` wins over `how`; the account's own apps under Yours, the rest under Discover.
 */
class ConnectAppsRowTest {
  private fun app(json: String) = KiffClient.json.decodeFromString(ConnectApps.App.serializer(), json)

  @Test fun tafsCloudsLineIsDrawnAsSent() {
    val a = app("""{"entry":"stripe@1","title":"Stripe","state":"connected","method":"sign_in","line":"Sign in · Connected · Used by Refunds","whose":"Company","used_by":[{"taf":"t1","name":"Refunds"}]}""")
    assertEquals("Sign in · Connected · Used by Refunds", ConnectAppsWords.secondLine(a, listOf("Refunds")))
    assertEquals(listOf("Refunds"), a.usedBy?.map { it.name })
    assertEquals("Company", a.whose)
  }

  @Test fun withoutALineTheSameWordsArePutTogetherHere() {
    val key = app("""{"entry":"intercom","title":"Intercom","how":"oauth","method":"key","state":"connected","whose":"Company"}""")
    // method wins over how.
    assertTrue(key.byKey)
    assertEquals(
      listOf(Words.Connect.How.key, "Company", Words.Connect.Apps.usedBy(tafs = "Refunds, Tickets")).joinToString(" · "),
      ConnectAppsWords.secondLine(key, listOf("Refunds", "Tickets")),
    )
    // A tafs cloud from before method and line: how alone, and nothing about whose.
    val old = app("""{"entry":"notion@1","title":"Notion","how":"oauth"}""")
    assertFalse(old.byKey)
    assertEquals(Words.Connect.How.signIn, ConnectAppsWords.secondLine(old, emptyList()))
  }

  @Test fun theAccountsOwnAreYoursAndTheRestDiscover() {
    assertTrue(app("""{"entry":"a","state":"connected"}""").yours)
    assertTrue(app("""{"entry":"b","state":"needs_reconnect"}""").yours)
    assertTrue(app("""{"entry":"c","state":"signed_in"}""").yours)
    assertFalse(app("""{"entry":"d","state":"available"}""").yours)
    assertFalse(app("""{"entry":"e","state":"needs_setup"}""").yours)
  }
}
