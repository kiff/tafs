package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.views.ConnectAppsSheet
import bot.tafs.app.views.ConnectAppsWords
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Connect apps (A1): the catalog as tafs cloud lists it, what's connected, and Coach for an app nobody listed. */
class ConnectAppsSheetTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val list = ConnectApps(
    listOf(
      ConnectApps.App("stripe@1", "Stripe", "Stripe", state = "connected", grant = "g1"),
      ConnectApps.App("notion@1", "Notion", "Notion"),
      ConnectApps.App("google", "Google", "Google Sheets", state = "needs_reconnect", grant = "g2"),
    ),
    byKey = true,
  )
  private val started = mutableListOf<String>()
  private var askedCoach = 0

  private fun show(connect: Boolean) = rule.setShown {
    TafsTheme {
      ConnectAppsSheet(
        AppState(mode = Mode.kiff, ready = true, connectApps = list),
        load = {},
        onConnect = if (connect) ({ started += it.entry }) else null,
        onAskCoach = { askedCoach += 1 },
        onClose = {},
      )
    }
  }

  private fun count(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

  @Test fun eachAppSaysWhetherItsConnectedAndConnectStartsOne() {
    show(connect = true)
    rule.onNodeWithText("Stripe").assertIsDisplayed()
    rule.onNodeWithText(ConnectAppsWords.CONNECTED).assertIsDisplayed()
    rule.onNodeWithText(ConnectAppsWords.CONNECT).performClick()
    rule.onNodeWithText(ConnectAppsWords.SIGN_IN_AGAIN).performClick()
    rule.waitForIdle()
    assertEquals(listOf("notion@1", "google"), started)
  }

  @Test fun withNoWayToConnectNoAppOffersConnect() {
    show(connect = false)
    assertEquals(0, count(ConnectAppsWords.CONNECT))
    rule.onNodeWithText(ConnectAppsWords.SIGN_IN_AGAIN).assertIsDisplayed()
  }

  @Test fun anAppNobodyListedGoesToCoach() {
    show(connect = false)
    rule.onNodeWithText(ConnectAppsWords.NOT_LISTED).performClick()
    rule.waitForIdle()
    assertEquals(1, askedCoach)
  }
}
