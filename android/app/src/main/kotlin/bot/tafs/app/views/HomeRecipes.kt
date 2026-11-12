package bot.tafs.app.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.Panel
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.kiff.Recipe
import bot.tafs.app.kiff.RecipeLabels
import bot.tafs.app.kiff.Recipes

/**
 * Home with no taf, where tafs cloud lists the recipes (`home.shown`, docs/android-app.md): its line, then a chip per category
 * to narrow the list, then each recipe led by the problem it removes ([Recipe.headline]) with its line under it. A tap opens its
 * sheet. Every word is tafs cloud's, in the person's language.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeRecipes(recipes: Recipes, labels: RecipeLabels, onOpen: (Recipe) -> Unit) {
  // A category picked narrows the list; picked again, every recipe shows. Nothing picked is all of them.
  var picked by rememberSaveable { mutableStateOf<String?>(null) }
  val groups = recipes.byCategory
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    recipes.home?.words?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(17, FontWeight.SemiBold, Theme.ink)) }
    if (groups.size > 1) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (category, _) ->
          CategoryChip(category.title, picked == category.id, categoryColor(category)) { picked = if (picked == category.id) null else category.id }
        }
      }
    }
    groups.filter { picked == null || it.first.id == picked }.forEach { (category, inIt) ->
      Eyebrow(category.title)
      inIt.forEach { recipe -> JobRow(recipe, categoryColor(category)) { onOpen(recipe) } }
    }
  }
}

/** A category's colour, tafs cloud's token for the theme on screen; null from an older tafs cloud. */
internal fun categoryColor(category: bot.tafs.app.kiff.RecipeCategory?): androidx.compose.ui.graphics.Color? =
  category?.color?.argb(Theme.dark)?.let { androidx.compose.ui.graphics.Color(it) }

@Composable
private fun CategoryChip(title: String, selected: Boolean, color: androidx.compose.ui.graphics.Color?, onClick: () -> Unit) {
  // The category's colour, where tafs cloud gives one: its text and edge, and its ground once picked.
  val tint = color ?: Theme.ink
  Text(
    title,
    style = scaled(13, color = if (selected) Theme.background else color ?: Theme.soft),
    modifier = Modifier
      .clip(CircleShape)
      .background(if (selected) tint else Theme.panel)
      .border(BorderStroke(1.dp, if (selected || color != null) tint else Theme.line), CircleShape)
      .clickable(onClick = onClick)
      .semantics { this.selected = selected }
      .heightIn(min = 32.dp)
      .padding(horizontal = 12.dp, vertical = 6.dp),
  )
}

/**
 * One job: the problem it removes first, then its line, then the apps its taf works with, each with its logo. Its category's colour
 * is its edge. A recipe from before headlines leads with its title.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JobRow(recipe: Recipe, color: androidx.compose.ui.graphics.Color?, onClick: () -> Unit) {
  val edge = Modifier.drawBehind { if (color != null) drawRect(color, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
  Panel(modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).then(edge).clickable(onClick = onClick)) {
    Text(recipe.headline.ifBlank { recipe.title }, style = scaled(16, FontWeight.SemiBold, Theme.ink))
    if (recipe.line.isNotBlank()) Text(recipe.line, style = scaled(14, color = Theme.soft))
    if (recipe.apps.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      recipe.apps.forEach { app -> if (app.name.isNotBlank()) bot.tafs.app.design.AppWithLogo(app.name, app.logo) }
    }
  }
}

/**
 * Every job a taf can take, from + New on Tafs (`GET /v1/recipes/gallery`, docs/android-app.md), also once a taf runs: a chip per
 * category, each job in its colour, and "Say it in your own words", which opens Coach empty. Every word is tafs cloud's.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GallerySheet(
  gallery: bot.tafs.app.kiff.RecipesGallery,
  canUseCoach: Boolean,
  onOpen: (Recipe) -> Unit,
  onOwnWords: () -> Unit,
  onClose: () -> Unit,
  /** Browsing (Explore jobs, See all jobs): every job to read, with nothing created, and no "own words" to start from. */
  browsing: Boolean = false,
) {
  var picked by rememberSaveable { mutableStateOf<String?>(null) }
  val groups = gallery.byCategory
  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(
      Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      val title = if (browsing) bot.tafs.app.model.HomeWords.explore(gallery) else gallery.words?.title?.takeIf { it.isNotBlank() }
      title?.let { Text(it, style = scaled(24, FontWeight.Bold, Theme.ink, display = true)) }
      // In the person's own words: Coach, empty, to build the taf with them. Not while browsing: that starts something.
      val words = gallery.words
      if (!browsing && canUseCoach && words != null && words.ownWords.isNotBlank()) {
        Panel(modifier = Modifier.fillMaxWidth().clickable(onClick = onOwnWords)) {
          Text(words.ownWords, style = scaled(16, FontWeight.SemiBold, Theme.amberText))
          if (words.ownWordsLine.isNotBlank()) Text(words.ownWordsLine, style = scaled(14, color = Theme.soft))
        }
      }
      words?.all?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(17, FontWeight.SemiBold, Theme.ink)) }
      if (groups.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          groups.forEach { (category, _) ->
            CategoryChip(category.title, picked == category.id, categoryColor(category)) { picked = if (picked == category.id) null else category.id }
          }
        }
      }
      groups.filter { picked == null || it.first.id == picked }.forEach { (category, inIt) ->
        Eyebrow(category.title)
        inIt.forEach { recipe -> JobRow(recipe, categoryColor(category)) { onOpen(recipe) } }
      }
    }
  }
}

