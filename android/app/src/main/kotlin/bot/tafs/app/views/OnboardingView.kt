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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

/**
 * The first screen: sign in with KIFF, or look around with the sample. With the new onboarding on ([onboarding],
 * docs/onboarding.md), one tick for the terms above the buttons, privacy as links and the age line, and Continue in Sign in
 * with KIFF's place, off until the box is ticked. Trying the sample never waits on the tick.
 */
@Composable
fun OnboardingView(
  onSignIn: () -> Unit,
  onSample: () -> Unit,
  modifier: Modifier = Modifier,
  onboarding: Boolean = ONBOARDING_ON,
  /** With the new onboarding: KIFF's sign-in inside tafs (`AppModel.signInInApp`) with the terms version the tick showed. */
  signInInApp: (suspend (termsVersion: String?) -> Unit)? = null,
  /** The terms version in force, read as the tick shows (`AppModel.appTermsVersion`). */
  termsVersion: (suspend () -> String)? = null,
  /**
   * A tablet's second way, under Continue and waiting on the same tick: added from the person's phone (pairing); null on a phone.
   * It's given the terms version the tick agreed to, which the pairing claim carries.
   */
  onSignInWithPhone: ((termsVersion: String) -> Unit)? = null,
  /**
   * How many times KIFF said the terms changed on the phone way (a count, saved with the screen, so a rotation while the
   * sheet is open neither loses one nor unticks the box twice): each new one unticks the box and says why.
   */
  phoneTermsChanged: Int = 0,
  /**
   * Whether the person is back from KIFF's sign-in tab with no answer from it (`AppModel.appSignInLeft`), and the way to end that
   * wait (`AppModel.endAppSignIn`): Continue is never left busy with nothing on screen while the tab is gone.
   */
  signInLeft: Boolean = false,
  endSignIn: () -> Unit = {},
) {
  var tick by rememberSaveable { mutableStateOf(false) }
  val consent = ConsentTick(tick)
  var busy by remember { mutableStateOf(false) }
  var problem by rememberSaveable { mutableStateOf<String?>(null) }
  var termsChanged by rememberSaveable { mutableStateOf(false) }
  var shownTerms by rememberSaveable { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  if (onboarding && termsVersion != null) LaunchedEffect(Unit) { shownTerms = runCatching { termsVersion() }.getOrNull() }
  // Terms that changed since the tick, from either way: the box is unticked and says why; the new version is for the new tick.
  val untick: () -> Unit = {
    tick = false
    termsChanged = true
    if (termsVersion != null) scope.launch { shownTerms = runCatching { termsVersion() }.getOrNull() }
  }
  var phoneTermsHandled by rememberSaveable { mutableIntStateOf(0) }
  LaunchedEffect(phoneTermsChanged) {
    if (phoneTermsChanged > phoneTermsHandled) { phoneTermsHandled = phoneTermsChanged; untick() }
  }
  // The phone way carries the version the tick agreed to, read now if it couldn't be when the welcome showed; without one,
  // nothing opens, so consent is never skipped (as iPad).
  val onPhone: (() -> Unit)? = onSignInWithPhone?.let { open ->
    {
      problem = null
      termsChanged = false
      scope.launch {
        val version = shownTerms ?: termsVersion?.let { read -> runCatching { read() }.getOrNull() }?.also { shownTerms = it }
        if (version == null) problem = Words.SignIn.didntWork else open(version)
      }
    }
  }
  // Back from the tab with no answer while Continue waits: the wait ends, and Continue says it didn't work and is free again.
  LaunchedEffect(signInLeft, busy) { if (signInLeft && busy) endSignIn() }
  val onContinue: () -> Unit = if (!onboarding || signInInApp == null) onSignIn else ({
    problem = null
    termsChanged = false
    busy = true
    scope.launch {
      try {
        signInInApp(shownTerms)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        when (continueOutcome(e)) {
          ContinueOutcome.Quiet -> Unit
          ContinueOutcome.TermsChanged -> untick()
          // KIFF's own words where it refuses for good (not offered where the person is), else ours.
          ContinueOutcome.Problem -> problem = bot.tafs.app.model.ErrorWords.kiffWords(e) ?: Words.SignIn.didntWork
          // Signing in inside the app isn't on (KIFF's switch): said plainly, in KIFF's words when it sends them.
          ContinueOutcome.NotOn -> problem = (e as? bot.tafs.app.kiff.KiffError.Server)?.words?.takeIf { it.isNotBlank() } ?: Words.SignIn.inAppNotOn
        }
      } finally {
        busy = false
      }
    }
  })
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
      if (onboarding) {
        if (termsChanged) Text(Words.Onboarding.termsChanged, style = scaled(14, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.testTag(ONBOARDING_TERMS_CHANGED))
        ConsentLines(consent) { tick = consent.toggled().agreed }
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      problem?.let { Text(it, style = scaled(14, color = Theme.no), modifier = Modifier.testTag(ONBOARDING_PROBLEM)) }
      // With the new onboarding, Continue (and a tablet's phone way) in Sign in with KIFF's place, both waiting on the tick:
      // the old way never shows beside them, or it would sign in with no consent recorded.
      if (onboarding) {
        PrimaryButton(Words.Common.`continue`, onContinue, Modifier.testTag(ONBOARDING_CONTINUE), enabled = consent.canContinue && !busy)
        if (onPhone != null) {
          OutlineButton(Words.SignIn.withPhone, onPhone, Modifier.fillMaxWidth().testTag(ONBOARDING_WITH_PHONE), enabled = consent.canContinue && !busy)
        }
      } else {
        PrimaryButton(Words.SignIn.withKiff, onSignIn)
      }
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

/** How Continue's sign-in ended, when it didn't sign in. */
internal enum class ContinueOutcome { Quiet, TermsChanged, Problem, NotOn }

/**
 * The welcome's line for a sign-in that didn't sign in: closing KIFF's page says nothing (the welcome is as it was), terms that
 * changed since the tick untick the box and say so, and anything else says signing in didn't work.
 */
internal fun continueOutcome(e: Throwable): ContinueOutcome = when {
  e is bot.tafs.app.kiff.VendorSignInCancelled -> ContinueOutcome.Quiet
  e is bot.tafs.app.kiff.AppSignInRefused && e.error == "access_denied" -> ContinueOutcome.Quiet
  e is bot.tafs.app.kiff.AppSignInRefused && e.error == bot.tafs.app.app.AppModel.TERMS_OUTDATED -> ContinueOutcome.TermsChanged
  // The same refusal from KIFF's authorize page, sent back to the app.
  e is bot.tafs.app.kiff.AppSignInRefused && e.error == UNSUPPORTED_CLIENT -> ContinueOutcome.NotOn
  // Continue's KIFF calls are all row 39's (`/v1/auth/app/…`), so a 400 `unsupported_client` here is that sign-in being off.
  e is bot.tafs.app.kiff.KiffError.Server && e.status == 400 && e.refused(UNSUPPORTED_CLIENT) -> ContinueOutcome.NotOn
  else -> ContinueOutcome.Problem
}

/** KIFF's code while signing in inside the app is off for this app or account. */
internal const val UNSUPPORTED_CLIENT = "unsupported_client"

internal const val ONBOARDING_TICK = "onboarding.tick"
internal const val ONBOARDING_TERMS_CHANGED = "onboarding.terms_changed"
internal const val ONBOARDING_PROBLEM = "onboarding.problem"
internal const val ONBOARDING_WITH_PHONE = "onboarding.with_phone"
internal const val ONBOARDING_CONTINUE = "onboarding.continue"
