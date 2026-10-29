package bot.tafs.app.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.Share
import bot.tafs.app.model.PeopleWords
import kotlinx.coroutines.launch

/** Who a taf is shared with, on its page, and Share: only for whoever manages it, as KIFF says, and only for a taf KIFF runs. */
@Composable
fun ShareLine(tafId: String, state: AppState, actions: PeopleActions) {
  val taf = state.taf(tafId) ?: return
  if (state.mode != Mode.kiff || !state.manages(tafId)) return
  var shares by remember { mutableStateOf<List<Share>?>(null) }
  var sharing by remember { mutableStateOf(false) }
  // Read on open, when the groups change, and again once the sheet closes, whatever it changed.
  LaunchedEffect(tafId, state.groups, sharing) { if (!sharing) shares = actions.shares(tafId) }
  LaunchedEffect(Unit) { if (state.groups.isEmpty() && state.people.isEmpty()) actions.refresh() }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    // Large enough to find, with who uses the taf beside it.
    val shape = RoundedCornerShape(50)
    Text(
      "Share",
      style = scaled(15, FontWeight.SemiBold, Theme.ink),
      modifier = Modifier.clip(shape).border(BorderStroke(1.dp, Theme.line), shape).clickable { sharing = true }.padding(horizontal = 10.dp, vertical = 6.dp),
    )
    Text(PeopleWords.sharedWith(shares, state.groups, state.people), style = scaled(14, color = Theme.soft), maxLines = 1)
  }
  if (sharing) ShareTafSheet(tafId, taf.name, state, actions, onClose = { sharing = false })
}

/** Share a taf, in two steps: who uses it, then what that means for them, then one confirmation for all of it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTafSheet(agent: String, name: String, state: AppState, actions: PeopleActions, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  var step by remember { mutableStateOf(1) }
  var had by remember { mutableStateOf<Set<Share>>(emptySet()) }
  var chosen by remember { mutableStateOf<Set<Share>>(emptySet()) }
  var loaded by remember { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  LaunchedEffect(agent) {
    if (state.groups.isEmpty() && state.people.isEmpty()) actions.refresh()
    actions.shares(agent)?.let { now -> had = now.toSet(); chosen = had }
    loaded = true
  }
  ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
          if (step == 1) "Cancel" else "Back",
          style = scaled(17, FontWeight.SemiBold, Theme.amber),
          modifier = Modifier.heightIn(min = 44.dp).clickable { if (step == 1) onClose() else step = 1 }.padding(top = 8.dp),
        )
        Text("Share $name", style = scaled(17, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f).padding(top = 8.dp))
        if (step == 1) {
          val ready = loaded && chosen != had
          Text("Next", style = scaled(17, FontWeight.SemiBold, if (ready) Theme.amber else Theme.dim), modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = ready) { step = 2 }.padding(top = 8.dp))
        } else {
          Text(
            "Share",
            style = scaled(17, FontWeight.SemiBold, if (saving) Theme.dim else Theme.amber),
            modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = !saving) {
              saving = true
              scope.launch {
                val done = actions.setShares(agent, name, chosen - had, had - chosen)
                saving = false
                if (done) onClose()
              }
            }.padding(top = 8.dp),
          )
        }
      }
      Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (step == 1) {
          Text("STEP 1 OF 2", style = scaled(11, FontWeight.Bold, Theme.dim))
          Text("Who uses $name?", style = scaled(20, FontWeight.Bold, Theme.ink))
          if (state.groups.isNotEmpty()) {
            Panel {
              Text("GROUPS", style = scaled(12, color = Theme.dim, mono = true))
              state.groups.forEach { group ->
                Pick(Share(group = group.id), chosen, { chosen = it }) {
                  Column {
                    Text(group.name, style = scaled(15, FontWeight.SemiBold, Theme.ink))
                    Text(PeopleWords.peopleOf(group, state.people), style = scaled(12, color = Theme.dim), maxLines = 1)
                  }
                }
              }
            }
          }
          Panel {
            Text("ONE PERSON", style = scaled(12, color = Theme.dim, mono = true))
            state.people.forEach { person ->
              Pick(Share(subject = person.subject), chosen, { chosen = it }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  PersonBadge(person.label, size = 28)
                  Text(person.label, style = scaled(15, color = Theme.ink), maxLines = 1)
                }
              }
            }
            Text("Choose someone on the account.", style = scaled(12, color = Theme.dim))
          }
        } else {
          Text("STEP 2 OF 2", style = scaled(11, FontWeight.Bold, Theme.dim))
          Text(if (chosen.isEmpty()) "$name won't be shared" else "Who gets $name", style = scaled(20, FontWeight.Bold, Theme.ink))
          Panel {
            PeopleWords.consequences(name, had, chosen, state.groups, state.people).forEach { line ->
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("✓", style = scaled(14, FontWeight.SemiBold, Theme.ok))
                Text(line, style = scaled(14, color = Theme.ink))
              }
            }
          }
        }
        state.peopleError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
      }
    }
  }
}

/** One choice of who uses the taf: tapped in or out. */
@Composable
private fun Pick(share: Share, chosen: Set<Share>, onChosen: (Set<Share>) -> Unit, label: @Composable () -> Unit) {
  val on = share in chosen
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChosen(if (on) chosen - share else chosen + share) }.padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) { label() }
    Text(if (on) "●" else "○", style = scaled(18, color = if (on) Theme.amber else Theme.dim))
  }
}
