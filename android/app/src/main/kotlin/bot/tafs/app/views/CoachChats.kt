package bot.tafs.app.views

import bot.tafs.app.design.tapping
import bot.tafs.app.model.Words
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.DriveAlert
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.testTag
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
  /** The tools step asked at the end of a first Start was put off: the one-line row opens it again. */
  val notNowTools: () -> Unit = {},
)

/** Coach's earlier conversations, newest first: each reopens; one can be archived, and the archived ones put back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviousConversations(state: AppState, actions: CoachChatActions, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  var archived by remember { mutableStateOf(false) }
  // The conversation waiting for the owner's yes: archiving takes it off every device's list.
  var archiving by remember { mutableStateOf<TafsCloud.Chat?>(null) }
  // An archived conversation to put back, asked by its title.
  var puttingBack by remember { mutableStateOf<TafsCloud.Chat?>(null) }
  LaunchedEffect(archived) { if (archived) actions.loadArchived() else actions.load() }
  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (archived) {
          Text(Words.Coach.Chats.back, style = scaled(17, color = Theme.amber), modifier = Modifier.heightIn(min = 44.dp).tapping { archived = false }.padding(top = 10.dp, end = 12.dp))
        }
        Text(if (archived) Words.Coach.Chats.archived else Words.Coach.Chats.previous, style = scaled(22, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.weight(1f))
        Text(Words.Common.done, style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = onClose).padding(top = 10.dp))
      }
      val chats = if (archived) state.coachArchived else state.coachChats
      if (chats.isEmpty()) {
        Text(if (archived) Words.Coach.Chats.nothingArchived else Words.Coach.Chats.none, style = scaled(15, color = Theme.dim), modifier = Modifier.padding(vertical = 16.dp))
      }
      chats.forEach { chat ->
        ChatRow(
          chat,
          action = if (archived) Words.Coach.Chats.putBack else Words.Coach.Chats.archive,
          onOpen = {
            scope.launch {
              actions.open(chat.session)
              onClose()
            }
          },
          onAction = { if (archived) puttingBack = chat else archiving = chat },
        )
      }
      if (!archived) {
        Text(Words.Coach.Chats.archived, style = scaled(15, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.heightIn(min = 44.dp).tapping { archived = true }.padding(top = 12.dp))
      }
    }
  }
  archiving?.let { chat ->
    DriveAlert(
      onDismissRequest = { archiving = null },
      title = { Text(Words.Coach.Chats.archiveTitle) },
      // The chat it acts on, by its title, so nobody has to guess which one.
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(chat.title.ifBlank { Words.Coach.Chats.untitled }, style = scaled(15, FontWeight.SemiBold, Theme.ink), maxLines = 3, modifier = Modifier.testTag("archive.chat"))
          Text(Words.Coach.Chats.archiveBody)
        }
      },
      confirmButton = {
        TextButton({
          archiving = null
          scope.launch { actions.archive(chat.session) }
        }) { Text(Words.Coach.Chats.archive, color = Theme.no) }
      },
      dismissButton = { TextButton({ archiving = null }) { Text(Words.Common.cancel, color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
  puttingBack?.let { chat ->
    DriveAlert(
      onDismissRequest = { puttingBack = null },
      title = { Text(chat.title.ifBlank { Words.Coach.Chats.untitled }, maxLines = 3, modifier = Modifier.testTag("archive.chat")) },
      confirmButton = {
        TextButton({
          puttingBack = null
          scope.launch { actions.unarchive(chat.session) }
        }) { Text(Words.Coach.Chats.putBack) }
      },
      dismissButton = { TextButton({ puttingBack = null }) { Text(Words.Common.cancel, color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}

/** One conversation: its title and when it last moved; a long press offers Archive, or Put back. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: TafsCloud.Chat, action: String, onOpen: () -> Unit, onAction: () -> Unit) {
  Box {
    // A long press asks about this chat by name (Archive, or Put back): no menu that could open over another row.
    Column(
      Modifier.fillMaxWidth().heightIn(min = 52.dp).combinedClickable(onClick = onOpen, onLongClickLabel = action, onLongClick = onAction).padding(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(chat.title.ifBlank { Words.Coach.Chats.untitled }, style = scaled(15, FontWeight.SemiBold, Theme.ink), maxLines = 2)
      Text(endsWords(chat.updated), style = scaled(12, color = Theme.dim))
    }
  }
}

/**
 * Coach's empty chat (kiff/tafs#138, #107): what the conversation is about, and ways to start it. Improving a taf, it names the
 * taf and starts from what it did; otherwise it's anything about the account's tafs, and tafs cloud's recipes by category
 * (kiff/tafs#476): a tap puts a recipe's words in the message box, to edit and send, never sends them.
 */
