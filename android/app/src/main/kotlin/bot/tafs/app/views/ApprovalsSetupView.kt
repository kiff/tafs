package bot.tafs.app.views

import bot.tafs.app.drive.confirms
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.ApprovalReadiness
import bot.tafs.app.model.ApprovalWords

/** Whether this phone can approve now, read again each time the app comes back (MainActivity). Ready in previews and tests. */
val LocalApprovalReadiness = compositionLocalOf { ApprovalReadiness.Ready }

/** Reads it again at once: Check again. */
val LocalRecheckApprovals = compositionLocalOf<() -> Unit> { {} }

/**
 * What the approval buttons see: the phone's own answer with KIFF. The sample signs nothing (its approvals are local
 * demonstrations), so there it's always Ready, whatever the phone has.
 */
fun gateReadiness(mode: Mode, device: ApprovalReadiness): ApprovalReadiness = if (mode == Mode.kiff) device else ApprovalReadiness.Ready

/** Gives the screens below [gateReadiness] and Check again. */
@Composable
fun ProvideApprovalReadiness(mode: Mode, device: ApprovalReadiness, recheck: () -> Unit, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalApprovalReadiness provides gateReadiness(mode, device), LocalRecheckApprovals provides recheck, content = content)
}

/** The phone's own answer: a biometric it counts as strong, the only one the approval key opens with. */
fun approvalReadiness(context: Context): ApprovalReadiness =
  ApprovalReadiness.of(BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG))

/** The phone's own screen to add a fingerprint, asking for a strong one; its security settings where that screen isn't there. */
fun openApprovalSettings(context: Context) {
  val enroll = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
    .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(enroll) }.onFailure {
    runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  }
}

/**
 * In an approval button's place while this phone can't approve: its state in one line, and what to do. Never a button that
 * would only fail at the prompt.
 */
@Composable
fun ApprovalsUnavailable(readiness: ApprovalReadiness, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val recheck = LocalRecheckApprovals.current
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(ApprovalWords.cantApprove(readiness), style = scaled(14, color = Theme.amberText))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      if (readiness == ApprovalReadiness.NoneEnrolled) {
        Text(
          ApprovalWords.OPEN_SETTINGS,
          style = scaled(14, FontWeight.SemiBold, Theme.amberText),
          modifier = Modifier.heightIn(min = 44.dp).clickable { openApprovalSettings(context) }.padding(vertical = 12.dp),
        )
      }
      if (readiness != ApprovalReadiness.Unsupported) {
        Text(
          ApprovalWords.CHECK_AGAIN,
          style = scaled(14, FontWeight.SemiBold, Theme.dim),
          modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = recheck).padding(vertical = 12.dp),
        )
      }
    }
  }
}

/** An approval's button, or, while this phone can't approve, what to do instead. */
@Composable
fun ApprovalGate(content: @Composable () -> Unit) {
  val readiness = LocalApprovalReadiness.current
  // What signs with the approval key confirms: a driven touch on it is refused (docs/drive.md, "Coach never confirms").
  if (readiness == ApprovalReadiness.Ready) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.confirms()) { content() } else ApprovalsUnavailable(readiness)
}
