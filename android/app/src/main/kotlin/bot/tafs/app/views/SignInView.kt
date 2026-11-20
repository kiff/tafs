package bot.tafs.app.views

import bot.tafs.app.drive.confirms
import bot.tafs.app.design.tapping
import bot.tafs.app.model.Words
import bot.tafs.app.drive.DriveSheet
import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.testTag
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
fun SignInSheet(
  model: AppModel,
  onClose: () -> Unit,
  /** Only "Sign in with your phone" (a tablet's second way under the new welcome's Continue, docs/onboarding.md): no KIFF code. */
  phoneOnly: Boolean = false,
  /** With [phoneOnly]: the terms version the welcome showed ticked, sent with the claim so KIFF records it for this device. */
  termsVersion: String? = null,
  /** With [phoneOnly]: KIFF said the terms changed since the tick; the sheet closes and the welcome asks the tick again. */
  onTermsChanged: () -> Unit = {},
) {
  // Closed by the person: the code is forgotten. A screen recreated meanwhile isn't closed, and goes on with the same code.
  val close = { model.endSignIn(); onClose() }
  var start by remember { mutableStateOf<DeviceStart?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  var copied by remember { mutableStateOf(false) }
  // Bumped by "Try again": the sign-in begins over with a new code.
  var attempt by remember { mutableIntStateOf(0) }
  // A tablet shows both ways at once, as the owner's mockup does (16 Nov): the phone's QR from the start, and KIFF's code once
  // the person taps Continue. A phone signs in with KIFF only. One at a time, so a sign-in is never started twice.
  val split = offersPairSignIn(bot.tafs.app.model.ThisDevice.tablet)
  // Signing in with your other phone instead: its QR, whether the last one expired, and why it stopped.
  var pairing by remember { mutableStateOf(split || phoneOnly) }
  // Signed in, either way: "You're connected." until the person taps Let's go.
  var connected by remember { mutableStateOf(false) }
  var offer by remember { mutableStateOf<bot.tafs.app.kiff.PairOffer?>(null) }
  var renewed by remember { mutableStateOf(false) }
  var pairProblem by remember { mutableStateOf<String?>(null) }
  var pairAttempt by remember { mutableIntStateOf(0) }
  val uri = LocalUriHandler.current
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()

  // Back from approving in the browser: ask KIFF at once.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.nudgeSignIn() }

  LaunchedEffect(attempt, pairing) {
    // While the other phone adds this one, the KIFF sign-in waits: its code isn't asked about.
    if (pairing || connected) return@LaunchedEffect
    problem = null
    start = null
    copied = false
    try {
      // "Try again" starts over with a new code; otherwise a sign-in under way goes on with its own.
      val s = model.startSignIn(fresh = attempt > 0)
      start = s
      model.finishSignIn(s)
      connected = true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // The app's words, or tafs cloud's: never KIFF's raw reason.
      problem = bot.tafs.app.model.ErrorWords.of(e).ifBlank { Words.SignIn.didntWork }
    }
  }

  LaunchedEffect(pairing, pairAttempt) {
    if (!pairing) return@LaunchedEffect
    pairProblem = null
    offer = null
    // A new offer each time the last one runs out, until the other phone approves one or the person leaves.
    while (true) {
      val o = try {
        model.startPairSignIn()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        pairProblem = bot.tafs.app.model.ErrorWords.of(e).ifBlank { Words.SignIn.didntWork }
        return@LaunchedEffect
      }
      offer = o
      try {
        model.finishPairSignIn(o, termsVersion.takeIf { phoneOnly })
        connected = true
        return@LaunchedEffect
      } catch (e: bot.tafs.app.kiff.AppSignInRefused) {
        // Terms that changed since the tick: nothing was claimed; the sheet closes and the welcome asks the tick again.
        if (e.error == AppModel.TERMS_OUTDATED) { close(); onTermsChanged() } else pairProblem = Words.SignIn.didntWork
        return@LaunchedEffect
      } catch (e: CancellationException) {
        throw e
      } catch (_: bot.tafs.app.kiff.KiffError.Expired) {
        renewed = true
      } catch (e: Exception) {
        pairProblem = bot.tafs.app.model.ErrorWords.of(e).ifBlank { Words.SignIn.didntWork }
        return@LaunchedEffect
      }
    }
  }

  DriveSheet(
    onDismissRequest = close,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      // Once connected, Let's go is the one way on: no Cancel above it. Its row keeps its height, so the sheet doesn't shrink
      // and show the screen behind it cut at its edge.
      if (!connected) Text(
        Words.Common.cancel,
        style = scaled(17, color = Theme.amber),
        modifier = Modifier.padding(horizontal = 24.dp).heightIn(min = 44.dp).tapping(onClick = close).padding(top = 8.dp),
      ) else androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 24.dp).heightIn(min = 44.dp).padding(top = 8.dp))
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (connected) {
          SignedInCelebration(onGo = close)
          return@Column
        }
        // KIFF's way: its code, Open KIFF and the wait. A phone shows only this; a tablet shows it as its left half.
        val kiff: @Composable () -> Unit = {
        Text(Words.SignIn.withKiff, style = scaled(34, FontWeight.Bold, Theme.ink, display = true))
        Text(
          Words.SignIn.lead(bot.tafs.app.model.ThisDevice.word),
          style = scaled(15, color = Theme.soft),
        )

        start?.let { s ->
          Panel(stroke = Theme.amber.copy(alpha = 0.4f)) {
            Eyebrow(Words.SignIn.yourCode)
            SelectionContainer {
              Text(s.userCode, style = scaled(34, FontWeight.SemiBold, Theme.ink, mono = true))
            }
            Text(Words.SignIn.checkCode, style = scaled(13, color = Theme.dim))
          }
          PrimaryButton(Words.SignIn.openKiff, { uri.openUri(s.verificationUriComplete) })
          // To approve in another browser, where they're signed in to KIFF.
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Words.SignIn.otherBrowser, style = scaled(13, color = Theme.dim))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
              SelectionContainer(Modifier.weight(1f)) {
                Text(
                  s.verificationUriComplete,
                  style = scaled(13, color = Theme.soft, mono = true),
                  maxLines = 2,
                  overflow = TextOverflow.MiddleEllipsis,
                )
              }
              Text(
                if (copied) Words.Common.copied else Words.Common.copy,
                style = scaled(13, FontWeight.SemiBold, Theme.amber),
                modifier = Modifier.heightIn(min = 44.dp).tapping {
                  scope.launch { clipboard.setClipEntry(ClipEntry(bot.tafs.app.model.Res.clip("KIFF sign-in link", s.verificationUriComplete))) }
                  copied = true
                }.padding(horizontal = 4.dp),
              )
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Theme.amber, strokeWidth = 2.dp)
            Text(Words.SignIn.waiting, style = scaled(14, color = Theme.soft))
          }
        }
        if (start == null && problem == null) {
          CircularProgressIndicator(Modifier.size(28.dp), color = Theme.amber, strokeWidth = 3.dp)
        }
        problem?.let {
          Text(it, style = scaled(14, color = Theme.no))
          OutlineButton(Words.Common.tryAgain, { attempt += 1 }, Modifier.fillMaxWidth())
        }
        }
        if (phoneOnly) {
          // Only the phone's way: KIFF's own is the welcome's Continue.
          PairSignInContent(
            offer, renewed, pairProblem, onTryAgain = { renewed = false; pairAttempt += 1 }, onKiff = null,
            title = Words.SignIn.withPhone, lead = Words.SignIn.scanWithTafs,
          )
        } else if (split) {
          // A tablet: both halves side by side. The person's phone adds it from the right half, with no browser; KIFF's code is
          // made only once they tap Continue on the left, and going back to the phone forgets that code. A phone always signs in
          // with KIFF itself, so it isn't offered being added from another phone (the owner, 16 Nov), as on the iPhone.
          Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
              if (pairing) {
                Text(Words.SignIn.withKiff, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
                Text(Words.SignIn.lead(bot.tafs.app.model.ThisDevice.word), style = scaled(15, color = Theme.soft))
                // Starts KIFF's sign-in: the person's own tap, never Coach's (docs/drive.md).
                PrimaryButton(Words.Common.`continue`, { pairing = false }, Modifier.testTag(KIFF_CONTINUE).confirms())
              } else {
                kiff()
              }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
              if (pairing) {
                PairSignInContent(
                  offer, renewed, pairProblem, onTryAgain = { renewed = false; pairAttempt += 1 }, onKiff = null,
                  title = Words.SignIn.withPhone, lead = Words.SignIn.scanWithTafs,
                )
              } else {
                Text(Words.SignIn.withPhone, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
                OutlineButton(Words.SignIn.withPhone, { model.endSignIn(); renewed = false; pairing = true }, Modifier.fillMaxWidth().testTag(PAIR_START))
              }
            }
          }
        } else {
          kiff()
        }

        Text(
          buildAnnotatedString {
            // The links sit where the sentence puts them, in any language.
            Words.SignIn.agree(terms = "\u0001", privacy = "\u0002").forEach { c ->
              when (c) {
                '\u0001' -> link(Words.Settings.terms, bot.tafs.app.model.Legal.terms())
                '\u0002' -> link(Words.Settings.privacyPolicy, bot.tafs.app.model.Legal.privacy())
                else -> append(c)
              }
            }
          },
          style = scaled(12, color = Theme.dim),
        )
      }
    }
  }
}

