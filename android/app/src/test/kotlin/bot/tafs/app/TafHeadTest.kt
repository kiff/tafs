package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.Version
import bot.tafs.app.model.Card
import bot.tafs.app.model.Res
import bot.tafs.app.model.Taf
import bot.tafs.app.model.Words
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafDetailView
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A taf's head, as the iPad's (parity V13): under its name, its live version, quiet, opening Versions, for whoever manages it on
 * KIFF; read when the page opens. Nothing of it for someone who only uses the taf.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class TafHeadTest {
  @get:Rule val compose = createComposeRule()

  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "Refund", tools = emptyList(), colorIndex = 1, card = Card(emptyList(), "You"), cloudId = "taf_1")
  private val read = mutableListOf<String>()
  private val opened = mutableListOf<String>()

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
    loadVersions = { read += it },
  )

  private fun show(
    mode: Mode = Mode.kiff, manage: Boolean = true, versions: List<Version> = emptyList(), tablet: Boolean = true,
    activity: Map<String, bot.tafs.app.app.SourcePages<bot.tafs.app.kiff.TafActivityPage>> = emptyMap(),
  ) {
    RuntimeEnvironment.setQualifiers("en-w1366dp-h1024dp-land-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val state = AppState(
      mode = mode, ready = true, tafs = listOf(taf), kiffAccount = Account("t_01", "acme", capabilities = listOf("cards.manage")),
      access = mapOf("refunds-1" to Access(use = true, oversee = true, manage = manage)),
      versions = if (versions.isEmpty()) emptyMap() else mapOf("refunds-1" to versions),
      knobs = mapOf("refunds-1" to bot.tafs.app.kiff.KnobWire()),
      activityPages = activity,
    )
    compose.setContent {
      TafsTheme {
        CompositionLocalProvider(LocalBiometry provides "your fingerprint") {
          TafDetailView("refunds-1", state, actions, onBack = {}, onHalt = {}, onUsage = {}, onVersions = { opened += it }, showBack = !tablet)
        }
      }
    }
    compose.waitForIdle()
  }

  @Test fun theLiveVersionIsUnderTheNameAndOpensVersions() {
    show(versions = listOf(Version(1, created = Instant.EPOCH), Version(2, created = Instant.EPOCH, live = true)))
    assertEquals(listOf("refunds-1"), read)
    compose.onNode(hasText(Words.Version.liveNumber("2"))).assertIsDisplayed().performClick()
    assertEquals(listOf("refunds-1"), opened)
  }

  @Test fun beforeItsVersionsAreReadTheLineSaysVersions() {
    show()
    compose.onNode(hasText(Words.Version.title)).assertIsDisplayed()
  }

  @Test fun someoneWhoOnlyUsesItHasNoVersionLine() {
    show(manage = false, versions = listOf(Version(2, created = Instant.EPOCH, live = true)))
    assertTrue(compose.onAllNodes(hasText(Words.Version.liveNumber("2"))).fetchSemanticsNodes().isEmpty())
    assertTrue(read.isEmpty())
  }

  /** Beside a tablet's sidebar, Starts when opens in the page's place, as the iPad pushes it (V26), and Back returns to Setup. */
  @Test fun besideTheSidebarStartsWhenOpensInThePagesPlace() {
    show()
    compose.onNode(hasText("Setup") and androidx.compose.ui.test.hasClickAction()).performClick()
    compose.waitForIdle()
    compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(bot.tafs.app.model.SetupWords.STARTS_WHEN, substring = true) and androidx.compose.ui.test.hasClickAction())[0].performClick()
    compose.waitForIdle()
    compose.onNode(androidx.compose.ui.test.hasTestTag("setup-page")).assertIsDisplayed()
    // The page's head and tabs are gone while it's open.
    assertTrue(compose.onAllNodes(hasText("Activity")).fetchSemanticsNodes().isEmpty())
    compose.onNode(androidx.compose.ui.test.hasContentDescription(Words.Common.back)).performClick()
    compose.waitForIdle()
    assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("setup-page")).fetchSemanticsNodes().isEmpty())
    compose.onNode(hasText("Activity")).assertIsDisplayed()
  }

  /** On a phone it stays a sheet: nothing takes the page's place. */
  @Test fun onAPhoneStartsWhenIsASheet() {
    show(tablet = false)
    compose.onNode(hasText("Setup") and androidx.compose.ui.test.hasClickAction()).performClick()
    compose.waitForIdle()
    compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(bot.tafs.app.model.SetupWords.STARTS_WHEN, substring = true) and androidx.compose.ui.test.hasClickAction())[0].performClick()
    compose.waitForIdle()
    assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("setup-page")).fetchSemanticsNodes().isEmpty())
  }

  /** Activity's filters, as the iPad's tinted pills: the chosen one says so. */
  @Test fun activitysFiltersMarkTheOneChosen() {
    val page = bot.tafs.app.kiff.TafActivityPage(filters = listOf(bot.tafs.app.kiff.ActivityChoice("all", "All"), bot.tafs.app.kiff.ActivityChoice("done", "Done")))
    show(activity = mapOf("refunds-1:all:" to bot.tafs.app.app.SourcePages(listOf(page))))
    compose.onNode(hasText("Activity") and androidx.compose.ui.test.hasClickAction()).performClick()
    compose.waitForIdle()
    compose.onNode(hasText("All") and androidx.compose.ui.test.hasClickAction()).assertIsSelected()
    compose.onNode(hasText("Done") and androidx.compose.ui.test.hasClickAction()).assertIsNotSelected()
  }
}
