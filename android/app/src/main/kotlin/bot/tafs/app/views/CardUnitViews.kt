package bot.tafs.app.views

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import bot.tafs.app.app.AppState
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.CardUnitException
import kotlinx.coroutines.launch

/** What a currency request's buttons do: talk it over in Coach, or dismiss it at KIFF. */
data class CardUnitActions(val discuss: (CardUnitException) -> Unit, val dismiss: suspend (CardUnitException) -> Unit)

val LocalCardUnitActions = staticCompositionLocalOf<CardUnitActions?> { null }

/**
 * A Card asked to take a currency it has no limit for, in tafs cloud's words: what it is, its tries so far, and its two ways on.
 * Never Approve once: only a changed Card lets those calls through.
 */
@Composable
fun CardUnitExceptionPanel(ask: CardUnitException, state: AppState) {
  val actions = LocalCardUnitActions.current
  val scope = rememberCoroutineScope()
  val taf = state.tafs.firstOrNull { it.cloudId == ask.taf }
  Panel {
    Text(ask.title, style = scaled(16, FontWeight.SemiBold, Theme.ink))
    Text(ask.words, style = scaled(14, color = Theme.soft))
    if (ask.history.isNotBlank()) Text(ask.history, style = scaled(12, color = Theme.dim))
    if (actions != null && taf != null && state.mayUseCoach && state.manages(taf.id)) {
      TextButton({ actions.discuss(ask) }) { Text(ask.actionTitle, color = Theme.amberText) }
    }
    if (actions != null && state.canAnswerHolds != false) {
      OutlineButton(
        ask.dismissTitle, { scope.launch { actions.dismiss(ask) } },
        Modifier.fillMaxWidth(), color = Theme.no, enabled = ask.id !in state.dismissingCardUnits,
      )
    }
  }
}
