package bot.tafs.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.model.Res
import bot.tafs.app.views.ConnectAppsSheet
import bot.tafs.app.views.ConnectAppsWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Connect apps on a narrow phone, in the languages whose "Sign in again" is longest (the owner's Windows note 3: "Volver a
 * iniciar sesión" was cut, and Ask Coach covered it). Every line of an app's row is drawn whole: its name, its second line and
 * the action beside it, none cut short.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class ConnectAppsNarrowTest(private val language: String) {
  companion object {
    @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun languages() = listOf("es", "de", "fr", "pl", "en")
  }

  @get:Rule val compose = createComposeRule()

  private val list = ConnectApps(
    listOf(
      ConnectApps.App("stripe@1", "Stripe", "Stripe", state = "connected", grant = "g1"),
      ConnectApps.App("notion@1", "Notion", "Notion"),
      ConnectApps.App("google", "Google", "Google Sheets", state = "needs_reconnect", grant = "g2"),
    ),
    byKey = true,
  )

  private fun taf() = bot.tafs.app.model.Taf(
    id = "refunds-1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1,
    card = bot.tafs.app.model.Card(rules = emptyList(), issuedBy = "You"), now = "", cloudId = "cloud_refunds-1",
  )

  private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
    val out = mutableListOf<TextLayoutResult>()
    node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
    return out.single()
  }

  @Test fun everyLineOfAnAppsRowIsDrawnWholeOnANarrowPhone() {
    // The narrowest phone the app is laid out for, at the largest of the usual densities.
    RuntimeEnvironment.setQualifiers("$language-w360dp-h740dp-port-xxhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    assertEquals(language.substringBefore("-"), Res.language)
    val tafs = listOf(taf())
    compose.setContent {
      TafsTheme {
        ConnectAppsSheet(
          AppState(mode = Mode.kiff, ready = true, connectApps = list, tafs = tafs, access = tafs.associate { it.id to bot.tafs.app.kiff.Access(use = true, manage = true) }),
          load = {},
          onConnect = { _, _, _ -> },
          onAskCoach = {},
          onClose = {},
        )
      }
    }
    compose.waitForIdle()
    // Each text on the screen: nothing cut at its last line, whatever wraps.
    val texts = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
    val cut = texts.mapIndexedNotNull { i, node ->
      val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") ?: return@mapIndexedNotNull null
      val out = mutableListOf<TextLayoutResult>()
      node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
      val l = out.singleOrNull() ?: return@mapIndexedNotNull null
      // Cut means lines lost, below its last line or to an ellipsis. Not didOverflowWidth: Robolectric reports it for a short
      // line drawn whole in a box shrunk to fit it.
      words.takeIf { l.didOverflowHeight || (0 until l.lineCount).any { l.isLineEllipsized(it) } }
    }
    assertEquals("cut short on a 360 dp phone in $language", emptyList<String>(), cut)
    // "Sign in again" beside Google Sheets is one whole line of its own words.
    val again = layout(compose.onNode(hasText(ConnectAppsWords.SIGN_IN_AGAIN) and androidx.compose.ui.test.hasClickAction()))
    assertFalse("Sign in again cut in $language", again.didOverflowHeight || (0 until again.lineCount).any { again.isLineEllipsized(it) })
  }
}
