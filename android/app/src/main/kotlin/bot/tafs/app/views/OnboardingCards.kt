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
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
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
 * The three cards on how tafs works (docs/onboarding.md, step 3): after signing in, before the first Home, and again from How
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
    OnboardingPage.Card -> TafCardFace(onboardingSampleCard(), issuedBy = Words.Common.you, color = Theme.tafColor(0), modifier = Modifier.widthIn(max = 420.dp))
    OnboardingPage.Control -> Panel(Modifier.widthIn(max = 420.dp)) {
      Eyebrow(Words.Sample.Deploys.name)
      Text(Words.Sample.Deploys.deployApi, style = scaled(16, FontWeight.SemiBold, Theme.ink))
      Text(Words.Sample.Deploys.deployApiWhy, style = scaled(14, color = Theme.soft))
      Text(Words.NeedsYou.nothingSent, style = scaled(13, color = Theme.dim))
      // As Needs you words them on this phone; the picture takes no taps.
      PrimaryButton(Words.NeedsYou.approveOnceWith(LocalBiometry.current), {}, Modifier.padding(top = 8.dp))
      OutlineButton(Words.Sample.Deploys.nextStep, {}, Modifier.fillMaxWidth())
      OutlineButton(Words.NeedsYou.refuse, {}, Modifier.fillMaxWidth(), color = Theme.no)
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

internal const val ONBOARDING_SKIP = "onboarding.skip"
internal const val ONBOARDING_NEXT = "onboarding.next"
