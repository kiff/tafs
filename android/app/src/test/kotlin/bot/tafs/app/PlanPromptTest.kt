package bot.tafs.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.model.Card
import bot.tafs.app.model.Res
import bot.tafs.app.model.Taf
import bot.tafs.app.model.Words
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.PLAN_PROMPT
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafChatView
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A chat refused for no plan (kiff/tafs#2190): tafs cloud's words as a prompt, Choose a plan and Not now. Either button puts it
 * away, so someone who chose a plan and comes back doesn't read "Choose a plan" above the box; Choose a plan also opens Plan & usage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlanPromptTest {
  @get:Rule val compose = createComposeRule()

  private val words = "Choose a plan to start your tafs."
  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "Refund", tools = emptyList(), colorIndex = 1, card = Card(emptyList(), "You"), cloudId = "taf_1")
  private val state = AppState(
    mode = Mode.kiff, ready = true, tafs = listOf(taf), kiffAccount = Account("t_01", "acme", capabilities = listOf("cards.manage")),
    access = mapOf("refunds-1" to Access(use = true, oversee = true, manage = true)),
    chatErrors = mapOf("refunds-1" to words), chatPlanNeeded = mapOf("refunds-1" to words),
  )
  private val calls = mutableListOf<String>()
  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}, openPlan = { calls += "coach plan" }),
    connections = ConnectionActions({}, { false }),
    openPlan = { calls += "plan" },
    dismissPlanPrompt = { calls += "dismiss $it" },
  )

  @Before fun words() {
    RuntimeEnvironment.setQualifiers("en-w411dp-h2400dp-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
  }

  @Test fun choosePlanPutsThePromptAwayAndOpensPlanAndUsage() {
    compose.setContent { TafsTheme { TafChatView("refunds-1", state, actions) } }
    compose.onNodeWithTag(PLAN_PROMPT).assertExists()
    compose.onNodeWithText(words).assertExists()
    compose.onNodeWithText(Words.Usage.choosePlan).performClick()
    assertEquals(listOf("dismiss refunds-1", "plan"), calls)
  }

  @Test fun notNowPutsItAwayAndOpensNothing() {
    compose.setContent { TafsTheme { TafChatView("refunds-1", state, actions) } }
    compose.onNodeWithText(Words.Common.notNow).performClick()
    assertEquals(listOf("dismiss refunds-1"), calls)
  }

  @Test fun anotherLineIsNeverThePrompt() {
    val other = state.copy(chatErrors = mapOf("refunds-1" to "Your tafs are waiting: this period's credits are used."))
    compose.setContent { TafsTheme { TafChatView("refunds-1", other, actions) } }
    compose.onNodeWithTag(PLAN_PROMPT).assertDoesNotExist()
    compose.onNodeWithText("Your tafs are waiting: this period's credits are used.").assertExists()
  }
}
