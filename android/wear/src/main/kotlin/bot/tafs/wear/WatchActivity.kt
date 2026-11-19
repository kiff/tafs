package bot.tafs.wear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.remote.interactions.RemoteActivityHelper
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.DecisionLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import kotlinx.coroutines.launch

/**
 * The watch app (docs/watch-wear.md): the microphone first ("Ask tafs"), then Needs you and Your tafs, read from the phone each
 * time the watch shows them, and forgotten when it's put away. Speaking is turned into text on the watch, by the system's speech
 * recognizer, and sent when the person stops, with nothing to confirm: only the text goes to the phone.
 */
class WatchActivity : ComponentActivity() {
  private val model by lazy {
    val keyguard = getSystemService(android.app.KeyguardManager::class.java)
    // A screen lock set and the watch unlocked now: the confirmation for an ask, a Refuse and a Halt (docs/watch-wear.md).
    WatchModel(DataLayerPhone(this), unlocked = { keyguard.isDeviceSecure && !keyguard.isDeviceLocked }, tile = TileCount(PreferencesTileStore(this)))
  }

  private val listener by lazy { Listener(this, model, lifecycleScope) }
  private var speaker: Speaker? = null

  /** The ask a Reply continues, kept while the microphone permission is asked. */
  private var pendingReply: String? = null
  private var pendingListen = false

  /** The microphone, and on Wear OS 4 and later the result's notification, asked once, at the first ask. */
  private val microphone = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    val mic = granted[android.Manifest.permission.RECORD_AUDIO] == true
    if (mic && pendingListen) startListening(pendingReply)
    else if (!mic) model.notListening(Words.Watch.micNeeded)
    pendingListen = false
  }

  /** The system's speech input, where the watch has no recognizer for apps: its text comes back here, and is sent at once. */
  private val speechInput = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
    val heard = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
    lifecycleScope.launch { if (heard != null) model.heard(heard) else model.heardNothing() }
  }

  private fun listen(replyTo: String? = null) {
    if (!model.listen(replyTo)) return
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      pendingReply = replyTo
      pendingListen = true
      val asked = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
      if (android.os.Build.VERSION.SDK_INT >= 33) asked += android.Manifest.permission.POST_NOTIFICATIONS
      microphone.launch(asked.toTypedArray())
      return
    }
    startListening(replyTo)
  }

  private fun startListening(replyTo: String?) {
    if (!listener.start(replyTo)) {
      val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, Words.Watch.ask)
      runCatching { speechInput.launch(intent) }.onFailure { model.heardNothing() }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        val shown by model.shown.collectAsState()
        val outcome by model.outcome.collectAsState()
        val confirmingHaltAll by model.confirmingHaltAll.collectAsState()
        val voice by model.voice.collectAsState()
        val result by model.result.collectAsState()
        val asks by model.asks.collectAsState()
        // "Sent": the wrist can drop; the microphone comes back by itself.
        androidx.compose.runtime.LaunchedEffect(voice) {
          if (voice is WatchModel.Voice.Sent) {
            kotlinx.coroutines.delay(SENT_SHOWN_MS)
            if (model.voice.value is WatchModel.Voice.Sent) model.closeVoice()
          }
        }
        val actions = Actions(
          openOnPhone = ::openOnPhone,
          tryAgain = { lifecycleScope.launch { model.refresh() } },
          refuse = { hold -> lifecycleScope.launch { model.refuse(hold) } },
          halt = { taf -> lifecycleScope.launch { model.halt(taf) } },
          askHaltAll = model::askHaltAll,
          confirmHaltAll = { lifecycleScope.launch { model.confirmHaltAll() } },
          cancelHaltAll = model::cancelHaltAll,
          listen = { listen() },
          closeVoice = { listener.cancel(); model.closeVoice() },
          retry = { lifecycleScope.launch { model.retry() } },
          open = model::open,
          hear = { ask -> speaker().say(ask.line ?: ask.words) },
          reply = { ask -> listen(ask.id) },
          closeResult = model::closeResult,
          dismiss = model::dismissOutcome,
        )
        val working = asks.values.let { model.working() }
        val ready = asks.values.let { model.ready() }
        val timing = result?.let { r -> if (BuildConfig.TIMING) model.timing(r.clientId)?.let(::readout) else null }
        // v2's pages once every step is in (WatchPages.kt); v1 until then.
        if (WATCH_V2) WatchPages(shown, outcome, confirmingHaltAll, voice, result, working, ready, actions, timing = timing)
        else WatchScreen(shown, outcome, confirmingHaltAll, voice, result, working, ready, actions, timing = timing)
      }
    }
    handle(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handle(intent)
  }

  /** A result's notification opens its card; the tile's "Ask tafs" starts listening. */
  private fun handle(intent: Intent?) {
    intent?.getByteArrayExtra(AskResults.EXTRA_ASK)?.let(WatchLink::decodeResult)?.let { model.took(it); model.open(it) }
    if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
      intent.removeExtra(EXTRA_LISTEN)
      listen()
    }
  }

  override fun onStart() {
    super.onStart()
    // While the app is open, an ask's new state from the phone is drawn here instead of notified.
    AskResults.shown = { ask -> runOnUiThread { model.took(ask) } }
    lifecycleScope.launch {
      model.refresh()
      model.readAsks()
      // The tile shows the count just read, or forgets it if the phone signed out.
      NeedsYouTileService.update(this@WatchActivity)
    }
  }

  override fun onStop() {
    super.onStop()
    AskResults.shown = null
    listener.cancel()
    speaker?.close()
    speaker = null
    model.forget()
  }

  override fun onDestroy() {
    listener.close()
    super.onDestroy()
  }

  private fun speaker(): Speaker = speaker ?: Speaker(this).also { speaker = it }

  /** Opens tafs on the phone, at [where] (`needs-you`): approvals, and anything longer than the watch draws, are the phone's. */
  private fun openOnPhone(where: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WatchLink.OPEN_ON_PHONE + where)).addCategory(Intent.CATEGORY_BROWSABLE)
    runCatching { RemoteActivityHelper(this).startRemoteActivity(intent) }
  }

  companion object {
    /** The tile's "Ask tafs": open listening. */
    const val EXTRA_LISTEN = "bot.tafs.wear.LISTEN"

    /** How long "Sent" stays before the microphone comes back. */
    const val SENT_SHOWN_MS = 2_500L

    /** The internal builds' timing line: listen, receipt and result, in milliseconds as the watch measured them. */
    fun readout(t: WatchModel.Timing): String = Words.Watch.timing(ms(t.listenMs), ms(t.receiptMs), ms(t.resultMs))

    private fun ms(v: Long?): String = v?.toString() ?: "–"
  }
}

