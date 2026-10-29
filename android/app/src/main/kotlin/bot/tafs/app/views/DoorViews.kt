package bot.tafs.app.views

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
)

/**
 * A taf's Doors tab (docs/doors.md): where work reaches it from outside the app. One chip per door and a last one to add
 * one; below, the door picked: its address, who may give it work, and its day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoorsTab(taf: Taf, state: AppState, actions: DoorActions, myEmail: String?) {
  val doors = state.doors[taf.id] ?: TafDoors()
  val scope = rememberCoroutineScope()
  var selected by rememberSaveable { mutableStateOf(0) }
  var adding by rememberSaveable { mutableStateOf(false) }
  var pairing by remember { mutableStateOf<Door?>(null) }
  var removing by remember { mutableStateOf<Door?>(null) }
  LaunchedEffect(taf.id) { actions.load(taf.id) }
  val door = doors.doors.getOrNull(selected)

  Panel {
    Eyebrow("Doors")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      doors.doors.forEachIndexed { i, d ->
        val knocking = doors.openKnocks.any { it.door == d.id && !it.isLine }
        DoorChip(d.app, DoorNames.label(d.app).replaceFirstChar { it.uppercase() }, d.open, knocking, selected == i) { selected = i }
      }
      DoorChip("+", "Add", open = false, knocking = false, selected = door == null) { adding = true }
    }
  }
  if (door != null) DoorPanels(taf, door, doors, myEmail, actions, onPair = { pairing = door }, onRemove = { removing = door })
  else {
    Panel {
      Eyebrow("Add a door")
      Text(DoorWords.addIntro(taf.name), style = scaled(15, color = Theme.ink))
      PrimaryButton("Add a door", { adding = true }, enabled = doors.loaded && doors.addable.isNotEmpty())
      if (doors.loaded && doors.addable.isEmpty()) Text("No more doors can be added here.", style = scaled(13, color = Theme.dim))
    }
  }
  val waiting = doors.openKnocks.filter { !it.isLine }
  if (waiting.isNotEmpty()) {
    Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
      Eyebrow("Knocks · waiting on you", Theme.amber)
      waiting.forEach { KnockCard(it, taf.name, doors.busy, compact = true) { action, say -> scope.launch { actions.answer(taf.id, it, action, say) } } }
    }
  }
  doors.error?.let { Text(it, style = scaled(13, color = Theme.no)) }
  Text(DoorWords.footer(taf.name), style = scaled(13, color = Theme.dim))

  if (adding) AddDoorSheet(doors, onClose = { adding = false }) { app ->
    scope.launch {
      val added = actions.add(taf.id, app)
      if (added != null) {
        adding = false
        selected = (state.doors[taf.id]?.doors?.size ?: 1) - 1
      }
    }
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
    AlertDialog(
      onDismissRequest = { removing = null },
      title = { Text(DoorWords.removeTitle(d)) },
      text = { Text(DoorWords.removeBody(taf.name)) },
      confirmButton = {
        TextButton({
          removing = null
          scope.launch { actions.remove(taf.id, d); selected = minOf(selected, (state.doors[taf.id]?.doors?.size ?: 1) - 1).coerceAtLeast(0) }
        }) { Text("Remove", color = Theme.no) }
      },
      dismissButton = { TextButton({ removing = null }) { Text("Cancel", color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}

@Composable
private fun DoorPanels(taf: Taf, door: Door, doors: TafDoors, myEmail: String?, actions: DoorActions, onPair: () -> Unit, onRemove: () -> Unit) {
  val scope = rememberCoroutineScope()
  val clipboard = LocalClipboard.current
  var copied by remember { mutableStateOf(false) }
  Panel {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      DoorBadge(door.app, door.open, 34)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow("${door.label} door")
        Text(DoorWords.status(door), style = scaled(13, FontWeight.Medium, Theme.soft))
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
              modifier = Modifier.heightIn(min = 44.dp).clickable { scope.launch { actions.unpair(taf.id, door, key) } }.padding(start = 12.dp, top = 12.dp),
            )
          }
        }
      }
      Text(DoorWords.keysFoot(door), style = scaled(12, color = Theme.dim))
      OutlineButton("Pair someone", onPair, Modifier.fillMaxWidth())
    } else {
      Text(DoorWords.noKeys(door, taf.name), style = scaled(15, color = Theme.ink))
    }
    Text("Remove door", style = scaled(14, FontWeight.Medium, Theme.no), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onRemove).padding(top = 12.dp))
  }
}

/** One stop: the door's badge and name, lit while open, with a dot while a knock waits. */
@Composable
private fun DoorChip(app: String, label: String, open: Boolean, knocking: Boolean, selected: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(50)
  Row(
    Modifier
      .heightIn(min = 44.dp)
      .clip(shape)
      .background(if (selected) Theme.ink.copy(alpha = 0.08f) else Color.Transparent)
      .border(BorderStroke(1.dp, if (selected) Theme.ink.copy(alpha = 0.4f) else Theme.line), shape)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box {
      DoorBadge(app, open, 24)
      if (knocking) Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(CircleShape).background(Theme.amber))
    }
    Text(label, style = scaled(14, if (selected) FontWeight.SemiBold else FontWeight.Normal, if (selected) Theme.ink else Theme.soft))
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
private fun AddDoorSheet(doors: TafDoors, onClose: () -> Unit, onPick: (DoorApp) -> Unit) {
  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.6f).navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Add a door", style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 10.dp))
      }
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // WhatsApp's signup runs in a browser this app doesn't open yet.
        val offered = doors.addable.filter { it.kind != "whatsapp" }
        AppSection("Knows who's writing · can hold keys", offered.filter { it.proof == "proven" }, doors.busy, onPick)
        AppSection("Can't prove who's writing · knocks only", offered.filter { it.proof != "proven" }, doors.busy, onPick)
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
        .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
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
  AlertDialog(
    onDismissRequest = onClose,
    title = { Text("Pair someone") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(DoorWords.pairIntro(door), style = scaled(14, color = Theme.soft))
        Field(sender, { sender = it }, DoorWords.pairHint(door))
        Field(name, { name = it }, "Name (optional)")
      }
    },
    confirmButton = { TextButton({ onPair(sender, name) }, enabled = sender.isNotBlank() && !busy) { Text("Pair", color = Theme.amber) } },
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
    onValueChange = onValue,
    textStyle = scaled(15, color = Theme.ink),
    cursorBrush = SolidColor(Theme.amber),
    modifier = Modifier.fillMaxWidth().clip(shape).background(Theme.background).border(BorderStroke(1.dp, Theme.line), shape),
    decorationBox = { field ->
      Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).heightIn(min = minHeight.dp)) {
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
  Column(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(Theme.amber.copy(alpha = 0.07f))
      .border(BorderStroke(1.dp, Theme.amber.copy(alpha = 0.45f)), shape)
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(DoorWords.knockTitle(knock), style = scaled(10, FontWeight.SemiBold, Theme.amberText, mono = true))
    if (knock.rest != null) {
      Text(DoorWords.restLine(knock), style = scaled(14, color = Theme.ink))
      OutlineButton("OK", { onAnswer("ignore", null) }, Modifier.fillMaxWidth(), enabled = !busy)
    } else {
      Text(DoorWords.wrote(knock), style = scaled(14, FontWeight.SemiBold, Theme.ink))
      Text(knock.text, style = scaled(14, color = Theme.soft), maxLines = if (compact) 3 else 8, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
      DoorWords.attachments(knock)?.let { Text(it, style = scaled(12, color = Theme.dim)) }
      DoorWords.proofNote(knock)?.let { Text(it, style = scaled(12, color = Theme.dim)) }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (DoorWords.canDoIt(knock)) {
          Text(
            "Do it",
            style = scaled(14, FontWeight.SemiBold, Color.White),
            modifier = Modifier.heightIn(min = 44.dp).clip(CircleShape).background(Theme.amberText).clickable(enabled = !busy) { asking = true }.padding(horizontal = 16.dp, vertical = 12.dp),
          )
        }
        DoorWords.pairLabel(knock)?.let { label ->
          Text(
            label,
            style = scaled(14, FontWeight.Medium, Theme.ink),
            modifier = Modifier.heightIn(min = 44.dp).clip(CircleShape).border(BorderStroke(1.dp, Theme.ink.copy(alpha = 0.2f)), CircleShape).clickable(enabled = !busy) { onAnswer("pair", null) }.padding(horizontal = 14.dp, vertical = 12.dp),
          )
        }
        Text(
          "Ignore",
          style = scaled(14, FontWeight.Medium, Theme.soft),
          modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = !busy) { onAnswer("ignore", null) }.padding(horizontal = 12.dp, vertical = 12.dp),
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
  AlertDialog(
    onDismissRequest = onClose,
    title = { Text(DoorWords.doItTitle(tafName)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

/** The knocks waiting in a taf's chat, read with the chat. */
@Composable
fun ChatKnocks(tafId: String, tafName: String?, state: AppState, actions: DoorActions) {
  val doors = state.doors[tafId] ?: return
  val scope = rememberCoroutineScope()
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    doors.openKnocks.forEach { knock ->
      KnockCard(knock, tafName, doors.busy) { action, say -> scope.launch { actions.answer(tafId, knock, action, say) } }
    }
    // A read that fails in the background says nothing in the chat: the Doors tab says it, if it keeps failing.
  }
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
