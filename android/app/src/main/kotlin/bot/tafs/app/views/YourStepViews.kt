package bot.tafs.app.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import bot.tafs.app.drive.confirms
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.YourStep
import kotlinx.coroutines.launch

/** What a step's buttons do: answer it at KIFF, Done or Skip it; and open its link, where tafs cloud sent one. */
data class YourStepActions(val answer: suspend (YourStep, Boolean) -> Unit, val open: ((String) -> Unit)? = null)

val LocalYourStepActions = staticCompositionLocalOf<YourStepActions?> { null }

/**
 * A step a taf may never take itself, handed to its owner (docs/api.md › Your step), in tafs cloud's words: its title, the taf,
 * what to do and why. **Open in {vendor}** shows only for an https link whose host is tafs cloud's `link_host`, with its words, and
 * opens the browser on it, the host under it; the app never builds a link. Done and Skip it are signed by this phone and go to KIFF, for those who answer holds.
 */
@Composable
fun YourStepPanel(step: YourStep, state: AppState) {
  val actions = LocalYourStepActions.current
  val scope = rememberCoroutineScope()
  val uri = LocalUriHandler.current
  val answering = step.handoff in state.answeringSteps
  Panel(stroke = Theme.amber.copy(alpha = 0.5f)) {
    Eyebrow(listOfNotNull(step.words.title.ifBlank { null }, step.taf?.name?.ifBlank { null }).joinToString(" · "), color = Theme.amberText)
    Text(step.what, style = scaled(16, FontWeight.SemiBold, Theme.ink))
    if (step.why.isNotBlank()) Text(step.why, style = scaled(14, color = Theme.soft))
    step.open?.let { o ->
      OutlineButton(o.words, { val open = actions?.open; if (open != null) open(o.link) else runCatching { uri.openUri(o.link) } }, Modifier.fillMaxWidth())
      // Where it really goes, as tafs cloud read the link.
      Text(o.host, style = scaled(12, color = Theme.dim, mono = true))
    }
    if (actions != null && state.canAnswerHolds != false) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(step.words.done, { scope.launch { actions.answer(step, true) } }, Modifier.weight(1f), enabled = !answering)
        OutlineButton(step.words.skip, { scope.launch { actions.answer(step, false) } }, Modifier.weight(1f), enabled = !answering)
      }
    }
  }
}

/**
 * A Coach chat waiting on this person (docs/api.md › Coach waiting), in tafs cloud's words: what Coach waits for, and the way into
 * that chat. With no way in (sample mode), the line alone.
 */
@Composable
fun CoachWaitingPanel(waiting: bot.tafs.app.kiff.CoachWaiting, onOpen: ((bot.tafs.app.kiff.CoachWaiting) -> Unit)?, onSetAside: ((String?) -> Unit)? = null) {
  val panel = @Composable {
    Panel(stroke = Theme.amber.copy(alpha = 0.5f)) {
      Text(waiting.words.line, style = scaled(16, FontWeight.SemiBold, Theme.ink))
      if (onOpen != null && waiting.words.open.isNotBlank()) PrimaryButton(waiting.words.open, { onOpen(waiting) }, Modifier.fillMaxWidth())
      // The swipe's words, as a button too: a swipe isn't the only way (accessibility).
      // Coach driving the app never sets a chat aside: it's the person's to confirm (docs/drive.md).
      if (onSetAside != null) OutlineButton(waiting.words.setAside, { onSetAside(waiting.session) }, Modifier.fillMaxWidth().confirms())
    }
  }
  if (onSetAside == null) { panel(); return }
  // Swiped either way, it's set aside: tafs cloud's "not now", and the row goes once tafs cloud says so.
  // Once per gesture: confirmValueChange is asked more than once in one swipe, and the model also ignores a chat in flight.
  var fired by remember(waiting.session) { mutableStateOf(false) }
  val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState(confirmValueChange = { v ->
    if (v != androidx.compose.material3.SwipeToDismissBoxValue.Settled && !fired) { fired = true; bot.tafs.app.design.Haptics.tap(); onSetAside(waiting.session) }
    false
  })
  LaunchedEffect(swipe.targetValue) { if (swipe.targetValue == androidx.compose.material3.SwipeToDismissBoxValue.Settled) fired = false }
  androidx.compose.material3.SwipeToDismissBox(swipe, modifier = Modifier.confirms(), backgroundContent = {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
      Text(waiting.words.setAside, style = scaled(14, FontWeight.SemiBold, Theme.amberText))
    }
  }) { panel() }
}
