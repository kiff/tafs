package bot.tafs.app.views

import bot.tafs.app.design.tapping
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.ReceiptsResult
import bot.tafs.app.app.RecordSearch
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.kiff.ReceiptsExport
import bot.tafs.app.kiff.RecordCase
import bot.tafs.app.kiff.RecordHit
import bot.tafs.app.model.Words
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the Record can do across the person's tafs (`GET /v1/record`, docs/android-app.md › The Record): search it, open one case, and
 * export receipts for an accountant. tafs cloud finds, words and decides; the app sends what's typed or tapped.
 */
class RecordActions(
  val loadHint: suspend () -> Unit = {},
  val search: suspend (String, Boolean) -> Unit = { _, _ -> },
  val openCase: suspend (String) -> Unit = {},
  val closeCase: () -> Unit = {},
  val export: suspend (String, String) -> ReceiptsResult = { _, _ -> ReceiptsResult.NotExported("") },
)

/** How long the box waits after the last key before it searches. */
internal const val RECORD_SEARCH_PAUSE_MS = 400L

/**
 * The Record's search box: tafs cloud's words as its placeholder. It searches as you type, [RECORD_SEARCH_PAUSE_MS] after the
 * last key, and at once on the keyboard's Search; an empty box shows the Record.
 */
@Composable
internal fun RecordSearchBox(hint: String?, onSearch: (String) -> Unit) {
  var query by rememberSaveable { mutableStateOf("") }
  // What was last searched, so a pause or a rotation doesn't send the same search twice.
  var sent by rememberSaveable { mutableStateOf("") }
  val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
  val search: (String) -> Unit = { q -> sent = q; onSearch(q) }
  LaunchedEffect(query) {
    if (query.trim() == sent.trim()) return@LaunchedEffect
    if (query.isNotBlank()) delay(RECORD_SEARCH_PAUSE_MS)
    // Search pressed during the pause already sent it.
    if (query.trim() == sent.trim()) return@LaunchedEffect
    search(query)
  }
  OutlinedTextField(
    query,
    driveTyped({ query = it }),
    Modifier.fillMaxWidth(),
    placeholder = { Text(hint ?: Words.Record.searchHint) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    // Search sends at once and puts the keyboard away, so the results show whole.
    keyboardActions = KeyboardActions(onSearch = { search(query); keyboard?.hide() }),
  )
}

/**
 * What a search found, in tafs cloud's groups, each call as a taf's Activity draws it, under its taf's name. A call about something
 * (its `about`) opens that case. Nothing found says so in tafs cloud's words; more pages come on More.
 */
@Composable
internal fun RecordResults(search: RecordSearch, state: AppState, actions: TafActions?, onCase: (String) -> Unit, onMore: () -> Unit, onConnectApps: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val pages = search.pages
    pages.forEach { page ->
      val groups = page.groups.ifEmpty { if (page.entries.isEmpty()) emptyList() else listOf(bot.tafs.app.kiff.RecordHitGroup("all", "", page.entries)) }
      groups.forEach { group ->
        if (group.title.isNotBlank()) Eyebrow(group.title)
        group.entries.forEach { hit -> RecordHitRow(hit, state, actions, onCase, onConnectApps) }
      }
    }
    if (pages.isNotEmpty() && pages.all { it.entries.isEmpty() && it.groups.all { g -> g.entries.isEmpty() } }) {
      pages.last().noneWords?.let { Text(it, style = scaled(14, color = Theme.soft)) }
    }
    search.error?.let { Text(it, style = scaled(13, color = Theme.no)) }
    if (search.loading) Text(Words.Common.loading, style = scaled(13, color = Theme.dim))
    else if (search.nextCursor != null) OutlineButton(Words.Common.more, onMore, Modifier.fillMaxWidth())
    else pages.lastOrNull()?.endWords?.let { Text(it, style = scaled(13, color = Theme.dim)) }
  }
}

