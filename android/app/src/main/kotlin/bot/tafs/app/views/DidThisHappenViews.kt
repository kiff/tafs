package bot.tafs.app.views

import bot.tafs.app.design.tapping
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.UnsureAnswer
import bot.tafs.app.kiff.UnsureCall
import bot.tafs.app.model.DidThisHappenWords

/**
 * A money call KIFF can't prove, in Needs you: what it was (KIFF's words, else its title and amount), why KIFF can't tell, and
 * the answers. With KIFF's context, it says which taf, when, on what and where to look, and offers KIFF's answers in its words:
 * Yes and No answer it, Not sure opens Coach on it ([onCheck]). [onAnswer] null: the answers aren't offered yet.
 */
@Composable
fun UnsureCallCard(
  call: UnsureCall,
  answering: Boolean,
  error: String? = null,
  onAnswer: ((UnsureCall, Boolean) -> Unit)?,
  onCheck: ((UnsureCall) -> Unit)? = null,
) {
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    Eyebrow(DidThisHappenWords.TITLE, Theme.amberText)
    if (call.words != null) {
      Text(call.words, style = scaled(15, color = Theme.ink))
    } else {
      Text(call.title, style = scaled(15, FontWeight.SemiBold, Theme.ink))
      (call.amountWords ?: call.money?.formatted)?.let { Text(it, style = scaled(20, FontWeight.Bold, Theme.ink, display = true)) }
    }
    // KIFF's context, each line as it sends it.
    listOfNotNull(call.taf?.name, call.whenWords).takeIf { it.isNotEmpty() }?.let {
      Text(it.joinToString(" · "), style = scaled(13, color = Theme.soft))
    }
    call.counterparty?.let { cp -> (cp.words ?: cp.id)?.let { listOf(cp.noun, it).filter(String::isNotBlank).joinToString(" ") } }
      ?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
    Text(call.why ?: DidThisHappenWords.why(call), style = scaled(13, color = Theme.soft))
    call.recordUrl?.takeIf { it.startsWith("https://") }?.let { url ->
      val uri = LocalUriHandler.current
      Text(
        DidThisHappenWords.record(call.vendor),
        style = scaled(13, FontWeight.SemiBold, Theme.amberText),
        modifier = Modifier.heightIn(min = 44.dp).tapping { runCatching { uri.openUri(url) } }.padding(vertical = 12.dp),
      )
    }
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
    if (onAnswer == null) return@Panel
    val known = call.answers.filter { it.answer in KNOWN && it.words.isNotBlank() }
    if (known.isEmpty()) {
      // An older KIFF: the app's two answers.
      Text(DidThisHappenWords.WHAT_NO_DOES, style = scaled(12, color = Theme.dim))
      ApprovalGate {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          OutlineButton(DidThisHappenWords.NO, { onAnswer(call, false) }, Modifier.weight(1f), enabled = !answering)
          OutlineButton(DidThisHappenWords.YES, { onAnswer(call, true) }, Modifier.weight(1f), enabled = !answering)
        }
      }
      return@Panel
    }
    // KIFF's answers, in its order and words, each with what follows from it. Not sure only where this app can open Coach.
    ApprovalGate {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        known.filter { it.answer != UnsureAnswer.NOT_SURE || onCheck != null }.forEach { a ->
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlineButton(a.words, {
              when (a.answer) {
                UnsureAnswer.YES -> onAnswer(call, true)
                UnsureAnswer.NO -> onAnswer(call, false)
                else -> onCheck?.invoke(call)
              }
            }, Modifier.fillMaxWidth(), color = if (a.answer == UnsureAnswer.NO) Theme.no else Theme.ink, enabled = !answering)
            if (a.detail.isNotBlank()) Text(a.detail, style = scaled(12, color = Theme.dim))
          }
        }
      }
    }
  }
}

private val KNOWN = setOf(UnsureAnswer.YES, UnsureAnswer.NO, UnsureAnswer.NOT_SURE)
