package bot.tafs.app.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.KeyReceipt
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.drive.confirms
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.rememberLogo
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.SecureSheet
import bot.tafs.app.model.SecureSheetWords

/** What the secure sheet can do: give the key (behind the fingerprint), disconnect from the receipt, close. */
class SecureKeyActions(
  val give: (Grant, String) -> Unit,
  val disconnect: (String) -> Unit,
  val close: () -> Unit,
)

/**
 * The secure connection sheet for an app's key (docs/secure-connection.md): every word is tafs cloud's, by the grant. The key is
 * pasted, never typed, so there is no keyboard and no field; only its last 4 show, and the clipboard is cleared at once. The
 * technical lines (and the connection server's own words, which its digest covers) sit under a collapsed Details. One button
 * gives the key behind the fingerprint; then the receipt.
 */
@Composable
fun SecureKeyContent(
  state: AppState,
  grant: Grant,
  sheet: SecureSheet,
  profileWords: @Composable () -> Unit,
  ready: Boolean,
  receipt: KeyReceipt?,
  actions: SecureKeyActions,
) {
  // Kept only while the sheet is open, never saved: it goes to the connection server once.
  var key by remember(grant.id) { mutableStateOf("") }
  var detailsOpen by remember(grant.id) { mutableStateOf(false) }
  val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
  val busy = state.connecting == grant.id

  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    rememberLogo(sheet.vendor.logo)?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
    Lock(Modifier.size(20.dp))
    Text(sheet.title, style = scaled(22, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.weight(1f))
  }

  if (receipt != null && receipt.grant == grant.id) {
    Text(SecureSheetWords.receipt(sheet, receipt.last4), style = scaled(16, FontWeight.SemiBold, Theme.ink))
    sheet.receipt?.disconnect?.takeIf { it.isNotBlank() }?.let { words ->
      var confirming by remember(grant.id) { mutableStateOf(false) }
      Text(
        words,
        style = scaled(15, FontWeight.SemiBold, Theme.no),
        modifier = Modifier.heightIn(min = 44.dp).clickable { confirming = true }.padding(top = 10.dp),
      )
      // Asked first, naming the one taf it ends, as from Connect apps' list; only then the connection goes.
      if (confirming) {
        val taf = ConnectAppsWords.tafOf(state, grant)
        val alsoStops = ConnectAppsWords.alsoStops(state, grant)
        bot.tafs.app.drive.DriveAlert(
          onDismissRequest = { confirming = false },
          containerColor = Theme.panel,
          title = { Text(bot.tafs.app.model.ConnectionWords.disconnectTitle(grant, taf, alsoStops)) },
          text = { Text(bot.tafs.app.model.ConnectionWords.disconnectBody(grant, taf, alsoStops)) },
          confirmButton = {
            androidx.compose.material3.TextButton({ confirming = false; actions.disconnect(grant.id) }, modifier = Modifier.confirms()) { Text(ConnectAppsWords.DISCONNECT, color = Theme.no) }
          },
          dismissButton = { androidx.compose.material3.TextButton({ confirming = false }) { Text(bot.tafs.app.model.Words.Common.cancel, color = Theme.soft) } },
        )
      }
    }
    Text(
      bot.tafs.app.model.Words.Common.done,
      style = scaled(15, FontWeight.SemiBold, Theme.dim),
      modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = actions.close).padding(top = 10.dp),
    )
    return
  }

  sheet.lines.forEach { line -> Text(line.text, style = scaled(15, color = Theme.ink)) }

  // Paste the key: a button, never a typing field. The clipboard, which other apps can read, is cleared as soon as it's read.
  sheet.paste.what?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
  OutlineButton(
    sheet.paste.button,
    {
      val pasted = runCatching { clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull()
      if (!pasted.isNullOrBlank()) {
        key = SecureSheetWords.cleaned(pasted)
        runCatching { clipboard?.clearPrimaryClip() }
      }
    },
    Modifier.fillMaxWidth(),
    enabled = !busy,
  )
  if (key.isNotEmpty()) {
    Text(SecureSheetWords.pasted(sheet, key), style = scaled(15, FontWeight.SemiBold, Theme.ink, mono = true))
    sheet.paste.cleared?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(12, color = Theme.dim)) }
    // What to do now, in tafs cloud's words: Connect is right below.
    sheet.paste.next?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(14, FontWeight.SemiBold, Theme.amberText)) }
  }

  // Its Card, when the setup has one drafted: tafs cloud's title over KIFF's own lines (the one-signature step, when it ships).

  state.coachNotes[bot.tafs.app.model.ConnectWords.cardKey(grant.id)]?.let { Text(it, style = scaled(13, color = Theme.no)) }

  // Connect, right under the pasted key. The first line is checked against the grant this phone signs: a "read only" that
  // doesn't hold leaves nothing to sign.
  val holds = grant.digestMatches && SecureSheetWords.firstLineHolds(sheet, grant)
  if (!holds) {
    Text(bot.tafs.app.model.CoachWords.DIGEST_MISMATCH, style = scaled(13, color = Theme.no))
  } else {
    ApprovalGate {
      PrimaryButton(
        if (busy) state.connectStep ?: bot.tafs.app.model.Words.Approval.approving else sheet.button.ifBlank { bot.tafs.app.model.ConnectWords.giveKeyWith(LocalBiometry.current) },
        { actions.give(grant, key) },
        enabled = !busy && ready && key.isNotBlank(),
      )
    }
  }

  // Details, collapsed: the technical lines and the connection server's own words, verbatim.
  sheet.details?.let { details ->
    Text(
      (if (detailsOpen) "▾ " else "▸ ") + details.title,
      style = scaled(14, FontWeight.SemiBold, Theme.soft),
      modifier = Modifier.heightIn(min = 44.dp).clickable { detailsOpen = !detailsOpen }.padding(top = 10.dp),
    )
    if (detailsOpen) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        details.lines.forEach { d ->
          Column {
            Text(d.label, style = scaled(12, color = Theme.dim))
            Text(d.value, style = scaled(13, color = Theme.soft, mono = true))
          }
        }
        Text(grant.text, style = scaled(13, color = Theme.soft))
        profileWords()
      }
    }
  }

  if (holds) sheet.legal?.let { LegalLine(it) }
  Text(
    bot.tafs.app.model.ConnectWords.CANCEL,
    style = scaled(15, FontWeight.SemiBold, Theme.dim),
    modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = actions.close).padding(top = 10.dp),
  )
}

