package bot.tafs.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An approval card whose Approve signs nothing (`signs.takes: "none"`, docs/chat.md): tafs cloud answers it with no step, or
 * reads it again when it can't yet tell whether this conversation asked for it. The button says "Approve", never naming the
 * fingerprint; the phone asks for one only when tafs cloud's answer is a step that signs (`AppModel.approveCoach`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h900dp-port")
class NothingToSignCardTest {
  @get:Rule val compose = createComposeRule()

  private val state = AppState(mode = Mode.kiff, ready = true, planqueReady = true,
    grants = listOf(Grant(id = "g1", status = "pending", text = "Qonto may list client invoices.", vendorName = "Qonto", digestMatches = true)))

  private fun card(takes: String) = KiffClient.json.decodeFromString(
    SetupComponent.serializer(),
    """{"type":"approval","id":"ap-qonto","title":"Connect Qonto","grant":"g1","signs":{"card":false,"takes":"$takes"}}""",
  )

  private fun draw(takes: String, onApprove: () -> Unit = {}) {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    compose.setContent {
      TafsTheme {
        CoachCard(
          ShownComponent(java.time.Instant.EPOCH, "s", "r", card(takes), null, "k"), state,
          CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> onApprove() }, cancelSignIn = {}),
        )
      }
    }
    compose.waitForIdle()
  }

  @Test fun approveThatSignsNothingNeverNamesTheFingerprint() {
    var approved = 0
    draw("none", onApprove = { approved++ })
    compose.onAllNodesWithText(Words.Approval.approveWith(Words.Approval.Biometry.screenLock)).assertCountEquals(0)
    compose.onNodeWithText(Words.Approval.approve).performClick()
    compose.waitForIdle()
    assertEquals(1, approved)
  }

  @Test fun aCardThatTakesTheFingerprintStillNamesIt() {
    draw("fingerprint")
    compose.onAllNodesWithText(Words.Approval.approve).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Approval.approveWith(Words.Approval.Biometry.screenLock)).assertCountEquals(1)
  }
}
