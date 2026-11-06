package bot.tafs.app.views

import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.DriveSheet
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.KnobWire
import bot.tafs.app.model.KnobWords
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.SetupWords
import bot.tafs.app.model.Taf
import bot.tafs.app.model.dayTitle
import bot.tafs.app.model.localDate
import bot.tafs.app.model.shortTime
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A taf's Setup tab: how it runs, in sections. The job, then when it starts and where its results go (each a short summary that
 * opens its own sheet), its connected apps, what it remembers, its versions, and removing it. Everything is tafs cloud's and
 * KIFF's, drawn as sent; the switches send their own route or the knob's `setting`, and changes to the dial wait for one switch.
 */
@Composable
fun SetupTab(
  taf: Taf,
  state: AppState,
  actions: TafActions,
  onVersions: () -> Unit,
  onConnectApps: () -> Unit,
  onRemove: (() -> Unit)?,
  onActivity: (String) -> Unit = {},
) {
  val signedIn = state.mode == Mode.kiff && taf.cloudId != null
  val manages = state.manages(taf.id)
  if (signedIn) LaunchedEffect(taf.id) { actions.doors.loadKnob(taf.id) }
  val knob = state.knobs[taf.id]
  var startsOpen by rememberSaveable(taf.id) { mutableStateOf(false) }
  var resultsOpen by rememberSaveable(taf.id) { mutableStateOf(false) }

  // Its Card asked to take another currency: first, since every call in it is refused until the Card changes.
  state.tafSetups[taf.id]?.cardUnitExceptions?.forEach { ask -> CardUnitExceptionPanel(ask, state) }

  // The job, and changing it with Coach.
  Panel {
    Eyebrow(SetupWords.JOB)
    Text(taf.job, style = scaled(15, color = Theme.ink))
    LocalImproveInCoach.current?.takeIf { signedIn && manages }?.let { improve ->
      OutlineButton("Improve in Coach", { improve(taf.id) }, Modifier.fillMaxWidth())
    }
  }
  // When it starts: one line per start, opening the sheet that changes them.
  if (signedIn) {
    SectionRow(SetupWords.STARTS_WHEN, startsSummary(taf, knob)) { startsOpen = true }
  } else {
    Panel {
      Eyebrow(SetupWords.STARTS_WHEN)
      Text(startsSummary(taf, null).joinToString("\n"), style = scaled(15, color = Theme.ink))
    }
  }
  // The apps it's connected to, read from planque, for the people who manage it.
  if (signedIn) ConnectedToolsRing(taf, state, actions, if (manages) onConnectApps else null, onActivity)
  if (signedIn) SectionRow(SetupWords.RESULTS_GO_TO, resultsSummary(knob, state)) { resultsOpen = true }
  if (signedIn) MemorySummaryPanel(taf, state, actions) else if (state.mode == Mode.sample) MemoryPanel(taf.id, state.memories[taf.id], state.memoryErrors[taf.id], actions)
  // In the sample, Pause is its own; signed in, it's every start, in Starts when.
  if (state.mode == Mode.sample && taf.state != bot.tafs.app.model.TafState.revoked) {
    OutlineButton(if (taf.state == bot.tafs.app.model.TafState.paused) "Resume" else "Pause", { actions.togglePause(taf.id) }, Modifier.fillMaxWidth().confirms())
  }
  onRemove?.let { OutlineButton("Remove for good", it, Modifier.fillMaxWidth().confirms(), color = Theme.no) }

  if (startsOpen && knob != null) StartsWhenSheet(taf, knob, state, actions) { startsOpen = false }
  if (resultsOpen && knob != null) ResultsSheet(taf, knob, state, actions) { resultsOpen = false }
}

/** A section of Setup in one panel: its title, what it is now in a line or two, and Open ›. */
@Composable
private fun SectionRow(title: String, lines: List<String>, onOpen: () -> Unit) {
  Panel(Modifier.clickable(onClick = onOpen).semantics { contentDescription = "$title. ${lines.joinToString(". ")}. Open" }) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Eyebrow(title)
      androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
      Text(SetupWords.OPEN, style = scaled(14, FontWeight.SemiBold, Theme.amberText))
    }
    lines.forEach { Text(it, style = scaled(15, color = Theme.ink)) }
  }
}

