package bot.tafs.app.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.PackRequest
import bot.tafs.app.kiff.Usage
import bot.tafs.app.model.UsageWords
import kotlinx.coroutines.launch

/**
 * Tasks asked for, on Usage (as iOS's): a member asks an admin, sees their request waiting with Withdraw, and reads the
 * answer; an admin sees the open requests, approves one by buying its pack, or declines it with a note.
 */
@Composable
fun TaskRequests(usage: Usage, state: AppState, actions: AccountActions) {
  val scope = rememberCoroutineScope()
  var asking by remember { mutableStateOf(false) }
  var declining by remember { mutableStateOf<PackRequest?>(null) }
  val usedUp = (usage.tasks.left ?: 1) <= 0

  if (usage.canAddTasks == true) {
    val open = UsageWords.openRequests(usage)
    if (open.isNotEmpty()) Panel(stroke = Theme.amber.copy(alpha = 0.5f)) {
      Eyebrow("Requests", color = Theme.amber)
      open.forEach { r ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("A member asks for ${UsageWords.packLine(r.pack, usage)}.", style = scaled(15, color = Theme.ink))
          if (r.reason.isNotEmpty()) Text(r.reason, style = scaled(14, color = Theme.soft))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Approving is buying the pack, through Google Play: only where this phone offers buying.
            val product = usage.store?.packs?.firstOrNull { it.id == r.pack }?.googleProduct
            if (state.offersPurchases && product != null) {
              PrimaryButton("Approve", { scope.launch { actions.approveRequest(product, r.id) } }, Modifier.weight(1f), enabled = state.buying == null)
            }
            OutlineButton("Decline", { declining = r }, Modifier.weight(1f))
          }
        }
      }
      state.tasksMessage?.let { Text(it, style = scaled(14, color = Theme.no)) }
    }
  } else {
    val mine = UsageWords.waiting(usage)
    if (mine != null) {
      Panel {
        Eyebrow("Waiting")
        Text("You asked for ${UsageWords.packLine(mine.pack, usage)}. An admin answers.", style = scaled(15, color = Theme.ink))
        OutlineButton("Withdraw", { scope.launch { actions.withdrawRequest(mine.id) } })
        state.tasksMessage?.let { Text(it, style = scaled(14, color = Theme.no)) }
      }
    } else {
      UsageWords.lastAnswer(usage)?.let { Panel { Text(it, style = scaled(14, color = Theme.soft)) } }
      if (usedUp) PrimaryButton("Ask an admin", { asking = true }) else OutlineButton("Ask an admin", { asking = true })
    }
  }

  if (asking) AskAdminSheet(usage, state, actions) { asking = false }
  declining?.let { r -> DeclineRequestSheet(r, usage, state, actions) { declining = null } }
}

/** Asking the account's admins for a pack, with a reason. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskAdminSheet(usage: Usage, state: AppState, actions: AccountActions, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  var pack by remember { mutableStateOf<String?>(null) }
  var reason by remember { mutableStateOf("") }
  var sending by remember { mutableStateOf(false) }
  ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SheetTitle("Ask an admin", onClose)
      Text(UsageWords.ASK_LINE, style = scaled(15, color = Theme.soft))
      Panel {
        Eyebrow("Which pack")
        usage.store?.packs.orEmpty().forEach { p ->
          Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { pack = p.id }, verticalAlignment = Alignment.CenterVertically) {
            Text(p.line.ifEmpty { p.name }, style = scaled(15, color = Theme.ink), modifier = Modifier.weight(1f))
            Text(if (pack == p.id) "●" else "○", style = scaled(18, color = if (pack == p.id) Theme.amber else Theme.dim))
          }
        }
      }
      Panel {
        Eyebrow("Why (optional)")
        Field(reason, { reason = it.take(500) }, "What the tasks are for")
      }
      state.tasksMessage?.let { Text(it, style = scaled(14, color = Theme.no)) }
      PrimaryButton(if (sending) "Asking…" else "Ask", {
        val id = pack ?: return@PrimaryButton
        sending = true
        scope.launch {
          val asked = actions.askForPack(id, reason)
          sending = false
          if (asked) onClose()
        }
      }, enabled = pack != null && !sending)
    }
  }
}

/** Declining a member's request, with a note they'll read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeclineRequestSheet(request: PackRequest, usage: Usage, state: AppState, actions: AccountActions, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  var note by remember { mutableStateOf("") }
  var sending by remember { mutableStateOf(false) }
  ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SheetTitle("Decline", onClose)
      Text("Decline the request for ${UsageWords.packLine(request.pack, usage)}?", style = scaled(16, FontWeight.SemiBold, Theme.ink))
      Panel {
        Eyebrow("Note (optional)")
        Field(note, { note = it.take(500) }, "Why, for them")
      }
      state.tasksMessage?.let { Text(it, style = scaled(14, color = Theme.no)) }
      OutlineButton(if (sending) "Declining…" else "Decline", {
        sending = true
        scope.launch {
          val done = actions.declineRequest(request.id, note)
          sending = false
          if (done) onClose()
        }
      }, color = Theme.no, enabled = !sending)
    }
  }
}

@Composable
private fun SheetTitle(title: String, onClose: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = scaled(22, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.weight(1f))
    Text("Cancel", style = scaled(17, color = Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 10.dp))
  }
}