/** One call found: its taf's name, the call as its taf's Activity draws it, and what it's about, which opens the case. */
@Composable
internal fun RecordHitRow(hit: RecordHit, state: AppState, actions: TafActions?, onCase: (String) -> Unit, onConnectApps: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Eyebrow(hit.taf.name)
    val taf = state.tafByCloudId(hit.taf.id)
    if (taf != null && actions != null) {
      SourceActivityRow(hit.asActivity, taf, state, actions, onConnectApps)
    } else {
      // A taf the phone doesn't list (one they oversee only): the call as tafs cloud words it, without its next step.
      Panel {
        Text(hit.title, style = scaled(15, FontWeight.SemiBold, Theme.ink))
        Text(hit.detail, style = scaled(13, color = Theme.soft))
        hit.amountText?.let { Text(it, style = scaled(14, FontWeight.Medium, Theme.ink)) }
        Text(hit.time, style = scaled(12, color = Theme.dim))
      }
    }
    hit.about?.takeIf { it.isNotBlank() }?.let { about ->
      Text(
        "$about ›",
        style = scaled(14, FontWeight.SemiBold, Theme.amberText),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).tapping { onCase(about) }.padding(vertical = 10.dp),
      )
    }
  }
}

/**
 * One case: every call about the same thing, oldest first, each as its taf's Activity draws it (its receipt, and its own next step).
 * The step that didn't go through is named first, in tafs cloud's words; its retry is that step's own next, under its taf's Card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordCaseSheet(case: RecordCase, error: String?, state: AppState, actions: TafActions?, onClose: () -> Unit, onConnectApps: () -> Unit) {
  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(case.about, style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
      case.failed?.words?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.no)) }
      error?.let { Text(it, style = scaled(14, color = Theme.no)) }
      if (case.steps.isEmpty() && error == null) Text(Words.Common.loading, style = scaled(13, color = Theme.dim))
      case.steps.forEach { step -> RecordHitRow(step.copy(about = null), state, actions, onCase = {}, onConnectApps = onConnectApps) }
    }
  }
}

/** The day range offered for an export, inside tafs cloud's 92 days. */
internal val EXPORT_DAYS = listOf(7, 30, 90)

/**
 * Export receipts, for someone who sees every taf's Record: a range of days, counted in the account's zone as tafs cloud counts
 * them (not the phone's), then tafs cloud's export offered as two files through the share sheet (KIFF's receipts as tafs cloud
 * passed them, and its CSV). [share] hands the files on; a test passes its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportReceiptsRow(onExport: suspend (String, String) -> ReceiptsResult, today: () -> LocalDate = { LocalDate.now(bot.tafs.app.model.zone) }, share: ((ReceiptsExport) -> Unit)? = null) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var open by remember { mutableStateOf(false) }
  var preparing by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var missing by remember { mutableStateOf(0) }
  val offer = share ?: { export -> shareReceipts(context, export) }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      if (preparing) Words.Record.exportPreparing else Words.Record.exportReceipts,
      style = scaled(15, FontWeight.SemiBold, if (preparing) Theme.dim else Theme.amberText),
      modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).tapping(enabled = !preparing) { open = !open }.padding(vertical = 10.dp),
    )
    if (open && !preparing) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EXPORT_DAYS.forEach { days ->
          OutlineButton(Words.Record.exportLastDays(count = days), {
            val to = today()
            val from = to.minusDays(days.toLong() - 1)
            preparing = true; error = null; missing = 0
            scope.launch {
              try {
                when (val r = onExport(from.toString(), to.toString())) {
                  is ReceiptsResult.Ready -> try {
                    offer(r.export); missing = r.export.missing; open = false
                  } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                  } catch (_: Exception) {
                    error = Words.Record.exportNotExported(why = bot.tafs.app.model.ExportWords.NOT_OFFERED)
                  }
                  is ReceiptsResult.NotExported -> error = Words.Record.exportNotExported(why = r.why)
                }
              } finally {
                preparing = false
              }
            }
          })
        }
      }
    }
    if (missing > 0) Text(Words.Record.exportMissing(count = missing), style = scaled(13, color = Theme.soft))
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}

/** Writes the two files to the app's own cache and offers them through the share sheet; they're replaced at each export. */
fun shareReceipts(context: Context, export: ReceiptsExport) {
  val dir = File(context.cacheDir, "exports").apply { mkdirs() }
  val stem = "receipts-${export.from}-to-${export.to}"
  val json = File(dir, "$stem.json").apply { writeText(export.receiptsJson) }
  val csv = File(dir, "$stem.csv").apply { writeText(export.csv) }
  val uris = ArrayList<Uri>(listOf(json, csv).map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
  val send = Intent(Intent.ACTION_SEND_MULTIPLE)
    .setType("*/*")
    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  send.clipData = ClipData.newRawUri(json.name, uris[0]).apply { addItem(ClipData.Item(uris[1])) }
  context.startActivity(Intent.createChooser(send, Words.Record.exportReceipts).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
