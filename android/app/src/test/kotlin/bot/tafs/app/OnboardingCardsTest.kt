package bot.tafs.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Recipes
import bot.tafs.app.kiff.RecipesHome
import bot.tafs.app.model.OnboardingPage
import bot.tafs.app.model.OnboardingSteps
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.model.firstHomeLines
import bot.tafs.app.model.showsFirstHome
import bot.tafs.app.model.showsOnboardingCards
import bot.tafs.app.views.HomeView
import bot.tafs.app.views.OnboardingCards
import bot.tafs.app.views.onboardingSampleCard
import bot.tafs.app.views.onboardingSampleTafs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The three cards and the first Home (docs/onboarding.md, steps 3 and 4), as iOS's OnboardingTests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OnboardingCardsTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() = Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))

  @Test fun nextGoesCardByCardThenGetStartedEnds() {
    var steps = OnboardingSteps()
    assertEquals(OnboardingPage.Tafs, steps.page)
    steps = steps.advanced()
    assertEquals(OnboardingPage.Card, steps.page)
    steps = steps.advanced()
    assertEquals(OnboardingPage.Control, steps.page)
    assertTrue("the last card's button is Get started", steps.isLast)
    assertFalse(steps.finished)
    steps = steps.advanced()
    assertTrue(steps.finished)
    assertEquals("Get started ends on the last card, never past it", OnboardingPage.Control, steps.page)
  }

  @Test fun skipEndsFromAnyCard() = OnboardingPage.entries.forEach { assertTrue(OnboardingSteps(it).skipped().finished) }

  /** The last card names Android's own way to approve, the screen lock, never Face ID. */
  @Test fun theLastCardNamesTheScreenLock() {
    assertEquals(Words.Onboarding.controlLeadAndroid, OnboardingPage.Control.lead)
    assertTrue(Words.Onboarding.controlLeadAndroid != Words.Onboarding.controlLead)
  }

  @Test fun theCardsShowOnceAndOnlyWhenOn() {
    assertTrue(showsOnboardingCards(seen = false, on = true))
    assertFalse("seen here: Home at once", showsOnboardingCards(seen = true, on = true))
    assertFalse("off: Home as it ships", showsOnboardingCards(seen = false, on = false))
    assertFalse("ships off", showsOnboardingCards(seen = false))
  }

  @Test fun theFirstHomeShowsWithTheSwitchForWhoeverMayUseCoach() {
    assertTrue(showsFirstHome(mayUseCoach = true, on = true))
    assertFalse("a viewer reads where shared tafs show, as today", showsFirstHome(mayUseCoach = false, on = true))
    assertFalse(showsFirstHome(mayUseCoach = true, on = false))
    assertFalse("ships off", showsFirstHome(mayUseCoach = true))
  }

  @Test fun theFirstHomeSaysTafsCloudsLineFirst() {
    assertEquals("Nothing set up yet. Ask Coach." to null, firstHomeLines("Nothing set up yet. Ask Coach."))
    for (none in listOf(null, "", "  ")) assertEquals(Words.Home.noTafs to Words.Onboarding.homeLead, firstHomeLines(none))
  }

  @Test fun thePicturesDrawTheSamplesOwnTafsAndCard() {
    assertEquals(listOf("support", "parcels", "leads"), onboardingSampleTafs().map { it.id.removePrefix("onboarding-") })
    val card = onboardingSampleCard()
    assertEquals(40, card.calls?.limit)
    assertEquals(12, card.calls?.used)
    assertEquals(Words.Sample.Card.refundsStripe, card.title)
  }

  /** Next card by card, then Get started ends them; each card draws its title. */
  @Test fun theCardsGoByAndEnd() {
    var done = 0
    compose.setContent { TafsTheme { OnboardingCards { done += 1 } } }
    for (page in OnboardingPage.entries) {
      compose.onNodeWithText(page.title).assertExists()
      compose.onNodeWithTag("onboarding.next").performClick()
    }
    assertEquals(1, done)
  }

  @Test fun skipEndsTheCards() {
    var done = 0
    compose.setContent { TafsTheme { OnboardingCards(OnboardingPage.Card) { done += 1 } } }
    compose.onNodeWithText(OnboardingPage.Card.title).assertExists()
    compose.onNodeWithTag("onboarding.skip").performClick()
    assertEquals(1, done)
  }

  // Tall enough that Home's whole list is composed.
  private fun home(@Suppress("UNUSED_PARAMETER") onboarding: Boolean, words: String = "") = AppState(mode = Mode.kiff, ready = true, listLoaded = true,
    kiffAccount = bot.tafs.app.kiff.Account("t_01", "acme", capabilities = listOf("members.manage")),
    recipes = Recipes(home = RecipesHome(shown = false, words = words)))

  /** Tall enough that Home's whole list is composed. */
  private fun tall() = org.robolectric.RuntimeEnvironment.setQualifiers("w400dp-h3000dp")

  /** Home with no taf, the new onboarding on: tafs cloud's line alone, Ask Coach, and How tafs works. */
  @Test fun theFirstHomeAsksCoachUnderTafsCloudsLine() {
    var asked = 0
    var how = 0
    tall()
    compose.setContent {
      TafsTheme {
        HomeView(home(true, "Nothing yet: tell Coach."), onNeedsYou = {}, onPlayNext = {}, onOpenTaf = {}, onHaltAll = {},
          onOwnWords = { asked += 1 }, onHowItWorks = { how += 1 }, onboarding = true)
      }
    }
    compose.onNodeWithText("Nothing yet: tell Coach.").assertExists()
    assertTrue(compose.onAllNodesWithTag("unused").fetchSemanticsNodes().isEmpty())
    compose.onNodeWithTag("home.ask_coach").performScrollTo().performClick()
    compose.onNodeWithTag("home.how_it_works").performScrollTo().performClick()
    assertEquals(1, asked)
    assertEquals(1, how)
  }

  @Test fun offHomeIsAsItShips() {
    tall()
    compose.setContent {
      TafsTheme {
        HomeView(home(false, "Nothing yet: tell Coach."), onNeedsYou = {}, onPlayNext = {}, onOpenTaf = {}, onHaltAll = {},
          onOwnWords = {}, onHowItWorks = {}, onboarding = false)
      }
    }
    compose.onNodeWithText("Nothing yet: tell Coach.").assertExists()
    assertTrue(compose.onAllNodesWithTag("home.ask_coach").fetchSemanticsNodes().isEmpty())
    assertTrue(compose.onAllNodesWithTag("home.how_it_works").fetchSemanticsNodes().isEmpty())
  }
}