/** What the screen can do: everything that reaches the phone goes through [WatchModel]. */
internal class Actions(
  val openOnPhone: (String) -> Unit,
  val tryAgain: () -> Unit,
  val refuse: (String) -> Unit,
  /** One taf, by its id. */
  val halt: (String) -> Unit,
  /** Halt all: asked first, then confirmed or cancelled. */
  val askHaltAll: () -> Unit,
  val confirmHaltAll: () -> Unit,
  val cancelHaltAll: () -> Unit,
  /** Ask tafs: listen, and send when the person stops. */
  val listen: () -> Unit,
  val closeVoice: () -> Unit,
  /** "Not sure it arrived": the same words, the same id. */
  val retry: () -> Unit,
  /** Opens an ask's result card. */
  val open: (AskLine) -> Unit,
  /** Reads the result aloud, on the watch. */
  val hear: (AskLine) -> Unit,
  /** Listens again, continuing this ask. */
  val reply: (AskLine) -> Unit,
  val closeResult: () -> Unit,
  val dismiss: () -> Unit,
) {
  companion object {
    /** Nothing happens: for a screen drawn on its own (the store screenshots). */
    val none = Actions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
  }
}

/**
 * The watch's screen. The microphone's screens and a result's card take the whole face; otherwise the microphone is first, with
 * the asks at work and Needs you's count under it, then Needs you and Your tafs below. [list] is the screen's own scroll unless a
 * caller (the store screenshots) opens it on another item.
 */
