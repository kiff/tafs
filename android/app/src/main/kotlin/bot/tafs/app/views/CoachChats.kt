package bot.tafs.app.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.TafsCloud
import bot.tafs.app.model.CoachWords
import bot.tafs.app.model.endsWords
import kotlinx.coroutines.launch

/** What Coach's conversations ask of the model (kiff/tafs#73, #82, #97). */
class CoachChatActions(
  val newChat: () -> Unit = {},
  val open: suspend (String) -> Unit = {},
  val load: suspend () -> Unit = {},
  val loadArchived: suspend () -> Unit = {},
  val archive: suspend (String) -> Boolean = { false },
  val unarchive: suspend (String) -> Boolean = { false },
  /** Gives Coach the tools tafs cloud says are still to give. */
  val giveTools: suspend () -> Boolean = { false },
)

/** Coach's earlier conversations, newest first: each reopens; one can be archived, and the archived ones put back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviousConversations(state: AppState, actions: CoachChatActions, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  var archived by remember { mutableStateOf(false) }
  // The conversation waiting for the owner's yes: archiving takes it off every device's list.
  var archiving by remember { mutableStateOf<TafsCloud.Chat?>(null) }
  LaunchedEffect(archived) { if (archived) actions.loadArchived() else actions.load() }
  ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (archived) {
          Text("‹ Back", style = scaled(17, color = Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable { archived = false }.padding(top = 10.dp, end = 12.dp))
        }
        Text(if (archived) "Archived" else "Previous conversations", style = scaled(22, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.weight(1f))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 10.dp))
      }
      val chats = if (archived) state.coachArchived else state.coachChats
      if (chats.isEmpty()) {
        Text(if (archived) "Nothing archived." else "No conversations yet.", style = scaled(15, color = Theme.dim), modifier = Modifier.padding(vertical = 16.dp))
      }
      chats.forEach { chat ->
        ChatRow(
          chat,
          action = if (archived) "Put back" else "Archive",
          onOpen = {
            scope.launch {
              actions.open(chat.session)
              onClose()
            }
          },
          onAction = { if (archived) scope.launch { actions.unarchive(chat.session) } else archiving = chat },
        )
      }
      if (!archived) {
        Text("Archived", style = scaled(15, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.heightIn(min = 44.dp).clickable { archived = true }.padding(top = 12.dp))
      }
    }
  }
  archiving?.let { chat ->
    AlertDialog(
      onDismissRequest = { archiving = null },
      title = { Text("Archive this conversation?") },
      text = { Text("It leaves this list on all your devices. You'll find it under Archived, where you can open it or put it back.") },
      confirmButton = {
        TextButton({
          archiving = null
          scope.launch { actions.archive(chat.session) }
        }) { Text("Archive", color = Theme.no) }
      },
      dismissButton = { TextButton({ archiving = null }) { Text("Cancel", color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}

/** One conversation: its title and when it last moved; a long press offers Archive, or Put back. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: TafsCloud.Chat, action: String, onOpen: () -> Unit, onAction: () -> Unit) {
  var menu by remember { mutableStateOf(false) }
  Box {
    Column(
      Modifier.fillMaxWidth().heightIn(min = 52.dp).combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(chat.title.ifBlank { "Conversation" }, style = scaled(15, FontWeight.SemiBold, Theme.ink), maxLines = 2)
      Text(endsWords(chat.updated), style = scaled(12, color = Theme.dim))
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
      DropdownMenuItem(text = { Text(action) }, onClick = { menu = false; onAction() })
    }
  }
}

/**
 * Coach's empty chat (kiff/tafs#138, #107): what the conversation is about, and ways to start it. Improving a taf, it names the
 * taf and starts from what it did; otherwise it's anything about the account's tafs.
 */
