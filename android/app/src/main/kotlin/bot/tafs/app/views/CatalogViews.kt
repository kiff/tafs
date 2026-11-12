package bot.tafs.app.views

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.catalog.CatalogDisagreement
import bot.tafs.app.catalog.CatalogOperator
import bot.tafs.app.catalog.CatalogRevision
import bot.tafs.app.catalog.CatalogSheet
import bot.tafs.app.catalog.CatalogState
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.kiff.Jcs
import bot.tafs.app.model.ThisDevice
import bot.tafs.app.model.Words
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull

/** What the operator sheet can do: enrol, open a revision by its id, publish or retire it, close. */
class CatalogActions(
  val enrol: (String) -> Boolean,
  val open: suspend (String) -> Boolean,
  val act: suspend (String) -> Boolean,
  val close: () -> Unit,
)

/**
 * The catalog operator's sheet (docs/android-app.md, "Catalog operator"). Enrolling: the operator id, typed once, then this
 * phone's catalog key to send. Then one revision by its id: what the signed entry says (its app, where its key goes, each
 * tool's name and title), each tool as the connection server reads it (read or write, and what must be pinned), and Publish
 * or Retire, only when the phone's own digest of the entry is the server's and the two agree. Every refusal is said in the
 * server's words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogOperatorSheet(state: CatalogState, actions: CatalogActions) {
  val sheet = state.sheet ?: return
  val scope = rememberCoroutineScope()
  DriveSheet(
    onDismissRequest = actions.close,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(
      Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (sheet.enrolling) {
        Enrol(state, actions)
      } else {
        Text(Words.Catalog.openTitle, style = scaled(22, FontWeight.Bold, Theme.ink, display = true))
        var id by remember { mutableStateOf(sheet.id) }
        OutlinedTextField(
          id,
          driveTyped({ id = it }),
          Modifier.fillMaxWidth(),
          placeholder = { Text(Words.Catalog.revisionId) },
          singleLine = true,
          enabled = !sheet.busy,
        )
        OutlineButton(Words.Catalog.open, { scope.launch { actions.open(id) } }, Modifier.fillMaxWidth(), enabled = !sheet.busy && id.isNotBlank())
        sheet.revision?.let { Revision(it, sheet) { action -> scope.launch { actions.act(action) } } }
        sheet.done?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.ok)) }
      }
      sheet.error?.let { Text(it, style = scaled(13, color = Theme.no)) }
      Text(
        Words.Common.done,
        style = scaled(15, FontWeight.SemiBold, Theme.dim),
        modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = actions.close).padding(top = 10.dp),
      )
    }
  }
}

/** Enrolling: the operator id, typed once; then the catalog key's public half, to copy and send. */
@Composable
private fun Enrol(state: CatalogState, actions: CatalogActions) {
  val scope = rememberCoroutineScope()
  val clipboard = LocalClipboard.current
  Text(Words.Catalog.enrolTitle(ThisDevice.word), style = scaled(22, FontWeight.Bold, Theme.ink, display = true))
  Text(Words.Catalog.enrolText(ThisDevice.word, LocalBiometry.current), style = scaled(15, color = Theme.soft))
  val key = state.publicKey
  if (key == null || state.operator.isNullOrBlank()) {
    var operator by remember { mutableStateOf(state.operator.orEmpty()) }
    OutlinedTextField(operator, driveTyped({ operator = it }), Modifier.fillMaxWidth(), placeholder = { Text(Words.Catalog.operatorId) }, singleLine = true)
    PrimaryButton(Words.Catalog.makeKey, { actions.enrol(operator) }, Modifier.fillMaxWidth(), enabled = operator.isNotBlank())
  } else {
    Text(Words.Catalog.operatorId, style = scaled(12, color = Theme.dim))
    Text(state.operator.orEmpty(), style = scaled(15, FontWeight.SemiBold, Theme.ink, mono = true))
    Text(Words.Catalog.publicKey(ThisDevice.word), style = scaled(12, color = Theme.dim))
    Text(key, style = scaled(12, color = Theme.soft, mono = true))
    var copied by remember { mutableStateOf(false) }
    OutlineButton(
      if (copied) Words.Common.copied else Words.Common.copy,
      { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("catalog key", key))); copied = true } },
      Modifier.fillMaxWidth(),
    )
  }
}

/**
 * One revision. What it is, where its key goes and each tool's name and title come from the signed entry, so they're what the
 * digest covers; read or write, the method and path, and what must be pinned come from the server's reading of the entry's
 * description. Where the two don't agree, it's said in amber, and there's nothing to sign.
 */
