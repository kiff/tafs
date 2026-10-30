package bot.tafs.app.views

import bot.tafs.app.model.ErrorWords
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Dot
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.ReceiptCheck
import bot.tafs.app.kiff.ReceiptSteps
import bot.tafs.app.model.ReceiptWords
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.shownDate
import bot.tafs.app.model.zone
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** Reads KIFF's receipt for a Record line, by its trace id, as KIFF wrote it; null signed out and in the sample. */
val LocalReceipt = staticCompositionLocalOf<(suspend (String) -> ByteArray)?> { null }

private fun seconds(at: Instant): String = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).format(at.atZone(zone))

/**
 * One line of the Record, as a sheet (kiff/tafs#167, as iOS's `ReceiptView`): the facts, what happened, and KIFF's receipt,
 * checked on this phone (`ReceiptCheck`). Nothing opens outside the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSheet(entry: RecordEntry, state: AppState, onClose: () -> Unit) {
  val load = LocalReceipt.current
  val scope = rememberCoroutineScope()
  // KIFF's receipt as it wrote it, and the phone's check of it.
  var raw by remember { mutableStateOf<ByteArray?>(null) }
  var check by remember { mutableStateOf<ReceiptCheck?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  val trace = entry.receipt?.traceId

  suspend fun loadReceipt() {
    if (trace == null || load == null) return
    problem = null
    try {
      val data = load(trace)
      raw = data
      check = ReceiptCheck.check(data)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      problem = "KIFF's receipt couldn't be loaded. ${ErrorWords.of(e)}"
    }
  }
  LaunchedEffect(trace) { if (state.mode == Mode.kiff) loadReceipt() }
  val steps = raw?.let(ReceiptSteps::of).orEmpty()

  ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Dot(entry.kind.color, 10.dp)
        Text(ReceiptWords.state(entry), style = scaled(14, FontWeight.SemiBold, entry.kind.color), modifier = Modifier.padding(start = 8.dp).weight(1f))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 10.dp))
      }
      Text(entry.titleWithAmount, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
      Panel {
        Fact("Taf", state.taf(entry.tafId)?.name ?: "–")
        Fact("When", "${shownDate(entry.at)}, ${seconds(entry.at)}")
        entry.onValue?.let { Fact("On", "${(entry.onArg ?: "").replace("_", " ")} $it".trim()) }
        Fact("Outcome", ReceiptWords.outcome(entry), bold = true)
        // KIFF's words, never its codes.
        entry.reason?.let { Fact("Why", it) }
        Fact("Decided by", ReceiptWords.decidedBy(entry, state.mode == Mode.kiff))
      }
      if (steps.isNotEmpty()) {
        Eyebrow("What happened")
        Column {
          steps.forEach { step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Dot(Theme.dim, 8.dp, Modifier.padding(top = 6.dp))
              Column(Modifier.padding(bottom = 10.dp)) {
                Text(step.words.replaceFirstChar { it.uppercase() }, style = scaled(14, color = Theme.ink))
                step.at?.let { Text(seconds(it), style = scaled(12, color = Theme.dim, mono = true)) }
              }
            }
          }
        }
      }
      entry.signature?.let { signature ->
        Panel(stroke = Theme.ok.copy(alpha = 0.4f)) {
          Text("✓ Signed on this phone", style = scaled(14, FontWeight.SemiBold, Theme.ok))
          Text("Your answer was signed with a key that never leaves this phone.", style = scaled(13, color = Theme.soft))
          Text(signature, style = scaled(11, color = Theme.dim, mono = true), maxLines = 3)
        }
      }
      if (entry.receipt != null) {
        val mismatch = if (check is ReceiptCheck.Verified) ReceiptSteps.mismatch(entry, steps) else null
        when (val c = check) {
          is ReceiptCheck.Verified -> if (mismatch != null) Failed(mismatch, entry, raw, steps, null) else Panel(stroke = Theme.ok.copy(alpha = 0.4f)) {
            Text("✓ KIFF's receipt · checked on this phone", style = scaled(14, FontWeight.SemiBold, Theme.ok))
            Text(
              "KIFF signed this when it decided. The signature matches KIFF's address and none of its steps has changed since.",
              style = scaled(13, color = Theme.soft),
            )
            Facts(entry, raw)
            FullReceipt(entry, raw, steps, c.signer)
            ReceiptButtons(raw, share = true)
          }
          ReceiptCheck.NotSignedYet -> Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Dot(Theme.amber, 9.dp)
              Text("KIFF's receipt · not signed yet", style = scaled(14, FontWeight.SemiBold, Theme.ink))
            }
            Text("KIFF signs receipts within a minute of deciding. Check again shortly.", style = scaled(13, color = Theme.soft))
            if (java.time.Duration.between(entry.at, Instant.now()).toMinutes() > 10) {
              Text("If this stays, tell us the receipt id below.", style = scaled(13, color = Theme.soft))
              Text(trace.orEmpty(), style = scaled(11, color = Theme.dim, mono = true))
            }
            OutlineButton("Check again", { scope.launch { loadReceipt() } })
          }
          is ReceiptCheck.Failed -> Failed("This receipt was changed after KIFF signed it, or it wasn't signed by KIFF. Don't rely on this line.", entry, raw, steps, null)
          null -> Panel {
            val p = problem
            if (p != null) {
              Text(p, style = scaled(13, color = Theme.no))
              OutlineButton("Try again", { scope.launch { loadReceipt() } })
            } else if (state.mode == Mode.kiff) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Theme.amber, strokeWidth = 2.dp)
                Text("Checking KIFF's receipt on this phone…", style = scaled(13, color = Theme.soft))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Fact(name: String, value: String, bold: Boolean = false) {
  Row(Modifier.fillMaxWidth()) {
    Text(name, style = scaled(13, color = Theme.dim), modifier = Modifier.width(96.dp))
    Text(value, style = scaled(14, if (bold) FontWeight.SemiBold else FontWeight.Normal, Theme.ink), modifier = Modifier.weight(1f))
  }
}

@Composable
private fun Failed(why: String, entry: RecordEntry, raw: ByteArray?, steps: List<bot.tafs.app.kiff.ReceiptStep>, signer: String?) {
  Panel(stroke = Theme.no.copy(alpha = 0.5f)) {
    Text("✕ KIFF's receipt doesn't check out", style = scaled(14, FontWeight.SemiBold, Theme.no))
    Text(why, style = scaled(13, color = Theme.soft))
    Facts(entry, raw)
    FullReceipt(entry, raw, steps, signer)
    ReceiptButtons(raw, share = false)
  }
}

/** The decision and when it was issued; the receipt's id and signer are under "Show the full receipt": ids aren't for reading at a glance. */
@Composable
private fun Facts(entry: RecordEntry, raw: ByteArray?) {
  val issued = raw?.let { field(it, "issued_at") }?.let { runCatching { Instant.parse(it) }.getOrNull() }
  val lines = listOfNotNull(ReceiptWords.decision(entry), issued?.let { "issued ${shownDate(it)}, ${seconds(it)}" })
  Text(lines.joinToString(" · "), style = scaled(11, color = Theme.dim, mono = true))
}