@Composable
fun CoachStarts(state: AppState, improve: (String) -> Unit = {}, send: (String) -> Unit) {
  val improving = state.coachImproving?.let { state.taf(it)?.name }
  val sending = COACH_CHAT in state.chatSending
  Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(improving?.let { "Improve $it" } ?: CoachWords.ASK_ANYTHING, style = scaled(26, FontWeight.Bold, Theme.ink, display = true))
    Text(improving?.let(CoachWords::improveLine) ?: CoachWords.ASK_ANYTHING_LINE, style = scaled(15, color = Theme.soft))
    // Or start on one taf, as iOS does: each taf this person manages, its own conversation.
    if (improving == null) {
      CoachWords.improvable(state).forEach { taf ->
        Text(
          "Improve ${taf.name}",
          style = scaled(15, FontWeight.SemiBold, Theme.amberText),
          modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = !sending) { improve(taf.id) }.padding(vertical = 10.dp),
        )
      }
    }
    (if (improving != null) CoachWords.IMPROVE_EXAMPLES else CoachWords.EXAMPLES).forEach { example ->
      val shape = RoundedCornerShape(14.dp)
      Text(
        example,
        style = scaled(15, color = Theme.ink),
        modifier = Modifier
          .fillMaxWidth()
          .clip(shape)
          .background(Theme.panel)
          .border(BorderStroke(1.dp, Theme.line), shape)
          .clickable(enabled = !sending) { send(example) }
          .padding(horizontal = 14.dp, vertical = 12.dp),
      )
    }
  }
}

/**
 * News of the tasks Coach handed tafs (kiff/tafs#206), until Coach reads it on its next turn: what came while the owner was
 * away together, under "While you were away"; the rest one line each.
 */
@Composable
fun CoachLines(lines: List<TafsCloud.CoachLine>) {
  val (away, here) = lines.partition { it.away == true }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (away.isNotEmpty()) {
      bot.tafs.app.design.Panel(Modifier.semantics(mergeDescendants = true) {}) {
        bot.tafs.app.design.Eyebrow("While you were away")
        away.forEach { Text("${it.taf}: ${it.said}", style = scaled(14, color = Theme.ink)) }
      }
    }
    here.forEach { Text("→ ${it.taf}: ${it.said}", style = scaled(14, color = Theme.soft)) }
  }
}

/**
 * Coach's tools still to give, as tafs cloud reads them from KIFF (docs/coach.md): each connection's line and Coach's Card in
 * KIFF's own words, read before the one confirmation that gives them. A name a vendor's tool holds, or KIFF not answering,
 * says so instead. Nothing shows once they're given.
 */
@Composable
fun CoachToolsPanel(state: AppState, actions: CoachChatActions) {
  val tools = state.coachTools ?: return
  if (tools.state == "given") return
  val scope = rememberCoroutineScope()
  var giving by remember { mutableStateOf(false) }
  bot.tafs.app.design.Panel(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), stroke = Theme.amber.copy(alpha = 0.45f)) {
    bot.tafs.app.design.Eyebrow("Coach's tools", color = Theme.amber)
    when (tools.state) {
      "taken" -> Text(CoachWords.taken(tools.taken), style = scaled(14, color = Theme.ink))
      "unknown" -> Text(CoachWords.TOOLS_UNKNOWN, style = scaled(14, color = Theme.ink))
      else -> {
        tools.connect?.lines?.forEach { Text("· $it", style = scaled(14, color = Theme.ink)) }
        tools.card?.let { card ->
          card.cardTitle?.let { Text(it, style = scaled(14, FontWeight.SemiBold, Theme.ink), modifier = Modifier.padding(top = 4.dp)) }
          card.words.forEach { (title, lines) ->
            if (title.isNotEmpty()) Text(title, style = scaled(13, FontWeight.SemiBold, Theme.ink))
            lines.forEach { Text(it, style = scaled(13, color = Theme.soft)) }
          }
        }
        bot.tafs.app.design.PrimaryButton(
          if (giving) "Giving…" else "Give Coach its tools",
          {
            giving = true
            scope.launch {
              actions.giveTools()
              giving = false
            }
          },
          enabled = !giving,
        )
      }
    }
    state.coachToolsError?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}
