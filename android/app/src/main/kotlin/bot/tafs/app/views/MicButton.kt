package bot.tafs.app.views

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import bot.tafs.app.R
import bot.tafs.app.design.Theme
import bot.tafs.app.voice.Dictation

/**
 * The mic in a chat's message bar (kiff/tafs#208, as iOS's `MicButton`), the same in a taf's chat and in Coach: tap to
 * dictate into the box, tap again (or send) to stop. Nothing is sent by voice: the words wait in the box until the owner
 * sends them. The microphone is asked for at the first tap.
 */
@Composable
fun MicButton(dictation: Dictation, text: String, onText: (String) -> Unit) {
  val context = LocalContext.current
  val state by dictation.state.collectAsState()
  val listening = state == Dictation.State.Listening
  val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
    // Allowed: it dictates at once. Refused: it says so, with the way to the phone's settings.
    dictation.start(text, onText)
  }
  Box(
    Modifier
      .size(40.dp)
      .clip(CircleShape)
      .background(if (listening) Theme.no else Theme.panel)
      .border(BorderStroke(1.dp, Theme.line), CircleShape)
      .clickable {
        if (listening) dictation.stop()
        else if (!dictation.mayListen && dictation.available) ask.launch(Manifest.permission.RECORD_AUDIO)
        else dictation.start(text, onText)
      }
      .semantics { contentDescription = if (listening) "Stop dictating" else "Dictate a message" },
    contentAlignment = Alignment.Center,
  ) {
    if (listening) {
      Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
    } else {
      Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(18.dp))
    }
  }
  (state as? Dictation.State.Blocked)?.let { blocked ->
    AlertDialog(
      onDismissRequest = dictation::dismiss,
      title = { Text("Dictation") },
      text = { Text(blocked.why) },
      confirmButton = {
        if (blocked.settings) {
          TextButton({
            dictation.dismiss()
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
          }) { Text("Open Settings", color = Theme.amberText) }
        } else {
          TextButton(dictation::dismiss) { Text("OK", color = Theme.amberText) }
        }
      },
      dismissButton = if (blocked.settings) ({ TextButton(dictation::dismiss) { Text("OK", color = Theme.soft) } }) else null,
      containerColor = Theme.panel,
    )
  }
}
