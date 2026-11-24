package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.views.LinkConfirm
import bot.tafs.app.views.LocalAskLink
import bot.tafs.app.views.PageTurnText
import bot.tafs.app.views.linkAsks
import bot.tafs.app.views.linkConfirmBody
import bot.tafs.app.views.linkOpens
import org.junit.Assert.assertFalse
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A link in Coach's answer about a page asks before it opens (docs/chat.md): the whole address, Open and Cancel, and it stays up
 * when the answer it came from finishes while it's showing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LinkConfirmTest {
  @get:Rule val compose = createComposeRule()

  private val opened = mutableListOf<String>()
  private var ask: ((String) -> Unit)? = null
  private var live by mutableStateOf(true)
  private val address = "https://shop.example/a/very/long/path?q=1"

  @Before fun words() {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
  }

  private fun chat() {
    compose.setContent {
      TafsTheme {
        CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
          LinkConfirm {
            ask = LocalAskLink.current
            // A live answer, then the same answer finished and drawn anew as a message without the flag.
            if (live) PageTurnText("[the shop]($address)", asks = true) else PageTurnText("[the shop]($address)", asks = false)
          }
        }
      }
    }
  }

  @Test fun itShowsTheWholeAddressAndCancelOpensNothing() {
    chat()
    compose.runOnIdle { ask!!(address) }
    compose.onNodeWithText(Words.Chat.linkConfirmTitle).assertExists()
    assertTrue(compose.onNodeWithTag("link-confirm-body").fetchSemanticsNode().config.toString().contains(address))
    compose.onNodeWithText(Words.Common.cancel).performClick()
    compose.onNodeWithText(Words.Chat.linkConfirmTitle).assertDoesNotExist()
    assertEquals(emptyList<String>(), opened)
  }

  @Test fun itStaysUpWhenTheAnswerFinishesAndOpenOpensIt() {
    chat()
    compose.runOnIdle { ask!!(address) }
    compose.onNodeWithText(Words.Chat.linkConfirmTitle).assertExists()
    live = false
    compose.waitForIdle()
    compose.onNodeWithText(Words.Chat.linkConfirmTitle).assertExists()
    compose.onNodeWithText(Words.Chat.linkConfirmOpen).performClick()
    compose.onNodeWithText(Words.Chat.linkConfirmTitle).assertDoesNotExist()
    assertEquals(listOf(address), opened)
  }

  /** Open is offered only for https: another app's scheme, tel:, sms: or plain http gets the address and Cancel alone. */
  @Test fun onlyAnHttpsAddressCanBeOpened() {
    assertTrue(linkOpens("https://shop.example/a?q=1"))
    assertTrue(linkOpens("HTTPS://shop.example/"))
    for (url in listOf(
      "http://shop.example/", "tel:+15550100", "sms:+15550100", "market://details?id=x", "intent://pay#Intent;scheme=bank;end",
      "bank://pay?to=1", "javascript:alert(1)", "https:///nohost", "https://shop.example/\u202Egnp.exe", "https://shop.example/a b", "not a url",
    )) assertFalse(url, linkOpens(url))
    chat()
    compose.runOnIdle { ask!!("tel:+15550100") }
    compose.onNodeWithText(Words.Chat.linkConfirmTitle).assertExists()
    compose.onNodeWithText(Words.Chat.linkConfirmOpen).assertDoesNotExist()
    compose.onNodeWithText(Words.Common.cancel).performClick()
    compose.onNodeWithText(Words.Chat.linkConfirmTitle).assertDoesNotExist()
    assertEquals(emptyList<String>(), opened)
  }

  /** In Arabic the address sits in a right-to-left sentence: it's set apart left to right, whole, so what's shown is what opens. */
  @Test fun inArabicTheAddressIsSetApartLeftToRight() {
    RuntimeEnvironment.setQualifiers("ar")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val body = linkConfirmBody("https://shop.example/a/b?q=1&n=20")
    assertTrue(body, body.contains("\u2066https://shop.example/a/b?q=1&n=20\u2069"))
    assertTrue("in the Arabic words", body.any { it in '\u0600'..'\u06FF' })
    // A direction character in the address can't close the isolate early.
    assertFalse(linkConfirmBody("https://a.example/\u2069\u202Ex").contains("\u202E"))
  }

  /** In any answer, a link that isn't plain https asks (with no Open); a plain https link opens on the tap unless it's a page turn's. */
  @Test fun aChatLinkThatIsntHttpsAsksInEveryAnswer() {
    assertFalse(linkAsks("https://shop.example/a", pageTurn = false))
    assertTrue(linkAsks("https://shop.example/a", pageTurn = true))
    for (url in listOf("tel:+15550100", "bank://pay?to=1", "http://shop.example/", "intent://x#Intent;end", "https://shop.example/a b")) {
      assertTrue(url, linkAsks(url, pageTurn = false))
    }
  }
}
