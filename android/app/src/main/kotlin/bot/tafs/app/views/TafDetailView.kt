package bot.tafs.app.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.R
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Segmented
import bot.tafs.app.design.StateChip
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.Answer
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.MemoryNote
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafState
import bot.tafs.app.model.shortTime

/** Chat first: talking to a taf is the main thing. */
private enum class Section(val title: String) { Chat("Chat"), Activity("Activity"), Card("Card"), Doors("Doors") }

/** What the screen asks of the model: its changes, so the screen holds no state of the app's. */
class TafActions(
  val answer: suspend (HeldCall, Answer) -> Unit,
  val togglePause: (String) -> Unit,
  val revoke: (String) -> Unit,
  val loadMemory: (String) -> Unit,
  val forget: (MemoryNote, String) -> Unit,
  val loadChat: suspend (String) -> Unit,
  val sendChat: (String, String, (String) -> Unit) -> Unit,
  val issuer: String,
  val doors: DoorActions,
  /** What Coach's cards ask of the model: answers, and approvals. */
  val coach: CoachActions,
  /** The taf's connections, read from planque, and ending one. */
  val connections: ConnectionActions,
  /** Stops what the taf is doing in its chat. */
  val stopChat: suspend (String) -> Unit = {},
)

@Composable
fun TafDetailView(
  tafId: String,
  state: AppState,
  actions: TafActions,
  onBack: () -> Unit,
  onHalt: (String) -> Unit,
  onUsage: (String) -> Unit,
  onVersions: (String) -> Unit,
  people: PeopleActions? = null,
  modifier: Modifier = Modifier,
) {
  val taf = state.taf(tafId)
  Column(modifier.fillMaxSize().statusBarsPadding()) {
    BackBar(onBack)
    if (taf == null) {
      Text("This taf is gone.", style = scaled(15, color = Theme.dim), modifier = Modifier.padding(20.dp))
      return@Column
    }
    val canChat = state.canChat(taf.id)
    val hasDoors = state.hasDoors(taf.id)
    val sections = Section.entries.filter { (it != Section.Chat || canChat) && (it != Section.Doors || hasDoors) }
    var section by rememberSaveable { mutableStateOf(sections.first()) }
    var frontCard by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }
    val cards = taf.shownCards

    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Header(taf)
      // Who it's shared with, and Share: the taf's manager's to see.
      if (people != null && taf.cloudId != null) ShareLine(taf.id, state, people)
      Segmented(sections, section, { it.title }) { section = it }
      // The Cards are one line above the chat and the activity; it opens the Card tab (kiff/tafs#211).
      if (section != Section.Card) {
        (cards.firstOrNull { it.id == frontCard } ?: cards.firstOrNull())?.let {
          CardBar(it, cards.size, Theme.tafColor(taf.colorIndex), taf.card.revoked) { section = Section.Card }
        }
      }
    }

    if (section == Section.Chat) {
      // The thread scrolls on its own, with the field under it.
      TafChatView(taf.id, state, actions, Modifier.weight(1f))
    } else {
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        when (section) {
          Section.Activity -> Activity(taf, state, actions)
          Section.Card -> {
            CardTab(taf, actions, frontCard, { frontCard = it }, controls = state.mode == Mode.sample, onRevoke = { confirmRevoke = true })
            // Which tools it is connected to, and whose sign-ins: read from planque, for the people who manage it.
            if (state.canHalt(taf.id) && state.manages(taf.id)) ConnectionsPanel(taf, state, actions.connections)
            // Halting is a job of its own: its row opens its screen. Its usage is the account's Usage, with this taf's count.
            // Only what this person manages, as KIFF says: a Viewer sees no Halt or Versions, each refused only once tapped.
            if (state.canHalt(taf.id) && state.manages(taf.id) && taf.state != TafState.revoked) HaltRow(taf.id, state.haltOf(taf.id), { onHalt(taf.id) })
            if (state.mode == Mode.sample || state.canHalt(taf.id)) UsageRow(state.tafUsage(taf.id)?.counted) { onUsage(taf.id) }
            // Its versions are a job of their own: their own screen, not another section here.
            if (state.canHalt(taf.id) && state.manages(taf.id)) VersionsRow { onVersions(taf.id) }
          }
          Section.Doors -> DoorsTab(taf, state, actions.doors, state.kiffAccount?.email)
          Section.Chat -> Unit
        }
      }
    }

    if (confirmRevoke) {
      AlertDialog(
        onDismissRequest = { confirmRevoke = false },
        title = { Text("Revoke ${taf.name}'s Card?") },
        text = { Text("No new action goes through. Anything already done stays done.") },
        confirmButton = {
          TextButton({ confirmRevoke = false; actions.revoke(taf.id) }) { Text("Revoke and stop", color = Theme.no) }
        },
        dismissButton = { TextButton({ confirmRevoke = false }) { Text("Cancel", color = Theme.soft) } },
        containerColor = Theme.panel,
      )
    }
  }
}