internal const val PAIR_START = "sign_in.pair.start"
internal const val KIFF_CONTINUE = "sign_in.kiff.continue"
internal const val SIGNED_IN = "sign_in.connected"

/**
 * Signed in, either way: the owner's celebration (16 Nov), not a sheet that just closes. The mascot, which carries no words
 * (rule 14), "You're connected.", the line under it, and Let's go, which closes the sheet onto the app.
 */
@Composable
internal fun SignedInCelebration(onGo: () -> Unit) {
  Column(
    Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(SIGNED_IN),
    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    bot.tafs.app.design.Mascot(size = 120.dp)
    Text(Words.SignIn.connected, style = scaled(30, FontWeight.Bold, Theme.ink, display = true))
    Text(Words.SignIn.connectedLine, style = scaled(16, color = Theme.soft))
    PrimaryButton(Words.SignIn.letsGo, onGo, Modifier.fillMaxWidth())
  }
}

/** Signed in inside tafs (docs/onboarding.md): the sign-in sheet's own celebration, in a sheet of its own, closed by Let's go. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InAppSignedIn(onGo: () -> Unit) {
  DriveSheet(
    onDismissRequest = onGo,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
      SignedInCelebration(onGo = onGo)
    }
  }
}

/** Whether the sign-in offers being added from the person's phone: on a tablet only, never on a phone. */
internal fun offersPairSignIn(tablet: Boolean): Boolean = tablet

private fun androidx.compose.ui.text.AnnotatedString.Builder.link(text: String, url: String) {
  withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Theme.amberText)))) { append(text) }
}