/** The legal line, its two links in place of `{terms}` and `{privacy}`, each opening in the browser. */
@Composable
private fun LegalLine(legal: bot.tafs.app.kiff.SecureLegal) {
  val text = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = Theme.amber, textDecoration = TextDecoration.Underline))
    var rest = legal.text
    while (rest.isNotEmpty()) {
      val at = listOf("{terms}", "{privacy}").map { it to rest.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
      if (at == null) { append(rest); break }
      append(rest.substring(0, at.second))
      val link = if (at.first == "{terms}") legal.terms else legal.privacy
      if (link != null && link.url.startsWith("https://")) withLink(LinkAnnotation.Url(link.url, style)) { append(link.words) } else append(link?.words.orEmpty())
      rest = rest.substring(at.second + at.first.length)
    }
  }
  Text(text, style = scaled(12, color = Theme.dim))
}

/** A small lock, drawn: the sheet's mark that this step is protected. */
@Composable
private fun Lock(modifier: Modifier) {
  val color = Theme.amber
  Canvas(modifier.semantics { contentDescription = "" }) {
    val w = size.width
    val h = size.height
    val stroke = w * 0.12f
    drawArc(color, 180f, 180f, false, Offset(w * 0.25f, h * 0.08f), Size(w * 0.5f, h * 0.55f), style = Stroke(stroke))
    drawRoundRect(color, Offset(w * 0.12f, h * 0.42f), Size(w * 0.76f, h * 0.52f), CornerRadius(w * 0.12f))
  }
}
