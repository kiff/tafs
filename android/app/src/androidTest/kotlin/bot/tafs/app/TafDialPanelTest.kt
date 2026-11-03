package bot.tafs.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.app.TafDoors
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Door
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KnobWire
import bot.tafs.app.model.Card
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.KnobWords
import bot.tafs.app.model.Money
import bot.tafs.app.model.ProposedAction
import bot.tafs.app.model.Taf
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafDialPanel
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The settled dial in a taf's Doors tab: the Why, "Showing ▾", the remote (◀ − ⏻ + ▶ with the face as OK), one size per stop,
 * and "Make N changes live".
 */
class TafDialPanelTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1, card = Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "taf_1")
  private val slack = Door(id = "door_s", app = "slack", kind = "slack", label = "Slack", open = true)
  private val wire = KiffClient.json.decodeFromString<KnobWire>(
    """{"version":7,"why":"Refund double charges","does":{"label":"Refund","line":"Refunds a payment in Stripe, within its Card."},
        "rings":[{"key":"wakes","name":"When"},{"key":"what","name":"What"},{"key":"results","name":"Where"}],
        "wakes":[{"id":"trg_s","kind":"door","door":"door_s","grant":"g_slack","label":"Slack","words":"Messages in #refunds start a request.","state":"Open","tap":"Tap to close"},
                 {"id":"trg_r","kind":"run_now","label":"Run now","words":"Runs it once, now.","state":"Now","tap":"Tap to run once"},
                 {"id":"trg_f","kind":"follow_up","follow_up":"fu_1","label":"Tomorrow 9:00","words":"Check refund 4402 again","state":"Once","tap":"Tap to remove"},
                 {"id":"trg_w","kind":"schedule","label":"Weekdays","words":"Weekdays at 09:00","state":"09:00",
                  "size":{"label":"Time","index":0,"choices":[{"label":"09:00","setting":{"at":"09:00"}},{"label":"12:00","setting":{"at":"12:00"}}]}}],
        "what":[{"id":"s1","step":1,"label":"Check","words":"Looks up the charge"}],
        "results":[{"id":"res_1","place":"door_reply","label":"Sender","words":"Replies to whoever wrote"}],
        "connections":[{"grant":"g_slack","name":"Slack"}],
        "rim":{"done":3,"line":"3 done today"}}""",
  )
  private val log = mutableListOf<String>()

  private fun show(held: List<HeldCall> = emptyList(), changes: Int = 0) = rule.setShown {
    TafsTheme {
      // In a scrolling column, as the Doors tab is.
      Column(Modifier.verticalScroll(rememberScrollState())) {
        TafDialPanel(
          taf, wire,
          AppState(
            mode = Mode.kiff, ready = true, tafs = listOf(taf), held = held,
            access = mapOf("refunds-1" to bot.tafs.app.kiff.Access(use = true, manage = true)),
            knobChanges = if (changes > 0) mapOf("refunds-1" to KnobWire.Change(9)) else emptyMap(),
            knobChangeCount = if (changes > 0) mapOf("refunds-1" to changes) else emptyMap(),
          ),
          TafDoors(doors = listOf(slack), loaded = true),
          DoorActions(
            load = {}, add = { _, _ -> null }, setOpen = { _, door, open -> log += "open ${door.id} $open" }, remove = { _, _ -> },
            pair = { _, _, _, _ -> }, unpair = { _, _, _ -> }, answer = { _, _, _, _ -> }, dismissError = {},
            loadKnob = { log += "reload" }, runNow = { log += "run" }, approveOnce = { log += "approve ${it.id}" },
            removeFollowUp = { _, fid -> log += "remove $fid" }, openRimRequest = { log += "rim" },
            changeKnobSize = { _, setting -> log += "size $setting" }, confirmKnobChange = { log += "live" },
            setPaused = { _, paused -> log += "pause $paused" }, askCoach = { log += "coach" },
          ),
          onDoor = { log += "door $it" },
        )
      }
    }
  }

  @Test fun theFaceIsTheWhyWithTodaysWorkUnderIt() {
    show()
    rule.onNodeWithText(KnobWords.WHY).assertIsDisplayed()
    rule.onNodeWithText("Refund double charges").assertIsDisplayed()
    rule.onNodeWithText("${KnobWords.SET_UP_BY_COACH} · 3 done today ›").performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("rim"), log)
  }

  @Test fun theSearchFieldAsksCoach() {
    show()
    rule.onNodeWithText(KnobWords.SEARCH, substring = true).performClick()
    rule.waitForIdle()
    assertEquals(listOf("coach"), log)
  }

  @Test fun aDoorStopPicksItsDoorAndOkClosesIt() {
    show()
    rule.onNodeWithContentDescription("Slack, When, open").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Messages in #refunds start a request.").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Tap to close").performClick()
    rule.waitForIdle()
    assertEquals(listOf("door door_s", "open door_s false", "reload"), log)
  }

  @Test fun okRunsTheTafNowAndRemovesAFollowUp() {
    show()
    rule.onNodeWithContentDescription("Run now, When, now").performClick()
    rule.onNodeWithText("Tap to run once").performClick()
    rule.onNodeWithContentDescription("Tomorrow 9:00, When, once").performClick()
    rule.onNodeWithText("Tap to remove").performClick()
    rule.waitForIdle()
    assertEquals(listOf("run", "remove fu_1"), log)
  }

  @Test fun aHeldCallTakesTheFaceAndOkApprovesItOnce() {
    val call = HeldCall(
      id = "h1", tafId = "refunds-1", proposed = ProposedAction("refund", "Refund order 4409", Money(6000, "USD", 2)),
      reason = "$60 is over the $50 limit for one refund.", heldAt = Instant.parse("2026-11-03T00:00:00Z"), expiresAt = Instant.parse("2026-11-03T01:00:00Z"),
    )
    show(held = listOf(call))
    rule.onNodeWithText("$60 is over the $50 limit for one refund.").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(KnobWords.APPROVE_ONCE).performClick()
    rule.waitForIdle()
    assertEquals(listOf("approve h1"), log)
  }

  @Test fun nextAndBackStepThroughTheStops() {
    show()
    rule.onNode(hasContentDescription(KnobWords.NEXT)).performScrollTo().performClick()
    rule.waitForIdle()
    rule.onNodeWithText("WHEN · SLACK").assertIsDisplayed()
    rule.onNode(hasContentDescription(KnobWords.BACK)).performScrollTo().performClick()
    rule.waitForIdle()
    rule.onNodeWithText("WHERE · SENDER").assertIsDisplayed()
  }

  @Test fun raiseSendsTheNextChoiceAndLowerStopsAtTheFirst() {
    show()
    rule.onNodeWithContentDescription("Weekdays, When, 09:00").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Time · 09:00").performScrollTo().assertIsDisplayed()
    rule.onNode(hasContentDescription(KnobWords.LOWER)).performScrollTo().assertIsNotEnabled()
    rule.onNode(hasContentDescription(KnobWords.RAISE)).performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("""size {"at":"12:00"}"""), log)
  }

  @Test fun aStopWithNoSizeSaysAskCoachToChange() {
    show()
    rule.onNodeWithContentDescription("1 · Check, What").performClick()
    rule.waitForIdle()
    rule.onNodeWithText(KnobWords.ASK_COACH_TO_CHANGE).performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("coach"), log)
  }

  @Test fun showingOneConnectionKeepsOnlyItsStops() {
    show()
    rule.onNodeWithText("${KnobWords.SHOWING} ${KnobWords.ALL} ▾").performClick()
    rule.onNodeWithText("Slack · 1").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("${KnobWords.SHOWING} Slack ▾").assertIsDisplayed()
    assertEquals("the taf's own stops are hidden", 0, rule.onAllNodes(hasText("Run now")).fetchSemanticsNodes().size)
  }

  @Test fun changesCollectIntoOneSwitch() {
    show(changes = 2)
    rule.onNodeWithText("Make 2 changes live", substring = true).performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("live"), log)
  }

  @Test fun pauseIsOnTheRemote() {
    show()
    rule.onNode(hasContentDescription(KnobWords.PAUSE)).performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("pause true"), log)
  }
}
