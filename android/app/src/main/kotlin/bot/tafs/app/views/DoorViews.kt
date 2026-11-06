package bot.tafs.app.views

import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.DriveAlert
import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.TafDoors
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.Meter
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.Door
import bot.tafs.app.kiff.DoorApp
import bot.tafs.app.kiff.DoorKey
import bot.tafs.app.kiff.Knock
import bot.tafs.app.model.DoorMark
import bot.tafs.app.model.DoorNames
import bot.tafs.app.model.DoorWords
import bot.tafs.app.model.SetupWords
import bot.tafs.app.model.Taf
import kotlinx.coroutines.launch

/** What the Doors screens and the chat's knocks ask of the model. */
class DoorActions(
  val load: suspend (String) -> Unit,
  val add: suspend (String, DoorApp) -> Door?,
  val setOpen: suspend (String, Door, Boolean) -> Unit,
  val remove: suspend (String, Door) -> Unit,
  val pair: suspend (String, Door, String, String) -> Unit,
  val unpair: suspend (String, Door, DoorKey) -> Unit,
  val answer: suspend (String, Knock, String, String?) -> Unit,
  val dismissError: (String) -> Unit,
  /** A Slack door: the channels a Slack bot connection sees (grant id), and adding the door on one (taf, app, grant, channel). */
  val loadSlackChannels: suspend (String) -> Unit = {},
  val addSlack: suspend (String, DoorApp, bot.tafs.app.kiff.Grant, bot.tafs.app.kiff.SlackChannel) -> Door? = { _, _, _, _ -> null },
  /** A bot door, such as Telegram's, on one of the taf's bot connections (taf, app, grant). */
  val addBot: suspend (String, DoorApp, bot.tafs.app.kiff.Grant) -> Door? = { _, _, _ -> null },
  /** The taf's dial, read again; and Run now, from its face. */
  val loadKnob: suspend (String) -> Unit = {},
  val runNow: suspend (String) -> Unit = {},
  /** Approves a held call once, from the dial's face. */
  val approveOnce: suspend (bot.tafs.app.model.HeldCall) -> Unit = {},
  /** The remote's − and +: a stop's next choice, as its whole setting (taf, setting); making the changes live; Pause. */
  val changeKnobSize: suspend (String, kotlinx.serialization.json.JsonObject) -> Unit = { _, _ -> },
  val confirmKnobChange: suspend (String) -> Unit = {},
  val setPaused: suspend (String, Boolean) -> Unit = { _, _ -> },
  /** "Ask Coach or find anything", "Add a door" and "Ask Coach to change": Coach, about this taf. */
  val askCoach: (String) -> Unit = {},
  /** An app's event on the dial approved (taf, trigger): its filters subscribed at planque, then recorded by tafs cloud. */
  val approveEvent: suspend (String, String) -> Boolean = { _, _ -> false },
  /** Removes a follow-up the taf set itself (taf, follow-up id). */
  val removeFollowUp: suspend (String, String) -> Unit = { _, _ -> },
  /** Opens the request the dial's rim points at. */
  val openRimRequest: suspend (String) -> Unit = {},
)

/**
 * A taf's doors (docs/doors.md), in Setup › Starts when: each door, open or closed, with Edit for its own screen (its address,
 * who may give it work, its day), then Add a door….
 */
