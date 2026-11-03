package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** The dial in a taf's Doors tab: a door stop picks its door, the face opens or closes it or runs the taf, a held call is approved once. */
class TafDialPanelTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1, card = Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "taf_1")
  private val slack = Door(id = "door_slack", app = "slack", kind = "slack", label = "Slack", open = true)
  private val wire = KiffClient.json.decodeFromString<KnobWire>(
    """{"does":{"label":"Refund","line":"Refunds a payment in Stripe, within its Card."},
        "wakes":[{"id":"trg_1","kind":"door","door":"door_slack","label":"Slack","words":"Messages in #refunds start a request.","state":"Open","tap":"Tap to close"},
                 {"id":"trg_2","kind":"run_now","label":"Run now","words":"Runs it once, now.","state":"Now","tap":"Tap to run once"}],
        "when":[{"id":"when_arrivals","kind":"arrivals","label":"Arrivals","words":"Works each one as it arrives"}],
        "results":[{"id":"res_1","place":"door_reply","label":"Sender","words":"Replies to whoever wrote"}],
        "rim":{"done":3,"line":"3 done today"}}""",
  )
  private val log = mutableListOf<String>()

  private fun show(held: List<HeldCall> = emptyList()) = rule.setShown {
    TafsTheme {
      TafDialPanel(
        taf, wire, AppState(mode = Mode.kiff, ready = true, tafs = listOf(taf), held = held), TafDoors(doors = listOf(slack), loaded = true),
        DoorActions(
          load = {}, add = { _, _ -> null }, setOpen = { _, door, open -> log += "open ${door.id} $open" }, remove = { _, _ -> },
          pair = { _, _, _, _ -> }, unpair = { _, _, _ -> }, answer = { _, _, _, _ -> }, dismissError = {},
          loadKnob = { log += "reload" }, runNow = { log += "run" }, approveOnce = { log += "approve ${it.id}" },
        ),
        onDoor = { log += "door $it" },
      )
    }
  }

  @Test fun theJobAndTodaysWorkShowUnderTheDial() {
    show()
    rule.onNodeWithText("Refunds a payment in Stripe, within its Card.").assertIsDisplayed()
    rule.onNodeWithText("3 done today").assertIsDisplayed()
  }

  @Test fun aDoorStopPicksItsDoorAndTheFaceClosesIt() {
    show()
    rule.onNodeWithContentDescription("Slack, Wakes it, open").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Messages in #refunds start a request.").assertIsDisplayed()
    rule.onNodeWithText("Tap to close").performClick()
    rule.waitForIdle()
    assertEquals(listOf("door door_slack", "open door_slack false", "reload"), log)
    rule.onNodeWithText("‹ ${KnobWords.OVERVIEW}").performClick()
    rule.waitForIdle()
    rule.onNodeWithText(KnobWords.TAP_A_RING).assertIsDisplayed()
  }

  @Test fun runNowRunsTheTafFromTheFace() {
    show()
    rule.onNodeWithContentDescription("Run now, Wakes it, now").performClick()
    rule.onNodeWithText("Tap to run once").performClick()
    rule.waitForIdle()
    assertEquals(listOf("run"), log)
  }

  @Test fun aHeldCallTakesTheFaceAndATapApprovesItOnce() {
    val call = HeldCall(
      id = "h1", tafId = "refunds-1", proposed = ProposedAction("refund", "Refund order 4409", Money(6000, "USD", 2)),
      reason = "$60 is over the $50 limit for one refund.", heldAt = Instant.parse("2026-11-03T00:00:00Z"), expiresAt = Instant.parse("2026-11-03T01:00:00Z"),
    )
    show(held = listOf(call))
    rule.onNodeWithText("$60 is over the $50 limit for one refund.").assertIsDisplayed()
    rule.onNodeWithText(KnobWords.APPROVE_ONCE).performClick()
    rule.waitForIdle()
    assertEquals(listOf("approve h1"), log)
  }
}
