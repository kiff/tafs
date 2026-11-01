package bot.tafs.app.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.ReportResult
import bot.tafs.app.model.ReportWords
import kotlinx.coroutines.launch

/**
 * An answer Coach or a taf wrote, which a long press can report (docs/chat.md): Report this answer opens the sheet. [onReport]
 * sends the reason and the note; null where the answer can't be reported (the sample's, or one tafs cloud has no id for yet).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReportableAnswer(onReport: (suspend (String, String?) -> ReportResult)?, content: @Composable () -> Unit) {
  if (onReport == null) {
    content()
    return
  }
  var menu by remember { mutableStateOf(false) }
  var reporting by remember { mutableStateOf(false) }
  Box(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true })) {
    content()
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
      DropdownMenuItem(text = { Text(ReportWords.MENU) }, onClick = { menu = false; reporting = true })
    }
  }
  if (reporting) ReportSheet(onReport, onClose = { reporting = false })
}

/** Why the answer is reported, an optional note, and Send. A report that didn't go keeps the sheet open, to send again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(onReport: suspend (String, String?) -> ReportResult, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  var reason by remember { mutableStateOf<String?>(null) }
  var note by remember { mutableStateOf("") }
  var sending by remember { mutableStateOf(false) }
  var sent by remember { mutableStateOf<ReportResult.Sent?>(null) }
  var error by remember { mutableStateOf<String?>(null) }

  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(ReportWords.MENU, style = scaled(22, FontWeight.Bold, Theme.ink, display = true))
      val done = sent
      if (done != null) {
        Text(if (done.first) ReportWords.THANKS else ReportWords.ALREADY, style = scaled(15, color = Theme.ink))
        PrimaryButton("Done", onClose)
        return@Column
      }
      ReportWords.REASONS.forEach { (value, words) ->
        val chosen = reason == value
        Row(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, if (chosen) Theme.amber else Theme.line), RoundedCornerShape(14.dp))
            .clickable(enabled = !sending, role = Role.RadioButton) { reason = value }
            .semantics { selected = chosen }
            .padding(horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Box(Modifier.size(18.dp).clip(CircleShape).border(BorderStroke(2.dp, if (chosen) Theme.amber else Theme.dim), CircleShape), contentAlignment = Alignment.Center) {
            if (chosen) Box(Modifier.size(8.dp).clip(CircleShape).background(Theme.amber))
          }
          Text(words, style = scaled(16, color = Theme.ink))
        }
      }
      val shape = RoundedCornerShape(14.dp)
      BasicTextField(
        value = note,
        onValueChange = { note = it.take(ReportWords.NOTE_MAX) },
        enabled = !sending,
        textStyle = scaled(15, color = Theme.ink),
        cursorBrush = SolidColor(Theme.amber),
        minLines = 3,
        modifier = Modifier.fillMaxWidth().clip(shape).background(Theme.panel).border(BorderStroke(1.dp, Theme.line), shape),
        decorationBox = { field ->
          Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (note.isEmpty()) Text("Add a note (optional)", style = scaled(15, color = Theme.dim))
            field()
          }
        },
      )
      error?.let { Text(it, style = scaled(13, color = Theme.no)) }
      PrimaryButton(
        if (sending) "Sending…" else "Send",
        {
          val chosen = reason ?: return@PrimaryButton
          sending = true
          error = null
          scope.launch {
            when (val r = onReport(chosen, note.ifBlank { null })) {
              is ReportResult.Sent -> sent = r
              is ReportResult.NotSent -> error = ReportWords.notSent(r.why)
            }
            sending = false
          }
        },
        enabled = reason != null && !sending,
      )
      OutlineButton("Cancel", onClose, Modifier.fillMaxWidth(), color = Theme.soft, enabled = !sending)
    }
  }
}
