package bot.tafs.app.views

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.ChatExport
import bot.tafs.app.model.ExportResult
import bot.tafs.app.model.ExportWords
import java.io.File
import kotlinx.coroutines.launch

/**
 * Settings → Account → Export my chats (docs/data.md, Your own copy): tafs cloud's export, offered as two files through the
 * system's share sheet, where the person saves or sends them. "Preparing your chats…" while it comes; if it doesn't, why, and
 * Try again. [share] hands the files to the sheet; a test passes its own.
 */
@Composable
fun ExportChatsRow(onExport: suspend () -> ExportResult, share: ((ChatExport) -> Unit)? = null) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var preparing by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val offer = share ?: { export -> shareExport(context, export) }

  fun export() {
    if (preparing) return
    preparing = true
    error = null
    scope.launch {
      when (val r = onExport()) {
        is ExportResult.Ready -> offer(r.export)
        is ExportResult.NotExported -> error = ExportWords.notExported(r.why)
      }
      preparing = false
    }
  }

  Column {
    Text(
      if (preparing) ExportWords.PREPARING else ExportWords.ROW,
      style = scaled(16, color = if (preparing) Theme.dim else Theme.ink),
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !preparing) { export() }.padding(horizontal = 12.dp, vertical = 12.dp),
    )
    error?.let {
      Text(it, style = scaled(13, color = Theme.no), modifier = Modifier.padding(horizontal = 12.dp))
      Text(
        ExportWords.TRY_AGAIN,
        style = scaled(15, color = Theme.amberText),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = !preparing) { export() }.padding(horizontal = 12.dp, vertical = 10.dp),
      )
    }
    HorizontalDivider(color = Theme.line, modifier = Modifier.padding(start = 12.dp))
  }
}

/**
 * Writes the two files to the app's own cache and offers them through the share sheet, where Files, Drive or mail can take
 * them. They're replaced at each export and never leave the cache any other way.
 */
fun shareExport(context: Context, export: ChatExport) {
  val dir = File(context.cacheDir, "exports").apply { mkdirs() }
  val json = File(dir, ExportWords.JSON_FILE).apply { writeText(export.json) }
  val text = File(dir, ExportWords.TEXT_FILE).apply { writeText(export.text) }
  val uris = ArrayList<Uri>(listOf(json, text).map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
  val send = Intent(Intent.ACTION_SEND_MULTIPLE)
    .setType("*/*")
    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  // The grant reaches the app the person picks only through the clip.
  send.clipData = ClipData.newRawUri(ExportWords.JSON_FILE, uris[0]).apply { addItem(ClipData.Item(uris[1])) }
  context.startActivity(Intent.createChooser(send, ExportWords.ROW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
