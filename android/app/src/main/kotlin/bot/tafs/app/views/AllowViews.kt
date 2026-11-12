package bot.tafs.app.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AllowSheetState
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.Words

/** The sheet's actions, the model's. */
class AllowActions(
  val round: () -> Unit,
  val sendHeld: (Boolean) -> Unit,
  val confirm: () -> Unit,
  val close: () -> Unit,
)

/**
 * "Allow this from now on" (docs/allow-from-now-on.md): tafs cloud's change, before and after, in its words; KIFF's own lines for each
 * Card that changes, under them; what it would have done; the held call's switch; and one signature, only when KIFF's draft is
 * what was offered. A `none` says tafs cloud's words, with nothing to sign.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllowSheet(open: AllowSheetState, actions: AllowActions) {
  DriveSheet(
    onDismissRequest = actions.close,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(Words.NeedsYou.allowTitle, style = scaled(22, FontWeight.Bold, Theme.ink, display = true))
      val answer = open.answer
      val offer = answer?.offer
      when {
        answer == null && open.busy -> CircularProgressIndicator(Modifier.padding(8.dp), color = Theme.dim)
        answer?.none != null -> Text(answer.words.orEmpty(), style = scaled(15, color = Theme.soft))
        offer != null -> {
          offer.changes.forEach { change ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(change.wordsBefore, style = scaled(14, color = Theme.soft))
              Text("→ " + change.wordsAfter, style = scaled(16, FontWeight.SemiBold, Theme.ink))
            }
          }
          // KIFF's own lines for each Card that changes, as it keeps them: what's signed.
          open.draft?.cards.orEmpty().filter { !it.isUnchanged }.forEach { card ->
            card.lines.forEach { Text(it, style = scaled(13, color = Theme.dim)) }
          }
          offer.would?.words?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(14, color = Theme.ink)) }
          if (!open.matches) {
            Text(Words.NeedsYou.allowNotOffered, style = scaled(14, color = Theme.no))
          } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Text(Words.NeedsYou.allowSendThisToo, style = scaled(15, color = Theme.ink), modifier = Modifier.weight(1f))
              Switch(checked = open.sendHeld, onCheckedChange = actions.sendHeld, enabled = !open.busy)
            }
            if (!open.sendHeld) Text(Words.NeedsYou.allowSwitchOff, style = scaled(13, color = Theme.soft))
            offer.round?.words?.takeIf { it.isNotBlank() && !open.rounded }?.let { figure ->
              OutlineButton(Words.NeedsYou.allowRoundUp(amount = figure), actions.round, Modifier.fillMaxWidth(), enabled = !open.busy)
            }
            ApprovalGate {
              PrimaryButton(
                if (open.busy) Words.Approval.approving else Words.Approval.approveWith(LocalBiometry.current),
                actions.confirm,
                enabled = !open.busy,
              )
            }
          }
        }
      }
      open.error?.let { Text(it, style = scaled(13, color = Theme.no)) }
      Text(
        Words.Common.cancel,
        style = scaled(15, FontWeight.SemiBold, Theme.dim),
        modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = actions.close).padding(top = 10.dp),
      )
    }
  }
}
