package bot.tafs.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.Knob
import bot.tafs.app.model.KnobFace
import bot.tafs.app.model.KnobRim
import bot.tafs.app.model.KnobRing
import bot.tafs.app.model.KnobStop
import bot.tafs.app.model.KnobWords
import bot.tafs.app.model.Money
import bot.tafs.app.model.ProposedAction
import bot.tafs.app.views.TafKnob
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The taf's dial (K1 to K3), with the design's Refunds: the job in the face, a stop chosen, and a held call on the face. */
class TafKnobTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private fun stop(id: String, label: String, on: Boolean, state: String, tap: String?, about: String = "") = KnobStop(id, label, on, state, tap, about)

  private val refunds = Knob(
    does = "Refund",
    overview = "Refunds a payment in Stripe, within its Card. Wakes on email and Slack, works as they arrive, replies to the sender.",
    rings = listOf(
      KnobRing(KnobRing.Kind.WakesIt, listOf(
        stop("email", "Email", true, "Open", "Tap to close"),
        stop("whatsapp", "WhatsApp", false, "Closed", "Tap to open"),
        stop("slack", "Slack", true, "Open", "Tap to close", "Messages in #refunds start a request. Refunds replies in the thread."),
        stop("run", "Run now", false, "Now", "Tap to run once"),
      )),
      KnobRing(KnobRing.Kind.When, listOf(
        stop("arrivals", "Arrivals", true, "On", "Tap to turn off"),
        stop("weekdays", "Weekdays", true, "09:00", "Tap to change"),
        stop("missed", "If missed", true, "Next run", null),
        stop("busy", "If busy", true, "Latest", null),
      )),
      KnobRing(KnobRing.Kind.ResultsGo, listOf(
        stop("sender", "Sender", true, "On", "Tap to turn off"),
        stop("you", "You", true, "On", "Tap to turn off"),
        stop("notion", "Notion", true, "On", "Tap to turn off"),
        stop("summary", "Summary", false, "Off", "Tap to turn on"),
      )),
    ),
    rim = KnobRim(done = 12, working = 1, retrying = 1, needsYou = 1),
    summary = "12 done today · 1 working · 1 retrying its reply",
  )

  private val held = HeldCall(
    tafId = "refunds-1",
    proposed = ProposedAction("refund", "Refund order 4409", Money(6000, "USD", 2)),
    reason = "$60 is over the $50 limit for one refund.",
    heldAt = Instant.parse("2026-11-02T20:00:00Z"),
    expiresAt = Instant.parse("2026-11-02T21:00:00Z"),
  )

  private var pressed = 0

  private fun show(start: KnobFace) {
    rule.setShown {
      var face by androidx.compose.runtime.remember { mutableStateOf(start) }
      TafsTheme {
        TafKnob(refunds, face, onChoose = { ring, id -> face = KnobFace.Chosen(ring, refunds.rings.first { it.kind == ring }.stops.first { it.id == id }) }, onFace = { pressed += 1 })
      }
    }
  }

  @Test fun theFaceHoldsTheJobAndEveryRingsWordsShow() {
    show(KnobFace.Overview)
    rule.onNodeWithText(KnobWords.DOES).assertIsDisplayed()
    rule.onNodeWithText("Refund").assertIsDisplayed()
    rule.onNodeWithText(KnobWords.TAP_A_RING).assertIsDisplayed()
    listOf("Email", "WhatsApp", "Slack", "Run now", "Arrivals", "Weekdays", "If missed", "If busy", "Sender", "You", "Notion", "Summary").forEach {
      rule.onNodeWithText(it).assertIsDisplayed()
    }
  }

  @Test fun tappingAStopChoosesItAndTheFaceSaysWhatItIs() {
    show(KnobFace.Overview)
    rule.onNodeWithContentDescription("Slack, Wakes it, open").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("WAKES IT · SLACK").assertIsDisplayed()
    rule.onNodeWithText("Open").assertIsDisplayed()
    rule.onNodeWithText("Tap to close").assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(1, pressed)
  }

  @Test fun aHeldCallTakesTheFaceAndATapApprovesIt() {
    show(KnobFace.Held(held))
    rule.onNodeWithText("REFUND ORDER 4409").assertIsDisplayed()
    rule.onNodeWithText(KnobWords.APPROVE_ONCE).assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(1, pressed)
    assertEquals(1, rule.onAllNodes(hasText("$60", substring = true)).fetchSemanticsNodes().size)
  }
}
