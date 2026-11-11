package bot.tafs.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.HealthFix
import bot.tafs.app.kiff.OldestWaiting
import bot.tafs.app.kiff.TafHealth
import bot.tafs.app.kiff.TafPromise
import bot.tafs.app.kiff.TafWork
import bot.tafs.app.kiff.WorkSummary
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafActivityList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import bot.tafs.app.app.TafDoors
import bot.tafs.app.kiff.Knock
import java.time.Instant
import org.junit.Rule
import org.junit.Test

/**
 * The Activity of a taf on tafs cloud (one with a `cloudId`), drawn as the app draws it on a real account (`TafActivityList`): its
 * health with its fix, its oldest waiting case, and Answer within for whoever manages it. Android 50 drew these only on a view a
 * taf on tafs cloud never reaches, so this renders the one it does.
 */
class CloudTafActivityTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val taf = Taf(
    id = "refunds-1", name = "Daily refunds report", job = "", tools = emptyList(), colorIndex = 1,
    card = Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "taf_1",
  )
  private val say = "Daily refunds report can't read Stripe. Reconnect it."
  private val work = TafWork(
    summary = WorkSummary(status = "idle", line = "Its last check couldn't read the app"),
    health = TafHealth(
      level = "at_risk",
      lines = listOf("Its last check couldn't read the app. Ask Coach to reconnect it."),
      fixes = listOf(HealthFix(line = 0, kind = "coach", words = "Ask Coach to fix it", say = say)),
    ),
    oldestWaiting = OldestWaiting(words = "Oldest case waiting 5 h, past the 4 h you set", late = true),
    promise = TafPromise(hours = 4),
  )

  private val knock = Knock(
    "k_1", "door_1", "email", "mara@customer.example", "Mara", "pass", "Order", "Where is my refund?", 0,
    Instant.parse("2026-11-11T09:00:00Z"), "open", null, 0, false,
  )

  private fun show(
    manage: Boolean,
    loaded: MutableList<String> = mutableListOf(),
    coach: MutableList<Pair<String, String>> = mutableListOf(),
    doorsLoaded: MutableList<String> = mutableListOf(),
  ) {
    val actions = actions(loaded, coach, doorsLoaded)
    val state = state(manage)
    rule.setShown { TafsTheme { TafActivityList(taf, state, actions, selection = "", clearSelection = {}) } }
  }

  private fun actions(loaded: MutableList<String>, coach: MutableList<Pair<String, String>>, doorsLoaded: MutableList<String>) =
    TafActions(
      answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
      sendChat = { _, _, _, _ -> }, issuer = "You",
      doors = DoorActions({ doorsLoaded += it }, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
      coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
      connections = ConnectionActions({}, { false }),
      loadWork = { loaded += it },
      askCoachAbout = { id, words -> coach += id to words },
    )

  private fun state(manage: Boolean) = AppState(
      mode = Mode.kiff, ready = true, tafs = listOf(taf), works = mapOf(taf.id to work),
      kiffAccount = Account("t_01", "acme", capabilities = listOf("members.manage", "cards.manage")),
      access = mapOf(taf.id to Access(use = true, oversee = true, manage = manage)),
      doors = mapOf(taf.id to TafDoors(knocks = listOf(knock), loaded = true)),
    )

  @Test fun aCloudTafsActivityShowsItsHealthWithItsFixItsOldestWaitingCaseAndAnswerWithin() {
    val loaded = mutableListOf<String>()
    val coach = mutableListOf<Pair<String, String>>()
    show(manage = true, loaded, coach)
    // Its work is read for this taf.
    rule.waitForIdle()
    assertEquals(listOf("refunds-1"), loaded.distinct())
    // The health line and its fix, which opens Coach with tafs cloud's words.
    rule.onNodeWithText("Its last check couldn't read the app. Ask Coach to reconnect it.").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Ask Coach to fix it").performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("refunds-1" to say), coach)
    // The work's line is that reason, so it's said once.
    rule.onAllNodesWithText("Its last check couldn't read the app", substring = false).assertCountEquals(0)
    // Its oldest waiting case, and Answer within with the promise as tafs cloud keeps it.
    rule.onNodeWithText("Oldest case waiting 5 h, past the 4 h you set").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Answer within").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("4 hours", substring = true).performScrollTo().assertIsDisplayed()
  }

  @Test fun someoneWhoDoesNotManageTheTafSeesItsHealthButNoAnswerWithin() {
    show(manage = false)
    rule.onNodeWithText("Its last check couldn't read the app. Ask Coach to reconnect it.").performScrollTo().assertIsDisplayed()
    rule.onAllNodesWithText("Answer within").assertCountEquals(0)
  }

  @Test fun whoeverManagesTheTafSeesItsKnocksAboveItsRuns() {
    val doorsLoaded = mutableListOf<String>()
    show(manage = true, doorsLoaded = doorsLoaded)
    rule.waitForIdle()
    assertEquals(listOf("refunds-1"), doorsLoaded.distinct())
    rule.onNodeWithText("KNOCKS · WAITING ON YOU").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Where is my refund?", substring = true).performScrollTo().assertIsDisplayed()
    val knocks = rule.onNodeWithText("KNOCKS · WAITING ON YOU").fetchSemanticsNode().boundsInRoot.top
    val runs = rule.onNodeWithText("RUNS").fetchSemanticsNode().boundsInRoot.top
    assertTrue("knocks come before the runs", knocks < runs)
  }

  @Test fun someoneWhoDoesNotManageTheTafSeesNoKnocks() {
    val doorsLoaded = mutableListOf<String>()
    show(manage = false, doorsLoaded = doorsLoaded)
    rule.waitForIdle()
    rule.onAllNodesWithText("KNOCKS · WAITING ON YOU").assertCountEquals(0)
    assertTrue(doorsLoaded.isEmpty())
  }

  @Test fun theTafsPageOpensThisActivityForATafOnTafsCloud() {
    // The view choice is what broke in Android 1.5: the whole page, Activity tapped, shows the work, ahead of the runs.
    val loaded = mutableListOf<String>()
    rule.setShown {
      TafsTheme {
        androidx.compose.runtime.CompositionLocalProvider(bot.tafs.app.design.LocalBiometry provides "your fingerprint") {
          bot.tafs.app.views.TafDetailView(taf.id, state(manage = true), actions(loaded, mutableListOf(), mutableListOf()), onBack = {}, onHalt = {}, onUsage = {}, onVersions = {})
        }
      }
    }
    rule.onNodeWithText("Activity").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Its last check couldn't read the app. Ask Coach to reconnect it.").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Answer within").performScrollTo().assertIsDisplayed()
    val work = rule.onNodeWithText("Its last check couldn't read the app. Ask Coach to reconnect it.").fetchSemanticsNode().boundsInRoot.top
    val runs = rule.onNodeWithText("RUNS").performScrollTo().fetchSemanticsNode().boundsInRoot.top
    assertTrue("its work comes before its runs, as on iOS", work < runs)
    assertEquals(listOf("refunds-1"), loaded.distinct())
  }
}
