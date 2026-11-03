package bot.tafs.app.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.UnsureCall
import bot.tafs.app.model.DidThisHappenWords

/**
 * A money call KIFF can't prove, in Needs you: what it was (KIFF's words, else its title and amount), why KIFF can't tell, and
 * No or Yes, it did. [onAnswer] null: the answers aren't offered yet.
 */
@Composable
fun UnsureCallCard(call: UnsureCall, answering: Boolean, error: String? = null, onAnswer: ((UnsureCall, Boolean) -> Unit)?) {
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    Eyebrow(DidThisHappenWords.TITLE, Theme.amberText)
    if (call.words != null) {
      Text(call.words, style = scaled(15, color = Theme.ink))
    } else {
      Text(call.title, style = scaled(15, FontWeight.SemiBold, Theme.ink))
      call.money?.let { Text(it.formatted, style = scaled(20, FontWeight.Bold, Theme.ink, display = true)) }
    }
    Text(DidThisHappenWords.why(call), style = scaled(13, color = Theme.soft))
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
    if (onAnswer != null) {
      Text(DidThisHappenWords.WHAT_NO_DOES, style = scaled(12, color = Theme.dim))
      ApprovalGate {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          OutlineButton(DidThisHappenWords.NO, { onAnswer(call, false) }, Modifier.weight(1f), enabled = !answering)
          OutlineButton(DidThisHappenWords.YES, { onAnswer(call, true) }, Modifier.weight(1f), enabled = !answering)
        }
      }
    }
  }
}
