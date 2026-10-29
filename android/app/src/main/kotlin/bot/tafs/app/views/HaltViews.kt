package bot.tafs.app.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Dot
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.Halt
import bot.tafs.app.model.HaltWords
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the Halt and Usage screens ask of the model. */
class AccountActions(
  val halt: suspend (String?) -> Unit,
  val refreshHalt: suspend (String?) -> Unit,
  val resumeHalt: suspend (String?) -> Boolean,
  val refreshUsage: suspend () -> Unit,
  val refreshTafUsage: suspend (String) -> Unit,
  /** Buys Solo or a pack through Google Play, by its Play product; and brings back purchases not finished yet. */
  val buy: suspend (String) -> Unit = {},
  val restorePurchases: suspend () -> Unit = {},
)

/** How often a halt is read again while it is halting, and for how long: every 2 seconds, up to 2 minutes. */
private const val HALT_POLL_MS = 2_000L
private const val HALT_POLLS = 60

/**
 * A halt of one taf or, with none, of every taf. Halting is one tap, no confirmation: it only takes authority
 * away. It reads Halted only once kiff/agents, KIFF and planque all hold it, Halting… until then. Resuming asks
 * for the screen lock or a biometric, and what the taf was doing before the halt doesn't come back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaltSheet(tafId: String?, state: AppState, actions: AccountActions, onClose: () -> Unit) {
  val h = state.haltOf(tafId)
  val name = tafId?.let { state.taf(it)?.name } ?: "All your tafs"
  var busy by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val latest by rememberUpdatedState(state)

  // Read now, then again every 2 seconds while it is halting, for up to 2 minutes.
  suspend fun follow() {
    for (i in 0 until HALT_POLLS) {
      if (!latest.haltOf(tafId).halting) return
      delay(HALT_POLL_MS)
      actions.refreshHalt(tafId)
    }
  }
  LaunchedEffect(tafId) {
    actions.refreshHalt(tafId)
    follow()
  }

  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    // Its height is fixed: what the halt becomes (running, halting, halted) doesn't resize a sheet already measured.
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(if (tafId == null) "Halt all" else "Halt", style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 10.dp))
      }
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Panel {
          Eyebrow(name, if (h.running) Theme.dim else Theme.no)
          Text(HaltWords.headline(h), style = scaled(26, FontWeight.Bold, Theme.ink, display = true))
          Text(HaltWords.explanation(h, all = tafId == null), style = scaled(15, color = Theme.soft))
        }
        if (!h.running) {
          Panel {
            Eyebrow("Who holds the halt")
            HoldRow("No run starts", h.runtime)
            HoldRow("KIFF refuses its calls", h.kiff)
            HoldRow("planque sends nothing to your tools", h.planque)
            if (HaltWords.resumesWithAccount(h, tafId)) {
              Text("Halted with all your tafs. Resume them all to let it run.", style = scaled(13, color = Theme.dim))
            }
          }
        }
        when {
          h.running -> OutlineButton(
            if (busy) "Halting…" else if (tafId == null) "Halt all now" else "Halt now",
            { scope.launch { busy = true; actions.halt(tafId); busy = false; follow() } },
            Modifier.fillMaxWidth(), color = Theme.no, enabled = !busy,
          )
          h.halted -> if (!HaltWords.resumesWithAccount(h, tafId)) {
            OutlineButton(
              if (busy) "Resuming…" else "Resume with ${LocalBiometry.current}",
              { scope.launch { busy = true; actions.resumeHalt(tafId); busy = false } },
              Modifier.fillMaxWidth(), enabled = !busy,
            )
          }
          else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Theme.dim)
              Text("Halting… waiting for every part to confirm.", style = scaled(14, color = Theme.dim))
            }
            // Halt again: a part that didn't take it the first time is asked again.
            OutlineButton(
              "Halt again",
              { scope.launch { busy = true; actions.halt(tafId); busy = false; follow() } },
              Modifier.fillMaxWidth(), color = Theme.no, enabled = !busy,
            )
          }
        }
      }
    }
  }
}

/** One part of a halt, and whether it holds it yet. */
@Composable
private fun HoldRow(text: String, held: Boolean) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    Dot(if (held) Theme.ok else Theme.dim)
    Text(text, style = scaled(15, color = Theme.ink), modifier = Modifier.weight(1f))
    Text(if (held) "Done" else "Waiting", style = scaled(13, color = Theme.dim))
  }
}

/** The row that opens a taf's halt, or every taf's, with where it stands. */
@Composable
fun HaltRow(tafId: String?, h: Halt, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Panel(modifier.clickable(onClick = onClick)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (tafId == null) "Halt all" else "Halt", style = scaled(15, FontWeight.SemiBold, Theme.ink))
        Text(HaltWords.rowLine(h), style = scaled(13, color = if (h.running) Theme.dim else Theme.no))
      }
      Text("›", style = scaled(20, color = Theme.dim))
    }
  }
}
