package bot.tafs.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Home
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.views.NeedsYouView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A knock at a taf's door waits for the owner (docs/doors.md): tafs cloud counts the taf in Needs you, so Needs you lists it, as
 * iOS does, and the row opens the taf on Activity, where the knock is answered. The count is Home's, per door; the app adds them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h900dp-port")
class NeedsYouKnocksTest {
  @get:Rule val compose = createComposeRule()

  private fun taf(doors: String?) = KiffMapping.taf(
    KiffClient.json.decodeFromString<Home>(
      """{"tafs":[{"id":"taf_1","name":"File receipts","kiff_agent":"receipts-1","status":"active","color":1,"state":"waiting",""" +
        """"card":{"ids":["c1"],"active":true}${doors?.let { ""","doors":$it""" } ?: ""}}]}""",
    ).tafs.single(),
    "You",
  )

  @Test fun homesDoorLightsGiveTheTafsKnocks() {
    assertEquals(3, taf("""[{"app":"email","resting":false,"knocks":1},{"app":"slack","knocks":2}]""").knocks)
    assertEquals("no doors sent (someone who doesn't manage it)", 0, taf(null).knocks)
    assertEquals("a door with none", 0, taf("""[{"app":"email","resting":true}]""").knocks)
  }

  private fun draw(state: AppState, opened: MutableList<String>?) {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    compose.setContent {
      TafsTheme { NeedsYouView(state, onAnswer = { _, _ -> }, onOpenKnocks = opened?.let { o -> { id: String -> o += id } }) }
    }
  }

  @Test fun aTafWithAKnockIsListedAndOpensWhereItsAnswered() {
    val knocked = taf("""[{"app":"email","knocks":1}]""")
    val opened = mutableListOf<String>()
    draw(AppState(mode = Mode.kiff, tafs = listOf(knocked)), opened)
    compose.onNodeWithText(Words.NeedsYou.knocks(count = 1)).assertExists()
    // Not "nothing waiting": the knock waits.
    compose.onAllNodesWithTag("knocked-${knocked.id}").assertCountEquals(1)
    compose.onNodeWithTag("knocked-${knocked.id}").performClick()
    assertEquals(listOf(knocked.id), opened)
    assertEquals(0, compose.onAllNodesWithText(Words.NeedsYou.nothingWaiting).fetchSemanticsNodes().size)
  }

  @Test fun noKnocksNoRowAndTheSampleHasNone() {
    draw(AppState(mode = Mode.kiff, tafs = listOf(taf(null))), mutableListOf())
    compose.onNodeWithText(Words.NeedsYou.nothingWaiting).assertExists()
    compose.onAllNodesWithTag("knocked-${taf(null).id}").assertCountEquals(0)
  }

  @Test fun withNoWayToOpenItTheSampleListsNoKnock() {
    val knocked = taf("""[{"app":"email","knocks":1}]""")
    draw(AppState(mode = Mode.sample, tafs = listOf(knocked)), null)
    compose.onAllNodesWithTag("knocked-${knocked.id}").assertCountEquals(0)
  }
}