@Composable
fun DoorsList(taf: Taf, state: AppState, actions: DoorActions, myEmail: String?) {
  val doors = state.doors[taf.id] ?: TafDoors()
  val scope = rememberCoroutineScope()
  var adding by rememberSaveable { mutableStateOf(false) }
  var editing by rememberSaveable { mutableStateOf<String?>(null) }
  var pairing by remember { mutableStateOf<Door?>(null) }
  var removing by remember { mutableStateOf<Door?>(null) }
  LaunchedEffect(taf.id) { actions.load(taf.id) }

  doors.doors.forEach { door ->
    Row(
      Modifier.fillMaxWidth().heightIn(min = 48.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      DoorBadge(door.app, door.open, 28)
      Column(Modifier.weight(1f)) {
        Text(door.label, style = scaled(15, FontWeight.SemiBold, Theme.ink))
        Text(door.words?.state ?: DoorWords.status(door), style = scaled(13, color = Theme.soft))
      }
      Text(
        SetupWords.EDIT,
        style = scaled(14, FontWeight.SemiBold, Theme.amberText),
        modifier = Modifier.heightIn(min = 44.dp).clickable { editing = door.id }.padding(horizontal = 6.dp, vertical = 12.dp),
      )
    }
  }
  if (doors.loaded && doors.addable.isNotEmpty()) {
    androidx.compose.material3.TextButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
      Text("+", style = scaled(22, FontWeight.SemiBold, Theme.amberText))
      Text(SetupWords.ADD_DOOR, style = scaled(15, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.padding(start = 8.dp))
    }
  }
  doors.error?.let { Text(it, style = scaled(13, color = Theme.no)) }

  editing?.let { id -> doors.doors.firstOrNull { it.id == id } }?.let { door ->
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    DriveSheet(
      onDismissRequest = { editing = null },
      sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
      containerColor = Theme.background,
    ) {
      Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        DoorPanels(taf, door, doors, myEmail, actions, onPair = { pairing = door }, onRemove = { removing = door })
        Text(DoorWords.footer(taf.name), style = scaled(13, color = Theme.dim))
      }
    }
  }
  if (adding) AddDoorSheet(
    doors,
    slack = SlackPicking(state.slackBots(taf.id), state.slackChannels, actions.loadSlackChannels) { app, grant, channel ->
      scope.launch { if (actions.addSlack(taf.id, app, grant, channel) != null) adding = false }
    },
    bots = BotPicking({ provider -> state.bots(taf.id, provider) }) { app, grant ->
      scope.launch { if (actions.addBot(taf.id, app, grant) != null) adding = false }
    },
    onClose = { adding = false },
  ) { app ->
    scope.launch { if (actions.add(taf.id, app) != null) adding = false }
  }
  pairing?.let { d ->
    PairDialog(d, doors.busy, onClose = { pairing = null }) { sender, name ->
      scope.launch {
        actions.pair(taf.id, d, sender, name)
        if (state.doors[taf.id]?.error == null) pairing = null
      }
    }
  }
  removing?.let { d ->
    DriveAlert(
      onDismissRequest = { removing = null },
      title = { Text(DoorWords.removeTitle(d)) },
      text = { Text(DoorWords.removeBody(taf.name)) },
      confirmButton = {
        TextButton({
          removing = null
          editing = null
          scope.launch { actions.remove(taf.id, d) }
        }) { Text("Remove", color = Theme.no) }
      },
      dismissButton = { TextButton({ removing = null }) { Text("Cancel", color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}

/**
 * What waits for the owner at the taf's doors, on its Activity: strangers' knocks (Do it, Pair them, Ignore), then a paired
 * sender's message kept from its knock.
 */
@Composable
fun KnocksPanel(taf: Taf, state: AppState, actions: DoorActions) {
  val doors = state.doors[taf.id] ?: return
  val scope = rememberCoroutineScope()
  val (paired, waiting) = doors.openKnocks.filter { !it.isLine }.partition { it.paired }
  if (waiting.isNotEmpty()) {
    Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
      Eyebrow("Knocks · waiting on you", Theme.amber)
      waiting.forEach { KnockCard(it, taf.name, doors.busy, compact = true) { action, say -> scope.launch { actions.answer(taf.id, it, action, say) } } }
    }
  }
  if (paired.isNotEmpty()) {
    Panel {
      Eyebrow("Paired")
      paired.forEach { KnockCard(it, taf.name, doors.busy, compact = true) { action, say -> scope.launch { actions.answer(taf.id, it, action, say) } } }
    }
  }
}

@Composable
private fun DoorPanels(taf: Taf, door: Door, doors: TafDoors, myEmail: String?, actions: DoorActions, onPair: () -> Unit, onRemove: () -> Unit) {
  val scope = rememberCoroutineScope()
  val clipboard = LocalClipboard.current
  var copied by remember { mutableStateOf(false) }
  Panel {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      DoorBadge(door.app, door.open, 34)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow("${door.label} door")
        Text(door.words?.state ?: DoorWords.status(door), style = scaled(13, FontWeight.Medium, Theme.soft))
      }
      Switch(
        checked = door.open,
        onCheckedChange = { on -> scope.launch { actions.setOpen(taf.id, door, on) } },
        enabled = !doors.busy,
        colors = SwitchDefaults.colors(checkedTrackColor = Theme.ok),
      )
    }
    Text(door.address, style = scaled(15, FontWeight.Medium, Theme.ink, mono = true))
    if (door.kind == "email") {
      OutlineButton(
        if (copied) "Copied" else "Copy address",
        {
          scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("address", door.address)))
            copied = true
            kotlinx.coroutines.delay(1_400)
            copied = false
          }
        },
        Modifier.fillMaxWidth(),
      )
    }
    Meter(door.today.toDouble() / maxOf(door.limit, 1), if (door.resting) Theme.dim else Theme.tafColor(taf.colorIndex))
    Text(DoorWords.dayLine(door), style = scaled(12, color = Theme.dim))
    DoorWords.othersLine(door)?.let { Text(it, style = scaled(12, color = Theme.dim)) }
  }
  Panel {
    Eyebrow(DoorWords.keysTitle(door))
    if (door.holdsKeys) {
      door.keys.forEach { key ->
        val (name, address) = DoorWords.keyName(key, myEmail)
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = scaled(15, FontWeight.SemiBold, Theme.ink))
            address?.let { Text(it, style = scaled(12, color = Theme.dim, mono = true)) }
          }
          if (!key.auto) {
            Text(
              "Unpair",
              style = scaled(13, FontWeight.Medium, Theme.soft),
              modifier = Modifier.heightIn(min = 44.dp).clickable { scope.launch { actions.unpair(taf.id, door, key) } }.padding(start = 8.dp, top = 8.dp),
            )
          }
        }
      }
      Text(DoorWords.keysFoot(door), style = scaled(12, color = Theme.dim))
      door.words?.pairFromKnock?.let { Text(it, style = scaled(13, color = Theme.soft)) }
      OutlineButton("Pair someone", onPair, Modifier.fillMaxWidth())
    } else {
      Text(DoorWords.noKeys(door, taf.name), style = scaled(15, color = Theme.ink))
    }
    Text("Remove door", style = scaled(14, FontWeight.Medium, Theme.no), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onRemove).padding(top = 8.dp))
  }
}

