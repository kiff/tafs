package bot.tafs.app.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import kotlinx.coroutines.launch

/** What deleting the account erases, as its sheet lists it. */
private val GOES = listOf(
  "Every taf, with its runs, chats, notes and schedules",
  "Your tools' sign-ins, disconnected",
  "Your Cards and keys: they stop working",
  "KIFF's records of your account, erased after 30 days",
)

/**
 * Deleting the account (kiff/tafs#185, Google Play's account deletion requirement): its own sheet, opened from Settings. It says
 * what goes, then deletes everything behind one confirmation: tafs cloud and kiff/agents, planque, then KIFF, and signs out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteAccountSheet(state: AppState, delete: suspend () -> Boolean, clearError: () -> Unit, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  val biometry = LocalBiometry.current
  val busy = state.deletingAccount
  LaunchedEffect(Unit) { clearError() }
  ModalBottomSheet(
    onDismissRequest = { if (!busy) onClose() },
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy }),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Delete account", style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text(
          "Cancel",
          style = scaled(17, FontWeight.SemiBold, if (busy) Theme.dim else Theme.amber),
          modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = !busy, onClick = onClose).padding(top = 8.dp),
        )
      }
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Panel {
          Text("This deletes your KIFF account and everything tafs keeps for it. It can't be undone.", style = scaled(15, FontWeight.SemiBold, Theme.ink))
        }
        Panel {
          Eyebrow("What goes")
          GOES.forEach { Text("• $it", style = scaled(14, color = Theme.soft)) }
        }
        state.deleteAccountError?.let { why -> Panel { Text(why, style = scaled(14, color = Theme.no)) } }
        Text(
          when {
            busy -> "Deleting…"
            state.deleteAccountError == null -> "Delete account"
            else -> "Try again"
          },
          style = scaled(17, FontWeight.SemiBold, if (busy) Theme.dim else Theme.no),
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = !busy) { scope.launch { if (delete()) onClose() } }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Text("You confirm with $biometry before anything is deleted. Signed in elsewhere, you're signed out there too.", style = scaled(13, color = Theme.dim), modifier = Modifier.padding(horizontal = 12.dp))
      }
    }
  }
}
