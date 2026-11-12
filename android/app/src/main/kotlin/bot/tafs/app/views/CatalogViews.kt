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
import bot.tafs.app.catalog.CatalogOperator
import bot.tafs.app.catalog.CatalogRevision
import bot.tafs.app.catalog.CatalogState
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.model.Words
import kotlinx.coroutines.launch

/** What the operator sheet can do: enrol, open a revision by its id, publish or retire it, close. */
class CatalogActions(
  val enrol: (String) -> Boolean,
  val open: suspend (String) -> Boolean,
  val act: suspend (String) -> Boolean,
  val close: () -> Unit,
)

/**
 * The catalog operator's sheet (docs/android-app.md, "Catalog operator"). Enrolling: the operator id, typed once, then this
 * phone's catalog key to send. Then one revision by its id: its state, each tool as the connection server reads it (read or
 * write, and each argument that's fixed, with its value), and Publish or Retire, only when the phone's own digest of the entry
 * is the server's. Every refusal is said in the server's words.
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
        sheet.revision?.let { Revision(it, sheet.matches, sheet.busy) { action -> scope.launch { actions.act(action) } } }
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
  Text(Words.Catalog.enrolTitle, style = scaled(22, FontWeight.Bold, Theme.ink, display = true))
  Text(Words.Catalog.enrolText, style = scaled(15, color = Theme.soft))
  val key = state.publicKey
  if (key == null || state.operator.isNullOrBlank()) {
    var operator by remember { mutableStateOf(state.operator.orEmpty()) }
    OutlinedTextField(operator, driveTyped({ operator = it }), Modifier.fillMaxWidth(), placeholder = { Text(Words.Catalog.operatorId) }, singleLine = true)
    PrimaryButton(Words.Catalog.makeKey, { actions.enrol(operator) }, Modifier.fillMaxWidth(), enabled = operator.isNotBlank())
  } else {
    Text(Words.Catalog.operatorId, style = scaled(12, color = Theme.dim))
    Text(state.operator.orEmpty(), style = scaled(15, FontWeight.SemiBold, Theme.ink, mono = true))
    Text(Words.Catalog.publicKey, style = scaled(12, color = Theme.dim))
    Text(key, style = scaled(12, color = Theme.soft, mono = true))
    var copied by remember { mutableStateOf(false) }
    OutlineButton(
      if (copied) Words.Common.copied else Words.Common.copy,
      { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("catalog key", key))); copied = true } },
      Modifier.fillMaxWidth(),
    )
  }
}

/** One revision: its state, its tools as the server reads them, and the one action its state allows, when the digest holds. */
@Composable
private fun Revision(revision: CatalogRevision, matches: Boolean, busy: Boolean, act: (String) -> Unit) {
  Text(revision.id, style = scaled(17, FontWeight.SemiBold, Theme.ink, mono = true))
  Text(stateWords(revision.state), style = scaled(14, color = Theme.soft))
  revision.described?.let { d ->
    d.why?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.amberText)) }
    d.tools.forEach { t ->
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(t.tool, style = scaled(14, FontWeight.SemiBold, Theme.ink, mono = true))
        val how = listOfNotNull(t.method, t.path).joinToString(" ")
        if (t.why != null) {
          Text(t.why, style = scaled(13, color = Theme.amberText))
        } else {
          Text((if (t.readOnly) Words.Catalog.reads else Words.Catalog.writes) + if (how.isEmpty()) "" else " · $how", style = scaled(13, color = if (t.readOnly) Theme.soft else Theme.amberText))
        }
        t.finalArguments.forEach { f ->
          Text(Words.Catalog.fixedTo(argument = f.argument, value = f.undo?.toString() ?: "null"), style = scaled(12, color = Theme.dim, mono = true))
        }
      }
    }
  }
  Text(revision.digest, style = scaled(11, color = Theme.dim, mono = true))
  when {
    !matches -> Text(Words.Catalog.notWhatIsSigned, style = scaled(14, color = Theme.no))
    CatalogOperator.expectedAction(revision.state) == null -> Text(Words.Catalog.nothingToSign, style = scaled(13, color = Theme.soft))
    else -> {
      val action = CatalogOperator.expectedAction(revision.state)!!
      PrimaryButton(
        if (busy) Words.Approval.approving else if (action == CatalogOperator.PUBLISH) Words.Catalog.publish else Words.Catalog.retire,
        { act(action) },
        Modifier.fillMaxWidth().confirms(),
        enabled = !busy,
      )
    }
  }
}

private fun stateWords(state: String): String = when (state) {
  "draft" -> Words.Catalog.State.draft
  "reviewed" -> Words.Catalog.State.reviewed
  "published" -> Words.Catalog.State.published
  "retired" -> Words.Catalog.State.retired
  else -> state
}