/** The way back to the list: the screens inside a tab keep the tab bar, as a navigation stack does. */
@Composable
internal fun BackBar(onBack: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onBack).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null, tint = Theme.amber, modifier = Modifier.size(20.dp))
    Text("Back", style = scaled(17, color = Theme.amber))
  }
}

@Composable
private fun Header(taf: Taf) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    TafAvatar(taf, 52.dp)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(taf.name, style = scaled(30, FontWeight.Bold, Theme.ink, display = true))
      Text(if (taf.tools.isEmpty()) "No tools yet" else taf.tools.joinToString(" · "), style = scaled(13, color = Theme.dim))
    }
    StateChip(taf.state)
  }
}

@Composable
private fun Activity(taf: Taf, state: AppState, actions: TafActions) {
  Panel {
    Eyebrow("The job")
    Text(taf.job, style = scaled(15, color = Theme.ink))
  }
  // How it does the job, beside the job: not part of its Card (kiff/tafs#96).
  // What it remembers is read from tafs cloud once signed in; until then only the sample has it.
  if (state.mode == Mode.sample) MemoryPanel(taf.id, state.memories[taf.id], actions)
  state.heldCalls(taf.id).forEach { HeldCallCard(it, state, actions.answer) }
  val entries = state.entries(taf.id)
  if (entries.isEmpty()) Text("Nothing yet.", style = scaled(15, color = Theme.dim))
  entries.forEach { RecordRow(it, state, showTaf = false) }
}

@Composable
private fun CardTab(taf: Taf, actions: TafActions, front: String?, onFront: (String) -> Unit, controls: Boolean, onRevoke: () -> Unit) {
  val cards = taf.shownCards
  val shown = cards.firstOrNull { it.id == front } ?: cards.firstOrNull()
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    CardStack(cards, actions.issuer, Theme.tafColor(taf.colorIndex), front, onFront, revoked = taf.card.revoked)
  }
  // All of the front Card's limits: the face shows the main one.
  CardLines(shown?.rules ?: emptyList())
  // Pausing and revoking are the sample's until KIFF's own routes are used: revoking one of the owner's real Cards is not faked.
  if (controls && taf.state != TafState.revoked) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlineButton(if (taf.state == TafState.paused) "Resume" else "Pause", { actions.togglePause(taf.id) }, Modifier.weight(1f))
      OutlineButton("Revoke", onRevoke, Modifier.weight(1f), color = Theme.no)
    }
  }
}

/** What the taf has written down about how the owner likes the job done. */
@Composable
private fun MemoryPanel(tafId: String, notes: List<MemoryNote>?, actions: TafActions) {
  LaunchedEffect(tafId) { actions.loadMemory(tafId) }
  // The note waiting for the owner's yes: forgetting can't be undone (the 755 retest).
  var forgetting by remember { mutableStateOf<MemoryNote?>(null) }
  Panel {
    Eyebrow("What it remembers")
    if (notes != null) {
      if (notes.isEmpty()) {
        Text("Nothing yet. It writes down what it learns about how you like the job done.", style = scaled(14, color = Theme.soft))
      }
      notes.forEach { note ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(note.content, style = scaled(15, color = Theme.ink))
            Text("${note.title} · ${shortTime(note.updatedAt)}", style = scaled(12, color = Theme.soft))
          }
          Text(
            "Forget",
            style = scaled(14, FontWeight.SemiBold, Theme.soft),
            modifier = Modifier.heightIn(min = 44.dp).clickable { forgetting = note },
          )
        }
      }
      Text(
        "Every run and chat starts with these. They shape how it works, never what its Card allows. Forget removes that one note and its earlier versions; past chats and run reports stay until the taf is removed.",
        style = scaled(12, color = Theme.soft),
      )
    }
  }
  forgetting?.let { note ->
    AlertDialog(
      onDismissRequest = { forgetting = null },
      title = { Text("Forget this note?") },
      text = { Text("“${note.content}”\n\nIt can't be undone: the taf stops working with it from its next run or chat. Other notes stay.") },
      confirmButton = { TextButton({ forgetting = null; actions.forget(note, tafId) }) { Text("Forget", color = Theme.no) } },
      dismissButton = { TextButton({ forgetting = null }) { Text("Cancel", color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}