@Composable
internal fun WatchScreen(
  shown: WatchModel.Shown,
  outcome: WatchModel.Outcome?,
  confirmingHaltAll: Boolean,
  voice: WatchModel.Voice?,
  result: AskLine?,
  working: List<AskLine>,
  ready: List<AskLine>,
  act: Actions,
  // Opened on the microphone.
  list: ScalingLazyListState = rememberScalingLazyListState(initialCenterItemIndex = 0),
  timing: String? = null,
) {
  when {
    voice != null -> return VoiceScreen(voice, act)
    result != null -> return ResultScreen(result, act, timing)
    outcome is WatchModel.Outcome.Halting -> return HaltingScreen(outcome.taf)
  }
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { AskHome(status(shown, working, ready), act.listen, opens(working, ready, act)) }
    outcome?.let { item { OutcomeCard(it, act) } }
    when (shown) {
      WatchModel.Shown.Asking -> line(Words.Watch.askingPhone)
      WatchModel.Shown.SignedOut -> line(Words.Watch.signInOnPhone)
      WatchModel.Shown.PhoneUnreachable -> {
        line(Words.Watch.phoneNotReachable)
        item { Button(onClick = act.tryAgain, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.tryAgain) } }
      }
      WatchModel.Shown.OpenOnPhone -> item {
        Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.openOnPhone) }
      }
      is WatchModel.Shown.Read -> {
        val brief = shown.brief
        if (brief != null) {
          // Your brief, in Needs you's place (the switch on, and the account has one).
          briefItems(brief, shown, act)
        } else {
          item { ListHeader { Text(Words.NeedsYou.title, color = Look.amber) } }
          // Nothing waits: the owner's all-clear (board 12) above Needs you's own words.
          if (shown.needsYou.allClear) item { AllClearIllustration() }
          line(shown.needsYou.words)
          for (call in shown.needsYou.calls) item { HeldCallCard(call, act) }
        }
        shown.tafs?.let { read ->
          item { ListHeader { Text(Words.Home.yourTafs) } }
          for (taf in read.tafs) item { TafRow(taf, act) }
          if (!read.allHalted && read.tafs.isNotEmpty()) item {
            if (confirmingHaltAll) {
              // Asked once more on the wrist: every taf stops until resumed on the phone.
              Card(onClick = act.cancelHaltAll, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(Words.HaltAll.title, style = MaterialTheme.typography.titleSmall)
                  Button(onClick = act.confirmHaltAll, modifier = Modifier.fillMaxWidth()) { Text(Words.Halt.allNow) }
                  Button(onClick = act.cancelHaltAll, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.cancel) }
                }
              }
            } else {
              Button(onClick = act.askHaltAll, modifier = Modifier.fillMaxWidth()) { Text(Words.Halt.allNow) }
            }
          }
        }
        item { Text(Words.Watch.askTextOnly, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
      }
    }
  }
}

/**
 * The lines under the microphone: each ask at work or ready, by its taf and tafs cloud's word, when there are one or two; past
 * that, how many; then Needs you's count, in amber, when something waits.
 */
internal fun status(shown: WatchModel.Shown, working: List<AskLine>, ready: List<AskLine>): List<Pair<String, androidx.compose.ui.graphics.Color?>> {
  val lines = mutableListOf<Pair<String, androidx.compose.ui.graphics.Color?>>()
  if (working.size + ready.size <= 2) {
    working.forEach { lines += stateLine(it) to Look.brand[2] }
    ready.forEach { lines += stateLine(it) to Look.green }
  } else {
    lines += listOfNotNull(
      working.size.takeIf { it > 0 }?.let(Words.Watch::workingCount),
      ready.size.takeIf { it > 0 }?.let(Words.Watch::readyCount),
    ).joinToString(" · ") to null
  }
  val count = (shown as? WatchModel.Shown.Read)?.needsYou?.count ?: 0
  if (count > 0) lines += Words.Watch.needsYouCount(count) to Look.amber
  return lines
}

/** What tapping each status line opens: a ready ask's card; the others open nothing. */
private fun opens(working: List<AskLine>, ready: List<AskLine>, act: Actions): List<() -> Unit> =
  if (working.size + ready.size > 2) emptyList() else working.map { {} } + ready.map { ask -> { act.open(ask) } }

