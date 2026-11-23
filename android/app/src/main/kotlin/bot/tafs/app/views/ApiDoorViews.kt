package bot.tafs.app.views

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.TafDoors
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.drive.DriveAlert
import bot.tafs.app.drive.confirms
import bot.tafs.app.kiff.Door
import bot.tafs.app.kiff.DoorKey
import bot.tafs.app.model.Taf
import bot.tafs.app.model.Words
import bot.tafs.app.model.shortTime
import bot.tafs.app.model.shownDate
import java.time.Instant
import kotlinx.coroutines.launch

/** What an `api` door's panel asks of the model (docs/doors.md): each change to its keys, and opening it again, signed. */
class ApiDoorActions(
  val newKey: suspend (String, Door, String, String) -> Unit = { _, _, _, _ -> },
  val revoke: suspend (String, Door, DoorKey, String) -> Unit = { _, _, _, _ -> },
  val reopen: suspend (String, Door, String) -> Unit = { _, _, _ -> },
  val pause: suspend (String, Door) -> Unit = { _, _ -> },
  val remove: suspend (String, Door, String) -> Unit = { _, _, _ -> },
  val keyShown: (String) -> Unit = {},
  /** Where the door's caller sends requests: its `address` on tafs cloud's own host ([bot.tafs.app.app.AppModel.apiEndpoint]). */
  val endpoint: (Door) -> String = { it.address },
)

/** The most keys an `api` door holds at once, so one can be replaced without stopping the other. */
internal const val API_KEYS_MAX = 2

internal const val API_DOOR_PANEL = "api.door.panel"
internal const val API_KEY_SHOWN = "api.key.shown"
internal const val API_INSTRUCTIONS = "api.instructions"
internal const val API_NEW_KEY = "api.new.key"

