package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppModel
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tablet's one sidebar, as the iPad's (docs/android-app.md, Tablets): at the iPad's size it opens on the Overview, with the
 * app's screens, the held calls and the tafs in the sidebar; a taf chosen there opens beside it, highlighted. A phone keeps its tab
 * bar and has no sidebar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class TabletSidebarTest {
  @get:Rule val compose = createComposeRule()

  private fun show(qualifiers: String): AppModel {
    RuntimeEnvironment.setQualifiers("en-$qualifiers")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val model = testModel(clock = { java.time.Instant.now() }).also { it.showForScreenshots() }
    compose.setContent {
      TafsTheme {
        CompositionLocalProvider(LocalBiometry provides "your fingerprint") {
          bot.tafs.app.views.ProvideApprovalReadiness(model.state.value.mode, bot.tafs.app.model.ApprovalReadiness.Ready, recheck = {}) {
            SignedInView(model, onSignIn = {})
          }
        }
      }
    }
    compose.waitForIdle()
    return model
  }

  private val inSidebar = hasAnyAncestor(hasTestTag("sidebar"))

  @Test fun aTabletOpensOnTheOverviewWithTheWholeAppInItsSidebar() {
    val model = show("w1366dp-h1024dp-land-xhdpi")
    // The Overview beside the sidebar, and the app's mark selected.
    compose.onNode(hasText(Words.Home.Overview.title)).assertIsDisplayed()
    compose.onNode(hasContentDescription(Words.App.name) and inSidebar).assertIsSelected()
    // Needs you with its count, Record, the held calls, each taf, Coach and the account, all in the sidebar.
    compose.onNode(hasText(model.state.value.needsYouTabTitle) and inSidebar).assertIsDisplayed()
    compose.onNode(hasText("${model.state.value.needsYouCount}") and inSidebar).assertIsDisplayed()
    compose.onNode(hasText(Words.Record.title) and inSidebar).assertIsDisplayed()
    compose.onNode(hasText(Words.NeedsYou.title.uppercase()) and inSidebar).assertIsDisplayed()
    model.state.value.tafs.forEach { taf -> assertTrue(taf.name, compose.onAllNodes(hasText(taf.name) and inSidebar).fetchSemanticsNodes().isNotEmpty()) }
    compose.onNode(hasContentDescription(Words.Coach.coachMode) and inSidebar).assertIsDisplayed()
    compose.onNode(hasText(Words.Settings.title) and inSidebar).assertIsDisplayed()
    // No tab bar on a tablet.
    assertTrue(compose.onAllNodes(hasText(Words.Settings.title) and !inSidebar).fetchSemanticsNodes().isEmpty())
  }

  @Test fun aTafChosenInTheSidebarOpensBesideItHighlighted() {
    show("w1366dp-h1024dp-land-xhdpi")
    compose.onNode(hasText("Refunds") and inSidebar).performClick()
    compose.waitForIdle()
    // Its page beside the sidebar (its tabs), and its row the one selected.
    compose.onNode(hasText("Activity")).assertIsDisplayed()
    compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true) and inSidebar and hasText("Refunds")).assertIsDisplayed()
    // The mark takes it back to the Overview.
    compose.onNode(hasContentDescription(Words.App.name) and inSidebar).performClick()
    compose.waitForIdle()
    compose.onNode(hasText(Words.Home.Overview.title)).assertIsDisplayed()
  }

  @Test fun needsYouAndRecordOpenBesideTheSidebar() {
    show("w1366dp-h1024dp-land-xhdpi")
    compose.onNode(hasText(Words.Record.title) and inSidebar).performClick()
    compose.waitForIdle()
    // The sidebar stays, and Record is the one selected.
    compose.onNode(hasTestTag("sidebar")).assertIsDisplayed()
    compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true) and hasText(Words.Record.title)).assertIsDisplayed()
  }

  /** Home's label chips sit over the Overview on a tablet, as on the iPad, and filter its table without changing its order. */
  @Test fun theOverviewsLabelChipsFilterItsTable() {
    RuntimeEnvironment.setQualifiers("en-w1366dp-h1024dp-land-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    fun taf(name: String, vararg labels: String) = bot.tafs.app.model.Taf(
      id = name, name = name, job = "", tools = emptyList(), colorIndex = 0, card = bot.tafs.app.model.Card(emptyList(), "o"), labels = labels.toList(),
    )
    val state = bot.tafs.app.app.AppState(
      mode = bot.tafs.app.app.Mode.kiff, ready = true, listLoaded = true,
      labels = listOf(bot.tafs.app.kiff.HomeLabel("l_money", "Money", count = 1), bot.tafs.app.kiff.HomeLabel("l_clients", "Clients", count = 1)),
      tafs = listOf(taf("Bills in", "l_money"), taf("New clients", "l_clients"), taf("Unlabelled")),
    )
    compose.setContent { TafsTheme { bot.tafs.app.views.OverviewView(state, loadReport = {}, onOpenCalendar = null) {} } }
    compose.waitForIdle()
    listOf("Bills in", "New clients", "Unlabelled").forEach { compose.onNode(hasText(it)).assertIsDisplayed() }
    compose.onNode(hasText("Money") and androidx.compose.ui.test.hasClickAction()).performClick()
    compose.waitForIdle()
    compose.onNode(hasText("Bills in")).assertIsDisplayed()
    assertTrue(compose.onAllNodes(hasText("New clients")).fetchSemanticsNodes().isEmpty())
    assertTrue(compose.onAllNodes(hasText("Unlabelled")).fetchSemanticsNodes().isEmpty())
  }

  /** Needs you counts more than the held calls (Coach's chats waiting): one line in tafs cloud's words, opening Needs you (V6). */
  @Test fun theOverviewSaysWhatElseNeedsYouAndOpensIt() {
    RuntimeEnvironment.setQualifiers("en-w1366dp-h1024dp-land-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val taf = bot.tafs.app.model.Taf(id = "t1", name = "Morning inbox", job = "", tools = emptyList(), colorIndex = 0, card = bot.tafs.app.model.Card(emptyList(), "o"))
    val state = bot.tafs.app.app.AppState(
      mode = bot.tafs.app.app.Mode.kiff, ready = true, listLoaded = true, tafs = listOf(taf),
      needsYou = bot.tafs.app.kiff.NeedsYouCount(3, "3 need you."),
    )
    var opened = 0
    compose.setContent { TafsTheme { bot.tafs.app.views.OverviewView(state, loadReport = {}, onOpenCalendar = null, onNeedsYou = { opened++ }) {} } }
    compose.waitForIdle()
    compose.onNode(hasText(Words.NeedsYou.title.uppercase())).assertIsDisplayed()
    compose.onNode(hasTestTag("overview.needsYou")).assertIsDisplayed().performClick()
    org.junit.Assert.assertEquals(1, opened)
    compose.onNode(hasText("3 need you.")).assertIsDisplayed()
  }

  /** Account opens beside the sidebar, as the iPad's, its row highlighted and with no Done; another row closes it (V40). */
  @Test fun accountOpensBesideTheSidebarAndAnotherRowClosesIt() {
    show("w1366dp-h1024dp-land-xhdpi")
    compose.onNode(hasText(Words.Settings.title) and inSidebar).performClick()
    compose.waitForIdle()
    compose.onNode(hasTestTag("beside-sidebar")).assertIsDisplayed()
    compose.onNode(hasTestTag("sidebar")).assertIsDisplayed()
    compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true) and inSidebar and hasContentDescription(Words.Settings.title, substring = true)).assertIsDisplayed()
    assertTrue(compose.onAllNodes(hasText(Words.Common.done)).fetchSemanticsNodes().isEmpty())
    compose.onNode(hasText(Words.Record.title) and inSidebar).performClick()
    compose.waitForIdle()
    assertTrue(compose.onAllNodes(hasTestTag("beside-sidebar")).fetchSemanticsNodes().isEmpty())
  }

  @Test fun aPhonesAccountIsStillASheet() {
    show("w384dp-h682dp-port-450dpi")
    compose.onAllNodes(hasText(Words.Settings.title) and androidx.compose.ui.test.hasClickAction())[0].performClick()
    compose.waitForIdle()
    assertTrue(compose.onAllNodes(hasTestTag("beside-sidebar")).fetchSemanticsNodes().isEmpty())
    compose.onAllNodes(hasText(Words.Common.done))[0].assertIsDisplayed()
  }

  /** tafs cloud's `relevant_now`: the sidebar lists only the tafs it marks, as the iPad's; with no word from it, every taf. */
  @Test fun theSidebarListsTheTafsTafsCloudSaysAreRelevantNow() {
    RuntimeEnvironment.setQualifiers("en-w1366dp-h1024dp-land-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    fun taf(name: String, relevant: Boolean?) = bot.tafs.app.model.Taf(
      id = name, name = name, job = "", tools = emptyList(), colorIndex = 0, card = bot.tafs.app.model.Card(emptyList(), "o"), relevantNow = relevant,
    )
    var state by androidx.compose.runtime.mutableStateOf(
      bot.tafs.app.app.AppState(mode = bot.tafs.app.app.Mode.kiff, ready = true, listLoaded = true, tafs = listOf(taf("Bills in", true), taf("Quiet one", false))),
    )
    compose.setContent {
      TafsTheme {
        bot.tafs.app.views.TabletSidebar(
          state, bot.tafs.app.views.Beside.Overview, onOverview = {}, onNeedsYou = {}, onRecord = {}, onCalendar = null, onUsage = null,
          onConnectApps = null, onHaltAll = {}, onOpenTaf = {}, onFinishSetup = null, onDiscardSetup = null, onExplore = null, onNewTaf = null,
          onPlayNext = null, onCoach = null, onAccount = {}, onFold = {},
        )
      }
    }
    compose.waitForIdle()
    compose.onNode(hasText("Bills in")).assertIsDisplayed()
    assertTrue(compose.onAllNodes(hasText("Quiet one")).fetchSemanticsNodes().isEmpty())
    // A tafs cloud from before the flag: every taf.
    state = state.copy(tafs = listOf(taf("Bills in", null), taf("Quiet one", null)))
    compose.waitForIdle()
    compose.onNode(hasText("Quiet one")).assertIsDisplayed()
  }

  @Test fun aPhoneKeepsItsTabBarAndHasNoSidebar() {
    show("w384dp-h682dp-port-450dpi")
    assertTrue(compose.onAllNodes(hasTestTag("sidebar")).fetchSemanticsNodes().isEmpty())
    compose.onNode(hasText(Words.Settings.title)).assertIsDisplayed()
  }
}
