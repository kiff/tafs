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
        WatchScreen(
          shown,
          outcome,
          Actions(
            openOnPhone = ::openOnPhone,
            tryAgain = { lifecycleScope.launch { model.refresh() } },
            refuse = { hold -> lifecycleScope.launch { model.refuse(hold) } },
            halt = { taf -> lifecycleScope.launch { model.halt(taf) } },
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
private class Actions(
  val openOnPhone: (String) -> Unit,
  val tryAgain: () -> Unit,
  val refuse: (String) -> Unit,
  /** A taf by its id, or null for every taf. */
  val halt: (String?) -> Unit,
  val dismiss: () -> Unit,
)

@Composable
private fun WatchScreen(shown: WatchModel.Shown, outcome: WatchModel.Outcome?, act: Actions) {
  ScalingLazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        item { ListHeader { Text(Words.NeedsYou.title) } }
        line(shown.needsYou.words)
        for (call in shown.needsYou.calls) item { HeldCallCard(call, act) }
        shown.tafs?.let { read ->
          item { ListHeader { Text(Words.Home.yourTafs) } }
          for (taf in read.tafs) item { TafRow(taf, act) }
          if (!read.allHalted && read.tafs.isNotEmpty()) item {
            Button(onClick = { act.halt(null) }, modifier = Modifier.fillMaxWidth()) { Text(Words.Halt.allNow) }
          }
        }
      }
    }
  }
}

private fun ScalingLazyListScope.line(words: String) = item {
  Text(words, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
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