/** A door's app, as a small round badge: filled while open. */
@Composable
fun DoorBadge(app: String, open: Boolean, size: Int = 24) {
  val fill = if (app == "+") Color.Transparent else if (open) doorColor(app) else Theme.panel
  Box(
    Modifier
      .size(size.dp)
      .clip(CircleShape)
      .background(fill)
      .border(BorderStroke(1.5.dp, if (open && app != "+") Color.Transparent else Theme.ink.copy(alpha = 0.25f)), CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      if (app == "+") "+" else DoorNames.mark(app),
      style = scaled((size * 0.46f).toInt(), FontWeight.ExtraBold, if (open && app != "+") Color.White else Theme.dim, display = true),
    )
  }
}

private fun doorColor(app: String): Color = when (app) {
  "email" -> Theme.blue
  "whatsapp" -> Theme.ok
  // The same colour on every launch: from the name's letters, not its hash.
  else -> Theme.tafColor(app.sumOf { it.code })
}

/** Picks the app a new door opens on: those that can tell who's writing first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDoorSheet(doors: TafDoors, slack: SlackPicking, bots: BotPicking, onClose: () -> Unit, onPick: (DoorApp) -> Unit) {
  // Slack has a step of its own: the taf's Slack bot connection, then a channel it sees. A bot door's (tafs cloud says which) is the bot alone.
  var slackApp by remember { mutableStateOf<DoorApp?>(null) }
  var botApp by remember { mutableStateOf<DoorApp?>(null) }
  DriveSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.6f).navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Add a door", style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 8.dp))
      }
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // WhatsApp's signup runs in a browser this app doesn't open yet.
        val offered = doors.addable.filter { it.kind != "whatsapp" }
        val pick: (DoorApp) -> Unit = { app ->
          when {
            app.bot != null -> { botApp = app; slackApp = null }
            app.kind == "slack" -> { slackApp = app; botApp = null }
            else -> onPick(app)
          }
        }
        slackApp?.let { app -> SlackDoorStep(app, slack, doors.busy) }
        botApp?.let { app -> BotDoorStep(app, bots, doors.busy) }
        AppSection("Knows who's writing · can hold keys", offered.filter { it.proof == "proven" }, doors.busy, pick)
        AppSection("Can't prove who's writing · knocks only", offered.filter { it.proof != "proven" }, doors.busy, pick)
        if (offered.isEmpty()) Text("No more doors can be added here.", style = scaled(13, color = Theme.dim))
        if (doors.addable.any { it.kind == "whatsapp" }) Text(DoorWords.WHATSAPP_ELSEWHERE, style = scaled(12, color = Theme.dim))
        doors.error?.let { Text(it, style = scaled(13, color = Theme.no)) }
        Text("Replies go only to who wrote. Anything else the taf does still goes through KIFF.", style = scaled(12, color = Theme.dim), modifier = Modifier.padding(top = 6.dp))
      }
    }
  }
}

@Composable
private fun AppSection(title: String, apps: List<DoorApp>, busy: Boolean, onPick: (DoorApp) -> Unit) {
  if (apps.isEmpty()) return
  Eyebrow(title)
  apps.forEach { app ->
    val shape = RoundedCornerShape(14.dp)
    Row(
      Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(Theme.panel)
        .border(BorderStroke(1.dp, Theme.line), shape)
        .clickable(enabled = !busy) { onPick(app) }
        .padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      DoorBadge(app.id, true, 32)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(app.label, style = scaled(15, FontWeight.SemiBold, Theme.ink))
        Text(app.how, style = scaled(12, color = Theme.dim))
      }
    }
  }
}

/** Pairs a sender with a door: they can give the taf work from now on. */
@Composable
private fun PairDialog(door: Door, busy: Boolean, onClose: () -> Unit, onPair: (String, String) -> Unit) {
  var sender by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  DriveAlert(
    onDismissRequest = onClose,
    title = { Text("Pair someone") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(DoorWords.pairIntro(door), style = scaled(14, color = Theme.soft))
        Field(sender, { sender = it }, DoorWords.pairHint(door))
        Field(name, { name = it }, "Name (optional)")
      }
    },
    confirmButton = { TextButton({ onPair(sender, name) }, enabled = sender.isNotBlank() && !busy, modifier = Modifier.confirms()) { Text("Pair", color = Theme.amber) } },
    dismissButton = { TextButton(onClose) { Text("Cancel", color = Theme.soft) } },
    containerColor = Theme.panel,
  )
}

