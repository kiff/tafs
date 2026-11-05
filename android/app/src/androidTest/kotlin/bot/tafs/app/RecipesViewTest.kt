package bot.tafs.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Recipe
import bot.tafs.app.kiff.RecipeCategory
import bot.tafs.app.kiff.Recipes
import bot.tafs.app.views.CoachStarts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A new Coach conversation draws tafs cloud's recipes by category; a tap fills the message box and sends nothing (kiff/tafs#476). */
class RecipesViewTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val recipes = Recipes(
    categories = listOf(RecipeCategory("money", "Money"), RecipeCategory("customers", "Customers"), RecipeCategory("errands", "Errands")),
    recipes = listOf(
      Recipe("chase", "Chase overdue invoices", "money", "Reminds customers whose invoices are late.", "Chase my overdue invoices."),
      Recipe("leads", "Follow up new leads", "customers", "Writes back to new leads the same day.", "Follow up every new lead within a day."),
    ),
  )

  @Test fun recipesShowByCategoryAndATapFillsTheBox() {
    val filled = mutableListOf<String>()
    val sent = mutableListOf<String>()
    rule.setShown {
      TafsTheme {
        CoachStarts(AppState(mode = Mode.kiff, ready = true, recipes = recipes), fill = { filled += it }) { sent += it }
      }
    }
    rule.onAllNodesWithText("Money", substring = false, ignoreCase = true).assertCountEquals(1)
    rule.onAllNodesWithText("Customers", substring = false, ignoreCase = true).assertCountEquals(1)
    rule.onAllNodesWithText("Errands", substring = false, ignoreCase = true).assertCountEquals(0)
    rule.onNodeWithText("Writes back to new leads the same day.").assertExists()
    rule.onNodeWithText("Chase overdue invoices").performClick()
    rule.waitForIdle()
    assertEquals(listOf("Chase my overdue invoices."), filled)
    assertTrue("a recipe is never sent", sent.isEmpty())
  }
}
