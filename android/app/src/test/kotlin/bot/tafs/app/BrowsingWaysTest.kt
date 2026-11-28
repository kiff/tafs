package bot.tafs.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Res
import bot.tafs.app.views.WayCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How a taf uses websites (docs/api.md, "The two ways"): Connect apps' read carries both ways at the account level, each in tafs
 * cloud's words. The app reads each way on its own and draws its words as they come; it works out nothing about either.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h900dp-port")
class BrowsingWaysTest {
  @get:Rule val compose = createComposeRule()

  private val read = """{"apps":[],"ways":[
    {"way":"cloud","words":{"title":"Cloud browser","line":"Works while your computer is off.",
      "facts":["A fresh browser for each run, only on the sites you allow.","You sign in once, in a live view. Your password is never kept."],
      "status":"2 sign-ins saved"}},
    {"way":"chrome","words":{"title":"Chrome extension","line":"Works in your Chrome, where you're signed in.",
      "facts":["Uses the sign-ins already in your Chrome. Your passwords stay there."]}},
    {"way":"later","words":{"title":""}},
    {"bad":true}]}"""

  @Test fun bothWaysAreReadOneAtATimeAndOneWithoutATitleIsLeftOut() {
    val ways = KiffClient.json.decodeFromString<ConnectApps>(read).ways
    assertEquals(listOf("cloud", "chrome"), ways.map { it.way })
    assertEquals("2 sign-ins saved", ways[0].words.status)
    assertNull(ways[1].words.status)
  }

  @Test fun aReadWithoutWaysHasNone() {
    assertEquals(emptyList<ConnectApps.Way>(), KiffClient.json.decodeFromString<ConnectApps>("""{"apps":[]}""").ways)
  }

  @Test fun aSavedSignInOffersSignInAgainWhenTafsCloudDoesWhateverItsHealth() {
    // A healthy sign-in can still be refreshed: the button follows tafs cloud's `sign_in`, never the app's reading of the health.
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val read = KiffClient.json.decodeFromString<ConnectApps>("""{"apps":[],"saved_sign_ins":[
      {"grant":"g_9","site":"books.example","used_by":["Month end"],"health":{"state":"signed_in","words":{"line":"Signed in · checked today 07:00"}},
        "sign_in":{"title":"Sign in again"}},
      {"grant":"g_7","site":"supplier-portal.example","health":{"state":"needs_you_soon","words":{"line":"Needs you by Tue 2 Dec","why":"The site set your saved sign-in to end on 2 Dec."}}}]}""")
    val opened = mutableListOf<String>()
    compose.setContent { TafsTheme { androidx.compose.foundation.layout.Column { read.savedSignIns.forEach { bot.tafs.app.views.SavedSignInRow(it) { g -> opened += g } } } } }
    compose.onNodeWithText("Sign in again").performClick()
    assertEquals(listOf("g_9"), opened)
    // Needs the owner, but tafs cloud sent no action: the line and its why, no button of the app's own.
    compose.onNodeWithText("The site set your saved sign-in to end on 2 Dec.").assertExists()
  }

  @Test fun aWayIsDrawnInTafsCloudsWords() {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val cloud = KiffClient.json.decodeFromString<ConnectApps>(read).ways.first()
    compose.setContent { TafsTheme { WayCard(cloud) } }
    compose.onNodeWithTag("way-cloud").assertExists()
    compose.onNodeWithText("Cloud browser").assertExists()
    compose.onNodeWithText("Works while your computer is off.").assertExists()
    compose.onNodeWithText("· A fresh browser for each run, only on the sites you allow.").assertExists()
    compose.onNodeWithText("2 sign-ins saved").assertExists()
  }
}
