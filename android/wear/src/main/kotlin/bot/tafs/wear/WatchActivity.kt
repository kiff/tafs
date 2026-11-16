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
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchLink
import kotlinx.coroutines.launch

/**
 * The watch's one screen: Needs you (the count, each held call in the phone's words, approvals on the phone), then Your tafs,
 * read from the phone each time the watch shows it, and forgotten when it's put away (docs/watch-wear.md).
 */
class WatchActivity : ComponentActivity() {
  /** The watch's own speech input: it turns what's said into text on the watch's side, and only the text comes back here. */
  private val speech = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
    val heard = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
    if (heard != null) model.heard(heard)
  }

  private fun listen() {
    val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, Words.Coach.ask)
    runCatching { speech.launch(intent) }
  }

  private val model by lazy {
    val keyguard = getSystemService(android.app.KeyguardManager::class.java)
    // A screen lock set and the watch unlocked now: the confirmation for Refuse and Halt (docs/watch-wear.md).
    WatchModel(DataLayerPhone(this), unlocked = { keyguard.isDeviceSecure && !keyguard.isDeviceLocked }, tile = TileCount(PreferencesTileStore(this)))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        val shown by model.shown.collectAsState()
        val outcome by model.outcome.collectAsState()
        val confirmingHaltAll by model.confirmingHaltAll.collectAsState()
        val talk by model.talk.collectAsState()
        WatchScreen(
          shown,
          outcome,
          confirmingHaltAll,
          talk,
          Actions(
            openOnPhone = ::openOnPhone,
            tryAgain = { lifecycleScope.launch { model.refresh() } },
            refuse = { hold -> lifecycleScope.launch { model.refuse(hold) } },
            halt = { taf -> lifecycleScope.launch { model.halt(taf) } },
            askHaltAll = model::askHaltAll,
            confirmHaltAll = { lifecycleScope.launch { model.confirmHaltAll() } },
            cancelHaltAll = model::cancelHaltAll,
            askCoach = ::listen,
            sendToCoach = { lifecycleScope.launch { model.sendToCoach() } },
            closeTalk = model::closeTalk,
            dismiss = model::dismissOutcome,
          ),
        )
      }
    }
  }

  override fun onStart() {
    super.onStart()
    lifecycleScope.launch {
      model.refresh()
      // The tile shows the count just read, or forgets it if the phone signed out.
      NeedsYouTileService.update(this@WatchActivity)
    }
  }

  override fun onStop() {
    super.onStop()
    model.forget()
  }

  /** Opens tafs on the phone, at [where] (`needs-you`): approvals, and anything longer than the watch draws, are the phone's. */
  private fun openOnPhone(where: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WatchLink.OPEN_ON_PHONE + where)).addCategory(Intent.CATEGORY_BROWSABLE)
    runCatching { RemoteActivityHelper(this).startRemoteActivity(intent) }
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
  /** Talk to Coach: the watch's speech input, then the words shown, then Send. */
  val askCoach: () -> Unit,
  val sendToCoach: () -> Unit,
  val closeTalk: () -> Unit,
  val dismiss: () -> Unit,
)

/** The one screen. [list] is the screen's own scroll unless a caller (the store screenshots) opens it on another item. */
@Composable
internal fun WatchScreen(
  shown: WatchModel.Shown,
  outcome: WatchModel.Outcome?,
  confirmingHaltAll: Boolean,
  talk: WatchModel.Talk?,
  act: Actions,
  list: ScalingLazyListState = rememberScalingLazyListState(),
) {
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    outcome?.let { item { OutcomeCard(it, act) } }
    talk?.let { item { TalkCard(it, act) } }
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
        if (talk == null) item {
          Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Button(onClick = act.askCoach, modifier = Modifier.fillMaxWidth()) { Text(Words.Coach.ask) }
            Text(Words.Watch.voiceTextOnly, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
          }
        }
        item { ListHeader { Text(Words.NeedsYou.title) } }
        // Nothing waits: the owner's all-clear (board 12) above Needs you's own words.
        if (shown.needsYou.allClear) item { AllClearIllustration() }
        line(shown.needsYou.words)
        for (call in shown.needsYou.calls) item { HeldCallCard(call, act) }
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
      }
    }
  }
}

private fun ScalingLazyListScope.line(words: String) = item {
  Text(words, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

/** Coach from the wrist: the words heard, shown before they're sent; Coach's answer, short; anything more on the phone. */
@Composable
private fun TalkCard(talk: WatchModel.Talk, act: Actions) {
  Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      when (talk) {
        is WatchModel.Talk.Heard -> {
          Text(talk.text)
          Button(onClick = act.sendToCoach, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.send) }
          Button(onClick = act.closeTalk, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.cancel) }
        }
        WatchModel.Talk.Sending -> Text(Words.Watch.askingPhone)
        is WatchModel.Talk.Answered -> {
          Text(talk.text)
          if (talk.long) Button(onClick = { act.openOnPhone("") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.openOnPhone) }
          Button(onClick = act.closeTalk, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.done) }
        }
        WatchModel.Talk.NeedsLock -> {
          Text(Words.Watch.needsLock)
          Button(onClick = act.closeTalk, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.close) }
        }
        is WatchModel.Talk.NotSent -> {
          talk.words?.let { Text(it) }
          Button(onClick = { act.openOnPhone("") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.openOnPhone) }
          Button(onClick = act.closeTalk, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.close) }
        }
      }
    }
  }
}

/** What came of a Refuse or a Halt, in the phone's words; anything that didn't go through offers Open on phone. */
@Composable
private fun OutcomeCard(outcome: WatchModel.Outcome, act: Actions) {
  Card(onClick = act.dismiss, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      when (outcome) {
        is WatchModel.Outcome.Done -> Text(outcome.words, style = MaterialTheme.typography.titleSmall)
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
private fun HeldCallCard(call: HeldLine, act: Actions) {
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
