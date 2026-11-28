package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Chat
import bot.tafs.app.model.LiveAnswer
import bot.tafs.app.model.LiveState
import bot.tafs.app.model.LiveStep
import bot.tafs.app.model.Res
import bot.tafs.app.model.StepLine
import bot.tafs.app.views.LIVE_STEPS
import bot.tafs.app.views.LiveWork
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * While a taf works, the steps it has done so far show under the working line (docs/chat.md, "The work block"), in tafs cloud's
 * words and as the folded "Worked … · N steps" will list them. The step at work is the line's, not the list's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h900dp-port")
class LiveStepsTest {
  @get:Rule val compose = createComposeRule()

  private val t0 = Instant.parse("2026-11-28T14:00:00Z")

  /** Three steps begun, two ended (one failed), and the third still at work. */
  private fun working(): LiveAnswer = LiveAnswer(started = t0, state = LiveState("working", "Checking the sign-in"))
    .began("qonto_list_invoices", "Reading your Qonto invoices…", "c1").ended("c1", LiveStep.Status.of("done"))
    .began("sheets_append", "Adding a row to Orders…", "c2").ended("c2", LiveStep.Status.of("error"))
    .began("qonto_check", "Checking the sign-in…", "c3")

  @Before fun words() = Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))

  @Test fun theStepsDoneShowInOrderWithoutTheLiveEllipsisAndTheOneAtWorkIsLeftToTheLine() {
    assertEquals(
      listOf(StepLine("Reading your Qonto invoices"), StepLine("Adding a row to Orders", failed = true)),
      Chat.liveSteps(working()),
    )
  }

  @Test fun aStepHeldForTheOwnerOrWithNoWordsIsNotListed() {
    val live = LiveAnswer(started = t0)
      .began("kiff_pending", "", "c1").ended("c1", LiveStep.Status.of("done"))
      .began("qonto_create_invoice", "Creating a draft invoice…", "c2").ended(null, LiveStep.Status.held)
      .began("sheets_append", "Adding a row…", "c3").ended("c3", LiveStep.Status.of("not_made"))
    assertEquals(listOf(StepLine("Adding a row", notMade = true)), Chat.liveSteps(live))
  }

  @Test fun theStepsDoneAreDrawnUnderTheWorkingLine() {
    compose.setContent { TafsTheme { LiveWork(working()) } }
    compose.onNodeWithTag(LIVE_STEPS).assertIsDisplayed()
    compose.onNodeWithText("• Reading your Qonto invoices").assertIsDisplayed()
    compose.onNodeWithText("• Adding a row to Orders").assertIsDisplayed()
    // The step at work is on the line, once, not in the list.
    compose.onNodeWithText("• Checking the sign-in").assertDoesNotExist()
  }

  @Test fun beforeAnyStepEndsThereIsNoList() {
    compose.setContent { TafsTheme { LiveWork(LiveAnswer(started = t0, state = LiveState("thinking"))) } }
    compose.onNodeWithTag(LIVE_STEPS).assertDoesNotExist()
  }
}
