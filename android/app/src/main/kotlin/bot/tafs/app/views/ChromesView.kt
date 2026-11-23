package bot.tafs.app.views

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppModel.ChromePairingInProgress
import bot.tafs.app.app.AppModel.ChromePairingStep
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.confirms
import bot.tafs.app.kiff.Chrome
import bot.tafs.app.kiff.ChromeOffer
import bot.tafs.app.kiff.ChromePairing
import bot.tafs.app.model.Words
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val CHROMES_SHEET = "chromes-sheet"
const val CHROME_CODE = "chrome-code"
const val CHROME_CODE_COPY = "chrome-code-copy"
const val CHROME_PAIR = "chrome-pair"
const val CHROME_CONFIRM = "chrome-confirm"
const val CHROME_REPLACE = "chrome-replace"
const val CHROME_REPLACE_NONE = "chrome-replace-none"

/** How often the sheet reads a pairing while the owner types its code in Chrome. */
private const val PAIRING_READ_MS = 2_000L

/**
 * Account › Chrome, as iOS's `ChromesView` and `PairChromeView` (docs/android-app.md, "Pairing a Chrome"; the contract is
 * docs/chrome.md): the Chromes paired to the account, with Unpair, and Pair a Chrome. The phone shows the code, whose secret it
 * never sends; it shows a Chrome only once that Chrome's proof holds under the secret, and one fingerprint naming it signs the
 * confirmation. Nothing here decides: planque keeps the pairings, and the extension checks this phone's signature itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChromesSheet(
  state: AppState,
  refresh: suspend () -> Unit,
  open: suspend () -> ChromePairingInProgress?,
  step: suspend (ChromePairingInProgress) -> ChromePairingStep,
  confirm: suspend (ChromePairingInProgress, ChromeOffer, String, Chrome?, List<ChromePairing.Carried>) -> Boolean,
  moves: (Chrome) -> List<ChromePairing.Carried>,
  remove: suspend (Chrome) -> Unit,
  onClose: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var pairing by remember { mutableStateOf<ChromePairingInProgress?>(null) }
  var current by remember { mutableStateOf<ChromePairingStep>(ChromePairingStep.Waiting) }
  var line by remember { mutableStateOf<String?>(null) }
  var name by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var removing by remember { mutableStateOf<Chrome?>(null) }
  LaunchedEffect(Unit) { refresh() }
  // While a pairing waits, read it: an offer shows only once its proof holds; an expired or refused one ends it.
  LaunchedEffect(pairing) {
    val p = pairing ?: return@LaunchedEffect
    current = ChromePairingStep.Waiting
    while (true) {
      val next = step(p)
      current = next
      when (next) {
        ChromePairingStep.Waiting -> delay(PAIRING_READ_MS)
        is ChromePairingStep.Offered -> return@LaunchedEffect
        ChromePairingStep.Refused -> { line = Words.Chrome.refused; pairing = null; return@LaunchedEffect }
        ChromePairingStep.Expired -> { line = Words.Chrome.expired; pairing = null; return@LaunchedEffect }
      }
    }
  }
  // The code's secret stays off screenshots, the recents screen and a screen shared out.
  val activity = LocalContext.current as? Activity
  DisposableEffect(pairing != null) {
    if (pairing != null) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
  }
  // The sheet is its own window: the code's secret is kept off it too, not only off the activity behind.
  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background, secure = pairing != null) {
    Column(Modifier.fillMaxSize().navigationBarsPadding().testTag(CHROMES_SHEET)) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(Words.Chrome.title, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text(Words.Common.done, style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = onClose).padding(top = 8.dp))
      }
      Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.chromesError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
        line?.let { Panel { Text(it, style = scaled(14, color = Theme.ink)) } }
        val p = pairing
        val offered = current as? ChromePairingStep.Offered
        // The pick: the owner's own choice once they've made one; until then its start, from the Chromes as last read (so a list
        // that arrives after the offer still starts on the same profile). Each is per offer.
        var picked by remember(offered?.offer) { mutableStateOf<String?>(null) }
        var touched by remember(offered?.offer) { mutableStateOf(false) }
        val paired = state.chromes.orEmpty()
        val replacingId = if (touched) picked else offered?.let { ChromePairing.replacedBy(it.offer, paired)?.id }
        // The Chrome as planque lists it now, never a copy kept from when it was picked: its connections are what moves.
        val replacing = paired.firstOrNull { it.id == replacingId }
        // Verified once per change of the Chrome or the connections, not on every redraw; the same list is what's signed.
        val moving = remember(replacing, state.grants) { replacing?.let(moves).orEmpty() }
        when {
          p != null && offered != null -> Panel {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(Words.Chrome.offeredTitle, style = scaled(17, FontWeight.SemiBold, Theme.ink))
              Text(Words.Chrome.offeredSays(browser = offered.offer.browser, system = offered.offer.os), style = scaled(14, color = Theme.dim))
              OutlinedTextField(name, bot.tafs.app.drive.driveTyped({ name = it.take(ChromePairing.NAME_MAX) }), label = { Text(Words.Chrome.nameLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
              // Re-pairing replaces, never adds ("Your Chrome" 1, docs/chrome.md): the owner picks which paired Chrome this one
              // replaces, starting on the same profile, else the one alike; with none picked it's a new Chrome, as before.
              if (paired.isNotEmpty()) {
                Text(Words.Chrome.replaceTitle, style = scaled(15, FontWeight.SemiBold, Theme.ink), modifier = Modifier.padding(top = 4.dp))
                paired.forEach { c -> ReplaceRow(c.name, selected = replacing?.id == c.id, enabled = !busy, tag = "$CHROME_REPLACE-${c.id}") { picked = c.id; touched = true } }
                ReplaceRow(Words.Chrome.replaceNone, selected = replacing == null, enabled = !busy, tag = CHROME_REPLACE_NONE) { picked = null; touched = true }
                replacing?.let { old ->
                  // What moves is what this phone signed for the old Chrome, checked with its own key; the rest ends with it.
                  val sites = moving.flatMap { it.sites }.distinct()
                  if (sites.isNotEmpty()) Text(Words.Chrome.replaceMoves(sites = sites.joinToString(", ")), style = scaled(14, color = Theme.soft))
                  Text(Words.Chrome.replaceUnpairs(name = old.name), style = scaled(14, color = Theme.soft))
                }
              }
              Text(
                Words.Chrome.confirm,
                style = scaled(17, FontWeight.SemiBold, if (busy) Theme.dim else Theme.amber),
                modifier = Modifier.heightIn(min = 48.dp).testTag(CHROME_CONFIRM).confirms().tapping(enabled = !busy) {
                  busy = true
                  scope.launch {
                    val done = confirm(p, offered.offer, name, replacing, moving)
                    busy = false
                    if (done) {
                      line = Words.Chrome.paired(name = ChromePairing.name(name, Words.Chrome.browserOn(browser = offered.offer.browser, system = offered.offer.os)))
                      pairing = null
                      name = ""
                      refresh()
                    }
                  }
                }.padding(vertical = 12.dp),
              )
            }
          }
          p != null -> Panel {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(Words.Chrome.codeTitle, style = scaled(17, FontWeight.SemiBold, Theme.ink))
              // Selectable, and copied with one tap: the code is typed on another device, so it's carried there on the clipboard
              // (the owner's ask, 22 Nov). As a key is: marked sensitive, and off the clipboard after its minute if still tafs's.
              androidx.compose.foundation.text.selection.SelectionContainer {
                Text(p.code, style = scaled(32, FontWeight.Bold, Theme.ink).copy(fontFamily = FontFamily.Monospace), modifier = Modifier.testTag(CHROME_CODE))
              }
              var copied by remember(p.code) { mutableStateOf(false) }
              val context = LocalContext.current
              Text(
                if (copied) Words.Common.copied else Words.Common.copy,
                style = scaled(15, FontWeight.SemiBold, Theme.amber),
                modifier = Modifier.heightIn(min = 44.dp).testTag(CHROME_CODE_COPY).tapping {
                  copyKeyForAMinute(context, p.code, label = CHROME_CODE_CLIP)
                  copied = true
                }.padding(top = 10.dp),
              )
              Text(Words.Chrome.codeBody, style = scaled(14, color = Theme.dim))
              Text(Words.Chrome.onlyYours, style = scaled(14, FontWeight.SemiBold, Theme.amberText))
              Text(Words.Chrome.profileNote, style = scaled(13, color = Theme.dim))
              Text(Words.Chrome.waiting, style = scaled(14, color = Theme.dim))
            }
          }
          else -> {
            val list = state.chromes.orEmpty()
            if (list.isEmpty()) Text(Words.Chrome.none, style = scaled(14, color = Theme.dim), modifier = Modifier.padding(horizontal = 4.dp))
            list.forEach { chrome ->
              Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(chrome.name, style = scaled(15, FontWeight.SemiBold, Theme.ink))
                    if (!chrome.browser.isNullOrBlank()) Text(Words.Chrome.browserOn(browser = chrome.browser, system = chrome.os.orEmpty()), style = scaled(13, color = Theme.dim))
                    // What it's used for ("Your Chrome" 4): the sites of the connections planque lists on it.
                    bot.tafs.app.kiff.chromeSites(chrome.connections).takeIf { it.isNotEmpty() }?.let { Text(Words.Chrome.usedFor(sites = it), style = scaled(13, color = Theme.soft)) }
                  }
                  Text(Words.Chrome.remove, style = scaled(15, FontWeight.SemiBold, Theme.no), modifier = Modifier.heightIn(min = 44.dp).confirms().tapping { removing = chrome }.padding(top = 8.dp))
                }
              }
            }
            Text(
              Words.Chrome.pair,
              style = scaled(15, FontWeight.SemiBold, if (busy) Theme.dim else Theme.amber),
              modifier = Modifier.heightIn(min = 44.dp).testTag(CHROME_PAIR).confirms().tapping(enabled = !busy) {
                busy = true
                line = null
                scope.launch {
                  pairing = open()
                  busy = false
                }
              }.padding(horizontal = 4.dp, vertical = 10.dp),
            )
          }
        }
        Text(Words.Chrome.footer, style = scaled(13, color = Theme.dim), modifier = Modifier.padding(horizontal = 4.dp))
      }
    }
  }
  removing?.let { chrome ->
    bot.tafs.app.drive.DriveAlert(
      onDismissRequest = { removing = null },
      title = { Text(Words.Chrome.removeNamed(name = chrome.name)) },
      // What unpairing ends ("Your Chrome" 3): the connections planque lists on it, which come back only by connecting again.
      text = bot.tafs.app.kiff.chromeSites(chrome.connections).takeIf { it.isNotEmpty() }?.let { ends -> { Text(Words.Chrome.unpairEnds(sites = ends)) } },
      confirmButton = { TextButton(onClick = { removing = null; scope.launch { remove(chrome) } }, modifier = Modifier.confirms()) { Text(Words.Chrome.remove, color = Theme.no) } },
      dismissButton = { TextButton({ removing = null }) { Text(Words.Common.cancel) } },
    )
  }
}

/** One answer to "Replaces": a paired Chrome, or a new one. Amber and ticked when picked. */
@Composable
private fun ReplaceRow(words: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(tag)
      .tapping(enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
      .semantics { this.selected = selected },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(if (selected) "◉" else "○", style = scaled(17, FontWeight.SemiBold, if (selected) Theme.amber else Theme.dim))
    Text(words, style = scaled(15, if (selected) FontWeight.SemiBold else FontWeight.Normal, Theme.ink), modifier = Modifier.weight(1f))
  }
}