/** What starts it, one line each, as tafs cloud words them: its schedule, its app events, its doors, its follow-ups. */
internal fun startsSummary(taf: Taf, knob: KnobWire?): List<String> {
  val starts = knob?.wakes.orEmpty().filter { it.kind != "add_door" && it.kind != "run_now" }.map { it.words.ifBlank { it.label } }
  if (starts.isNotEmpty()) return starts
  if (taf.worksFromChat) return listOf(SetupWords.NO_START)
  return listOfNotNull(if (taf.paused) "Paused" else taf.nextRun?.let { "Next run at ${shortTime(it)}" } ?: "On its schedule")
}

/** Where its results go now: the places on, by their titles. */
internal fun resultsSummary(knob: KnobWire?, state: AppState): List<String> =
  knob?.results.orEmpty().filter { it.on }.map { if (it.place in SetupWords.TAFS_PLACES) SetupWords.placeTitle(it.place!!) else it.label }
    .ifEmpty { listOf(SetupWords.placeTitle("door_reply")) }

/** Its connected apps, read from planque, with "Choose actions" for one signed in with none chosen yet, then "Connect an app…". */
@Composable
private fun ConnectedApps(taf: Taf, state: AppState, actions: TafActions, onConnectApps: () -> Unit) {
  ConnectionsPanel(taf, state, actions.connections)
  val improve = LocalImproveInCoach.current
  state.connections(taf.id).filter { it.listed }.forEach { grant ->
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(grant.vendor, style = scaled(15, color = Theme.ink), modifier = Modifier.weight(1f))
      improve?.let {
        Text(
          SetupWords.CHOOSE_ACTIONS,
          style = scaled(14, FontWeight.SemiBold, Theme.amberText),
          modifier = Modifier.heightIn(min = 44.dp).clickable { it(taf.id) }.padding(vertical = 12.dp),
        )
      }
    }
  }
  Text(
    SetupWords.CONNECT_APP,
    style = scaled(15, FontWeight.SemiBold, Theme.amberText),
    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onConnectApps).padding(vertical = 12.dp),
  )
}

