package bot.tafs.app

import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Res
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tablet's screens at the iPad's size (1366 × 1024 dp, landscape, 2732 × 2048 px), over the sample, to set beside the iPad's
 * reference files (docs/android-app.md, Tablets). Drawn into docs/screenshots/android/tablet/ only when asked
 * (`./gradlew :app:testDebugUnitTest --tests '*TabletScreenshots*' -PtabletScreenshots`); otherwise each screen is still drawn,
 * so one that no longer draws fails here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class TabletScreenshots {
  @get:Rule val compose = createComposeRule()

  private val repo = File(System.getProperty("tafs.repo") ?: "../..")

  private fun show(vararg taps: String) {
    RuntimeEnvironment.setQualifiers("en-w1366dp-h1024dp-land-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val model = testModel(clock = { java.time.Instant.now() }).also { it.showForScreenshots() }
    compose.setContent {
      TafsTheme {
        CompositionLocalProvider(LocalBiometry provides bot.tafs.app.signing.Biometry.name(strongEnrolled = true, face = false, fingerprint = true)) {
          bot.tafs.app.views.ProvideApprovalReadiness(model.state.value.mode, bot.tafs.app.model.ApprovalReadiness.Ready, recheck = {}) {
            SignedInView(model, onSignIn = {})
          }
        }
      }
    }
    for (words in taps) {
      compose.waitForIdle()
      compose.onAllNodes(hasText(words) and hasClickAction() and hasAnyAncestor(hasTestTag("sidebar")))[0].performClick()
    }
    compose.waitForIdle()
  }

  private fun shot(name: String) = captureScreenRoboImage(File(repo, "docs/screenshots/android/tablet/$name.png").path)

  /** Beside the iPad's `Overview.PNG`: the sidebar, and the Overview a tablet opens on. */
  @Test fun overview() { show(); shot("overview") }

  /** Beside the iPad's `Record-overview.PNG`: the Record's days, each heading with what its money came to (`record.day_spent`). */
  @Test fun recordDay() {
    kiffTablet()
    val now = java.time.Instant.now()
    fun call(title: String, value: String) = bot.tafs.app.model.RecordEntry(
      tafId = "refunds-1", kind = bot.tafs.app.model.RecordEntry.Kind.done, title = title, detail = "€$value, within the Card", at = now,
      amountValue = value, amountUnit = "EUR", amountText = "€$value",
    )
    val state = kiffState().copy(record = listOf(call("Refund order #4471", "40.00"), call("Credit on account #2290", "25.00"), call("Refund order #4480", "575.00")))
    compose.setContent {
      TafsTheme {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(bot.tafs.app.design.Theme.background)) {
          bot.tafs.app.views.RecordView(state)
        }
      }
    }
    compose.waitForIdle()
    shot("record-day")
  }

  /** Beside the iPad's `Needs-you.PNG`: Needs you, opened from the sidebar, beside it. */
  @Test fun needsYou() { show("Needs you"); shot("needs-you") }

  /** Beside the iPad's `Account-top.PNG`: Account beside the sidebar, its row highlighted, with no Done. */
  @Test fun accountBeside() { show("Account"); shot("account-beside") }

  /** Beside the iPad's `Taf-card-tab.PNG` and `Taf-chat-tab-finished.PNG`: a taf chosen in the sidebar, highlighted there. */
  @Test fun taf() { show("Refunds"); shot("taf") }

  private fun kiffTablet() {
    RuntimeEnvironment.setQualifiers("en-w1366dp-h1024dp-land-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
  }

  private val refunds = bot.tafs.app.model.Taf(
    id = "refunds-1", name = "Refunds", job = "Refund late or damaged orders", tools = listOf("create_refund"), colorIndex = 1,
    card = bot.tafs.app.model.Card(listOf(bot.tafs.app.model.CardRule(action = "create_refund", label = "Refund a payment", perWindow = bot.tafs.app.model.Money(200000, "EUR", 2))), "You"),
    cloudId = "taf_1", timeZoneWords = "Times in Europe/Paris",
    kiffCards = listOf(bot.tafs.app.model.TafCard("card_1", "Refunds", rules = listOf(bot.tafs.app.model.CardRule(action = "create_refund", label = "Refund a payment", perWindow = bot.tafs.app.model.Money(200000, "EUR", 2))))),
  )

  private fun kiffState() = bot.tafs.app.app.AppState(
    mode = bot.tafs.app.app.Mode.kiff, ready = true, listLoaded = true, tafs = listOf(refunds),
    kiffAccount = bot.tafs.app.kiff.Account("t_01", "acme", capabilities = listOf("cards.manage")),
    access = mapOf("refunds-1" to bot.tafs.app.kiff.Access(use = true, oversee = true, manage = true)),
    versions = mapOf("refunds-1" to listOf(bot.tafs.app.kiff.Version(1, created = java.time.Instant.EPOCH), bot.tafs.app.kiff.Version(2, created = java.time.Instant.EPOCH, live = true))),
    needsYou = bot.tafs.app.kiff.NeedsYouCount(3, "3 need you."),
  )

  /** Beside the iPad's `Taf-card-tab.PNG`, signed in: the head with its live version, and "Times in …" above the Card. */
  @Test fun tafCardSignedIn() {
    kiffTablet()
    val actions = bot.tafs.app.views.TafActions(
      answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
      sendChat = { _, _, _, _ -> }, issuer = "KIFF",
      doors = bot.tafs.app.views.DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
      coach = bot.tafs.app.views.CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
      connections = bot.tafs.app.views.ConnectionActions({}, { false }),
    )
    compose.setContent {
      TafsTheme {
        CompositionLocalProvider(LocalBiometry provides "your fingerprint") {
          androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(bot.tafs.app.design.Theme.background)) {
            bot.tafs.app.views.TafDetailView("refunds-1", kiffState(), actions, onBack = {}, onHalt = {}, onUsage = {}, onVersions = {}, showBack = false)
          }
        }
      }
    }
    compose.onNode(hasText("Card") and hasClickAction()).performClick()
    compose.waitForIdle()
    shot("taf-card-signed-in")
  }

  /** Beside the iPad's `Overview.PNG`: with nothing held but more waiting, the one "3 need you." line with its chevron. */
  @Test fun overviewNeedsYouSignedIn() {
    kiffTablet()
    compose.setContent {
      TafsTheme {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(bot.tafs.app.design.Theme.background)) {
          bot.tafs.app.views.OverviewView(kiffState(), loadReport = {}, onOpenCalendar = null, onNeedsYou = {}) {}
        }
      }
    }
    compose.waitForIdle()
    shot("overview-needs-you")
  }

  private fun kiffActions() = bot.tafs.app.views.TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = bot.tafs.app.views.DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = bot.tafs.app.views.CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = bot.tafs.app.views.ConnectionActions({}, { false }),
  )

  private fun page(state: bot.tafs.app.app.AppState) = compose.setContent {
    TafsTheme {
      CompositionLocalProvider(LocalBiometry provides "your fingerprint") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(bot.tafs.app.design.Theme.background)) {
          bot.tafs.app.views.TafDetailView("refunds-1", state, kiffActions(), onBack = {}, onHalt = {}, onUsage = {}, onVersions = {}, showBack = false)
        }
      }
    }
  }

  /** Beside the iPad's `Taf-setup-tab-inside-starts-when.PNG`: Starts when in the page's place, with a round Back and its title. */
  @Test fun startsWhenPushed() {
    kiffTablet()
    page(kiffState().copy(knobs = mapOf("refunds-1" to bot.tafs.app.kiff.KnobWire(wakes = listOf(bot.tafs.app.kiff.KnobWire.Item(id = "s1", kind = "schedule", label = "Every day at 09:00", words = "Every day at 09:00"))))))
    compose.onNode(hasText("Setup") and hasClickAction()).performClick()
    compose.waitForIdle()
    compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(bot.tafs.app.model.SetupWords.STARTS_WHEN, substring = true) and hasClickAction())[0].performClick()
    compose.waitForIdle()
    shot("starts-when")
  }

  /** Beside the iPad's `Taf-activity-tab.PNG`: Activity's filters as tinted pills, amber for the one chosen. */
  @Test fun activityFilters() {
    kiffTablet()
    val choices = listOf("all" to "All", "done" to "Done", "held" to "Held", "refused" to "Refused").map { (id, title) -> bot.tafs.app.kiff.ActivityChoice(id, title) }
    val pageOne = bot.tafs.app.kiff.TafActivityPage(filters = choices)
    page(kiffState().copy(activityPages = mapOf("refunds-1:all:" to bot.tafs.app.app.SourcePages(listOf(pageOne)))))
    compose.onNode(hasText("Activity") and hasClickAction()).performClick()
    compose.waitForIdle()
    shot("activity-filters")
  }
}
