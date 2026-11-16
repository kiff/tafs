package bot.tafs.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.PairQr
import bot.tafs.app.views.DEVICES_SHEET
import bot.tafs.app.views.DevicesSheet
import bot.tafs.app.views.PAIR_BUTTON
import bot.tafs.app.views.PAIR_CHECK
import bot.tafs.app.views.PAIR_LINE
import bot.tafs.app.views.PAIR_TITLE
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Devices, Add a device (docs/android-app.md, Pairing a computer): the sheet draws what the model holds (the QR asked about, the
 * line that says what came of it), so the sheet torn down and rebuilt as Google's scanner closes still shows them. It opens at full
 * height. Yes and Cancel go to the model; the model's tests cover what they do.
 */
class PairComputerTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val base = AppState(mode = Mode.kiff, ready = true, kiffAccount = Account("t_01", "acme"))
  private var shown by mutableStateOf(base)
  private var sheetUp by mutableStateOf(true)
  private var added = 0
  private var confirmed = 0
  private var cancelled = 0

  private fun key(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded,
  )

  private val qr = """{"v":"kiff.pair.v1","pub":"${key()}","approval_pub":"${key()}","name":"tafs on this Windows PC","platform":"windows",""" +
    """"kind":"hello","nonce":"bm9uY2Vub25jZW5vbmNlbm9uY2Vub25jZW5vbmNlMQ","exp":"2026-11-14T18:05:00Z"}"""

  private fun open() {
    rule.setShown {
      TafsTheme {
        if (sheetUp) {
          DevicesSheet(
            shown, refresh = {}, remove = { false }, onClose = {},
            addDevice = { added += 1 }, confirmPair = { confirmed += 1 }, cancelPair = { cancelled += 1 },
          )
        }
      }
    }
  }

  @Test fun addADeviceAsksTheModelToScan() {
    open()
    rule.onNodeWithTag(PAIR_BUTTON).performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(1, added)
  }

  @Test fun theQrHeldByTheModelIsAskedAboutAndYesGoesToTheModel() {
    shown = base.copy(pairAsking = PairQr.read(qr))
    open()
    val code = PairQr.read(qr)!!.checkCode!!
    // The title names no device: its name is only its claim, shown after the code. The Devices sheet behind has its own
    // "Add a device" button (itself in a dialog window, the sheet), so the alert's title is found by its own tag.
    rule.onNodeWithTag(PAIR_TITLE).assertIsDisplayed().assertTextEquals("Add a device")
    rule.onAllNodesWithText("Pair tafs on this Windows PC?").assertCountEquals(0)
    rule.onNodeWithTag(PAIR_CHECK).assertIsDisplayed()
    rule.onNodeWithText("Does your computer show $code?").assertIsDisplayed()
    rule.onNodeWithText("It calls itself tafs on this Windows PC.").assertIsDisplayed()
    rule.onNodeWithText("tafs on this Windows PC will be able to approve your tafs' actions with Windows Hello.").assertIsDisplayed()
    rule.onNodeWithText("Only pair a computer you're using right now.").assertIsDisplayed()
    rule.onNodeWithText("Yes, pair it").performClick()
    rule.waitForIdle()
    assertEquals(1, confirmed)
  }

  @Test fun aPhoneIsAskedAboutAsAPhone() {
    val phone = qr.replace("\"platform\":\"windows\",", "\"platform\":\"android\",").replace("\"kind\":\"hello\"", "\"kind\":\"keystore\"")
      .replace("tafs on this Windows PC", "Ana's tablet")
    shown = base.copy(pairAsking = PairQr.read(phone))
    open()
    val code = PairQr.read(phone)!!.checkCode!!
    rule.onNodeWithText("Does your new phone or tablet show $code?").assertIsDisplayed()
    rule.onNodeWithText("Only add a phone or tablet you're holding right now.").assertIsDisplayed()
    rule.onNodeWithText("Ana's tablet will be able to approve your tafs' actions.").assertIsDisplayed()
  }

  @Test fun cancelGoesToTheModel() {
    shown = base.copy(pairAsking = PairQr.read(qr))
    open()
    rule.onNodeWithText("Cancel").performClick()
    rule.waitForIdle()
    assertEquals(1, cancelled)
    assertEquals(0, confirmed)
  }

  /** The owner's phone: the line was set as the scanner closed, then gone once the sheet was back. Now it comes from the model. */
  @Test fun theLineSurvivesTheSheetGoingAndComingBack() {
    open()
    // The scanner is in front: the sheet goes, and the model sets the line meanwhile.
    sheetUp = false
    rule.waitForIdle()
    shown = base.copy(pairLine = "That QR code isn't a computer asking to be paired.")
    sheetUp = true
    rule.waitForIdle()
    rule.onNodeWithTag(PAIR_LINE).assertIsDisplayed()
    rule.onNodeWithText("That QR code isn't a computer asking to be paired.").assertIsDisplayed()
  }

  @Test fun devicesOpensAtFullHeight() {
    open()
    rule.waitForIdle()
    // The sheet is its own window, so it's measured in it, against the screen, as Connect apps' test does.
    val sheet = rule.onNodeWithTag(DEVICES_SHEET).fetchSemanticsNode().boundsInWindow
    val screen = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.heightPixels
    assertTrue("the sheet is ${sheet.height} of $screen px tall", sheet.height > screen * 0.7f)
  }
}