/**
 * Setup › Starts when: its schedule (− and + from its size, and what happens if it's still working), its app events (Approve, or
 * listening), its doors, the follow-ups it set itself, and pausing every start. Changes to the dial wait for one switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartsWhenSheet(taf: Taf, knob: KnobWire, state: AppState, actions: TafActions, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  val improve = LocalImproveInCoach.current
  val manages = state.manages(taf.id)
  var busy by rememberSaveable { mutableStateOf(false) }
  var approving by rememberSaveable { mutableStateOf<String?>(null) }
  fun work(block: suspend () -> Unit) {
    if (busy) return
    scope.launch { busy = true; try { block() } finally { busy = false } }
  }
  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(SetupWords.STARTS_WHEN, style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
      PendingChange(taf, knob, state, actions, busy) { work(it) }

      // Its schedule: its line, its next run in the account's zone, its size, and what happens if it's still working.
      val schedules = knob.wakes.filter { it.kind == "schedule" }
      if (schedules.isNotEmpty()) {
        Eyebrow(SetupWords.SCHEDULE)
        schedules.forEach { s ->
          Text(s.words.ifBlank { s.label }, style = scaled(15, color = Theme.ink))
          taf.nextRun?.takeIf { !taf.paused }?.let { Text(SetupWords.nextRun(shortTime(it), state.kiffAccount?.timeZone), style = scaled(13, color = Theme.soft)) }
          s.size?.let { size -> SizeRow(size, enabled = manages && !busy) { setting -> work { actions.doors.changeKnobSize(taf.id, setting) } } }
        }
        knob.setting?.let { setting ->
          val now = (setting["busy"] as? JsonPrimitive)?.content ?: "skip"
          Text(SetupWords.IF_BUSY, style = scaled(13, color = Theme.soft))
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("skip" to SetupWords.SKIP_IT, "latest" to SetupWords.RUN_AFTER).forEach { (value, words) ->
              Choice(words, selected = now == value, enabled = manages && !busy) {
                if (now != value) work { actions.doors.changeKnobSize(taf.id, JsonObject(setting + ("busy" to JsonPrimitive(value)))) }
              }
            }
          }
        }
      }

      // Its app events: each with its filter in words; Approve when it waits for the owner.
      val events = knob.wakes.filter { it.kind == "app_event" }
      if (events.isNotEmpty()) {
        Eyebrow(SetupWords.APP_EVENTS)
        events.forEach { e ->
          Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(e.label, style = scaled(15, FontWeight.SemiBold, Theme.ink))
              Text(e.words, style = scaled(13, color = Theme.soft))
            }
            if (e.approval?.needed == true && manages) {
              Text(
                SetupWords.APPROVE,
                style = scaled(14, FontWeight.SemiBold, Theme.amberText),
                modifier = Modifier.heightIn(min = 44.dp).confirms().clickable { approving = e.id }.padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
              )
            } else if (e.approval?.state == "approved" || e.on) {
              Text(SetupWords.LISTENING, style = scaled(13, FontWeight.SemiBold, Theme.ok))
              // Stop: the trigger leaves the dial's setting, a change that waits for one switch like any other.
              val trigger = e.trigger
              val setting = knob.setting
              if (manages && trigger != null && setting != null) {
                Text(
                  SetupWords.STOP,
                  style = scaled(14, FontWeight.SemiBold, Theme.no),
                  modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = !busy) {
                    work { actions.doors.changeKnobSize(taf.id, withoutTrigger(setting, trigger)) }
                  }.padding(start = 10.dp, top = 12.dp, bottom = 12.dp),
                )
              }
            }
          }
        }
        Text(SetupWords.APPROVE_NOTE, style = scaled(12, color = Theme.dim))
      }

      // Its doors, each with its own screen, then a new one.
      if (manages) {
        Eyebrow(SetupWords.DOORS)
        DoorsList(taf, state, actions.doors, state.kiffAccount?.email)
      }

      // The follow-ups it set itself, each removable.
      val followUps = knob.wakes.filter { it.kind == "follow_up" }
      if (followUps.isNotEmpty()) {
        Eyebrow(SetupWords.FOLLOW_UPS)
        followUps.forEach { f ->
          Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(f.words.ifBlank { f.label }, style = scaled(14, color = Theme.ink), modifier = Modifier.weight(1f))
            if (manages) f.followUp?.let { fid ->
              Text(
                SetupWords.REMOVE,
                style = scaled(14, FontWeight.SemiBold, Theme.no),
                modifier = Modifier.heightIn(min = 44.dp).confirms().clickable(enabled = !busy) { work { actions.doors.removeFollowUp(taf.id, fid) } }.padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
              )
            }
          }
        }
      }

      if (manages) improve?.let { change -> androidx.compose.material3.TextButton({ onClose(); change(taf.id) }) { Text("Change when it starts with Coach", color = Theme.amberText) } }
      state.knobErrors[taf.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
      // Every start at once: off for a taf that works from its chat (tafs cloud's `can_pause`).
      if (manages) {
        OutlineButton(
          if (taf.paused) SetupWords.RESUME_EVERY else SetupWords.PAUSE_EVERY,
          { work { actions.setPaused(taf.id, !taf.paused); actions.doors.loadKnob(taf.id) } },
          Modifier.fillMaxWidth().confirms(),
          enabled = knob.canPause && !busy,
        )
      }
    }
  }
  approving?.let { id -> knob.item(id) }?.takeIf { it.approval?.needed == true }?.let { event ->
    EventApprovalSheet(
      event, busy = taf.id in state.approvingEvent, error = state.knobErrors[taf.id],
      onApprove = { scope.launch { if (actions.doors.approveEvent(taf.id, event.id)) approving = null } },
      onClose = { approving = null },
    )
  }
}

/**
 * Setup › Results go to: tafs' own places (reply to the sender, alert our phones, email me), then each place in a connected app,
 * each a switch. The last place on stays on. A reply given up on can be sent again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsSheet(taf: Taf, knob: KnobWire, state: AppState, actions: TafActions, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  val manages = state.manages(taf.id)
  var busy by rememberSaveable { mutableStateOf(false) }
  fun work(block: suspend () -> Unit) {
    if (busy) return
    scope.launch { busy = true; try { block() } finally { busy = false } }
  }
  LaunchedEffect(taf.id) { actions.loadWork(taf.id) }
  val on = knob.results.filter { it.on }
  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(SetupWords.RESULTS_GO_TO, style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
      Text(SetupWords.AT_LEAST_ONE, style = scaled(13, color = Theme.soft))
      PendingChange(taf, knob, state, actions, busy) { work(it) }

      // tafs' own places: switched through `PUT …/results`, at once, so they show the live taf's places, never a waiting draft's
      // (switching from a draft's would make its other choices live). tafs cloud keeps one of them on at least, on either route
      // (the dial gives the last of them no Off), so the last of them stays on, whatever app places are on too.
      Eyebrow(SetupWords.FROM_TAFS)
      val live = (state.taf(taf.id) ?: taf).results.ifEmpty { listOf("door_reply") }
      val tafsOn = SetupWords.TAFS_PLACES.filter { it in live }
      SetupWords.TAFS_PLACES.forEach { place ->
        val isOn = place in tafsOn
        PlaceRow(
          SetupWords.placeTitle(place), SetupWords.placeWords(place, state.kiffAccount?.email), isOn,
          enabled = manages && !busy && !(isOn && tafsOn.size == 1),
        ) { turnOn ->
          work { actions.setResults(taf.id, if (turnOn) tafsOn + place else tafsOn - place) }
        }
      }

      // Places in its connected apps: each is a change to the dial, from its own on/off choice.
      val apps = knob.results.filter { it.place == "action" }
      Eyebrow(SetupWords.IN_APPS)
      apps.forEach { place ->
        val other = place.size?.let { s -> s.choices.firstOrNull { it.label != s.now?.label } }
        PlaceRow(place.label, place.words, place.on, enabled = manages && !busy && other != null) {
          other?.let { c -> work { actions.doors.changeKnobSize(taf.id, c.setting) } }
        }
      }
      LocalImproveInCoach.current?.takeIf { manages }?.let { improve ->
        Text(
          SetupWords.ADD_PLACE,
          style = scaled(15, FontWeight.SemiBold, Theme.amberText),
          modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onClose(); improve(taf.id) }.padding(vertical = 12.dp),
        )
      }

      // A reply given up on after its tries: sent again from here.
      val unsent = state.works[taf.id]?.requests.orEmpty().filter { r -> r.deliveries.any { it.sendAgain } }
      if (unsent.isNotEmpty()) {
        Eyebrow(SetupWords.NOT_SENT, Theme.amberText)
        unsent.forEach { r ->
          Text(r.line, style = scaled(14, color = Theme.ink))
          if (manages) {
            r.deliveries.filter { it.sendAgain }.forEach { d ->
              // A place of the taf's own says which, in tafs cloud's words; the door reply is the request's line.
              d.name?.let { Text(it, style = scaled(13, color = Theme.soft)) }
              OutlineButton(
                if (r.id in state.sendingAgain) "Sending again…" else SetupWords.SEND_AGAIN,
                { scope.launch { actions.sendAgain(taf.id, r.id, d.sendPlace) } },
                Modifier.fillMaxWidth(),
                enabled = r.id !in state.sendingAgain,
              )
            }
          }
        }
      }
      // A failed read of its work, or a Send again that didn't go, in words.
      state.workErrors[taf.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
      state.knobErrors[taf.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
    }
  }
}

/** The dial's setting with one trigger taken out of its `wakes`, compared in canonical form. */
internal fun withoutTrigger(setting: JsonObject, trigger: kotlinx.serialization.json.JsonElement): JsonObject {
  val wakes = (setting["wakes"] as? kotlinx.serialization.json.JsonArray).orEmpty()
  val gone = bot.tafs.app.kiff.Jcs.canonical(trigger)
  return JsonObject(setting + ("wakes" to kotlinx.serialization.json.JsonArray(wakes.filter { bot.tafs.app.kiff.Jcs.canonical(it) != gone })))
}

