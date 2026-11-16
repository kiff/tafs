package bot.tafs.app.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.SetupShape
import bot.tafs.app.model.Words

/**
 * Beside Coach's conversation on a wide screen (as iPad's): what it has made so far, the taf's name, the plan, the tools it
 * connects and the Card KIFF drafted. Only what the app already holds; the cards to answer stay in the conversation.
 */
@Composable
fun SetupShapePanel(shape: SetupShape, modifier: Modifier = Modifier) {
  Column(
    // A shade deeper than the conversation beside it.
    modifier.background(Theme.raised).verticalScroll(rememberScrollState()).padding(20.dp).testTag("setup-shape"),
    verticalArrangement = Arrangement.spacedBy(22.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Eyebrow(Words.Coach.Shape.takingShape)
      (shape.name ?: shape.title)?.let { Text(it, style = scaled(26, FontWeight.Bold, Theme.ink, display = true)) }
      if (shape.isEmpty) Text(Words.Billing.conversationGoesPlanToolsBeing, style = scaled(14, color = Theme.soft))
    }
    if (shape.steps.isNotEmpty()) ShapePlan(shape.steps)
    if (shape.tools.isNotEmpty()) ShapeTools(shape.tools)
    shape.card?.let { ShapeCard(it) }
  }
}

@Composable
private fun ShapePlan(steps: List<SetupShape.Step>) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Eyebrow(Words.Coach.Plan.progress(done = "${steps.count { it.state == SetupShape.Step.State.done }}", total = "${steps.size}"))
    steps.forEach { step ->
      val at = step.state == SetupShape.Step.State.needsYou || step.state == SetupShape.Step.State.now
      val later = step.state == SetupShape.Step.State.toDo || step.state == SetupShape.Step.State.skipped
      Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StepMark(stepMark(step.state))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            step.label,
            style = scaled(14, if (at) FontWeight.SemiBold else FontWeight.Normal, if (later) Theme.dim else Theme.ink)
              .copy(textDecoration = if (step.state == SetupShape.Step.State.skipped) TextDecoration.LineThrough else null),
          )
          if (step.state == SetupShape.Step.State.needsYou) Text(Words.NeedsYou.title, style = scaled(12, FontWeight.SemiBold, Theme.amberText))
        }
      }
    }
  }
}

/** A step's mark, as the plan above the thread draws its statuses. */
internal fun stepMark(state: SetupShape.Step.State): String = when (state) {
  SetupShape.Step.State.done -> "done"
  SetupShape.Step.State.failed -> "failed"
  SetupShape.Step.State.needsYou, SetupShape.Step.State.now -> "active"
  SetupShape.Step.State.toDo, SetupShape.Step.State.skipped -> "pending"
}

@Composable
private fun ShapeTools(tools: List<SetupShape.Tool>) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Eyebrow(Words.Coach.toolsEyebrow)
    val shape = RoundedCornerShape(14.dp)
    tools.forEach { tool ->
      val (status, color) = toolStatus(tool.state)
      Column(
        Modifier.fillMaxWidth().clip(shape).background(Theme.panel).border(1.dp, Theme.line, shape).padding(12.dp)
          .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(tool.vendor, style = scaled(15, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f))
          Text(status, style = scaled(12, FontWeight.SemiBold, color))
        }
        if (tool.actions.isNotEmpty()) Text(tool.actions.joinToString(", "), style = scaled(12, color = Theme.soft, mono = true))
        (tool.state as? SetupShape.Tool.State.Failed)?.why?.let { Text(it, style = scaled(12, color = Theme.soft)) }
      }
    }
    Text(Words.Connect.signInsKeptSealedNo, style = scaled(12, color = Theme.dim))
  }
}

/** A tool's status word and its color. */
internal fun toolStatus(state: SetupShape.Tool.State): Pair<String, Color> = when (state) {
  SetupShape.Tool.State.Connected -> Words.Connect.Status.connected to Theme.ok
  SetupShape.Tool.State.SignedIn -> Words.Coach.Answer.signedIn to Theme.amberText
  SetupShape.Tool.State.Waiting -> Words.Chat.waitingForYou to Theme.amberText
  is SetupShape.Tool.State.Failed -> Words.Coach.Shape.notConnected to Theme.no
}

@Composable
private fun ShapeCard(card: SetupShape.Card) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Eyebrow(if (card.confirmed) Words.Card.cardConfirmedKiff else Words.Card.cardDraftKiff)
    // Confirmed, a solid green edge; a draft, a dashed one.
    val edge = if (card.confirmed) Theme.ok else Theme.ink.copy(alpha = 0.25f)
    Column(
      Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Theme.panel)
        .drawWithContent {
          drawContent()
          val width = 1.dp.toPx()
          drawRoundRect(
            edge,
            topLeft = androidx.compose.ui.geometry.Offset(width / 2, width / 2),
            size = androidx.compose.ui.geometry.Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(14.dp.toPx()),
            style = Stroke(width, pathEffect = if (card.confirmed) null else PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
          )
        }
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(card.title, style = scaled(13, FontWeight.SemiBold, Theme.ink, mono = true))
      card.lines.forEach { Text(it, style = scaled(12, color = Theme.ink, mono = true)) }
      if (card.lines.isEmpty()) Text(Words.Card.noActionsShort, style = scaled(12, color = Theme.ink, mono = true))
    }
    if (!card.confirmed) Text(Words.Coach.Shape.draftUntilApproved, style = scaled(12, color = Theme.dim))
  }
}