@Composable
fun CoachStarts(state: AppState, improve: (String) -> Unit = {}, fill: (String) -> Unit = {}, send: (String) -> Unit) {
  val improving = state.coachImproving?.let { state.taf(it)?.name }
  val sending = COACH_CHAT in state.chatSending
  Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(improving?.let { Words.Coach.improveTaf(it) } ?: CoachWords.ASK_ANYTHING, style = scaled(26, FontWeight.Bold, Theme.ink, display = true))
    Text(improving?.let(CoachWords::improveLine) ?: CoachWords.ASK_ANYTHING_LINE, style = scaled(15, color = Theme.soft))
    // Or start on one taf, as iOS does: each taf this person manages, its own conversation.
    if (improving == null) {
      CoachWords.improvable(state).forEach { taf ->
        Text(
          Words.Coach.improveTaf(taf.name),
          style = scaled(15, FontWeight.SemiBold, Theme.amberText),
          modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).tapping(enabled = !sending) { improve(taf.id) }.padding(vertical = 10.dp),
        )
      }
    }
    if (improving == null) {
      state.recipes?.byCategory?.forEach { (category, recipes) ->
        bot.tafs.app.design.Eyebrow(category.title)
        recipes.forEach { recipe -> RecipeRow(recipe, enabled = !sending) { fill(recipe.say) } }
      }
      return@Column
    }
    CoachWords.IMPROVE_EXAMPLES.forEach { example ->
      val shape = RoundedCornerShape(14.dp)
      Text(
        example,
        style = scaled(15, color = Theme.ink),
        modifier = Modifier
          .fillMaxWidth()
          .clip(shape)
          .background(Theme.panel)
          .border(BorderStroke(1.dp, Theme.line), shape)
          .tapping(enabled = !sending) { send(example) }
          .padding(horizontal = 14.dp, vertical = 12.dp),
      )
    }
  }
}

