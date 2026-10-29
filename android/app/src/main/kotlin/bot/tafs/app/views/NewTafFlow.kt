package bot.tafs.app.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import bot.tafs.app.design.LocalBiometry
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Dot
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.brandBackground
import bot.tafs.app.design.scaled
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.Money
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafCard
import bot.tafs.app.model.TafDraft
import bot.tafs.app.model.TafTemplate
import bot.tafs.app.model.TafTemplates
import bot.tafs.app.model.Outside
import bot.tafs.app.model.threeMonthsOn
import java.time.Instant

/** Say the job → the taf proposes its tools and Card → issue the Card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTafFlow(
  draftFrom: (String) -> TafDraft,
  issuer: String,
  colorIndex: Int,
  /** Issues the Card after the owner confirms it; null when they didn't. */
  onIssue: suspend (TafDraft) -> Taf?,
  onClose: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  // Set on the tap itself, until the owner has confirmed or declined: a second tap issues nothing.
  var issuing by remember { mutableStateOf(false) }
  var step by rememberSaveable { mutableIntStateOf(1) }
  var job by rememberSaveable { mutableStateOf("") }
  var draft by remember { mutableStateOf(TafDraft("", "", emptyList(), emptyList())) }

  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TextAction("Close", onClose)
        if (step > 1) TextAction("Back") { step -= 1 }
      }
      Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Eyebrow("New taf · $step of 3")
        when (step) {
          1 -> JobStep(job, { job = it }) { draft = draftFrom(job); step = 2 }
          2 -> DraftStep(draft, { draft = it }) { step = 3 }
          else -> IssueStep(draft, issuer, colorIndex, issuing) {
            if (issuing) return@IssueStep
            issuing = true
            scope.launch {
              try {
                if (onIssue(draft) != null) onClose()
              } finally {
                issuing = false
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
  Text(text, style = scaled(17, color = Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClick).padding(top = 8.dp))
}

// MARK: 1. The job

@Composable
private fun JobStep(job: String, onJob: (String) -> Unit, onDraft: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("What's the job?", style = scaled(38, FontWeight.Bold, Theme.ink, display = true))
    Text("Say it the way you'd brief a new hire. Include what it must never do.", style = scaled(15, color = Theme.soft))
    val shape = RoundedCornerShape(18.dp)
    BasicTextField(
      value = job,
      onValueChange = onJob,
      textStyle = scaled(17, color = Theme.ink),
      cursorBrush = SolidColor(Theme.amber),
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 160.dp)
        .clip(shape)
        .background(Theme.panel)
        .border(BorderStroke(1.dp, Theme.line), shape)
        .padding(8.dp),
    )
    Text("Or start from one of these", style = scaled(13, color = Theme.dim))
    TafTemplates.all.forEach { t -> JobRow(t, selected = job == t.job) { onJob(t.job) } }
    PrimaryButton("Draft the taf", onDraft, enabled = job.isNotBlank())
  }
}

/** One of the jobs a taf can do, as a card to pick. */
@Composable
private fun JobRow(template: TafTemplate, selected: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(14.dp)
  Row(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(Theme.panel)
      .border(BorderStroke(1.dp, if (selected) Theme.pink.copy(alpha = 0.6f) else Theme.line), shape)
      .clickable(onClick = onClick)
      .padding(10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      Modifier.size(22.dp).clip(CircleShape).then(if (selected) Modifier.brandBackground() else Modifier.border(BorderStroke(1.5.dp, Theme.dim), CircleShape)),
      contentAlignment = Alignment.Center,
    ) {
      if (selected) Text("✓", style = scaled(13, FontWeight.Bold, Color.White))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(template.name, style = scaled(16, FontWeight.SemiBold, Theme.ink))
      Text(template.job, style = scaled(13, color = Theme.soft), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
  }
}

// MARK: 2. The draft

@Composable
private fun DraftStep(draft: TafDraft, onDraft: (TafDraft) -> Unit, onReview: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BasicTextField(
      value = draft.name,
      onValueChange = { onDraft(draft.copy(name = it)) },
      textStyle = scaled(34, FontWeight.Bold, Theme.ink, display = true),
      cursorBrush = SolidColor(Theme.amber),
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Text("Drafted from your words. Change anything.", style = scaled(13, color = Theme.dim))

    Eyebrow("Tools it needs")
    if (draft.tools.isEmpty()) {
      Text("No tools matched this job yet. Pick a starting job to see a full draft.", style = scaled(14, color = Theme.soft))
    }
    draft.tools.forEach { tool ->
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Theme.panel).padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(tool, style = scaled(15, FontWeight.SemiBold, Theme.ink))
        Text("through KIFF", style = scaled(12, color = Theme.dim))
      }
    }

    Eyebrow("Its Card")
    draft.rules.forEachIndexed { i, rule ->
      RuleEditor(rule) { changed -> onDraft(draft.copy(rules = draft.rules.mapIndexed { j, r -> if (j == i) changed else r })) }
    }

    PrimaryButton("Review its Card", onReview, enabled = draft.name.isNotEmpty() && draft.rules.isNotEmpty())
  }
}

/** One Card line, with its amounts editable in dollars. */
@Composable
private fun RuleEditor(rule: CardRule, onChange: (CardRule) -> Unit) {
  val shape = RoundedCornerShape(14.dp)
  Column(
    Modifier.fillMaxWidth().clip(shape).background(Theme.panel).border(BorderStroke(1.dp, Theme.line), shape).padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(rule.label, style = scaled(15, FontWeight.SemiBold, Theme.ink))
      Text(
        if (rule.never) "Refused, never asked" else if (rule.outside == Outside.hold) "Outside: held for you" else "Outside: refused",
        style = scaled(12, color = if (rule.never) Theme.no else Theme.dim),
      )
    }
    if (!rule.never) {
      rule.perAction?.let { AmountField("Up to, each", it) { m -> onChange(rule.copy(perAction = m)) } }
      rule.perWindow?.let { AmountField("Up to, ${rule.window.label}", it) { m -> onChange(rule.copy(perWindow = m)) } }
      rule.countPerWindow?.let { count -> CountStepper("$count ${rule.window.label}", count) { n -> onChange(rule.copy(countPerWindow = n)) } }
    }
  }
}

