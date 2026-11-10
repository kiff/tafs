package bot.tafs.app.views

import bot.tafs.app.model.Words
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.DriveSheet
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
  /** Buys Solo or a pack through Google Play, by its Play product; and brings back purchases not finished yet. */
  val buy: suspend (String) -> Unit = {},
  val restorePurchases: suspend () -> Unit = {},
  /** A member asks an admin for a pack, with a reason; and takes their request back. */
  val askForPack: suspend (String?, String) -> Boolean = { _, _ -> false },
  val withdrawRequest: suspend (String) -> Unit = {},
  /** An admin approves a request by buying its pack (its Play product, the request), or declines it with a note. */
  val approveRequest: suspend (String, String) -> Unit = { _, _ -> },
  val declineRequest: suspend (String, String) -> Boolean = { _, _ -> false },
  /** Raises the spend limit one step, signed (who manages billing only). */
  val raiseLimit: suspend () -> Boolean = { false },
  /** Reads the balance again, signing nothing. */
  val readBalance: suspend () -> Unit = {},
  /** Credits (docs/accounts.md#credits): this period's or the one before's; a taf's runs by tafs cloud's id, and more of them; a run's request. */
  val showCreditsPeriod: suspend (Boolean) -> Unit = {},
  val openTafCredits: suspend (String) -> Unit = {},
  val moreTafCredits: suspend () -> Unit = {},
  val openCreditsRun: suspend (String, String) -> Unit = { _, _ -> },
)

/**
 * When a halt is read again while it is halting: soon at first, since the three holds usually land within a second or two,
 * then every 2 seconds, for about 2 minutes in all.
 */
internal val HALT_POLLS_MS: List<Long> = listOf(500L, 1_000L) + List(59) { 2_000L }

/**
 * A halt of one taf or, with none, of every taf. Halting is one tap, no confirmation: it only takes authority
 * away. It reads Halted only once kiff/agents, KIFF and planque all hold it, Halting… until then. Resuming asks
 * for the screen lock or a biometric, and what the taf was doing before the halt doesn't come back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaltSheet(tafId: String?, state: AppState, actions: AccountActions, onClose: () -> Unit) {
  val h = state.haltOf(tafId)
  val name = tafId?.let { state.taf(it)?.name } ?: Words.Taf.Halt.allTafs
  // What's under way, a halt or a resume: each button says its own, even once the sheet has turned to the other's button.
  var underway by remember { mutableStateOf<Underway?>(null) }
  val busy = underway != null
  val scope = rememberCoroutineScope()
  val latest by rememberUpdatedState(state)

  // Read now, then again while it is halting: after half a second, a second, then every 2 seconds, for about 2 minutes.
  suspend fun follow() {
    for (wait in HALT_POLLS_MS) {
      if (!latest.haltOf(tafId).halting) return
      delay(wait)
      actions.refreshHalt(tafId)
    }
  }
  LaunchedEffect(tafId) {
    actions.refreshHalt(tafId)
    follow()
  }

  DriveSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    // Its height is fixed: what the halt becomes (running, halting, halted) doesn't resize a sheet already measured.
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(if (tafId == null) Words.Taf.Halt.titleAll else Words.Taf.Halt.title, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text(Words.Common.done, style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 8.dp))
      }
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Panel {
          Eyebrow(name, if (h.running) Theme.dim else Theme.no)
          Text(HaltWords.headline(h), style = scaled(26, FontWeight.Bold, Theme.ink, display = true))
          Text(HaltWords.explanation(h, all = tafId == null), style = scaled(15, color = Theme.soft))
        }
        if (!h.running) {
          Panel {
            Eyebrow(Words.Taf.Halt.whoHolds)
            HoldRow(Words.Taf.Halt.noRunStarts, h.runtime)
            HoldRow(Words.Taf.Halt.kiffRefuses, h.kiff)
            HoldRow(Words.Taf.Halt.connectionSendsNothing, h.planque)
            if (HaltWords.resumesWithAccount(h, tafId)) {
              Text(Words.Taf.Halt.haltedWithAll, style = scaled(13, color = Theme.dim))
            }
          }
        }
        when {
          h.running -> OutlineButton(
            if (busy) underway!!.words else if (tafId == null) Words.Halt.allNow else Words.Halt.now,
            { scope.launch { underway = Underway.Halting; actions.halt(tafId); underway = null; follow() } },
            Modifier.fillMaxWidth().confirms(), color = Theme.no, enabled = !busy,
          )
          h.halted -> if (!HaltWords.resumesWithAccount(h, tafId)) ApprovalGate {
            OutlineButton(
              if (busy) underway!!.words else Words.Taf.Halt.resumeWith(LocalBiometry.current),
              { scope.launch { underway = Underway.Resuming; actions.resumeHalt(tafId); underway = null } },
              Modifier.fillMaxWidth(), enabled = !busy,
            )
          }
          else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Theme.dim)
              Text(Words.Taf.Halt.haltingWait, style = scaled(14, color = Theme.dim))
            }
            // Halt again: a part that didn't take it the first time is asked again.
            OutlineButton(
              Words.Halt.again,
              { scope.launch { underway = Underway.Halting; actions.halt(tafId); underway = null; follow() } },
              Modifier.fillMaxWidth().confirms(), color = Theme.no, enabled = !busy,
            )
          }
        }
        // Why a Halt or Resume didn't go through, here where it was asked: a tap never ends with nothing said.
        if (!busy) state.haltError?.let { Text(it, style = scaled(14, color = Theme.no)) }
      }
    }
  }
}

/** What the Halt screen has under way, in the words its button shows meanwhile. */
private enum class Underway {
  Halting, Resuming;

  val words: String get() = when (this) { Halting -> Words.Taf.Halt.halting; Resuming -> Words.Taf.Halt.resuming }
}

/** One part of a halt, and whether it holds it yet. */
@Composable
private fun HoldRow(text: String, held: Boolean) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Dot(if (held) Theme.ok else Theme.dim)
    Text(text, style = scaled(15, color = Theme.ink), modifier = Modifier.weight(1f))
    Text(if (held) Words.Taf.Halt.partHeld else Words.Taf.Halt.partWaiting, style = scaled(13, color = Theme.dim))
  }
}

/** The row that opens a taf's halt, or every taf's, with where it stands. */
@Composable
fun HaltRow(tafId: String?, h: Halt, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Panel(modifier.clickable(onClick = onClick)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (tafId == null) Words.Taf.Halt.titleAll else Words.Taf.Halt.title, style = scaled(15, FontWeight.SemiBold, Theme.ink))
        Text(HaltWords.rowLine(h), style = scaled(13, color = if (h.running) Theme.dim else Theme.no))
      }
      Text("›", style = scaled(20, color = Theme.dim))
    }
  }
}
