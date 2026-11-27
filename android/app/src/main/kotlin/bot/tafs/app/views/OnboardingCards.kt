package bot.tafs.app.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.brandBackground
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.drive.confirms
import bot.tafs.app.model.Card
import bot.tafs.app.model.CardTotal
import bot.tafs.app.model.OnboardingPage
import bot.tafs.app.model.OnboardingSteps
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafCard
import bot.tafs.app.model.TafTemplates
import bot.tafs.app.model.Window
import bot.tafs.app.model.Words

/**
 * The four cards on how tafs works (docs/onboarding.md, step 3): after signing in, before the first Home, and again from How
 * tafs works ([start]). Each picture is the app's own view over the sample's words, so the person knows it a minute later and
 * reads it in their language. Skip and Get started call [done].
 */
@Composable
fun OnboardingCards(start: OnboardingPage = OnboardingPage.Tafs, done: () -> Unit) {
  var page by rememberSaveable { mutableStateOf(start) }
  val steps = OnboardingSteps(page)
  Column(
    Modifier.fillMaxSize().background(Theme.background).statusBarsPadding().navigationBarsPadding()
      .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Text(
      Words.Onboarding.skip,
      style = scaled(15, FontWeight.SemiBold, Theme.soft),
      modifier = Modifier.align(Alignment.End).heightIn(min = 44.dp).tapping { done() }.padding(8.dp).testTag(ONBOARDING_SKIP),
    )
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      // A picture of the app, not a control: what it says is in the title and the lead.
      Box(Modifier.fillMaxWidth().clearAndSetSemantics {}, contentAlignment = Alignment.Center) { OnboardingPicture(page) }
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(page.title, style = scaled(30, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.semantics { heading() })
        Text(page.lead, style = scaled(16, color = Theme.soft))
      }
    }
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
      OnboardingPage.entries.forEach { p ->
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (p == page) Theme.amber else Theme.dim.copy(alpha = 0.4f)))
      }
    }
    PrimaryButton(
      if (steps.isLast) Words.Onboarding.getStarted else Words.Common.next,
      { val next = steps.advanced(); if (next.finished) done() else page = next.page },
      Modifier.testTag(ONBOARDING_NEXT),
    )
  }
}

/** Each card's picture, drawn by the app's own views with the sample's words. */
@Composable
internal fun OnboardingPicture(page: OnboardingPage) {
  when (page) {
    OnboardingPage.Tafs -> Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      onboardingSampleTafs().forEach { TafRow(it, showState = false) {} }
    }
    // Coach's first exchange, as its chat draws it: the job in one line, then Coach's first answer.
    OnboardingPage.Coach -> Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        Words.Sample.Coach.ask, style = scaled(15, color = androidx.compose.ui.graphics.Color.White),
        modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(18.dp)).brandBackground().padding(horizontal = 10.dp, vertical = 8.dp),
      )
      Panel(Modifier.fillMaxWidth()) {
        Eyebrow(Words.Coach.title)
        Text(Words.Sample.Coach.connectFirst, style = scaled(15, color = Theme.ink))
        Text(Words.Sample.Coach.planLine, style = scaled(13, color = Theme.dim))
      }
    }
    // The Card, and under it a call it holds, as Needs you draws it: nothing is sent until the person approves once.
    OnboardingPage.Card -> Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      TafCardFace(onboardingSampleCard(), issuedBy = Words.Common.you, color = Theme.tafColor(0))
      Panel(Modifier.fillMaxWidth()) {
        Eyebrow(Words.Sample.Deploys.name)
        Text(Words.Sample.Deploys.deployApi, style = scaled(15, FontWeight.SemiBold, Theme.ink))
        Text(Words.NeedsYou.nothingSent, style = scaled(13, color = Theme.dim))
        // As Needs you words and marks it on this phone (a driven touch is refused); the picture takes no taps.
        // It never says the screen lock approves: with no strong biometric, it names the fingerprint the phone would need.
        PrimaryButton(Words.NeedsYou.approveOnceWith(bot.tafs.app.signing.Biometry.approvingWith(LocalBiometry.current)), {}, Modifier.padding(top = 8.dp).confirms())
      }
    }
    // The credits, as Plan & usage draws them: Solo's month and its meter, and Coach's own work counted apart.
    OnboardingPage.Credits -> Panel(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
      Text(Words.Billing.soloOn, style = scaled(15, FontWeight.SemiBold, Theme.ink))
      Box(Modifier.padding(vertical = 6.dp)) { bot.tafs.app.design.Meter(0.52) }
      Text(Words.Sample.Credits.notCountedCoach, style = scaled(13, color = Theme.soft))
    }
  }
}

