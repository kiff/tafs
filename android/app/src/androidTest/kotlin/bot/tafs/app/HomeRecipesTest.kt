package bot.tafs.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Recipe
import bot.tafs.app.kiff.RecipeCard
import bot.tafs.app.kiff.RecipeCategory
import bot.tafs.app.kiff.RecipeLabels
import bot.tafs.app.kiff.Recipes
import bot.tafs.app.kiff.RecipesHome
import bot.tafs.app.views.HomeView
import bot.tafs.app.views.RecipeSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Home with no taf (docs/android-app.md): where tafs cloud says so, the recipes under its line, each led by the problem it
 * removes, narrowed by category; a recipe's sheet has its Card in three zones and one button to hand it to Coach. Every word
 * is tafs cloud's.
 */
class HomeRecipesTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val labels = RecipeLabels(allows = "Does on its own", holds = "Asks you first", never = "Never does", hand = "Hand it to Coach")
  private val refunds = Recipe(
    "refunds", "Refunds within a limit", "money", "Refunds what fits the limit.", "Refund orders under €50.",
    headline = "No refund waits overnight.", today = "A refund that waits can turn into a chargeback.",
    card = RecipeCard(allows = listOf("Refunds up to €50"), holds = listOf("Anything over €50"), never = listOf("Refunding twice")),
  )
  private val leads = Recipe("leads", "Follow up new leads", "customers", "Writes back the same day.", "Follow up every lead.", headline = "Never lose a lead to a slow reply.")
  private fun recipes(home: RecipesHome) = Recipes(
    categories = listOf(RecipeCategory("money", "Money"), RecipeCategory("customers", "Customers")),
    recipes = listOf(refunds, leads), home = home, labels = labels,
  )

  private fun home(recipes: Recipes, opened: MutableList<String>) = rule.setShown {
    TafsTheme {
      HomeView(
        AppState(mode = Mode.kiff, ready = true, listLoaded = true, recipes = recipes),
        onNeedsYou = {}, onPlayNext = {}, onOpenTaf = {}, onHaltAll = {}, onRecipe = { opened += it.id },
      )
    }
  }

  @Test fun homeWithNoTafListsTheRecipesLedByTheProblemTheyRemove() {
    val opened = mutableListOf<String>()
    home(recipes(RecipesHome(shown = true, words = "Pick a job for your first taf.")), opened)
    rule.onNodeWithText("Pick a job for your first taf.").assertExists()
    rule.onNodeWithText("No refund waits overnight.").assertExists()
    rule.onNodeWithText("Refunds what fits the limit.").assertExists()
    rule.onAllNodesWithText("No tafs yet.", substring = true).assertCountEquals(0)
    // A new account's Home leads with the jobs: no count above them.
    rule.onAllNodesWithText(bot.tafs.app.model.Words.Home.working(count = 0)).assertCountEquals(0)
    // A category narrows the list; picked again, every recipe shows.
    rule.onAllNodesWithText("Customers")[0].performClick()
    rule.waitForIdle()
    rule.onAllNodesWithText("No refund waits overnight.").assertCountEquals(0)
    rule.onNodeWithText("Never lose a lead to a slow reply.").assertExists().performClick()
    rule.waitForIdle()
    assertEquals(listOf("leads"), opened)
  }

  @Test fun whereTafsCloudDoesntListThemHomeShowsOnlyItsLine() {
    home(recipes(RecipesHome(shown = false, words = "Connect an app to set up your first taf.")), mutableListOf())
    rule.onNodeWithText("Connect an app to set up your first taf.").assertExists()
    rule.onAllNodesWithText("No refund waits overnight.").assertCountEquals(0)
    // Without the jobs, Home keeps its count.
    rule.onNodeWithText(bot.tafs.app.model.Words.Home.working(count = 0)).assertExists()
    rule.onAllNodesWithText("No tafs yet.", substring = true).assertCountEquals(0)
  }

  @Test fun theSheetShowsTheCardInThreeZonesAndHandsItToCoach() {
    val handed = mutableListOf<String>()
    rule.setShown {
      TafsTheme { RecipeSheet(refunds, labels, canHand = true, onHand = { handed += refunds.id }, onClose = {}) }
    }
    rule.onNodeWithText("No refund waits overnight.").assertExists()
    rule.onNodeWithText("A refund that waits can turn into a chargeback.").assertExists()
    // Eyebrows draw their words in capitals.
    rule.onNodeWithText("DOES ON ITS OWN").assertExists()
    rule.onNodeWithText("Refunds up to €50").assertExists()
    rule.onNodeWithText("ASKS YOU FIRST").assertExists()
    rule.onNodeWithText("NEVER DOES").performScrollTo().assertExists()
    rule.onNodeWithText("Hand it to Coach").performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf("refunds"), handed)
  }

  @Test fun someoneWhoCantUseCoachGetsNoButton() {
    rule.setShown { TafsTheme { RecipeSheet(refunds, labels, canHand = false, onHand = {}, onClose = {}) } }
    rule.onAllNodesWithText("Hand it to Coach").assertCountEquals(0)
  }
}