/** A text field on a panel, with its hint while empty: the doors' dialogs and the group name's. */
@Composable
internal fun Field(value: String, onValue: (String) -> Unit, hint: String, minHeight: Int = 20) {
  val shape = RoundedCornerShape(12.dp)
  BasicTextField(
    value = value,
    onValueChange = driveTyped(onValue),
    textStyle = scaled(15, color = Theme.ink),
    cursorBrush = SolidColor(Theme.amber),
    modifier = Modifier.fillMaxWidth().clip(shape).background(Theme.background).border(BorderStroke(1.dp, Theme.line), shape),
    decorationBox = { field ->
      Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).heightIn(min = minHeight.dp)) {
        if (value.isEmpty()) Text(hint, style = scaled(15, color = Theme.dim))
        field()
      }
    },
  )
}

/** Someone without a key wrote: who, what they want, and the owner's three answers. */
@Composable
fun KnockCard(knock: Knock, tafName: String?, busy: Boolean, compact: Boolean = false, onAnswer: (String, String?) -> Unit) {
  var asking by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(16.dp)
  // A paired sender's message waits too, but it isn't a stranger at the door: it reads calmly, not in amber.
  val tone = if (knock.paired) Theme.ink.copy(alpha = 0.2f) else Theme.amber.copy(alpha = 0.45f)
  Column(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(if (knock.paired) Theme.panel else Theme.amber.copy(alpha = 0.07f))
      .border(BorderStroke(1.dp, tone), shape)
      .padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(DoorWords.knockTitle(knock), style = scaled(10, FontWeight.SemiBold, if (knock.paired) Theme.soft else Theme.amberText, mono = true))
    if (knock.paired) Text(DoorWords.PAIRED_LINE, style = scaled(12, color = Theme.dim))
    if (knock.rest != null) {
      Text(DoorWords.restLine(knock), style = scaled(14, color = Theme.ink))
      OutlineButton("OK", { onAnswer("ignore", null) }, Modifier.fillMaxWidth(), enabled = !busy)
    } else {
      Text(DoorWords.wrote(knock), style = scaled(14, FontWeight.SemiBold, Theme.ink))
      Text(knock.text, style = scaled(14, color = Theme.soft), maxLines = if (compact) 3 else 8, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
      DoorWords.attachments(knock)?.let { Text(it, style = scaled(12, color = Theme.dim)) }
      DoorWords.proofNote(knock)?.let { Text(it, style = scaled(12, color = Theme.dim)) }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (DoorWords.canDoIt(knock)) {
          Text(
            "Do it",
            style = scaled(14, FontWeight.SemiBold, Theme.panel),
            modifier = Modifier.heightIn(min = 44.dp).clip(CircleShape).background(Theme.amberText).clickable(enabled = !busy) { asking = true }.padding(horizontal = 12.dp, vertical = 8.dp),
          )
        }
        DoorWords.pairLabel(knock)?.let { label ->
          Text(
            label,
            style = scaled(14, FontWeight.Medium, Theme.ink),
            modifier = Modifier.heightIn(min = 44.dp).clip(CircleShape).border(BorderStroke(1.dp, Theme.ink.copy(alpha = 0.2f)), CircleShape).clickable(enabled = !busy) { onAnswer("pair", null) }.padding(horizontal = 10.dp, vertical = 8.dp),
          )
        }
        Text(
          "Ignore",
          style = scaled(14, FontWeight.Medium, Theme.soft),
          modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = !busy) { onAnswer("ignore", null) }.padding(horizontal = 8.dp, vertical = 8.dp),
        )
      }
    }
  }
  if (asking) DoItDialog(knock, tafName, busy, onClose = { asking = false }) { say -> asking = false; onAnswer("do", say) }
}