/** One recipe, as tafs cloud words it: its title, and its line under it. */
@Composable
private fun RecipeRow(recipe: bot.tafs.app.kiff.Recipe, enabled: Boolean, onTap: () -> Unit) {
  val shape = RoundedCornerShape(14.dp)
  Column(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(Theme.panel)
      .border(BorderStroke(1.dp, Theme.line), shape)
      .tapping(enabled = enabled, onClick = onTap)
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    // One name per job: led by the problem it ends, as the gallery and Home name it; a recipe without a headline by its title.
    Text(recipe.headline.ifBlank { recipe.title }, style = scaled(15, FontWeight.SemiBold, Theme.ink))
    if (recipe.line.isNotBlank()) Text(recipe.line, style = scaled(13, color = Theme.soft))
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
        bot.tafs.app.design.Eyebrow(Words.Coach.away)
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
fun CoachToolsRow(state: AppState, onOpen: () -> Unit) {
  val tools = state.coachTools ?: return
  if (tools.state == "given") return
  // One line above the conversation, never the step itself: the box keeps its room, keyboard open or not.
  Row(
    Modifier.fillMaxWidth().heightIn(min = 44.dp).tapping(onClickLabel = Words.Coach.Tools.title, onClick = onOpen).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(tools.step?.row?.ifBlank { null } ?: Words.Coach.Tools.title, style = scaled(14, FontWeight.SemiBold, Theme.amberText), maxLines = 1, modifier = Modifier.weight(1f))
    Text("›", style = scaled(20, color = Theme.dim))
  }
}

/**
 * Coach's tools as a step of their own (docs/coach.md): what tafs cloud says is still to give, the connections in its words and
 * the Card in KIFF's, then Give Coach its tools behind its own fingerprint, or Not now. Opened at the end of a first Start, and
 * from the one-line row after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachToolsSheet(state: AppState, actions: CoachChatActions, onClose: () -> Unit) {
  val tools = state.coachTools
  if (tools == null || tools.state == "given") {
    LaunchedEffect(Unit) { onClose() }
    return
  }
  val scope = rememberCoroutineScope()
  var giving by remember { mutableStateOf(false) }
  bot.tafs.app.drive.DriveSheet(onDismissRequest = onClose, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
  Column(
    Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    val step = tools.step
    // tafs cloud's words where it sends them; the app's own only for a tafs cloud from before them.
    fun said(server: String?, own: String) = server?.ifBlank { null } ?: own
    if (step?.title.isNullOrBlank()) bot.tafs.app.design.Eyebrow(Words.Coach.Tools.title, color = Theme.amber)
    else Text(step!!.title, style = scaled(22, FontWeight.Bold, Theme.ink, display = true))
    when (tools.state) {
      "taken" -> Text(CoachWords.taken(tools.taken), style = scaled(14, color = Theme.ink))
      "unknown" -> Text(CoachWords.TOOLS_UNKNOWN, style = scaled(14, color = Theme.ink))
      else -> {
        var folded by remember { mutableStateOf(true) }
        if (step != null && step.groups.isNotEmpty()) {
          if (step.intro.isNotBlank()) Text(step.intro, style = scaled(15, color = Theme.soft))
          // A few groups up front; each tool and its limits folded under them, closed at first.
          step.groups.forEach { g ->
            Column(Modifier.padding(top = 4.dp)) {
              Text(g.name, style = scaled(15, FontWeight.SemiBold, Theme.ink))
              if (g.line.isNotBlank()) Text(g.line, style = scaled(14, color = Theme.soft))
            }
          }
          if (step.details.isNotBlank()) Text(
            step.details + if (folded) " ⌄" else " ⌃",
            style = scaled(14, FontWeight.SemiBold, Theme.amberText),
            modifier = Modifier.heightIn(min = 44.dp).tapping { folded = !folded }.padding(vertical = 10.dp),
          )
        }
        // What's signed: the connections in tafs cloud's words and the Card in KIFF's, under the fold where the step has one.
        if (step == null || step.groups.isEmpty() || step.details.isBlank() || !folded) {
          step?.groups?.forEach { g ->
            if (g.tools.isNotEmpty()) Text(g.name, style = scaled(13, FontWeight.SemiBold, Theme.ink), modifier = Modifier.padding(top = 4.dp))
            g.tools.forEach { t ->
              Text(t.name, style = scaled(14, FontWeight.SemiBold, Theme.ink))
              if (t.does.isNotBlank()) Text(t.does, style = scaled(13, color = Theme.ink))
              if (t.why.isNotBlank()) Text(t.why, style = scaled(13, color = Theme.soft))
            }
          }
          if (step == null || step.groups.isEmpty()) tools.connect?.lines?.forEach { Text("· $it", style = scaled(14, color = Theme.ink)) }
        }
        step?.ask?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.ink), modifier = Modifier.padding(top = 6.dp)) }
        if (step == null || step.groups.isEmpty() || !folded) tools.card?.let { card ->
          card.cardTitle?.let { Text(it, style = scaled(14, FontWeight.SemiBold, Theme.ink), modifier = Modifier.padding(top = 4.dp)) }
          card.words.forEach { (title, lines) ->
            if (title.isNotEmpty()) Text(title, style = scaled(13, FontWeight.SemiBold, Theme.ink))
            lines.forEach { Text(it, style = scaled(13, color = Theme.soft)) }
          }
        }
        bot.tafs.app.design.PrimaryButton(
          if (giving) Words.Coach.Tools.giving else said(step?.give, Words.Coach.Tools.give),
          {
            giving = true
            scope.launch {
              if (actions.giveTools()) onClose()
              giving = false
            }
          },
          enabled = !giving, modifier = Modifier.confirms()
        )
      }
    }
    state.coachToolsError?.let { Text(it, style = scaled(13, color = Theme.no)) }
    bot.tafs.app.design.OutlineButton(said(step?.notNow, Words.Common.notNow), { actions.notNowTools(); onClose() }, Modifier.fillMaxWidth(), enabled = !giving)
  }
  }
}
