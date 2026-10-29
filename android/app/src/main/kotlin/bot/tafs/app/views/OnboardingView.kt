package bot.tafs.app.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Dot
import bot.tafs.app.design.Mascot
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled

/** The first screen: sign in with KIFF, or look around with the sample. */
@Composable
fun OnboardingView(onSignIn: () -> Unit, onSample: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier
      .fillMaxSize()
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
      Mascot(96.dp)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("They taf.\nYou kiff.", style = scaled(48, FontWeight.Bold, Theme.ink, display = true))
        Text("Hand a job to a taf. It works inside a Card you set, and asks you when it needs more.", style = scaled(16, color = Theme.soft))
      }
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Point("Inside the Card", "it acts, and every action has a receipt.", Theme.ok)
        Point("Outside it", "it waits for you, right here.", Theme.amber)
        Point("Revoked", "no new action goes through.", Theme.no)
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      PrimaryButton("Sign in with KIFF", onSignIn)
      OutlineButton("Try it with sample data", onSample, Modifier.fillMaxWidth())
    }
  }
}

@Composable
private fun Point(lead: String, rest: String, color: Color) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Dot(color)
    Text(
      buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Theme.ink)) { append(lead) }
        withStyle(SpanStyle(color = Theme.soft)) { append(" $rest") }
      },
      style = scaled(15),
    )
  }
}