private fun instant(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** A key's line: when it was made, when it was last used (or not yet), and its last four. Never the key. */
internal fun apiKeyLine(key: DoorKey): String {
  val made = instant(key.madeAt)?.let(::shownDate) ?: ""
  val used = instant(key.lastUsedAt)?.let { shownDate(it) + " " + shortTime(it) }
  return if (used != null) Words.Door.Api.keyLine(made = made, used = used, last4 = key.last4.orEmpty())
  else Words.Door.Api.keyNeverUsed(made = made, last4 = key.last4.orEmpty())
}

/**
 * A door "From another application" (`api`), behind [bot.tafs.app.kiff.ApiDoorFeature]: today against its cap,
 * its last request in tafs cloud's words, its keys by name and last four, New key (named, signed, shown once), Revoke (asked
 * once more, signed), the boundary line, and Pause or Open (open is signed). Remove is the door's own, signed too.
 */
@Composable
internal fun ApiDoorPanels(taf: Taf, door: Door, doors: TafDoors, actions: ApiDoorActions, onRemove: () -> Unit) {
  val scope = rememberCoroutineScope()
  var naming by remember { mutableStateOf(false) }
  var revoking by remember { mutableStateOf<DoorKey?>(null) }
  var instructing by remember { mutableStateOf(false) }
  Panel(Modifier.testTag(API_DOOR_PANEL)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      DoorBadge(door.app, door.open, 34)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(Words.Door.named(door.label))
        Text(door.words?.state ?: (if (door.open) "" else Words.Door.Api.paused), style = scaled(13, FontWeight.Medium, Theme.soft))
      }
      Switch(
        checked = door.open,
        // Pausing is always safe; opening it again re-admits its caller, so the fingerprint signs it.
        onCheckedChange = { on -> scope.launch { if (on) actions.reopen(taf.id, door, Words.Door.Api.openReason(door.label)) else actions.pause(taf.id, door) } },
        enabled = !doors.busy,
        colors = SwitchDefaults.colors(checkedTrackColor = Theme.ok),
      )
    }
    Row(Modifier.fillMaxWidth()) {
      Text(Words.Door.Api.today, style = scaled(14, color = Theme.soft), modifier = Modifier.weight(1f))
      Text(Words.Door.Api.ofLimit(count = door.today, limit = bot.tafs.app.model.Res.number(door.limit)), style = scaled(14, FontWeight.SemiBold, Theme.ink))
    }
    // No "waiting" row yet: `door.api.waiting` is the cap's "At once", not a word for requests waiting (as iOS, which shows none).
    // tafs cloud's word for the last request's state, as given: the app never words a state itself.
    door.lastRequest?.let { last ->
      val words = last.words?.takeIf { it.isNotBlank() }
      val at = instant(last.at)
      if (words != null && at != null) Text(Words.Door.Api.lastRequest(time = shortTime(at), status = words), style = scaled(13, color = Theme.dim))
    }
  }
  Panel {
    Eyebrow(Words.Door.Api.keys)
    door.keys.forEach { key ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(key.name ?: key.id, style = scaled(15, FontWeight.SemiBold, Theme.ink))
          Text(apiKeyLine(key), style = scaled(12, color = Theme.dim))
        }
        Text(
          Words.Taf.revoke,
          style = scaled(13, FontWeight.Medium, Theme.no),
          modifier = Modifier.heightIn(min = 44.dp).confirms().tapping { revoking = key }.padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
        )
      }
    }
    if (door.keys.size < API_KEYS_MAX) OutlineButton(Words.Door.Api.newKey, { naming = true }, Modifier.fillMaxWidth().testTag(API_NEW_KEY))
    Text(Words.Door.Api.twoKeys, style = scaled(12, color = Theme.dim))
    Text(Words.Door.Api.boundary(taf.name), style = scaled(13, color = Theme.soft))
    Text(
      "${Words.Door.Api.instructions} \u203a", style = scaled(15, FontWeight.Medium, Theme.ink),
      modifier = Modifier.heightIn(min = 48.dp).tapping(onClickLabel = Words.Door.Api.instructions) { instructing = true }.padding(top = 12.dp).testTag(API_INSTRUCTIONS),
    )
    Text(Words.Door.removeDoor, style = scaled(14, FontWeight.Medium, Theme.no), modifier = Modifier.heightIn(min = 44.dp).confirms().tapping(onClick = onRemove).padding(top = 8.dp))
  }

  if (naming) {
    ApiNewKeyDialog(door, doors.busy, onDismiss = { naming = false }) { name ->
      naming = false
      scope.launch { actions.newKey(taf.id, door, name, Words.Door.Api.newKeyReason(door.label)) }
    }
  }
  revoking?.let { key ->
    DriveAlert(
      onDismissRequest = { revoking = null },
      title = { Text(Words.Door.Api.revokeTitle(key.name ?: key.id)) },
      text = { Text(Words.Door.Api.revokeBody) },
      confirmButton = {
        TextButton({
          revoking = null
          scope.launch { actions.revoke(taf.id, door, key, Words.Door.Api.revokeReason(key.name ?: key.id)) }
        }, Modifier.confirms()) { Text(Words.Taf.revoke, color = Theme.no) }
      },
      dismissButton = { TextButton({ revoking = null }) { Text(Words.Common.cancel, color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
  if (instructing) ApiInstructions(taf, actions.endpoint(door)) { instructing = false }
  doors.issued?.let { issued -> ApiKeyShownOnce(door, issued, actions.endpoint(door)) { actions.keyShown(taf.id) } }
}

/** An example request, as iOS's: the key comes from the caller's own secret, `$TAFS_API_KEY`, and is never written in it. */
internal fun apiExample(endpoint: String): String =
  "curl -X POST $endpoint \\\n  -H \"Authorization: Bearer \$TAFS_API_KEY\" \\\n  -H \"Idempotency-Key: <your request id>\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"input\": {}}'"

/** Code as written: monospaced, selectable, scrolled sideways rather than wrapped. */
@Composable
private fun CodeBlock(text: String) {
  Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.raised).horizontalScroll(rememberScrollState())) {
    SelectionContainer { Text(text, style = scaled(12, color = Theme.ink, mono = true), softWrap = false, modifier = Modifier.padding(12.dp)) }
  }
}

/** Where to send requests and an example, for whoever builds the caller: everything but the key. */
@Composable
private fun WhereAndExample(endpoint: String) {
  Eyebrow(Words.Door.Api.where)
  SelectionContainer { Text(endpoint, style = scaled(13, color = Theme.ink, mono = true)) }
  Eyebrow(Words.Door.Api.example)
  CodeBlock(apiExample(endpoint))
}

/** API instructions: everything a developer needs but the key, which only ever shows once, on the phone that made it. */
@Composable
internal fun ApiInstructions(taf: Taf, endpoint: String, onDone: () -> Unit) {
  DriveAlert(
    onDismissRequest = onDone,
    title = { Text(Words.Door.Api.instructions) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WhereAndExample(endpoint)
        Text(Words.Door.Api.keyNotHere, style = scaled(14, color = Theme.soft))
        Text(Words.Door.Api.boundary(taf.name), style = scaled(14, color = Theme.soft))
      }
    },
    confirmButton = { TextButton(onDone) { Text(Words.Common.done) } },
    containerColor = Theme.panel,
  )
}

/** The name a new key is asked for under, as iOS's: as typed, trimmed, else the door's own name; none while the door is busy. */
internal fun apiNewKeyName(typed: String, door: Door, busy: Boolean): String? = if (busy) null else typed.trim().ifEmpty { door.label }

/** New key's dialog: the key's name (where it's used), then the ask, which the fingerprint signs. */
@Composable
internal fun ApiNewKeyDialog(door: Door, busy: Boolean, start: String = "", onDismiss: () -> Unit, onAsk: (String) -> Unit) {
  var name by remember { mutableStateOf(start) }
  DriveAlert(
    onDismissRequest = onDismiss,
    title = { Text(Words.Door.Api.newKey) },
    text = {
      OutlinedTextField(
        name, bot.tafs.app.drive.driveTyped({ name = it.take(60) }), singleLine = true,
        label = { Text(Words.Door.Api.keyName) }, placeholder = { Text(Words.Door.Api.keyNameHint) },
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      val asking = apiNewKeyName(name, door, busy)
      TextButton({ asking?.let(onAsk) }, Modifier.confirms(), enabled = asking != null) { Text(Words.Door.Api.newKey) }
    },
    dismissButton = { TextButton(onDismiss) { Text(Words.Common.cancel, color = Theme.soft) } },
    containerColor = Theme.panel,
  )
}

/**
 * The key, shown once, on the phone that made it: Copy (marked sensitive, so the system's copy preview never shows it, and cleared
 * from the clipboard after a minute while tafs is open), then Done, which forgets it. tafs keeps no copy it can show again.
 */
@Composable
internal fun ApiKeyShownOnce(door: Door, issued: bot.tafs.app.kiff.IssuedKey, endpoint: String, onDone: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var copied by remember { mutableStateOf(false) }
  DriveAlert(
    onDismissRequest = {},
    // The key never shows in a screenshot, the recents screen or a shared screen: its window is secure.
    properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
    title = { Text(Words.Door.Api.shownOnce) },
    text = {
      Column(Modifier.testTag(API_KEY_SHOWN).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(Words.Door.Api.shownOnceBody(door.label), style = scaled(14, color = Theme.ink))
        SelectionContainer { Text(issued.key, style = scaled(14, FontWeight.Medium, Theme.ink, mono = true)) }
        if (copied) Text(Words.Door.Api.copied, style = scaled(13, color = Theme.ok))
        WhereAndExample(endpoint)
      }
    },
    confirmButton = {
      TextButton({
        copyKeyForAMinute(context, issued.key)
        copied = true
      }) { Text(Words.Door.Api.copyKey) }
    },
    dismissButton = { TextButton(onDone) { Text(Words.Common.done) } },
    containerColor = Theme.panel,
  )
}

/** The label a copied key's clip carries, so the minute's clear only ever takes away tafs's own clip, never the person's next copy. */
internal const val API_KEY_CLIP = "tafs.api.key"

/** The label of a copied Chrome pairing code's clip, cleared the same way. */
internal const val CHROME_CODE_CLIP = "tafs.chrome.code"

/**
 * Copies [key], marked sensitive (Android 13+: the system's copy preview never shows it), and clears it from the clipboard a
 * minute later if it's still tafs's clip, while tafs is running: Android has no clipboard expiry of its own, and the app can't
 * stop a phone maker's clipboard sync for one clip.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
internal fun copyKeyForAMinute(context: android.content.Context, key: String, after: Long = 60_000, label: String = API_KEY_CLIP) {
  val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
  val clip = bot.tafs.app.model.Res.clip(label, key)
  if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
  clipboard.setPrimaryClip(clip)
  kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
    kotlinx.coroutines.delay(after)
    runCatching { if (clipboard.primaryClipDescription?.label == label && Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() }
  }
}

/** A field the application sends, as a developer reads it: its name, then its choices or its type (as iOS's `APIDoorText.field`). */
internal fun draftField(field: bot.tafs.app.kiff.DoorDraft.Field): String =
  field.name + " \u00b7 " + (field.values?.takeIf { it.isNotEmpty() }?.joinToString(" | ") ?: field.type)

internal const val DOOR_DRAFT_APPROVE = "door.draft.approve"

/**
 * Coach's drafted door "From another application" (`show {type: "door", taf_id, draft_id}`, the owner's mockup screen 1), drawn
 * from tafs cloud's draft, never from Coach's words: the application's name, what it may do, what the taf does, the fields it
 * sends and what it gets back, its own words' risk when they're on, and the boundary. Approve, for whoever manages the taf, signs
 * the draft's exact `body` with the fingerprint and opens the door; its first key is then shown once (as iOS's `DoorDraftCard`).
 */
@Composable
internal fun DoorDraftCard(c: bot.tafs.app.kiff.SetupComponent, state: bot.tafs.app.app.AppState, actions: CoachActions) {
  val cloudTaf = c.tafId ?: c.knobTaf
  val draftId = c.draftId
  val taf = cloudTaf?.let { id -> state.tafs.firstOrNull { it.cloudId == id } }
  val tafName = taf?.name.orEmpty()
  val scope = rememberCoroutineScope()
  var draft by remember(cloudTaf, draftId) { mutableStateOf<bot.tafs.app.kiff.DoorDraft?>(null) }
  var failed by remember(cloudTaf, draftId) { mutableStateOf<String?>(null) }
  // A door was made from this draft already (tafs cloud's 410 `draft_used`): its words, said as done, never as a failure.
  var made by remember(cloudTaf, draftId) { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var approved by remember(cloudTaf, draftId) { mutableStateOf(false) }
  // The door the approval opened and its first key, held only until its screen closes.
  var added by remember { mutableStateOf<bot.tafs.app.kiff.AddedApiDoor?>(null) }
  LaunchedEffect(cloudTaf, draftId) {
    if (cloudTaf == null || draftId == null) return@LaunchedEffect
    try {
      draft = actions.doorDraft(cloudTaf, draftId)
      failed = null
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      if (doorDraftUsed(e)) made = bot.tafs.app.model.ErrorWords.of(e) else failed = bot.tafs.app.model.ErrorWords.of(e)
    }
  }
  // Signs the draft's exact body (the fingerprint) and opens the door; a closed fingerprint sends nothing.
  fun approve(shown: bot.tafs.app.kiff.DoorDraft) {
    val taf = cloudTaf ?: return
    busy = true
    scope.launch {
      try {
        val made = actions.approveDoorDraft(taf, shown, Words.Door.Api.faceidBody(app = shown.draft.name, taf = tafName))
        if (made != null) { added = made; approved = true; failed = null }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        failed = bot.tafs.app.model.ErrorWords.of(e)
      } finally {
        busy = false
      }
    }
  }
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    Eyebrow(Words.Door.Api.draftKind, Theme.amberText)
    draft?.let { shown ->
      val d = shown.draft
      Text(d.name, style = scaled(17, FontWeight.SemiBold, Theme.ink))
      Text(Words.Door.Api.draftScope(app = d.name, taf = tafName), style = scaled(14, color = Theme.soft))
      Text(Words.Door.Api.draftWill(taf = tafName, job = d.job), style = scaled(15, color = Theme.ink))
      if (d.fields.isNotEmpty()) {
        Eyebrow(Words.Door.Api.draftSends)
        d.fields.forEach { Text(draftField(it), style = scaled(13, color = Theme.ink, mono = true)) }
      }
      if (d.outcomes.isNotEmpty()) Text(Words.Door.Api.draftBack(bot.tafs.app.model.Res.list(d.outcomes)), style = scaled(14, color = Theme.ink))
      if (d.text) {
        Text(Words.Door.Api.draftWords(tafName), style = scaled(14, FontWeight.SemiBold, Theme.ink))
        Text(Words.Door.Api.draftWordsRisk(taf = tafName, app = d.name), style = scaled(13, color = Theme.amberText))
      }
      Text(Words.Door.Api.boundary(tafName), style = scaled(13, color = Theme.soft))
      when {
        approved -> Text(Words.Door.Status.open, style = scaled(14, FontWeight.SemiBold, Theme.ok))
        taf != null && state.manages(taf.id) -> PrimaryButton(
          if (busy) Words.Common.confirming else Words.Approval.approve,
          { approve(shown) },
          Modifier.fillMaxWidth().confirms().testTag(DOOR_DRAFT_APPROVE),
          enabled = !busy,
        )
      }
    }
    made?.let { Text(it, style = scaled(14, FontWeight.SemiBold, Theme.ok), modifier = Modifier.testTag(DOOR_DRAFT_MADE)) }
    failed?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
  added?.let { a -> ApiKeyShownOnce(a.door, a.key, actions.endpoint(a.door)) { added = null } }
}

/** tafs cloud says a door was already made from this draft (410 `draft_used`): done, not a failure. */
internal fun doorDraftUsed(e: Throwable): Boolean = e is bot.tafs.app.kiff.CloudError && e.status == 410 && e.code == "draft_used"

const val DOOR_DRAFT_MADE = "door-draft-made"
