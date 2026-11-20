package bot.tafs.app.views

import bot.tafs.app.model.Words
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Dot
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.ConsentTick
import bot.tafs.app.model.Legal
import bot.tafs.app.model.ONBOARDING_ON

/**
 * The first screen: sign in with KIFF, or look around with the sample. With the new onboarding on ([onboarding],
 * docs/onboarding.md), one tick for the terms above the buttons, privacy as links and the age line, and Continue in Sign in
 * with KIFF's place, off until the box is ticked. Trying the sample never waits on the tick.
 */
@Composable
fun OnboardingView(onSignIn: () -> Unit, onSample: () -> Unit, modifier: Modifier = Modifier, onboarding: Boolean = ONBOARDING_ON) {
  var tick by rememberSaveable { mutableStateOf(false) }
  val consent = ConsentTick(tick)
  Column(
    modifier
      .fillMaxSize()
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
      // The owner's board 1, in place of the mascot: both don't fit on a small phone (as iOS).
      bot.tafs.app.design.Illustrated { bot.tafs.app.design.FirstRunArt(it) }
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Words.SignIn.Welcome.title, style = scaled(48, FontWeight.Bold, Theme.ink, display = true))
        Text(Words.SignIn.Welcome.lead, style = scaled(16, color = Theme.soft))
      }
      if (onboarding) ConsentLines(consent) { tick = consent.toggled().agreed }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (onboarding) PrimaryButton(Words.Common.`continue`, onSignIn, Modifier.testTag(ONBOARDING_CONTINUE), enabled = consent.canContinue)
      else PrimaryButton(Words.SignIn.withKiff, onSignIn)
      OutlineButton(Words.Sample.tryIt, onSample, Modifier.fillMaxWidth())
    }
  }
}

/**
 * The one tick, with both Terms linked in its line; privacy as links under it, never ticked; and the age line. The box is its
 * own control, so a link opens its page and never ticks the box. TalkBack reads the box as the whole line.
 */
@Composable
private fun ConsentLines(consent: ConsentTick, toggle: () -> Unit) {
  val tickLine = Words.Onboarding.consentTick(kiffTerms = Words.Onboarding.kiffTerms, tafsTerms = Words.Onboarding.tafsTerms)
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Checkbox(
        checked = consent.agreed,
        onCheckedChange = { toggle() },
        modifier = Modifier.testTag(ONBOARDING_TICK).semantics { contentDescription = tickLine },
        colors = CheckboxDefaults.colors(checkedColor = Theme.amber, uncheckedColor = Theme.dim, checkmarkColor = Theme.background),
      )
      Text(
        linked(
          Words.Onboarding.consentTick(kiffTerms = "\u0001", tafsTerms = "\u0002"),
          listOf(Legal.label(Words.Onboarding.kiffTerms) to Legal.kiffTerms(), Legal.label(Words.Onboarding.tafsTerms) to Legal.terms()),
        ),
        style = scaled(14, color = Theme.ink),
        modifier = Modifier.padding(top = 12.dp),
      )
    }
    Text(
      linked(
        Words.Onboarding.privacyLinks(kiffPrivacy = "\u0001", tafsPrivacy = "\u0002"),
        listOf(Legal.label(Words.Onboarding.kiffPrivacy) to Legal.kiffPrivacy(), Legal.label(Words.Onboarding.tafsPrivacy) to Legal.privacy()),
      ),
      style = scaled(13, color = Theme.soft),
    )
    Text(bot.tafs.app.model.ageLine(), style = scaled(12, color = Theme.dim))
  }
}

/**
 * A line whose placeholders were filled with the markers \u0001, \u0002… in order, each marker replaced by its link's words,
 * linked: the line's language puts the links wherever it needs them.
 */
internal fun linked(line: String, links: List<Pair<String, String>>): AnnotatedString = buildAnnotatedString {
  for (c in line) {
    val i = c.code - 1
    if (i in links.indices) {
      val (words, url) = links[i]
      withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Theme.amberText)))) { append(words) }
    } else append(c)
  }
}

internal const val ONBOARDING_TICK = "onboarding.tick"
internal const val ONBOARDING_CONTINUE = "onboarding.continue"
