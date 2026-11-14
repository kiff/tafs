package bot.tafs.app.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.kiff.HomeLabel
import bot.tafs.app.model.Taf
import bot.tafs.app.model.Words

/**
 * Labels on the tafs list (docs/android-app.md, Labels): Coach-managed chips on a flat list. The row opens on All every time; then each label
 * with its count and an amber dot when one of its tafs waits; at most two labels, then "+N" for the rest. tafs cloud lists the
 * labels, counts them and words the filtered headline: the app only filters the rows by them.
 */

/** The labels the row shows: two, and the number left for "+N", unless the row was opened out or the chosen one is further on. */
internal fun shownLabels(labels: List<HomeLabel>, expanded: Boolean, selected: String?): Pair<List<HomeLabel>, Int> {
  val further = selected != null && labels.indexOfFirst { it.id == selected } >= MAX_FOLDED
  return if (expanded || further || labels.size <= MAX_FOLDED) labels to 0 else labels.take(MAX_FOLDED) to labels.size - MAX_FOLDED
}

/** The tafs a label holds, or all of them with none chosen. */
internal fun onLabel(tafs: List<Taf>, label: String?): List<Taf> = if (label == null) tafs else tafs.filter { label in it.labels }

/** A taf's labels by name, in the account's order: at most three. */
internal fun labelTags(taf: Taf, labels: List<HomeLabel>): List<String> =
  labels.filter { it.id in taf.labels }.map { it.name }.take(3)

/** The list's headline filtered to a label: tafs cloud's ("2 tafs working · Money"), else the label's name. */
internal fun labelHeadline(label: HomeLabel): String = label.headline?.words?.takeIf { it.isNotBlank() } ?: label.name

/** The label chosen, while it's still one of the account's: one that went with a refresh falls back to All. */
internal fun chosenLabel(labels: List<HomeLabel>, selected: String?): HomeLabel? = labels.firstOrNull { it.id == selected }

private const val MAX_FOLDED = 2

@Composable
internal fun LabelRow(labels: List<HomeLabel>, all: String, allCount: Int, selected: String?, onSelect: (String?) -> Unit) {
  if (labels.isEmpty()) return
  var expanded by remember { mutableStateOf(false) }
  val (shown, more) = shownLabels(labels, expanded, selected)
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    LabelChip(all, allCount, waiting = false, chosen = selected == null) { onSelect(null) }
    shown.forEach { label -> LabelChip(label.name, label.count, label.waiting > 0, chosen = selected == label.id) { onSelect(label.id) } }
    if (more > 0) LabelChip("+$more", null, waiting = false, chosen = false, dim = true) { expanded = true }
  }
}

@Composable
private fun LabelChip(name: String, count: Int?, waiting: Boolean, chosen: Boolean, dim: Boolean = false, onClick: () -> Unit) {
  val shape = RoundedCornerShape(999.dp)
  val ink = if (chosen) Theme.panel else if (dim) Theme.dim else Theme.soft
  Row(
    Modifier
      .heightIn(min = 36.dp)
      .clip(shape)
      .background(if (chosen) Theme.ink else Theme.panel)
      .border(BorderStroke(1.dp, if (chosen) Theme.ink else Theme.line), shape)
      .semantics { selected = chosen }
      .tapping(role = Role.Tab, onClick = onClick)
      .padding(horizontal = 11.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (waiting) Box(Modifier.size(8.dp).clip(CircleShape).background(Theme.amber))
    Text(name, style = scaled(13, FontWeight.Medium, ink), maxLines = 1)
    count?.let { Text("$it", style = scaled(13, color = if (chosen) Theme.panel.copy(alpha = 0.7f) else Theme.dim)) }
  }
}

/** A taf's labels on its row: small chips under its line. */
@Composable
internal fun LabelTags(tags: List<String>) {
  if (tags.isEmpty()) return
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    tags.forEach { tag ->
      Text(
        tag,
        style = scaled(11, FontWeight.Medium, Theme.soft),
        maxLines = 1,
        modifier = Modifier
          .clip(RoundedCornerShape(999.dp))
          .background(Theme.raised)
          .border(BorderStroke(1.dp, Theme.line), RoundedCornerShape(999.dp))
          .padding(horizontal = 8.dp, vertical = 2.dp),
      )
    }
  }
}

/** "All", tafs cloud's word, else the app's. */
internal fun allWords(fromCloud: String?): String = fromCloud ?: Words.Home.labelsAll
