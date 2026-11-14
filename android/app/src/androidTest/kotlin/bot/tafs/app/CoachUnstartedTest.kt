package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Account
import bot.tafs.app.model.ApprovalReadiness
import bot.tafs.app.model.ApprovalWords
import bot.tafs.app.model.Words
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachScreen
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.LocalApprovalReadiness
import bot.tafs.app.views.TafActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Coach not started on this phone yet (docs/coach.md): a job handed to Coach shows its words in the box, with no step before
 * them, and the first Send is what starts Coach. A phone that can't approve yet still says why, and offers no box.
 */
class CoachUnstartedTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val handed = "Refund orders under €50."

  private val state = AppState(
    mode = Mode.kiff, ready = true, kiffAccount = Account("t_01", "acme", capabilities = listOf("members.manage")),
    coachChecked = true, coachCloudId = null, drafts = mapOf(COACH_CHAT to handed),
  )

  private fun actions(sent: MutableList<Pair<String, String>>) = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { text, tafId, _, _ -> sent += text to tafId }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
  )

  private fun show(readiness: ApprovalReadiness, sent: MutableList<Pair<String, String>>) = rule.setShown {
    TafsTheme {
      CompositionLocalProvider(LocalBiometry provides "your fingerprint", LocalApprovalReadiness provides readiness) {
        CoachScreen(state, actions(sent), loadCoach = {}, onBack = {})
      }
    }
  }

  @Test fun aJobHandedToCoachShowsItsWordsWithNoStepBeforeThemAndSendGoes() {
    val sent = mutableListOf<Pair<String, String>>()
    show(ApprovalReadiness.Ready, sent)
    rule.onNodeWithText(handed).assertExists()
    // What Coach is stays above the conversation, with no line of the app's own about a fingerprint (tafs cloud's Start says what
    // starting asks, when it does); there's no Start to tap first.
    rule.onAllNodesWithText(Words.Coach.startingAsks("your fingerprint")).assertCountEquals(0)
    rule.onAllNodesWithText(Words.Coach.start).assertCountEquals(0)
    rule.onNodeWithContentDescription(Words.Common.send).performClick()
    rule.waitForIdle()
    assertEquals(listOf(handed to COACH_CHAT), sent)
  }

  @Test fun aPhoneThatCantApproveYetSaysWhyAndOffersNoBox() {
    show(ApprovalReadiness.NoneEnrolled, mutableListOf())
    rule.onNodeWithText(ApprovalWords.FIRST_TAF).assertExists()
    rule.onAllNodesWithContentDescription(Words.Common.send).assertCountEquals(0)
  }
}
