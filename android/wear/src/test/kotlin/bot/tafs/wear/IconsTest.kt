package bot.tafs.wear

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.MaterialTheme
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Icons over words (row 44, B): on Coach's home, Type and Quick reply each fit under their icon in every language the watch
 * ships, on the smaller face, never cut, never broken inside a word; the retry is ↻, named for TalkBack.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = WatchApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconsTest(private val language: String) {
  companion object {
    @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun languages() = listOf("en", "de", "fr", "es", "it", "pt", "nl", "pl", "ko")
  }

  @get:Rule val compose = createComposeRule()

  @Before fun words() {
    // The 40 mm face's width, tall so the whole page is drawn.
    RuntimeEnvironment.setQualifiers("$language-w192dp-h2400dp-round-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  private val tafs = WatchModel.TafsRead(listOf(TafLine("taf_1", "Late parcels", "Working", halted = false)), allHalted = false)
  private val read = WatchModel.Shown.Read(
    WatchModel.NeedsYouRead(1, "1", listOf(HeldLine("hold_1", "Late parcels", "Reship", "€12", "Wait", refusable = true))), tafs)

  private fun layout(tag: String): TextLayoutResult {
    val out = mutableListOf<TextLayoutResult>()
    compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
    return out.single()
  }

  @Test fun typeAndQuickReplyFitUnderTheirIcons() {
    val pages = pages(false)
    compose.setContent {
      MaterialTheme {
        WatchPages(read, outcome = null, confirmingHaltAll = false, voice = null, result = null, working = emptyList(), ready = emptyList(),
          act = Actions.none, pages = pages, pager = rememberPagerState(initialPage = pages.indexOf(Page.Coach)) { pages.size })
      }
    }
    for ((tag, word) in listOf(HOME_TYPE to Words.Watch.type, HOME_QUICK to Words.Watch.quickReply)) {
      val r = layout("$tag-word")
      val text = r.layoutInput.text.text
      assertTrue("$language: \"$text\" isn't $word", text == word)
      assertTrue("$language: \"$word\" is cut or broken inside a word", fits(r))
    }
  }

  @Test fun retryIsARoundArrowNamedForTalkBack() {
    compose.setContent { MaterialTheme { VoiceScreen(WatchModel.Voice.Unsure("Move my call", "w_00000001", null), Actions.none) } }
    compose.onNodeWithTag(RETRY).assertContentDescriptionEquals(Words.Watch.retry)
  }
}