@Composable
private fun Revision(revision: CatalogRevision, sheet: CatalogSheet, act: (String) -> Unit) {
  val entry = sheet.entry
  val described = revision.described
  entry?.let { e ->
    if (e.vendor.isNotBlank()) Text(e.vendor, style = scaled(17, FontWeight.SemiBold, Theme.ink))
    if (e.title.isNotBlank()) Text(e.title, style = scaled(15, color = Theme.soft))
  }
  Text(revision.id, style = scaled(14, FontWeight.SemiBold, Theme.ink, mono = true))
  Text(stateWords(revision.state), style = scaled(14, color = Theme.soft))
  entry?.let { e ->
    e.base?.let { Text(Words.Catalog.keySentTo(base = it), style = scaled(15, FontWeight.SemiBold, Theme.ink)) }
    e.authName?.let { name ->
      Text(
        when (e.authIn) {
          "header" -> Words.Catalog.keyInHeader(name = name)
          "query" -> Words.Catalog.keyInQuery(name = name)
          else -> "${e.authIn.orEmpty()} $name".trim()
        },
        style = scaled(13, color = Theme.soft, mono = true),
      )
    }
    e.server?.let { Text(Words.Catalog.connectsTo(server = it), style = scaled(15, FontWeight.SemiBold, Theme.ink)) }
    e.descriptionUrl?.let { Text(Words.Catalog.descriptionAt(url = it, sha = e.descriptionSha256.orEmpty()), style = scaled(11, color = Theme.dim, mono = true)) }
  }
  described?.why?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.amberText)) }
  val read = described?.tools.orEmpty().associateBy { it.tool }
  entry?.tools?.forEach { t ->
    val d = read[t.tool]
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(t.title ?: t.name ?: t.tool, style = scaled(14, FontWeight.SemiBold, Theme.ink))
      Text(listOfNotNull(t.name, t.tool).distinct().joinToString(" · "), style = scaled(12, color = Theme.dim, mono = true))
      if (d != null) {
        val how = listOfNotNull(d.method, d.path).joinToString(" ")
        if (d.why != null) {
          Text(d.why, style = scaled(13, color = Theme.amberText))
        } else {
          Text((if (d.readOnly) Words.Catalog.reads else Words.Catalog.writes) + if (how.isEmpty()) "" else " · $how", style = scaled(13, color = if (d.readOnly) Theme.soft else Theme.amberText))
        }
        d.finalArguments.forEach { f ->
          if (CatalogOperator.pinned(t, f.argument, f.undo)) {
            Text(Words.Catalog.fixedTo(argument = f.argument, value = Jcs.canonical(f.undo ?: JsonNull)), style = scaled(12, color = Theme.dim, mono = true))
          } else {
            Text(Words.Catalog.leftOpen(argument = f.argument, value = Jcs.canonical(f.undo ?: JsonNull)), style = scaled(12, color = Theme.amberText, mono = true))
          }
        }
      }
      // What else the entry pins, beyond what keeps a write undoable: drawn as the entry says it.
      val finals = d?.finalArguments.orEmpty().map { it.argument }.toSet()
      t.fixed.filterKeys { it !in finals }.forEach { (argument, value) ->
        Text(Words.Catalog.fixedTo(argument = argument, value = Jcs.canonical(value)), style = scaled(12, color = Theme.dim, mono = true))
      }
    }
  }
  sheet.disagreements.forEach { x ->
    when (x) {
      is CatalogDisagreement.NotInEntry -> Words.Catalog.notInEntry(tool = x.tool)
      is CatalogDisagreement.NotDescribed -> Words.Catalog.notDescribed(tool = x.tool)
      is CatalogDisagreement.LabelDiffers -> Words.Catalog.labelDiffers(tool = x.tool)
      else -> null
    }?.let { Text(it, style = scaled(13, color = Theme.amberText)) }
  }
  Text(revision.digest, style = scaled(11, color = Theme.dim, mono = true))
  val action = CatalogOperator.expectedAction(revision.state)
  when {
    !sheet.matches -> Text(Words.Catalog.notWhatIsSigned, style = scaled(14, color = Theme.no))
    sheet.disagreements.isNotEmpty() -> Text(Words.Catalog.doesNotAgree, style = scaled(14, color = Theme.no))
    action == null -> Text(Words.Catalog.nothingToSign, style = scaled(13, color = Theme.soft))
    else -> PrimaryButton(
      if (sheet.busy) Words.Approval.approving else if (action == CatalogOperator.PUBLISH) Words.Catalog.publish else Words.Catalog.retire,
      { act(action) },
      Modifier.fillMaxWidth().confirms(),
      enabled = !sheet.busy,
    )
  }
}

private fun stateWords(state: String): String = when (state) {
  "draft" -> Words.Catalog.State.draft
  "reviewed" -> Words.Catalog.State.reviewed
  "published" -> Words.Catalog.State.published
  "retired" -> Words.Catalog.State.retired
  else -> state
}
