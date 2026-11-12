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
  /** Whether tafs cloud shows them now: only while the account has no live taf and its suggestions are on; else none come. */
  val shown: Boolean = true,
  val suggestions: Suggestions? = null,
  /** What Home shows with no taf: the recipes or not ([RecipesHome.shown]), and its line either way. Absent from an older tafs cloud. */
  val home: RecipesHome? = null,
  /** The recipe sheet's words: its three zones and its button. */
  val labels: RecipeLabels? = null,
) {
  /** Each category in tafs cloud's order, with its recipes; a category with none isn't shown. */
  val byCategory: List<Pair<RecipeCategory, List<Recipe>>>
    get() = categories.map { c -> c to recipes.filter { it.category == c.id } }.filter { it.second.isNotEmpty() }
}

@Serializable
data class RecipeCategory(
  val id: String,
  val title: String,
  /** The category's colour, tafs cloud's token for each theme, for its chip and its cards' edge. Absent from an older tafs cloud. */
  val color: CategoryColor? = null,
)

/** A colour as tafs cloud gives it, `#RRGGBB` for the light theme and for the dark one, both checked as text on the app's grounds. */
@Serializable
data class CategoryColor(val light: String = "", val dark: String = "") {
  /** The colour for [dark], as ARGB; null when tafs cloud's value isn't `#RRGGBB`. */
  fun argb(dark: Boolean): Long? = (if (dark) this.dark else light).let { hex ->
    if (!Regex("^#[0-9A-Fa-f]{6}$").matches(hex)) null else 0xFF000000L or hex.substring(1).toLong(16)
  }
}

/** One app a recipe's taf works with: its name, and its logo's path on tafs cloud when it has one. */
@Serializable
data class RecipeApp(val name: String = "", val logo: String? = null)

/**
 * Every job a taf can take, for + New and the strip under the tafs list (`GET /v1/recipes/gallery`, docs/android-app.md): also once a
 * taf runs. tafs cloud picks the strip's jobs and their order; the app draws [strip]'s ids as they come.
 */
@Serializable
data class RecipesGallery(
  val categories: List<RecipeCategory> = emptyList(),
  val recipes: List<Recipe> = emptyList(),
  val strip: GalleryStrip? = null,
  val words: GalleryWords? = null,
  val labels: RecipeLabels? = null,
) {
  val byCategory: List<Pair<RecipeCategory, List<Recipe>>>
    get() = categories.map { c -> c to recipes.filter { it.category == c.id } }.filter { it.second.isNotEmpty() }

  /** The strip's jobs, in tafs cloud's order; none when it isn't shown or names none the gallery has. */
  val stripRecipes: List<Recipe>
    get() = if (strip?.shown != true) emptyList() else strip.ids.mapNotNull { id -> recipes.firstOrNull { it.id == id } }

  fun category(id: String): RecipeCategory? = categories.firstOrNull { it.id == id }

  /** The gallery lists a job the strip doesn't show: then a row under the strip opens them all. */
  val holdsMoreThanStrip: Boolean get() = stripRecipes.isNotEmpty() && recipes.size > stripRecipes.size
}

@Serializable
data class GalleryStrip(val shown: Boolean = false, val title: String = "", val ids: List<String> = emptyList())

/** The gallery's words, tafs cloud's in the person's language. */
@Serializable
data class GalleryWords(
  val title: String = "",
  /** The heading over every job. */
  val all: String = "",
  @kotlinx.serialization.SerialName("own_words") val ownWords: String = "",
  @kotlinx.serialization.SerialName("own_words_line") val ownWordsLine: String = "",
  /** Browsing every job, with nothing created: the row on Tafs, and the sheet's title then. */
  val explore: String = "",
  /** The row under the strip that opens every job. */
  @kotlinx.serialization.SerialName("see_all") val seeAll: String = "",
  /** A job's one action while browsing. */
  @kotlinx.serialization.SerialName("try") val tryIt: String = "",
  /** The label over a job's sample report. */
  val sample: String = "",
)

/** The account's "Show suggestions" setting, kept in tafs cloud: whether it's on, and tafs cloud's words for it. */
@Serializable
data class Suggestions(val show: Boolean = true, val words: String = "")

@Serializable
internal data class SuggestionsAnswer(val suggestions: Suggestions)

/**
 * One recipe: its title and line, and [say], what a tap puts in Coach's message box for the person to edit and send. On Home, its
 * [headline] (the problem it removes) leads, then [line]; its sheet adds [today] (what it costs today) and its [card].
 */
@Serializable
data class Recipe(
  val id: String,
  val title: String,
  val category: String = "",
  val line: String = "",
  val say: String = "",
  val headline: String = "",
  val today: String = "",
  val card: RecipeCard = RecipeCard(),
  /** The apps its taf works with, each with its logo on tafs cloud. */
  val apps: List<RecipeApp> = emptyList(),
  /** Every app it needs is connected already. */
  val connected: Boolean = false,
  /** A taf on the account was built from it. */
  val running: Boolean = false,
  /** tafs cloud's line for where the job was proved ("Proved on a test help desk."); empty when it wasn't. */
  val proved: String = "",
  /** What the job reported after that proof run, as tafs cloud sends it; empty when there's none. */
  val sample: String = "",
)

/** The Card a recipe's taf would have, as its file says it: what it does on its own, asks first, and never does. */
@Serializable
data class RecipeCard(val allows: List<String> = emptyList(), val holds: List<String> = emptyList(), val never: List<String> = emptyList())

/** Home with no taf: [shown], the recipes are listed under [words]; not shown, [words] is Home's empty line. */
@Serializable
data class RecipesHome(val shown: Boolean = false, val words: String = "")

/** The recipe sheet's words, in the person's language. */
@Serializable
data class RecipeLabels(val allows: String = "", val holds: String = "", val never: String = "", val hand: String = "")
