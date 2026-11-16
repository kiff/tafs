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
  private val model by lazy { WatchModel(DataLayerPhone(this)) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        val shown by model.shown.collectAsState()
        WatchScreen(shown, openOnPhone = ::openOnPhone, tryAgain = { lifecycleScope.launch { model.refresh() } })
      }
    }
  }

  override fun onStart() {
    super.onStart()
    lifecycleScope.launch { model.refresh() }
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

@Composable
private fun WatchScreen(shown: WatchModel.Shown, openOnPhone: (String) -> Unit, tryAgain: () -> Unit) {
  ScalingLazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    when (shown) {
      WatchModel.Shown.Asking -> line(Words.Watch.askingPhone)
      WatchModel.Shown.SignedOut -> line(Words.Watch.signInOnPhone)
      WatchModel.Shown.PhoneUnreachable -> {
        line(Words.Watch.phoneNotReachable)
        item { Button(onClick = tryAgain, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.tryAgain) } }
      }
      WatchModel.Shown.OpenOnPhone -> item {
        Button(onClick = { openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.openOnPhone) }
      }
      is WatchModel.Shown.Read -> {
        item { ListHeader { Text(Words.NeedsYou.title) } }
        line(shown.needsYou.words)
        for (call in shown.needsYou.calls) item { HeldCallCard(call, openOnPhone) }
        shown.tafs?.let { read ->
          item { ListHeader { Text(Words.Home.yourTafs) } }
          for (taf in read.tafs) item { TafRow(taf) }
        }
      }
    }
  }
}

private fun ScalingLazyListScope.line(words: String) = item {
  Text(words, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

/** A held call as the phone's Needs you words it: its taf, what it wants, KIFF's amount and the reason. Approving is the phone's. */
@Composable
private fun HeldCallCard(call: HeldLine, openOnPhone: (String) -> Unit) {
  Card(onClick = { openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(call.taf, style = MaterialTheme.typography.titleSmall)
      Text(call.wants)
      call.amount?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
      call.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      Button(onClick = { openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.approveOnPhone) }
    }
  }
}

@Composable
private fun TafRow(taf: TafLine) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
    Text(taf.name, style = MaterialTheme.typography.titleSmall)
    Text(taf.state, style = MaterialTheme.typography.bodySmall)
  }
}