/** Every step KIFF signed, with its hash, folded. */
@Composable
private fun FullReceipt(entry: RecordEntry, raw: ByteArray?, steps: List<bot.tafs.app.kiff.ReceiptStep>, signer: String?) {
  if (steps.isEmpty()) return
  var open by remember { mutableStateOf(false) }
  Text(
    if (open) "Hide the full receipt" else "Show the full receipt",
    style = scaled(13, FontWeight.SemiBold, Theme.amberText),
    modifier = Modifier.heightIn(min = 44.dp).clickable { open = !open }.padding(top = 12.dp),
  )
  if (!open) return
  val style = scaled(11, color = Theme.dim, mono = true)
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(listOfNotNull("receipt ${entry.receipt?.traceId.orEmpty()}", signer?.let { "signer ${it.take(6)}…${it.takeLast(4)}" }).joinToString(" · "), style = style)
    steps.forEachIndexed { i, step ->
      Text("${i + 1}  ${step.words} · ${step.at?.let(::seconds).orEmpty()}\n   hash ${step.hash}", style = style)
    }
    raw?.let { attestation(it, "signature") }?.let { sig ->
      Text("✎  trace ${entry.receipt?.traceId.orEmpty()} · secp256k1 + keccak256\n   sig 0x${sig.take(4)}…${sig.takeLast(4)}", style = style)
    }
  }
}

@Composable
private fun ReceiptButtons(raw: ByteArray?, share: Boolean) {
  val text = raw?.let { String(it) } ?: return
  val clipboard = LocalClipboard.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    OutlineButton("Copy receipt", { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("KIFF receipt", text))) } }, Modifier.weight(1f))
    if (share) OutlineButton("Share", {
      val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
      context.startActivity(Intent.createChooser(send, "KIFF receipt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }, Modifier.weight(1f))
  }
}

private fun field(raw: ByteArray, key: String): String? = runCatching {
  (bot.tafs.app.kiff.KiffClient.json.parseToJsonElement(String(raw)) as kotlinx.serialization.json.JsonObject)[key]
    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
}.getOrNull()

private fun attestation(raw: ByteArray, key: String): String? = runCatching {
  val r = bot.tafs.app.kiff.KiffClient.json.parseToJsonElement(String(raw)) as kotlinx.serialization.json.JsonObject
  ((r["attestation"] as? kotlinx.serialization.json.JsonObject)?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
}.getOrNull()
