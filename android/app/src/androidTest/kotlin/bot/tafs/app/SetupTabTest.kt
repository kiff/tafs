package bot.tafs.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KnobWire
import bot.tafs.app.model.Card
import bot.tafs.app.model.SetupWords
import bot.tafs.app.model.Taf
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.LocalImproveInCoach
import bot.tafs.app.views.SetupTab
import bot.tafs.app.views.TafActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A taf's Setup tab: how it runs, in sections, each opening its sheet; the switches send tafs cloud's routes and settings. */
class SetupTabTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "Refund double charges", tools = emptyList(), colorIndex = 1, card = Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "taf_1")
  private val log = mutableListOf<String>()

  private fun knob(results: String, canPause: Boolean = true) = KiffClient.json.decodeFromString<KnobWire>(
    """{"version":7,"setting":{"wakes":[],"busy":"skip","results":["door_reply"]},"can_pause":$canPause,
        "wakes":[{"id":"w","kind":"schedule","label":"Weekdays","words":"Weekdays at 09:00"},
                 {"id":"e","kind":"app_event","label":"New deal","words":"A won deal in HubSpot","ok":"approve","approval":{"state":"needed","grant":"g","event":"HUBSPOT_DEAL_WON","config":{}}}],
        "results":$results,"rim":{}}""",
  )

  private fun show(knob: KnobWire) = rule.setShown {
    val actions = TafActions(
      answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
      sendChat = { _, _, _, _ -> }, issuer = "You",
      doors = DoorActions(
        {}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {},
        changeKnobSize = { _, setting -> log += "setting $setting" },
      ),
      coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
      connections = ConnectionActions({}, { false }),
      setPaused = { _, paused -> log += "pause $paused" },
      setResults = { _, places -> log += "results $places" },
    )
    TafsTheme {
      CompositionLocalProvider(LocalBiometry provides "your fingerprint", LocalImproveInCoach provides { id: String -> log += "coach $id" }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          SetupTab(
            taf,
            AppState(mode = Mode.kiff, ready = true, tafs = listOf(taf), access = mapOf("refunds-1" to Access(use = true, oversee = true, manage = true)), knobs = mapOf("refunds-1" to knob)),
            actions, onVersions = { log += "versions" }, onConnectApps = { log += "connect" }, onRemove = { log += "remove" },
          )
        }
      }
    }
  }

  private val twoOn = """[{"id":"r1","place":"door_reply","label":"Sender","on":true},{"id":"r2","place":"app","label":"You","on":false},{"id":"r3","place":"owner_email","label":"Email","on":false}]"""

  @Test fun setupIsTheJobThenHowItStartsThenWhereResultsGo() {
    show(knob(twoOn))
    rule.onNodeWithText(SetupWords.JOB, ignoreCase = true).assertIsDisplayed()
    rule.onNodeWithText("Refund double charges").assertIsDisplayed()
    rule.onNodeWithText("Weekdays at 09:00").assertIsDisplayed()
    rule.onNodeWithText("Remove for good").performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("remove"), log)
  }

  @Test fun theLastPlaceOnStaysOnAndAnotherIsSwitchedThroughItsRoute() {
    show(knob(twoOn))
    rule.onNode(hasText(SetupWords.RESULTS_GO_TO, ignoreCase = true)).performScrollTo().performClick()
    rule.waitForIdle()
    rule.onNode(hasContentDescription(SetupWords.placeTitle("door_reply"))).assertIsNotEnabled()
    rule.onNode(hasContentDescription(SetupWords.placeTitle("app"))).assertIsEnabled().performClick()
    rule.waitForIdle()
    assertEquals(listOf("results [door_reply, app]"), log)
  }

  @Test fun startsWhenAsksBeforeApprovingAnEventAndSendsWhatHappensIfItsStillWorking() {
    show(knob(twoOn))
    rule.onNode(hasText(SetupWords.STARTS_WHEN, ignoreCase = true)).performScrollTo().performClick()
    rule.waitForIdle()
    rule.onNodeWithText(SetupWords.APPROVE_NOTE).performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(SetupWords.RUN_AFTER).performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("""setting {"wakes":[],"busy":"latest","results":["door_reply"]}"""), log)
  }

  @Test fun pausingEveryStartIsOffForATafThatCantBePaused() {
    show(knob(twoOn, canPause = false))
    rule.onNode(hasText(SetupWords.STARTS_WHEN, ignoreCase = true)).performScrollTo().performClick()
    rule.waitForIdle()
    rule.onNodeWithText(SetupWords.PAUSE_EVERY).performScrollTo().assertIsNotEnabled()
  }

  @Test fun theLastOfTafsOwnPlacesStaysOnEvenWithAnAppsPlaceOn() {
    // As tafs cloud sends it: the last of its own places has no Off, and its route needs one of them on.
    show(
      knob(
        """[{"id":"r1","place":"door_reply","label":"Sender","on":true},{"id":"r2","place":"app","label":"You","on":false},{"id":"r3","place":"owner_email","label":"Email","on":false},
            {"id":"r4","place":"action","label":"Notion · Support log","on":true}]""",
      ),
    )
    rule.onNode(hasText(SetupWords.RESULTS_GO_TO, ignoreCase = true)).performScrollTo().performClick()
    rule.waitForIdle()
    rule.onNode(hasContentDescription(SetupWords.placeTitle("door_reply"))).assertIsNotEnabled()
    rule.onNode(hasContentDescription(SetupWords.placeTitle("app"))).assertIsEnabled()
  }
}
