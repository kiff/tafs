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

  /**
   * One of the account's own says who uses it alone, in tafs cloud's words, since its status and action are beside it: never
   * "Sign in" twice. An app to connect, or a tafs cloud that couldn't read who uses it, keeps the line as sent.
   */
  @Test fun theAccountsOwnSayWhoUsesThemAlone() {
    val unused = app("""{"entry":"connector:p:pipedrive","title":"Pipedrive","state":"signed_in","line":"Sign in · Signed in · Not used by any taf","used_by_words":"Not used by any taf","used_by":[]}""")
    assertEquals("Not used by any taf", ConnectAppsWords.secondLine(unused, emptyList()))
    val used = app("""{"entry":"stripe@3","title":"Stripe","state":"connected","line":"Sign in · Connected · Used by Rae","used_by_words":"Used by Rae"}""")
    assertEquals("Used by Rae", ConnectAppsWords.secondLine(used, listOf("Rae")))
    // Known, but only a taf this person can't see uses it: how it connects and whose it is, never the status twice.
    val unseen = app("""{"entry":"stripe@3","title":"Stripe","state":"connected","method":"sign_in","whose":"Company","line":"Sign in · Connected","used_by_words":"","used_by":[]}""")
    assertEquals(listOf(Words.Connect.How.signIn, "Company").joinToString(" · "), ConnectAppsWords.secondLine(unseen, emptyList()))
    // Unknown: the line as sent.
    val unknown = app("""{"entry":"stripe@3","title":"Stripe","state":"connected","line":"Sign in · Connected"}""")
    assertEquals("Sign in · Connected", ConnectAppsWords.secondLine(unknown, emptyList()))
    // Not the account's: the line, whatever else is sent.
    val other = app("""{"entry":"connector:p:typeform","title":"Typeform","state":"available","line":"Sign in","used_by_words":"Not used by any taf"}""")
    assertEquals("Sign in", ConnectAppsWords.secondLine(other, emptyList()))
  }

  @Test fun withoutALineTheSameWordsArePutTogetherHere() {
    val key = app("""{"entry":"intercom","title":"Intercom","how":"oauth","method":"key","state":"connected","whose":"Company"}""")
    // method wins over how.
    assertTrue(key.byKey)
    assertEquals(
      listOf(Words.Connect.How.key, "Company", Words.Connect.Status.connected, Words.Connect.Apps.usedBy(tafs = bot.tafs.app.model.Res.list(listOf("Refunds", "Tickets")))).joinToString(" · "),
      ConnectAppsWords.secondLine(key, listOf("Refunds", "Tickets")),
    )
    // To sign in again says so; an app that isn't yours is used by no taf.
    val out = app("""{"entry":"gmail","title":"Gmail","state":"needs_reconnect"}""")
    assertEquals(listOf(Words.Connect.How.signIn, Words.Connect.signInAgain).joinToString(" · "), ConnectAppsWords.secondLine(out, emptyList()))
    val fresh = app("""{"entry":"slack","title":"Slack","state":"available","method":"key"}""")
    assertEquals(Words.Connect.How.key, ConnectAppsWords.secondLine(fresh, listOf("Leads")))
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
