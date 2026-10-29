package bot.tafs.app.views

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import bot.tafs.app.app.AppModel
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.DeviceStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Sign in with KIFF: the phone shows a code, the person approves it in KIFF (on the web, signed in
 * as themselves), and the phone receives a session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInSheet(model: AppModel, onClose: () -> Unit) {
  var start by remember { mutableStateOf<DeviceStart?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  var copied by remember { mutableStateOf(false) }
  // Bumped by "Try again": the sign-in begins over with a new code.
  var attempt by remember { mutableIntStateOf(0) }
  val uri = LocalUriHandler.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()

  // Back from approving in the browser: ask KIFF at once.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.nudgeSignIn() }

  LaunchedEffect(attempt) {
    problem = null
    start = null
    copied = false
    try {
      val s = model.startSignIn()
      start = s
      model.finishSignIn(s)
      onClose()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      problem = e.message ?: "Signing in didn't work."
    }
  }

  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Text(
        "Cancel",
        style = scaled(17, color = Theme.amber),
        modifier = Modifier.padding(horizontal = 24.dp).heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 10.dp),
      )
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Text("Sign in with KIFF", style = scaled(34, FontWeight.Bold, Theme.ink, display = true))
        Text(
          "Your tafs act under KIFF Cards. Approve this phone in KIFF, and the Cards you issued show up here.",
          style = scaled(15, color = Theme.soft),
        )

        start?.let { s ->
          Panel(stroke = Theme.amber.copy(alpha = 0.4f)) {
            Eyebrow("Your code")
            SelectionContainer {
              Text(s.userCode, style = scaled(34, FontWeight.SemiBold, Theme.ink, mono = true))
            }
            Text("Check that KIFF shows the same code before you approve.", style = scaled(13, color = Theme.dim))
          }
          PrimaryButton("Open KIFF to approve", { uri.openUri(s.verificationUriComplete) })
          // To approve in another browser, where they're signed in to KIFF.
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Or open this link in another browser:", style = scaled(13, color = Theme.dim))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
              SelectionContainer(Modifier.weight(1f)) {
                Text(
                  s.verificationUriComplete,
                  style = scaled(13, color = Theme.soft, mono = true),
                  maxLines = 2,
                  overflow = TextOverflow.MiddleEllipsis,
                )
              }
              Text(
                if (copied) "Copied" else "Copy",
                style = scaled(13, FontWeight.SemiBold, Theme.amber),
                modifier = Modifier.heightIn(min = 44.dp).clickable {
                  scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("KIFF sign-in link", s.verificationUriComplete))) }
                  copied = true
                }.padding(horizontal = 4.dp),
              )
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Theme.amber, strokeWidth = 2.dp)
            Text("Waiting for your approval…", style = scaled(14, color = Theme.soft))
          }
        }
        if (start == null && problem == null) {
          CircularProgressIndicator(Modifier.size(28.dp), color = Theme.amber, strokeWidth = 3.dp)
        }
        problem?.let {
          Text(it, style = scaled(14, color = Theme.no))
          OutlineButton("Try again", { attempt += 1 }, Modifier.fillMaxWidth())
        }

        Text(
          buildAnnotatedString {
            append("By signing in, you agree to KIFF's ")
            link("Terms", "https://kiff.dev/terms#tafs")
            append(", which cover tafs, and its ")
            link("Privacy policy", "https://kiff.dev/privacy#tafs")
            append(".")
          },
          style = scaled(12, color = Theme.dim),
        )
      }
    }
  }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.link(text: String, url: String) {
  withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Theme.amberText)))) { append(text) }
}