/** A change to the dial waiting to go live (tafs cloud's `pending`): made live in one switch. */
@Composable
private fun PendingChange(taf: Taf, knob: KnobWire, state: AppState, actions: TafActions, busy: Boolean, work: (suspend () -> Unit) -> Unit) {
  val pending = knob.pending ?: return
  if (!state.manages(taf.id)) return
  Text(KnobWords.notLive(pending.version), style = scaled(11, FontWeight.SemiBold, Theme.amberText, mono = true))
  ApprovalGate {
    PrimaryButton(
      KnobWords.makeLive(pending.changes, bot.tafs.app.design.LocalBiometry.current),
      { work { actions.doors.confirmKnobChange(taf.id) } },
      enabled = !busy && taf.id !in state.switchingVersion,
    )
  }
}

/** A stop's one size: − its value +, sending the next choice's whole setting. */
@Composable
private fun SizeRow(size: KnobWire.Size, enabled: Boolean, onChoose: (JsonObject) -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(size.label, style = scaled(13, color = Theme.soft))
    val lower = size.step(-1)
    val raise = size.step(1)
    Text(
      "−",
      style = scaled(20, FontWeight.Bold, if (enabled && lower != null) Theme.ink else Theme.dim),
      modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = enabled && lower != null) { lower?.let { onChoose(it.setting) } }
        .semantics { contentDescription = KnobWords.LOWER }.padding(horizontal = 12.dp, vertical = 8.dp),
    )
    Text(size.now?.label ?: "", style = scaled(15, FontWeight.SemiBold, Theme.ink, mono = true))
    Text(
      "+",
      style = scaled(20, FontWeight.Bold, if (enabled && raise != null) Theme.ink else Theme.dim),
      modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = enabled && raise != null) { raise?.let { onChoose(it.setting) } }
        .semantics { contentDescription = KnobWords.RAISE }.padding(horizontal = 12.dp, vertical = 8.dp),
    )
  }
}

