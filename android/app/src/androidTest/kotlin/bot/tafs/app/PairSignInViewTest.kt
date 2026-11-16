package bot.tafs.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.PairOffer
import bot.tafs.app.views.PAIR_QR
import bot.tafs.app.views.PairSignInContent
import bot.tafs.app.views.SIGNED_IN
import bot.tafs.app.views.SignedInCelebration
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Signing in with your other phone (docs/android-app.md): the QR and the code both screens show, how to scan it from the other phone,
 * the wait, and a new code once the last one ran out. KIFF and the other phone decide.
 */
class PairSignInViewTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private fun key(): ByteArray = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded

  private val offer = PairOffer.make(key(), key(), "tafs on Android phone", "android_phone", ByteArray(32) { 1 }, Instant.parse("2026-11-14T22:00:00Z"))

  @Test fun theQrTheCodeAndHowToScanItShowWhileItWaits() {
    rule.setShown { TafsTheme { PairSignInContent(offer, renewed = false, problem = null, onTryAgain = {}, onKiff = {}) } }
    rule.onNodeWithText("Pair this phone with your other phone").assertIsDisplayed()
    rule.onNodeWithText("On the phone you already use with tafs, open Account → Devices → Add a device, and scan this code.").assertIsDisplayed()
    rule.onNodeWithTag(PAIR_QR).assertIsDisplayed()
    rule.onNodeWithText("It will ask whether this phone shows ${offer.checkCode}.").assertIsDisplayed()
    rule.onNodeWithText("Waiting for your other phone…").assertIsDisplayed()
  }

  @Test fun aNewCodeSaysTheLastOneExpired() {
    rule.setShown { TafsTheme { PairSignInContent(offer, renewed = true, problem = null, onTryAgain = {}, onKiff = {}) } }
    rule.onNodeWithText("That code expired, so here's a new one.").assertIsDisplayed()
  }

  @Test fun signInWithKiffGoesBack() {
    var back = 0
    rule.setShown { TafsTheme { PairSignInContent(offer, renewed = false, problem = null, onTryAgain = {}, onKiff = { back += 1 }) } }
    rule.onNodeWithText("Sign in with KIFF").performClick()
    rule.waitForIdle()
    assertEquals(1, back)
  }

  // A tablet's right half (the owner's mockup, 16 Nov): its own title and line, the QR at once, and no way back to KIFF's,
  // which is the left half beside it.
  @Test fun aTabletsPhoneHalfHasItsTitleTheQrAndNoKiffLink() {
    rule.setShown {
      TafsTheme {
        PairSignInContent(offer, renewed = false, problem = null, onTryAgain = {}, onKiff = null, title = "Sign in with your phone", lead = "Scan with tafs")
      }
    }
    rule.onNodeWithText("Sign in with your phone").assertIsDisplayed()
    rule.onNodeWithText("Scan with tafs").assertIsDisplayed()
    rule.onNodeWithTag(PAIR_QR).assertIsDisplayed()
    rule.onAllNodesWithText("Sign in with KIFF").assertCountEquals(0)
  }

  // Signed in, either way: the celebration, not a sheet that just closes; Let's go goes on to the app.
  @Test fun signedInSaysYoureConnectedAndLetsGoGoesOn() {
    var went = 0
    rule.setShown { TafsTheme { SignedInCelebration(onGo = { went += 1 }) } }
    rule.onNodeWithTag(SIGNED_IN).assertIsDisplayed()
    rule.onNodeWithText("You're connected.").assertIsDisplayed()
    rule.onNodeWithText("Let's build great things together.").assertIsDisplayed()
    rule.onNodeWithText("Let's go").performClick()
    rule.waitForIdle()
    assertEquals(1, went)
  }
}