/** "Do it": what the owner tells the taf to do about a knock. Their words are the instruction. */
@Composable
private fun DoItDialog(knock: Knock, tafName: String?, busy: Boolean, onClose: () -> Unit, onSend: (String) -> Unit) {
  var say by remember { mutableStateOf(DoorWords.DO_IT_DEFAULT) }
  DriveAlert(
    onDismissRequest = onClose,
    title = { Text(DoorWords.doItTitle(tafName)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(DoorWords.doItIntro(knock, tafName), style = scaled(14, color = Theme.soft))
        Field(say, { say = it }, "What should it do?", minHeight = 90)
      }
    },
    confirmButton = {
      TextButton({ onSend(say.trim()) }, enabled = say.isNotBlank() && !busy) { Text(if (busy) "Sending…" else "Send to ${tafName ?: "your taf"}", color = Theme.amber) }
    },
    dismissButton = { TextButton(onClose) { Text("Cancel", color = Theme.soft) } },
    containerColor = Theme.panel,
  )
}

/** One knock in a taf's chat, at its time among the messages (docs/doors.md): the owner answers it there. */
@Composable
fun ChatKnock(tafId: String, tafName: String?, knock: Knock, state: AppState, actions: DoorActions) {
  val scope = rememberCoroutineScope()
  val busy = state.doors[tafId]?.busy ?: false
  KnockCard(knock, tafName, busy) { action, say -> scope.launch { actions.answer(tafId, knock, action, say) } }
}