/** One of two answers, as a segment: ink when chosen. */
@Composable
private fun Choice(words: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  Text(
    words,
    style = scaled(14, FontWeight.SemiBold, if (selected) Theme.background else Theme.soft),
    modifier = Modifier.heightIn(min = 40.dp).clip(CircleShape).background(if (selected) Theme.ink else Theme.panel)
      .border(BorderStroke(1.dp, if (selected) Theme.ink else Theme.line), CircleShape)
      .clickable(enabled = enabled, onClick = onClick).semantics { this.selected = selected }
      .padding(horizontal = 14.dp, vertical = 10.dp),
  )
}

/** A place results can go: its title and words, and its switch. */
@Composable
private fun PlaceRow(title: String, words: String, on: Boolean, enabled: Boolean, onSwitch: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Column(Modifier.weight(1f)) {
      Text(title, style = scaled(15, FontWeight.SemiBold, Theme.ink))
      if (words.isNotBlank()) Text(words, style = scaled(13, color = Theme.soft))
    }
    Switch(
      checked = on,
      onCheckedChange = onSwitch,
      enabled = enabled,
      colors = SwitchDefaults.colors(checkedTrackColor = Theme.ok),
      modifier = Modifier.semantics { contentDescription = title },
    )
  }
}

/** Activity's Record filters: what happened to each call. */
internal enum class ActivityFilter(val title: String) {
  All("All"), Done("Done"), Held("Held"), Refused("Refused");

  fun keeps(e: RecordEntry): Boolean = when (this) {
    All -> true
    Done -> e.kind == bot.tafs.app.model.RecordEntry.Kind.done || e.kind == bot.tafs.app.model.RecordEntry.Kind.approved || e.kind == bot.tafs.app.model.RecordEntry.Kind.started
    Held -> e.kind == bot.tafs.app.model.RecordEntry.Kind.held || e.kind == bot.tafs.app.model.RecordEntry.Kind.sending || e.kind == bot.tafs.app.model.RecordEntry.Kind.unknown
    Refused -> e.kind == bot.tafs.app.model.RecordEntry.Kind.refused || e.kind == bot.tafs.app.model.RecordEntry.Kind.notDone || e.kind == bot.tafs.app.model.RecordEntry.Kind.revoked
  }
}

/** The taf's Record on its Activity: All, Done, Held, Refused, by day, newest first; each entry opens what KIFF signed. */
@Composable
internal fun ActivityRecord(entries: List<RecordEntry>, state: AppState) {
  var filter by rememberSaveable { mutableStateOf(ActivityFilter.All) }
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    ActivityFilter.entries.forEach { f -> Choice(f.title, selected = filter == f, enabled = true) { filter = f } }
  }
  val kept = entries.filter(filter::keeps).sortedByDescending { it.at }
  if (kept.isEmpty()) Text("Nothing yet.", style = scaled(15, color = Theme.dim))
  kept.groupBy { localDate(it.at) }.forEach { (day, lines) ->
    val spent = spentOn(lines)
    Text(
      (dayTitle(day) + (spent?.let { " · $it" } ?: "")).uppercase(),
      style = scaled(11, FontWeight.Medium, Theme.dim, mono = true),
      modifier = Modifier.padding(top = 6.dp),
    )
    lines.forEach { RecordRow(it, state, showTaf = false) }
  }
}