/** The sample's own tafs that never move money, in Home's order. */
internal fun onboardingSampleTafs(): List<Taf> =
  listOf(TafTemplates.support, TafTemplates.parcels, TafTemplates.leads).mapIndexed { i, t ->
    Taf(id = "onboarding-${t.key}", name = t.name, job = t.job, tools = t.tools, colorIndex = i, card = Card(t.rules, issuedBy = Words.Common.you))
  }

/** The Card the store screenshots show: "Support replies · Zendesk" with Support replies' own rules, 12 of 40 replies used today. */
internal fun onboardingSampleCard(): TafCard =
  TafCard(id = "onboarding-support", title = Words.Sample.Card.refundsStripe, rules = TafTemplates.support.rules,
    calls = CardTotal(limit = 40, used = 12, window = Window.day))

/**
 * How tafs works, any time (docs/onboarding.md): the four cards again, each opening where it's tapped, and the four legal pages
 * titled as their pages are (`onboarding.row_*`, never the tick line's link words, which sit inside its sentence). Then two
 * short sections, as iOS's: Chrome only where planque offers it ([chrome]), the emailed code only where KIFF can send it
 * ([code]), so the app says nothing about what the person can't use.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HowTafsWorksSheet(chrome: Boolean = false, code: Boolean = false, onClose: () -> Unit) {
  val uri = androidx.compose.ui.platform.LocalUriHandler.current
  var opened by rememberSaveable { mutableStateOf<OnboardingPage?>(null) }
  bot.tafs.app.drive.DriveSheet(
    onDismissRequest = onClose,
    sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(Words.Onboarding.howItWorks, style = scaled(28, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.semantics { heading() })
      Panel {
        OnboardingPage.entries.forEach { page ->
          Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).tapping { opened = page }.padding(vertical = 10.dp).testTag("how_it_works.${page.name}"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(page.title, style = scaled(16, color = Theme.ink), modifier = Modifier.weight(1f))
            Text("›", style = scaled(16, color = Theme.dim))
          }
        }
      }
      Panel {
        howItWorksLegalRows().forEach { (title, url) ->
          Text(bot.tafs.app.model.Legal.label(title), style = scaled(16, color = Theme.amberText),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).tapping { uri.openUri(url) }.padding(vertical = 10.dp))
        }
      }
      if (chrome) HelpSection(
        Words.Help.chromeTitle,
        Words.Help.chromeLead(needsYou = Words.NeedsYou.title),
        Words.Help.chromeManage(account = Words.Settings.title, chrome = Words.Chrome.title),
        HOW_IT_WORKS_CHROME,
      )
      if (code) HelpSection(
        Words.Help.codeTitle,
        Words.Help.codeLead(account = Words.Settings.title, signIn = Words.Settings.SignInCode.title),
        Words.Help.codeSafe,
        HOW_IT_WORKS_CODE,
      )
    }
  }
  opened?.let { start ->
    bot.tafs.app.drive.DriveDialog(
      onDismissRequest = { opened = null },
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) { OnboardingCards(start) { opened = null } }
  }
}

/** One of How tafs works' short sections: its title as a heading, what it does, and a second, quieter line. */
@Composable
private fun HelpSection(title: String, lead: String, more: String, tag: String) {
  Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, style = scaled(17, FontWeight.SemiBold, Theme.ink), modifier = Modifier.semantics { heading() })
    Panel {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(lead, style = scaled(15, color = Theme.soft))
        Text(more, style = scaled(14, color = Theme.dim))
      }
    }
  }
}

internal const val HOW_IT_WORKS_CHROME = "how_it_works.chrome"
internal const val HOW_IT_WORKS_CODE = "how_it_works.code"

/** The four legal pages as How tafs works lists them: each row's own standalone title, and its page in the app's language. */
internal fun howItWorksLegalRows(): List<Pair<String, String>> = listOf(
  Words.Onboarding.rowKiffPrivacy to bot.tafs.app.model.Legal.kiffPrivacy(),
  Words.Onboarding.rowTafsPrivacy to bot.tafs.app.model.Legal.privacy(),
  Words.Onboarding.rowKiffTerms to bot.tafs.app.model.Legal.kiffTerms(),
  Words.Onboarding.rowTafsTerms to bot.tafs.app.model.Legal.terms(),
)

internal const val ONBOARDING_SKIP = "onboarding.skip"
internal const val ONBOARDING_NEXT = "onboarding.next"