/** The line over a door's message, and under its answer: where it came from, and how it went back. */
@Composable
fun DoorStamp(mark: DoorMark, fromOwner: Boolean) {
  Text(
    DoorWords.stamp(mark, fromOwner),
    style = scaled(10, FontWeight.Medium, if (mark.reply == "not_sent") Theme.no else Theme.blue, mono = true),
    maxLines = 2,
    textAlign = if (fromOwner) TextAlign.End else TextAlign.Start,
    modifier = Modifier.fillMaxWidth(),
  )
}

/** What the Slack step needs: the taf's Slack bot connections, the channels read for each, reading them, and adding the door. */
class SlackPicking(
  val bots: List<bot.tafs.app.kiff.Grant>,
  val channels: Map<String, List<bot.tafs.app.kiff.SlackChannel>>,
  val load: suspend (String) -> Unit,
  val add: (DoorApp, bot.tafs.app.kiff.Grant, bot.tafs.app.kiff.SlackChannel) -> Unit,
)

/**
 * The Slack door's step: the taf's Slack bot connection (one is picked when there's one), then a channel it sees. Picking a
 * channel asks once, then planque's subscription and the door are made.
 */
@Composable
private fun SlackDoorStep(app: DoorApp, slack: SlackPicking, busy: Boolean) {
  var bot by remember { mutableStateOf(slack.bots.singleOrNull()) }
  Panel {
    Eyebrow("Slack door")
    if (slack.bots.isEmpty()) {
      Text(DoorWords.SLACK_CONNECT_FIRST, style = scaled(14, color = Theme.ink))
      return@Panel
    }
    if (bot == null) {
      Text(DoorWords.SLACK_WHICH_BOT, style = scaled(14, color = Theme.ink))
      slack.bots.forEach { grant ->
        Text(
          grant.account ?: grant.vendor,
          style = scaled(15, FontWeight.SemiBold, Theme.amber),
          modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { bot = grant }.padding(top = 10.dp),
        )
      }
      return@Panel
    }
    val grant = bot!!
    LaunchedEffect(grant.id) { slack.load(grant.id) }
    val channels = slack.channels[grant.id]
    Text(DoorWords.SLACK_WHICH_CHANNEL, style = scaled(14, color = Theme.ink))
    when {
      channels == null -> Text("Reading its channels…", style = scaled(13, color = Theme.soft))
      channels.isEmpty() -> Text(DoorWords.SLACK_NO_CHANNELS, style = scaled(13, color = Theme.soft))
      else -> channels.forEach { channel ->
        Text(
          (if (channel.private) "🔒 " else "#") + channel.name.ifBlank { channel.id },
          style = scaled(15, FontWeight.SemiBold, if (busy) Theme.dim else Theme.amber),
          modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = !busy) { slack.add(app, grant, channel) }.padding(top = 10.dp),
        )
      }
    }
  }
}

/** What a bot door's step needs: the taf's connections from a provider, and adding the door on one. */
class BotPicking(
  val bots: (String) -> List<bot.tafs.app.kiff.Grant>,
  val add: (DoorApp, bot.tafs.app.kiff.Grant) -> Unit,
)

/**
 * A bot door's step, such as Telegram's: the Slack step without a channel. tafs cloud names the provider and every word; each bot
 * is one door, a tap adds it, and pairing comes from knocks.
 */
@Composable
private fun BotDoorStep(app: DoorApp, picking: BotPicking, busy: Boolean) {
  val door = app.bot ?: return
  val bots = picking.bots(door.provider)
  Panel {
    Eyebrow("${app.label} door")
    if (bots.isEmpty()) {
      Text(door.words.none, style = scaled(14, color = Theme.ink))
      return@Panel
    }
    Text(door.words.pick, style = scaled(14, color = Theme.ink))
    bots.forEach { grant ->
      Text(
        grant.botName ?: grant.account ?: grant.vendor,
        style = scaled(15, FontWeight.SemiBold, if (busy) Theme.dim else Theme.amber),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = !busy) { picking.add(app, grant) }.padding(top = 10.dp),
      )
    }
  }
}