/**
 * Your brief, in tafs cloud's words, where Needs you would be: its title and `when` line, each decision on its own card (answered on
 * the phone, so Open on phone; Refuse only where Needs you offers it), its line for more, what got done, and the rest.
 */
internal fun ScalingLazyListScope.briefItems(brief: WatchReply.Brief, shown: WatchModel.Shown.Read, act: Actions) {
  item { ListHeader { Text(brief.title) } }
  brief.whenLine?.let { line(it) }
  for (decision in brief.decisions) item { DecisionCard(decision, brief.urgent, shown.refusable(decision), act) }
  brief.more?.let { line(it) }
  brief.done?.let { done ->
    item {
      Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(done, style = MaterialTheme.typography.titleSmall)
        for (case in brief.doneCases) Text(case, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
  if (brief.rest != null || brief.needsYou != null) item {
    // What waits and needs nothing now, and what Needs you holds beyond the brief: Needs you's list, on the phone.
    Card(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        brief.rest?.let { Text(it) }
        brief.needsYou?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      }
    }
  }
}

/**
 * One of the brief's decisions: its taf, the question, its deadline, and why it can't wait. It's answered on the phone, never here:
 * Open on phone, and Refuse only for a held call Needs you would let the wrist refuse ([refuseHold]).
 */
@Composable
private fun DecisionCard(decision: DecisionLine, urgentWord: String?, refuseHold: String?, act: Actions) {
  Card(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      decision.taf?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
      if (decision.urgent) urgentWord?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
      Text(decision.question)
      decision.deadline?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      decision.cantWait?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
      if (refuseHold != null) {
        Button(onClick = { act.refuse(refuseHold) }, modifier = Modifier.fillMaxWidth()) { Text(Words.NeedsYou.refuse) }
      }
      Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.openOnPhone) }
    }
  }
}

internal fun ScalingLazyListScope.line(words: String) = item {
  Text(words, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

/** What came of a Refuse or a Halt, in the phone's words; anything that didn't go through offers Open on phone. */
@Composable
internal fun OutcomeCard(outcome: WatchModel.Outcome, act: Actions) {
  Card(onClick = act.dismiss, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      when (outcome) {
        is WatchModel.Outcome.Done -> Text(outcome.words, style = MaterialTheme.typography.titleSmall)
        is WatchModel.Outcome.Halting -> Text(if (outcome.taf.isNullOrEmpty()) Words.Watch.haltingAll else Words.Watch.halting(outcome.taf))
        WatchModel.Outcome.AlreadyAnswered -> Text(Words.Watch.alreadyAnswered)
        WatchModel.Outcome.NeedsLock -> Text(Words.Watch.needsLock)
        WatchModel.Outcome.PhoneUnreachable -> Text(Words.Watch.phoneNotReachable)
        is WatchModel.Outcome.NotDone -> outcome.words?.let { Text(it) }
      }
      if (outcome !is WatchModel.Outcome.Done && outcome !is WatchModel.Outcome.AlreadyAnswered) {
        Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.openOnPhone) }
      }
    }
  }
}

/**
 * A held call as the phone's Needs you words it: its taf, what it wants, KIFF's amount and the reason. Refuse, where the phone
 * can answer it; approving is always the phone's.
 */
@Composable
internal fun HeldCallCard(call: HeldLine, act: Actions) {
  Card(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(call.taf, style = MaterialTheme.typography.titleSmall)
      Text(call.wants)
      call.amount?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
      call.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      if (call.refusable) {
        Button(onClick = { act.refuse(call.holdId) }, modifier = Modifier.fillMaxWidth()) { Text(Words.NeedsYou.refuse) }
      }
      Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.approveOnPhone) }
    }
  }
}

@Composable
private fun TafRow(taf: TafLine, act: Actions) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(taf.name, style = MaterialTheme.typography.titleSmall)
    Text(taf.state, style = MaterialTheme.typography.bodySmall)
    if (!taf.halted) Button(onClick = { act.halt(taf.tafId) }, modifier = Modifier.fillMaxWidth()) { Text(Words.Halt.now) }
  }
}
