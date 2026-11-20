package bot.tafs.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.ConsentTick
import bot.tafs.app.model.Legal
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.views.OnboardingView
import bot.tafs.app.views.linked
import bot.tafs.app.views.ContinueOutcome
import bot.tafs.app.views.continueOutcome
import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.AppSignInRefused
import bot.tafs.app.kiff.VendorSignInCancelled
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Assert.fail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The welcome with one tick (docs/onboarding.md, step 1): Continue waits on it, the sample never does, and off it's as it ships. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OnboardingWelcomeTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() = Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))

  @Test fun continueWaitsOnTheTick() {
    var signIn = 0
    var sample = 0
    compose.setContent { TafsTheme { OnboardingView(onSignIn = { signIn += 1 }, onSample = { sample += 1 }, onboarding = true) } }
    compose.onNodeWithTag("onboarding.continue").assertIsNotEnabled().performClick()
    assertEquals("Continue does nothing before the tick", 0, signIn)
    // The sample never waits on the tick.
    compose.onNodeWithText(Words.Sample.tryIt).performClick()
    assertEquals(1, sample)
    compose.onNodeWithTag("onboarding.tick").performScrollTo().performClick()
    compose.onNodeWithTag("onboarding.continue").assertIsEnabled().performClick()
    assertEquals(1, signIn)
    // Unticked again, Continue waits again.
    compose.onNodeWithTag("onboarding.tick").performScrollTo().performClick()
    compose.onNodeWithTag("onboarding.continue").assertIsNotEnabled()
  }

  @Test fun offTheWelcomeIsAsItShips() {
    compose.setContent { TafsTheme { OnboardingView(onSignIn = {}, onSample = {}, onboarding = false) } }
    compose.onNodeWithText(Words.SignIn.withKiff).assertIsEnabled()
    assertTrue(compose.onAllNodesWithText(Words.Onboarding.age).fetchSemanticsNodes().isEmpty())
    assertTrue(compose.onAllNodesWithText(Words.Common.`continue`).fetchSemanticsNodes().isEmpty())
  }

  /** Continue signs in inside tafs with the terms version the tick showed, and its button waits while it runs. */
  @Test fun continueSignsInWithTheTermsTheTickShowed() {
    val sent = mutableListOf<String?>()
    compose.setContent {
      TafsTheme {
        OnboardingView(onSignIn = { fail("the device-code sheet isn't opened") }, onSample = {}, onboarding = true,
          signInInApp = { t -> sent += t }, termsVersion = { "2026-11-14" })
      }
    }
    compose.onNodeWithTag("onboarding.tick").performScrollTo().performClick()
    compose.onNodeWithTag("onboarding.continue").performClick()
    compose.waitForIdle()
    assertEquals(listOf<String?>("2026-11-14"), sent)
  }

  /** Terms that changed since the tick untick the box and say so; Continue then waits on the new tick, with the new version. */
  @Test fun termsThatChangedUntickTheBoxAndSaySo() {
    val sent = mutableListOf<String?>()
    var version = "2026-11-14"
    compose.setContent {
      TafsTheme {
        OnboardingView(onSignIn = {}, onSample = {}, onboarding = true,
          signInInApp = { t -> sent += t; if (sent.size == 1) { version = "2026-11-20"; throw AppSignInRefused(AppModel.TERMS_OUTDATED) } },
          termsVersion = { version })
      }
    }
    compose.onNodeWithTag("onboarding.tick").performScrollTo().performClick()
    compose.onNodeWithTag("onboarding.continue").performClick()
    compose.waitForIdle()
    compose.onNodeWithTag("onboarding.terms_changed").assertExists()
    compose.onNodeWithText(Words.Onboarding.termsChanged).assertExists()
    compose.onNodeWithTag("onboarding.continue").assertIsNotEnabled()
    compose.onNodeWithTag("onboarding.tick").performScrollTo().performClick()
    compose.onNodeWithTag("onboarding.continue").performClick()
    compose.waitForIdle()
    assertEquals(listOf<String?>("2026-11-14", "2026-11-20"), sent)
  }

  /** Closing KIFF's page says nothing; anything else says signing in didn't work, and the tick stays. */
  @Test fun aSignInThatDidntWorkSaysSoAndAClosedPageSaysNothing() {
    var fail: Exception = VendorSignInCancelled()
    compose.setContent {
      TafsTheme { OnboardingView(onSignIn = {}, onSample = {}, onboarding = true, signInInApp = { throw fail }, termsVersion = { "v" }) }
    }
    compose.onNodeWithTag("onboarding.tick").performScrollTo().performClick()
    compose.onNodeWithTag("onboarding.continue").performClick()
    compose.waitForIdle()
    assertTrue(compose.onAllNodesWithTag("onboarding.problem").fetchSemanticsNodes().isEmpty())
    fail = AppSignInRefused(AppSignInRefused.STATE_MISMATCH)
    compose.onNodeWithTag("onboarding.continue").assertIsEnabled().performClick()
    compose.waitForIdle()
    compose.onNodeWithText(Words.SignIn.didntWork).assertExists()
    compose.onNodeWithTag("onboarding.continue").assertIsEnabled()
  }

  /** Where KIFF isn't offered, Continue says so in KIFF's words, not "didn't work". */
  @Test fun aRegionRefusalSaysKiffsWords() {
    val words = "KIFF isn't available where you are right now. We offer it only in the countries and regions where Anthropic's Claude is available."
    compose.setContent {
      TafsTheme {
        OnboardingView(onSignIn = {}, onSample = {}, onboarding = true, termsVersion = { "v" },
          signInInApp = { throw bot.tafs.app.kiff.KiffError.Server(403, "region_unsupported", code = "region_unsupported", words = words) })
      }
    }
    compose.onNodeWithTag("onboarding.tick").performScrollTo().performClick()
    compose.onNodeWithTag("onboarding.continue").performClick()
    compose.waitForIdle()
    compose.onNodeWithText(words).assertExists()
    assertTrue(compose.onAllNodesWithText(Words.SignIn.didntWork).fetchSemanticsNodes().isEmpty())
  }

  /** A tablet's second way, Sign in with your phone, waits on the same tick; a phone has none. */
  @Test fun aTabletsPhoneWayWaitsOnTheTickToo() {
    var phone = 0
    compose.setContent {
      TafsTheme { OnboardingView(onSignIn = {}, onSample = {}, onboarding = true, signInInApp = {}, termsVersion = { "v" }, onSignInWithPhone = { phone += 1 }) }
    }
    compose.onNodeWithTag("onboarding.with_phone").assertIsNotEnabled()
    compose.onNodeWithTag("onboarding.tick").performScrollTo().performClick()
    compose.onNodeWithTag("onboarding.with_phone").assertIsEnabled().performClick()
    assertEquals(1, phone)
    assertTrue("the old way never shows beside Continue", compose.onAllNodesWithText(Words.SignIn.withKiff).fetchSemanticsNodes().isEmpty())
  }

  @Test fun aPhoneHasNoPhoneWay() {
    compose.setContent { TafsTheme { OnboardingView(onSignIn = {}, onSample = {}, onboarding = true, signInInApp = {}, termsVersion = { "v" }) } }
    assertTrue(compose.onAllNodesWithTag("onboarding.with_phone").fetchSemanticsNodes().isEmpty())
    // Nor the old Sign in with KIFF: it would sign in with the box unticked and no consent recorded (qa-kiff, #1532).
    assertTrue(compose.onAllNodesWithText(Words.SignIn.withKiff).fetchSemanticsNodes().isEmpty())
    compose.onNodeWithTag("onboarding.continue").assertIsNotEnabled()
  }

  @Test fun eachEndingHasItsOutcome() {
    assertEquals(ContinueOutcome.Quiet, continueOutcome(VendorSignInCancelled()))
    assertEquals(ContinueOutcome.Quiet, continueOutcome(AppSignInRefused("access_denied")))
    assertEquals(ContinueOutcome.TermsChanged, continueOutcome(AppSignInRefused(AppModel.TERMS_OUTDATED)))
    assertEquals(ContinueOutcome.Problem, continueOutcome(AppSignInRefused(AppSignInRefused.STATE_MISMATCH)))
    assertEquals(ContinueOutcome.Problem, continueOutcome(AppSignInRefused("unsupported_client")))
    assertEquals(ContinueOutcome.Problem, continueOutcome(bot.tafs.app.kiff.KiffError.Server(400, "invalid_grant")))
    assertEquals(ContinueOutcome.Problem, continueOutcome(java.io.IOException("offline")))
  }

  @Test fun theTickAgreesOnlyWhenTicked() {
    assertFalse(ConsentTick().canContinue)
    assertTrue(ConsentTick().toggled().canContinue)
    assertFalse(ConsentTick().toggled().toggled().canContinue)
  }

  /** Each link sits where the language puts it, with its own words and page; the rest of the line is as written. */
  @Test fun theLinksSitWhereTheLineSaysAndOpenTheirPages() {
    val line = linked(Words.Onboarding.consentTick(kiffTerms = "\u0001", tafsTerms = "\u0002"),
      listOf(Words.Onboarding.kiffTerms to Legal.kiffTerms("en"), Words.Onboarding.tafsTerms to Legal.terms("en")))
    assertEquals(Words.Onboarding.consentTick(kiffTerms = Words.Onboarding.kiffTerms, tafsTerms = Words.Onboarding.tafsTerms), line.text)
    val urls = line.getLinkAnnotations(0, line.length).map { (it.item as LinkAnnotation.Url).url to line.text.substring(it.start, it.end) }
    assertEquals(listOf("https://kiff.dev/terms" to Words.Onboarding.kiffTerms, "https://kiff.dev/tafs/terms" to Words.Onboarding.tafsTerms), urls)
  }

  @Test fun kiffsPagesInTheLinesLanguage() {
    assertEquals("https://kiff.dev/de/terms", Legal.kiffTerms("de"))
    assertEquals("https://kiff.dev/pl/privacy", Legal.kiffPrivacy("pl"))
    assertEquals("https://kiff.dev/privacy", Legal.kiffPrivacy("en"))
    assertEquals("https://kiff.dev/terms", Legal.kiffTerms("ja"))
  }
}
