package bot.tafs.app.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.RequestDetail
import bot.tafs.app.kiff.RequestStep

/** A request's own words in the app; every line and time in it is tafs cloud's. */
object RequestWords {
  const val WHAT_HAPPENED = "What happened"
  const val TRY_REPLY_NOW = "Try the reply now"
  const val ASK_COACH = "Ask Coach"

  /** A step's mark, by its state. */
  fun mark(step: RequestStep): String = when (step.state) {
    "done" -> "✓"
    "failed" -> "✕"
    "waiting" -> "●"
    "not_made" -> "–"
    else -> "○"
  }

  /** Whether tafs cloud says [step] waits for a connected app's event, not for the owner: it isn't drawn as needing them. */
  fun waitsOnApp(step: RequestStep): Boolean = step.waitsOn == "app"
}

private fun markColor(step: RequestStep): Color = when {
  step.state == "done" -> Theme.ok
  step.state == "failed" -> Theme.no
  RequestWords.waitsOnApp(step) -> Theme.soft
  step.state == "waiting" -> Theme.amberText
  else -> Theme.dim
}

/**
 * One request from the dial's rim or Activity (K5): its line, where it came from, and what happened, step by step, as tafs cloud
 * wrote it. A reply given up on can be tried again ([onTryReply], for whoever manages the taf); Coach can be asked about it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestSheet(
  detail: RequestDetail,
  sending: Boolean,
  onTryReply: (() -> Unit)?,
  onAskCoach: (() -> Unit)?,
  onClose: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(
      Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(detail.line, style = scaled(22, FontWeight.Bold, Theme.ink, display = true))
      detail.from?.let { Text(it, style = scaled(14, color = Theme.soft)) }
      Panel {
        Eyebrow(RequestWords.WHAT_HAPPENED)
        detail.steps.forEach { step ->
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(RequestWords.mark(step), style = scaled(14, FontWeight.Bold, markColor(step)), modifier = Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
              Text(step.label, style = scaled(15, FontWeight.SemiBold, if (step.state == "next" || step.state == "not_made") Theme.dim else Theme.ink))
              val sub = listOfNotNull(step.line, step.at).joinToString(" · ")
              if (sub.isNotEmpty()) Text(sub, style = scaled(13, color = Theme.soft))
            }
          }
        }
      }
      onTryReply?.let { OutlineButton(if (sending) "Sending…" else RequestWords.TRY_REPLY_NOW, it, Modifier.fillMaxWidth(), enabled = !sending) }
      onAskCoach?.let {
        Text(
          RequestWords.ASK_COACH,
          style = scaled(15, FontWeight.SemiBold, Theme.amber),
          modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = it).padding(top = 10.dp),
        )
      }
    }
  }
}