/** Under the tafs list: a few jobs the account doesn't run yet, as tafs cloud picks and orders them. Nothing when it shows none. */
@Composable
internal fun JobStrip(gallery: bot.tafs.app.kiff.RecipesGallery, onOpen: (Recipe) -> Unit, onSeeAll: (() -> Unit)? = null) {
  val jobs = gallery.stripRecipes
  if (jobs.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    gallery.strip?.title?.takeIf { it.isNotBlank() }?.let { Eyebrow(it) }
    jobs.forEach { recipe -> JobRow(recipe, categoryColor(gallery.category(recipe.category))) { onOpen(recipe) } }
    // The strip shows a few: this row opens them all, to browse.
    if (onSeeAll != null && gallery.holdsMoreThanStrip) ExploreRow(bot.tafs.app.model.HomeWords.seeAll(gallery), onSeeAll)
  }
}

/**
 * A recipe's sheet: what it removes and what that costs today, then its Card in three plain zones (what it does on its own, asks
 * first, never does) and one button that hands it to Coach with the recipe's words, to edit and send.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RecipeSheet(
  recipe: Recipe,
  labels: RecipeLabels,
  canHand: Boolean,
  onHand: () -> Unit,
  onClose: () -> Unit,
  /** While browsing, the one action's words ("Try it with Coach"); else tafs cloud's hand-off words. */
  tryWords: String? = null,
  /** The label over the sample report, in tafs cloud's words or the app's. */
  sampleLabel: String = bot.tafs.app.model.Words.Home.sampleLabel,
) {
  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(
      Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(recipe.headline.ifBlank { recipe.title }, style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
      if (recipe.line.isNotBlank()) Text(recipe.line, style = scaled(15, color = Theme.ink))
      if (recipe.today.isNotBlank()) Text(recipe.today, style = scaled(14, color = Theme.soft))
      // Where the job was proved, as tafs cloud says it; nothing for a job with no proof run.
      if (recipe.proved.isNotBlank()) Text(recipe.proved, style = scaled(14, FontWeight.SemiBold, Theme.ok))
      if (recipe.apps.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        recipe.apps.forEach { app -> if (app.name.isNotBlank()) bot.tafs.app.design.AppWithLogo(app.name, app.logo) }
      }
      Zone(labels.allows, recipe.card.allows)
      Zone(labels.holds, recipe.card.holds)
      Zone(labels.never, recipe.card.never)
      // What it reported after that proof run, as sent.
      if (recipe.sample.isNotBlank()) Zone(sampleLabel, listOf(recipe.sample))
      val action = tryWords ?: labels.hand
      if (canHand && action.isNotBlank()) PrimaryButton(action, onHand, Modifier.fillMaxWidth())
    }
  }
}

/** One zone of the Card, its title and its lines; a zone the recipe's Card leaves empty isn't drawn. */
@Composable
private fun Zone(title: String, lines: List<String>) {
  if (lines.isEmpty()) return
  Panel {
    if (title.isNotBlank()) Eyebrow(title)
    lines.forEach { Text(it, style = scaled(14, color = Theme.ink)) }
  }
}