@Composable
private fun AmountField(label: String, money: Money, onChange: (Money) -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = scaled(14, color = Theme.soft))
    BasicTextField(
      value = (money.minor / 100).toString(),
      onValueChange = { text -> text.filter(Char::isDigit).toLongOrNull()?.let { onChange(Money(it * 100, "USD", 2)) } },
      textStyle = scaled(15, color = Theme.ink, mono = true).copy(textAlign = TextAlign.End),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      singleLine = true,
      cursorBrush = SolidColor(Theme.amber),
      modifier = Modifier.width(110.dp).clip(RoundedCornerShape(10.dp)).background(Theme.background).padding(vertical = 6.dp, horizontal = 8.dp),
    )
  }
}

@Composable
private fun CountStepper(text: String, count: Int, onChange: (Int) -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(text, style = scaled(14, color = Theme.ink, mono = true))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      StepButton("−", enabled = count > 1) { onChange(count - 1) }
      StepButton("+", enabled = count < 10_000) { onChange(count + 1) }
    }
  }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.size(44.dp, 36.dp).clip(RoundedCornerShape(8.dp)).background(Theme.ink.copy(alpha = if (enabled) 0.07f else 0.03f)).clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(symbol, style = scaled(18, FontWeight.Medium, if (enabled) Theme.ink else Theme.dim))
  }
}

// MARK: 3. Issue

@Composable
private fun IssueStep(draft: TafDraft, issuer: String, colorIndex: Int, issuing: Boolean, onIssue: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text("Give ${draft.name} its Card", style = scaled(32, FontWeight.Bold, Theme.ink, display = true))
    val face = TafCard.merged("draft", draft.name, draft.rules, threeMonthsOn(Instant.now()))
    TafCardFace(face, issuer, Theme.tafColor(colorIndex))
    // What is signed, in full: every action and its limits, not only the face's main one.
    CardLines(draft.rules)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Rule("Inside the Card,", "it acts and you get a receipt.", Theme.ok)
      Rule("Outside it,", "it waits for you, here and on your lock screen.", Theme.amber)
      Rule("Revoked,", "no new action goes through.", Theme.no)
    }
    PrimaryButton("Issue with ${LocalBiometry.current}", onIssue, enabled = !issuing)
  }
}

@Composable
private fun Rule(lead: String, rest: String, color: Color) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Dot(color)
    Text(
      buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Theme.ink)) { append(lead) }
        withStyle(SpanStyle(color = Theme.soft)) { append(" $rest") }
      },
      style = scaled(14),
    )
  }
}
