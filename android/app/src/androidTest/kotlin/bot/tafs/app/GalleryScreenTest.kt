package bot.tafs.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.CategoryColor
import bot.tafs.app.kiff.GalleryStrip
import bot.tafs.app.kiff.GalleryWords
import bot.tafs.app.kiff.Recipe
import bot.tafs.app.kiff.RecipeApp
import bot.tafs.app.kiff.RecipeCategory
import bot.tafs.app.kiff.RecipeLabels
import bot.tafs.app.kiff.RecipesGallery
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import bot.tafs.app.views.GallerySheet
import bot.tafs.app.views.HomeView
import bot.tafs.app.views.RecipeSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The jobs a taf can take, also once a taf runs (docs/android-app.md): + New on Home opens every job by category, each with its apps;
 * "Say it in your own words" opens Coach; a short strip under the tafs list draws tafs cloud's picks in its order. Every word is tafs
 * cloud's.
 */
class GalleryScreenTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val leads = Recipe("follow-up-new-leads", "Follow up leads", "customers", headline = "Never lose a lead to a slow reply.", apps = listOf(RecipeApp("HubSpot")))
  private val invoices = Recipe("chase-overdue-invoices", "Chase invoices", "money", headline = "Get paid without chasing.", apps = listOf(RecipeApp("Stripe"), RecipeApp("Gmail")))
  private val refunds = Recipe("refunds", "Refunds", "money", headline = "No refund waits overnight.", running = true)
  private val gallery = RecipesGallery(
    categories = listOf(
      RecipeCategory("money", "Money", CategoryColor("#107758", "#4CC38A")),
      RecipeCategory("customers", "Customers", CategoryColor("#D0104D", "#FF7FA3")),
    ),
    recipes = listOf(refunds, invoices, leads),
    strip = GalleryStrip(shown = true, title = "More jobs to hand off", ids = listOf("follow-up-new-leads", "chase-overdue-invoices")),
    words = GalleryWords(title = "New taf", all = "All jobs", ownWords = "Say it in your own words", ownWordsLine = "Tell Coach the job, and it builds the taf with you."),
    labels = RecipeLabels("Does on its own", "Asks you first", "Never does", "Hand it to Coach"),
  )
  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "Refund", tools = emptyList(), colorIndex = 1, card = Card(emptyList(), "You"), cloudId = "taf_1")

  private fun home(state: AppState, opened: MutableList<String>, newTaf: MutableList<Unit>, explored: MutableList<Unit> = mutableListOf()) = rule.setShown {
    TafsTheme {
      HomeView(
        state, onNeedsYou = {}, onPlayNext = {}, onOpenTaf = {}, onHaltAll = {}, onRecipe = { opened += it.id },
        onNewTaf = { newTaf += Unit }, onExplore = if (state.gallery != null) ({ explored += Unit }) else null,
      )
    }
  }

  private val signedIn = AppState(
    mode = Mode.kiff, ready = true, listLoaded = true, kiffAccount = Account("t_01", "acme", capabilities = listOf("members.manage")),
    tafs = listOf(taf), gallery = gallery,
  )

  @Test fun withATafHomeHasNewAndAStripOfTafsCloudsPicksInItsOrder() {
    val opened = mutableListOf<String>()
    val newTaf = mutableListOf<Unit>()
    home(signedIn, opened, newTaf)
    rule.onNodeWithText("+ New taf").assertExists().performClick()
    rule.waitForIdle()
    assertEquals(1, newTaf.size)
    rule.onNodeWithText("MORE JOBS TO HAND OFF").performScrollTo().assertExists()
    val first = rule.onNodeWithText("Never lose a lead to a slow reply.").performScrollTo().fetchSemanticsNode().boundsInRoot
    val second = rule.onNodeWithText("Get paid without chasing.").performScrollTo().fetchSemanticsNode().boundsInRoot
    assertEquals(true, first.top < second.top)
    // A job the account runs isn't in the strip.
    rule.onAllNodesWithText("No refund waits overnight.").assertCountEquals(0)
    rule.onNodeWithText("Get paid without chasing.").performClick()
    rule.waitForIdle()
    assertEquals(listOf("chase-overdue-invoices"), opened)
  }

  @Test fun withNoGalleryFromTafsCloudThereIsNoNewAndNoStrip() {
    home(signedIn.copy(gallery = null), mutableListOf(), mutableListOf())
    rule.onAllNodesWithText("MORE JOBS TO HAND OFF").assertCountEquals(0)
    rule.onAllNodesWithText("Explore jobs").assertCountEquals(0)
  }

  @Test fun newIsNamedEvenWhenTafsCloudSendsNoTitle() {
    val newTaf = mutableListOf<Unit>()
    home(signedIn.copy(gallery = gallery.copy(words = gallery.words!!.copy(title = ""))), mutableListOf(), newTaf)
    rule.onNodeWithText("+ New taf").assertExists().performClick()
    rule.waitForIdle()
    assertEquals(1, newTaf.size)
  }

  @Test fun exploreJobsAndSeeAllJobsOpenEveryJobToBrowse() {
    val explored = mutableListOf<Unit>()
    // No Coach needed to browse: someone a taf is shared with sees Explore jobs too, and no + New.
    home(signedIn.copy(kiffAccount = Account("t_01", "acme", capabilities = emptyList())), mutableListOf(), mutableListOf(), explored)
    rule.onAllNodesWithText("+ New taf").assertCountEquals(0)
    rule.onNodeWithText("Explore jobs").assertExists().performClick()
    rule.waitForIdle()
    assertEquals(1, explored.size)
  }

  @Test fun theStripEndsWithARowThatOpensEveryJob() {
    val explored = mutableListOf<Unit>()
    home(signedIn, mutableListOf(), mutableListOf(), explored)
    rule.onNodeWithText("See all jobs").performScrollTo().assertExists().performClick()
    rule.waitForIdle()
    assertEquals(1, explored.size)
  }

  @Test fun browsingTheGalleryOffersNoOwnWordsAndIsTitledExplore() {
    rule.setShown { TafsTheme { GallerySheet(gallery, canUseCoach = true, onOpen = {}, onOwnWords = {}, onClose = {}, browsing = true) } }
    rule.onNodeWithText("Explore jobs").assertExists()
    rule.onAllNodesWithText("Say it in your own words").assertCountEquals(0)
    rule.onNodeWithText("Never lose a lead to a slow reply.").performScrollTo().assertExists()
  }

  @Test fun aJobsSheetShowsItsAppsProofAndSampleAndTryItWithCoachIsTheOneAction() {
    val proved = leads.copy(provedSent = kotlinx.serialization.json.JsonPrimitive("Proved on a test help desk."), sampleSent = kotlinx.serialization.json.JsonPrimitive("3 tickets answered."))
    var tried = 0
    rule.setShown {
      TafsTheme {
        RecipeSheet(proved, gallery.labels!!, canHand = true, onHand = { tried++ }, onClose = {}, tryWords = "Try it with Coach", sampleLabel = "What it reported after its test run")
      }
    }
    rule.onNodeWithText("Proved on a test help desk.").assertExists()
    rule.onNodeWithText("HubSpot").assertExists()
    rule.onNodeWithText("3 tickets answered.").performScrollTo().assertExists()
    rule.onAllNodesWithText("Hand it to Coach").assertCountEquals(0)
    rule.onNodeWithText("Try it with Coach").performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(1, tried)
  }

  @Test fun theGalleryHasEveryJobByCategoryWithItsAppsAndOwnWords() {
    val opened = mutableListOf<String>()
    var ownWords = 0
    rule.setShown {
      TafsTheme { GallerySheet(gallery, canUseCoach = true, onOpen = { opened += it.id }, onOwnWords = { ownWords++ }, onClose = {}) }
    }
    rule.onNodeWithText("New taf").assertExists()
    rule.onNodeWithText("All jobs").assertExists()
    rule.onNodeWithText("Say it in your own words").performClick()
    rule.waitForIdle()
    assertEquals(1, ownWords)
    // Every job, the running one too, with its apps' names.
    rule.onNodeWithText("No refund waits overnight.").performScrollTo().assertExists()
    rule.onNodeWithText("Gmail").performScrollTo().assertExists()
    // A category narrows the list.
    rule.onAllNodesWithText("Customers")[0].performClick()
    rule.waitForIdle()
    rule.onAllNodesWithText("Get paid without chasing.").assertCountEquals(0)
    rule.onNodeWithText("Never lose a lead to a slow reply.").performClick()
    rule.waitForIdle()
    assertEquals(listOf("follow-up-new-leads"), opened)
  }

  @Test fun someoneWhoCantUseCoachIsNotOfferedOwnWords() {
    rule.setShown { TafsTheme { GallerySheet(gallery, canUseCoach = false, onOpen = {}, onOwnWords = {}, onClose = {}) } }
    rule.onAllNodesWithText("Say it in your own words").assertCountEquals(0)
  }
}
