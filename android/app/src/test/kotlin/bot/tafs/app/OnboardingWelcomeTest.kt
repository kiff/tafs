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
