package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.PairQr
import bot.tafs.app.kiff.Pairing
import bot.tafs.app.views.DevicesSheet
import bot.tafs.app.views.PAIR_BUTTON
import bot.tafs.app.views.PAIR_CHECK
import bot.tafs.app.views.PAIR_LINE
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Devices, Pair a computer (docs/android-app.md, Pairing a computer): the scanned QR's check code is asked about before anything
 * is signed; Yes pairs that computer and shows KIFF's answer; Cancel, or a QR that isn't a pairing, pairs nothing.
 */
class PairComputerTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val state = AppState(mode = Mode.kiff, ready = true, kiffAccount = Account("t_01", "acme"))
  private val paired = mutableListOf<PairQr>()

  private fun key(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded,
  )

  private val qr = """{"v":"kiff.pair.v1","pub":"${key()}","approval_pub":"${key()}","name":"tafs on this Windows PC","platform":"windows",""" +
    """"kind":"hello","nonce":"bm9uY2Vub25jZW5vbmNlbm9uY2Vub25jZW5vbmNlMQ","exp":"2026-11-14T18:05:00Z"}"""

  private fun open(scanned: String?) {
    rule.setShown {
      TafsTheme {
        DevicesSheet(
          state, refresh = {}, remove = { false }, onClose = {},
          scan = { scanned },
          pair = { paired += it; Pairing("pair_1", it.checkCode!!, "2026-11-14T18:05:00Z") },
        )
      }
    }
    rule.onNodeWithTag(PAIR_BUTTON).performScrollTo().performClick()
  }

  @Test fun theCheckCodeIsAskedAboutThenYesPairsThatComputer() {
    open(qr)
    val code = PairQr.read(qr)!!.checkCode!!
    rule.onNodeWithText("Pair tafs on this Windows PC?").assertIsDisplayed()
    rule.onNodeWithTag(PAIR_CHECK).assertIsDisplayed()
    rule.onNodeWithText("Does your computer show $code?").assertIsDisplayed()
    assertTrue("nothing is signed before the yes", paired.isEmpty())
    rule.onNodeWithText("Yes, pair it").performClick()
    rule.waitForIdle()
    assertEquals(listOf("tafs on this Windows PC"), paired.map { it.name })
    rule.onNodeWithTag(PAIR_LINE).assertIsDisplayed()
    rule.onNodeWithText("tafs on this Windows PC is paired. It showed $code.").assertIsDisplayed()
  }

  @Test fun cancelPairsNothing() {
    open(qr)
    rule.onNodeWithText("Cancel").performClick()
    rule.waitForIdle()
    assertTrue(paired.isEmpty())
    rule.onNodeWithText("Pair tafs on this Windows PC?").assertDoesNotExist()
  }

  @Test fun aQrThatIsntAPairingSaysSoAndAsksNothing() {
    open("https://example.com/menu")
    rule.onNodeWithText("That QR code isn't a computer asking to be paired.").assertIsDisplayed()
    assertTrue(paired.isEmpty())
  }
}
