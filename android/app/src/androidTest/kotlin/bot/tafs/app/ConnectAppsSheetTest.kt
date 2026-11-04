package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
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

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val list = ConnectApps(
    listOf(
      ConnectApps.App("stripe@1", "Stripe", "Stripe", state = "connected", grant = "g1"),
      ConnectApps.App("notion@1", "Notion", "Notion"),
      ConnectApps.App("google", "Google", "Google Sheets", state = "needs_reconnect", grant = "g2"),
      ConnectApps.App("connector:composio:gmail", "Gmail", "Gmail", state = "signed_in", grant = "g7"),
    ),
    byKey = true,
  )
  private val started = mutableListOf<String>()
  private var askedCoach = 0

  private fun taf(id: String, name: String) =
    bot.tafs.app.model.Taf(id = id, name = name, job = "", tools = emptyList(), colorIndex = 1, card = bot.tafs.app.model.Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "cloud_$id")

  private val chose = mutableListOf<String>()

  private fun show(connect: Boolean, tafs: List<bot.tafs.app.model.Taf> = listOf(taf("refunds-1", "Refunds")), connectedNow: bot.tafs.app.kiff.Grant? = null) = rule.setShown {
    TafsTheme {
      ConnectAppsSheet(
        AppState(mode = Mode.kiff, ready = true, connectApps = list, tafs = tafs, access = tafs.associate { it.id to bot.tafs.app.kiff.Access(use = true, manage = true) }, connectedNow = connectedNow),
        load = {},
        onConnect = if (connect) ({ app, taf -> started += "${app.entry}>$taf" }) else null,
        onAskCoach = { askedCoach += 1 },
        onClose = {},
        onChooseActions = { chose += "row ${it.grant}" },
        onConnectedNow = { chose += "now ${it.id}" },
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
    assertEquals("one taf: it's for that one", listOf("notion@1>refunds-1", "google>refunds-1"), started)
  }

  @Test fun aRowIsTheAppsTitleAloneNotTheProvidersIdForIt() {
    show(connect = true)
    rule.onNodeWithText("Google Sheets").assertIsDisplayed()
    assertEquals("the vendor's id isn't shown under it", 0, count("Google"))
  }

  @Test fun withSeveralTafsConnectAsksWhichFirst() {
    show(connect = true, tafs = listOf(taf("refunds-1", "Refunds"), taf("billing-1", "Billing")))
    rule.onNodeWithText(ConnectAppsWords.CONNECT).performClick()
    rule.waitForIdle()
    rule.onNodeWithText(bot.tafs.app.model.ConnectWords.WHICH_TAF).assertIsDisplayed()
    assertEquals(0, started.size)
    rule.onNodeWithText("Billing").performClick()
    rule.waitForIdle()
    assertEquals(listOf("notion@1>billing-1"), started)
  }

  @Test fun whichTafIsAskedOverTheListAndCancelStartsNothing() {
    show(connect = true, tafs = listOf(taf("refunds-1", "Refunds"), taf("billing-1", "Billing")))
    rule.onNodeWithText(ConnectAppsWords.CONNECT).performClick()
    rule.waitForIdle()
    // A dialog over the list: its question and its taf choices show where Connect was tapped, with no scrolling.
    rule.onNodeWithText(bot.tafs.app.model.ConnectWords.WHICH_TAF).assertIsDisplayed()
    rule.onNodeWithText(bot.tafs.app.model.ConnectWords.oneTaf("Notion")).assertIsDisplayed()
    assertEquals("over the sheet, its own window", 2, rule.onAllNodes(androidx.compose.ui.test.isDialog()).fetchSemanticsNodes().size)
    rule.onNodeWithText("Cancel").performClick()
    rule.waitForIdle()
    assertEquals(0, count(bot.tafs.app.model.ConnectWords.WHICH_TAF))
    assertEquals(0, started.size)
  }

  @Test fun withNoTafThereIsNothingToConnectFor() {
    show(connect = true, tafs = emptyList())
    rule.onNodeWithText(bot.tafs.app.model.ConnectWords.NO_TAF).assertIsDisplayed()
    assertEquals(0, count(ConnectAppsWords.CONNECT))
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

  @Test fun anAppSignedInSaysItsNextStepAndOpensItsCoach() {
    show(connect = true)
    rule.onNodeWithText(bot.tafs.app.model.ConnectWords.SIGNED_IN_ROW).performClick()
    rule.waitForIdle()
    assertEquals(listOf("row g7"), chose)
    assertEquals("one Connect: Notion's, not Gmail's", 1, count(ConnectAppsWords.CONNECT))
  }

  @Test fun aSignInThatJustCameBackIsSaidAtOnceWithItsNextStep() {
    val grant = bot.tafs.app.kiff.KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>(
      """{"id":"g7","status":"listed","vendor":"Notion","request":{"server":"","agent":"refunds-1","tools":[]}}""",
    )
    show(connect = true, connectedNow = grant)
    rule.onNodeWithText(bot.tafs.app.model.ConnectWords.signedInFor("Notion", "Refunds")).assertIsDisplayed()
    rule.onNodeWithText(bot.tafs.app.model.ConnectWords.CHOOSE_ACTIONS).performClick()
    rule.waitForIdle()
    assertEquals(listOf("now g7"), chose)
  }
}
