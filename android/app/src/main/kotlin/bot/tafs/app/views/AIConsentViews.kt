package bot.tafs.app.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.kiff.AIConsent

/**
 * Your data and the AI (docs/accounts.md): tafs cloud's screen, drawn as it sends it, in the person's language. It names the AI
 * provider, what's sent and what never is. Allow sends the person's yes for the version they read; Not now sends nothing, and
 * tafs cloud keeps refusing every model turn until they allow it. Not a signed call: it grants no authority.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIConsentSheet(consent: AIConsent, busy: Boolean, error: String?, onAllow: () -> Unit, onNotNow: () -> Unit) {
  DriveSheet(
    onDismissRequest = onNotNow,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(
      Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(consent.words.title, style = scaled(22, FontWeight.Bold, Theme.ink, display = true))
      consent.words.lines.forEach { Text(it, style = scaled(15, color = Theme.soft)) }
      error?.let { Text(it, style = scaled(13, color = Theme.no)) }
      PrimaryButton(consent.words.allow, onAllow, Modifier.fillMaxWidth(), enabled = !busy)
      OutlineButton(consent.words.notNow, onNotNow, Modifier.fillMaxWidth(), color = Theme.dim, enabled = !busy)
    }
  }
}
