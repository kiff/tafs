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
          CategoryChip(category.title, picked == category.id) { picked = if (picked == category.id) null else category.id }
        }
      }
    }
    groups.filter { picked == null || it.first.id == picked }.forEach { (category, inIt) ->
      Eyebrow(category.title)
      inIt.forEach { recipe -> HomeRecipeRow(recipe) { onOpen(recipe) } }
    }
  }
}

@Composable
private fun CategoryChip(title: String, selected: Boolean, onClick: () -> Unit) {
  Text(
    title,
    style = scaled(13, color = if (selected) Theme.background else Theme.soft),
    modifier = Modifier
      .clip(CircleShape)
      .background(if (selected) Theme.ink else Theme.panel)
      .border(BorderStroke(1.dp, if (selected) Theme.ink else Theme.line), CircleShape)
      .clickable(onClick = onClick)
      .semantics { this.selected = selected }
      .heightIn(min = 32.dp)
      .padding(horizontal = 12.dp, vertical = 6.dp),
  )
}

/** One recipe on Home: the problem it removes first, then its line. A recipe from before headlines leads with its title. */
@Composable
private fun HomeRecipeRow(recipe: Recipe, onClick: () -> Unit) {
  Panel(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Text(recipe.headline.ifBlank { recipe.title }, style = scaled(16, FontWeight.SemiBold, Theme.ink))
    if (recipe.line.isNotBlank()) Text(recipe.line, style = scaled(14, color = Theme.soft))
  }
}

/**
 * A recipe's sheet: what it removes and what that costs today, then its Card in three plain zones (what it does on its own, asks
 * first, never does) and one button that hands it to Coach with the recipe's words, to edit and send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeSheet(recipe: Recipe, labels: RecipeLabels, canHand: Boolean, onHand: () -> Unit, onClose: () -> Unit) {
  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(
      Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(recipe.headline.ifBlank { recipe.title }, style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
      if (recipe.line.isNotBlank()) Text(recipe.line, style = scaled(15, color = Theme.ink))
      if (recipe.today.isNotBlank()) Text(recipe.today, style = scaled(14, color = Theme.soft))
      Zone(labels.allows, recipe.card.allows)
      Zone(labels.holds, recipe.card.holds)
      Zone(labels.never, recipe.card.never)
      if (canHand && labels.hand.isNotBlank()) PrimaryButton(labels.hand, onHand, Modifier.fillMaxWidth())
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
