package bot.tafs.app.kiff

import kotlinx.serialization.Serializable

/**
 * The recipes a new Coach conversation opens to (`GET /v1/recipes`, docs/coach.md "Recipes"), by category, as tafs cloud
 * sends them: a recipe is a job and the words to say to Coach. The app draws them and keeps no list of its own.
 */
@Serializable
data class Recipes(
  val categories: List<RecipeCategory> = emptyList(),
  val recipes: List<Recipe> = emptyList(),
  /** False when tafs cloud couldn't read the catalog and lists every recipe. */
  val filtered: Boolean = true,
) {
  /** Each category in tafs cloud's order, with its recipes; a category with none isn't shown. */
  val byCategory: List<Pair<RecipeCategory, List<Recipe>>>
    get() = categories.map { c -> c to recipes.filter { it.category == c.id } }.filter { it.second.isNotEmpty() }
}

@Serializable
data class RecipeCategory(val id: String, val title: String)

/** One recipe: its title and line, and [say], what a tap puts in Coach's message box for the person to edit and send. */
@Serializable
data class Recipe(val id: String, val title: String, val category: String = "", val line: String = "", val say: String = "")
