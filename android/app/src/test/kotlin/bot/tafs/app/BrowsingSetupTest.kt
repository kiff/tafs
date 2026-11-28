package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.TafSetup
import bot.tafs.app.model.Res
import bot.tafs.app.views.BrowsingSection
import bot.tafs.app.views.ConnectedSiteRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A taf's setup and its websites (docs/api.md, "The two ways" and "Coach's pick, and why"): each browser connection's way and
 * its sites' health, and the taf's browsing with the other way's button, all in tafs cloud's words. The button only writes
 * tafs cloud's ask into Coach; the app chooses no way and works out no health.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h900dp-port")
class BrowsingSetupTest {
  @get:Rule val compose = createComposeRule()

  private val setup = """{"connections":[{"id":"g_7","name":"Browser","status":"connected","way":"cloud",
    "way_words":{"title":"Cloud browser","line":"Works while your computer is off."},
    "sites":[{"site":"supplier-portal.example","health":{"state":"needs_you_soon","needs_you_by":"2026-12-02T09:12:00Z",
      "words":{"line":"Needs you by Tue 2 Dec","why":"At 07:00, tafs opened one page of the site with your saved sign-in."}}},
      {"site":"books.example","health":{"state":"signed_in","words":{"line":"Signed in · checked today 07:00"}}},
      {"site":"blank.example","health":{"state":"unknown","words":{"line":""}}},{"site":""},{"bad":true}]},
    {"id":"g_8","name":"My Chrome","status":"connected","way":"chrome","way_words":{"title":"Chrome extension","line":"Works in your Chrome."},
      "chrome_name":"Chrome on the office Mac"}],
    "browsing":{"sites":["supplier-portal.example"],"way":"cloud","way_words":{"title":"Cloud browser","line":"Works while your computer is off."},
      "reason":"schedule","why":"Coach chose the cloud browser because File invoices runs at 08:00, when you may be away.","chosen_by":"coach",
      "switch":{"words":{"button":"Use your Chrome instead"},"ask":"Change File invoices to use my Chrome for supplier-portal.example."}}}"""

  @Before fun words() = Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))

  @Test fun eachConnectionCarriesItsWayAndEachSiteItsHealthAsSent() {
    val read = KiffClient.json.decodeFromString<TafSetup>(setup)
    val cloud = read.connections[0]
    assertEquals("cloud", cloud.way)
    assertEquals("Cloud browser", cloud.wayWords?.title)
    // A site without its name is left out; a health without its line is none, and the site still shows.
    assertEquals(listOf("supplier-portal.example", "books.example", "blank.example"), cloud.sites.map { it.site })
    assertTrue(cloud.sites[0].health!!.needsYou)
    assertFalse(cloud.sites[1].health!!.needsYou)
    assertNull(cloud.sites[2].health)
    assertEquals("Chrome on the office Mac", read.connections[1].chromeName)
    assertEquals("Use your Chrome instead", read.browsing?.switch?.words?.button)
  }

  @Test fun aSetupFromBeforeOrWithAnUnreadableBrowsingStillReads() {
    val before = KiffClient.json.decodeFromString<TafSetup>("""{"connections":[{"id":"g_1","name":"Stripe","status":"connected"}]}""")
    assertNull(before.browsing)
    assertNull(before.connections.single().wayWords)
    assertTrue(before.connections.single().sites.isEmpty())
    val odd = KiffClient.json.decodeFromString<TafSetup>(
      """{"connections":[],"browsing":{"way":"cloud","way_words":{"title":""},"switch":{"words":{"button":"x"},"ask":""}}}""",
    )
    assertNull(odd.browsing)
    val noAsk = KiffClient.json.decodeFromString<TafSetup>(
      """{"browsing":{"way":"cloud","way_words":{"title":"Cloud browser"},"switch":{"words":{"button":"Use your Chrome instead"}}}}""",
    )
    assertEquals("Cloud browser", noAsk.browsing?.wayWords?.title)
    assertNull(noAsk.browsing?.switch)
  }

  @Test fun theSwitchWritesTafsCloudsAskIntoCoachAndNothingElse() {
    val browsing = KiffClient.json.decodeFromString<TafSetup>(setup).browsing!!
    val asked = mutableListOf<String>()
    compose.setContent { TafsTheme { BrowsingSection(browsing, onSwitch = { asked += it }) } }
    compose.onNodeWithText("Coach chose the cloud browser because File invoices runs at 08:00, when you may be away.").assertIsDisplayed()
    compose.onNodeWithTag("taf-browsing-switch").performClick()
    assertEquals(listOf("Change File invoices to use my Chrome for supplier-portal.example."), asked)
  }

  @Test fun someoneWhoMayNotAskCoachSeesTheWayButNoButton() {
    val browsing = KiffClient.json.decodeFromString<TafSetup>(setup).browsing!!
    compose.setContent { TafsTheme { BrowsingSection(browsing, onSwitch = null) } }
    compose.onNodeWithText("Cloud browser").assertIsDisplayed()
    compose.onNodeWithTag("taf-browsing-switch").assertDoesNotExist()
  }

  @Test fun aSiteThatNeedsYouSaysWhyAndOneSignedInDoesNot() {
    val sites = KiffClient.json.decodeFromString<TafSetup>(setup).connections[0].sites
    compose.setContent { TafsTheme { androidx.compose.foundation.layout.Column { sites.take(2).forEach { ConnectedSiteRow("g_7", it) } } } }
    compose.onNodeWithText("Needs you by Tue 2 Dec").assertIsDisplayed()
    compose.onNodeWithText("At 07:00, tafs opened one page of the site with your saved sign-in.").assertIsDisplayed()
    compose.onNodeWithText("Signed in · checked today 07:00").assertIsDisplayed()
    compose.onNodeWithTag("connection-site-g_7-books.example").assertExists()
  }
}
