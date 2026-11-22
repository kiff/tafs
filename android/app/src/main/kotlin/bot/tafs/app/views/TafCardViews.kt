package bot.tafs.app.views

import bot.tafs.app.design.tapping
import bot.tafs.app.model.Words
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bot.tafs.app.R
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.Outside
import bot.tafs.app.model.TafCard
import bot.tafs.app.model.shownDate
import androidx.compose.material3.Icon

/** At most this wide, on a phone and a tablet alike. */
private val CardWidth = 300.dp

/** A payment card's proportions, 85.6 by 54 mm. */
private const val CardRatio = 85.6f / 54f

/** A live Card in the taf's colour; one scoped to another environment in grey. */
fun cardFill(card: TafCard, color: Color): Color = if (card.live) color else Theme.dim

private fun faded(card: TafCard, revoked: Boolean) = revoked || !card.active

/** The Card as TalkBack reads it: what's left, then its name, its limits, who issued it and until when. */
fun spokenCard(card: TafCard, issuedBy: String, revoked: Boolean): String {
  val parts = mutableListOf<String>()
  card.heroLine?.let { parts += it }
  parts += listOfNotNull(card.title, card.environmentLabel).joinToString(", ")
  parts += card.facts
  if (card.rules.isEmpty()) parts += Words.Card.noActions
  parts += Words.Card.issuedByNamed(issuedBy)
  val end = card.expires?.let(::shownDate)
  parts += if (revoked) Words.Card.revoked else if (!card.active) (end?.let { Words.Card.endedOn(it) } ?: Words.Card.ended) else end?.let { Words.Card.untilDate(it) } ?: Words.Card.noEndDate
  return parts.joinToString(". ") { it.trim() } + "."
}

/**
 * A Card drawn as a card (kiff/tafs#211): a payment card's shape at a set size, never the full
 * width; what's left is the big figure, the other limits small lines under it.
 */
@Composable
fun TafCardFace(card: TafCard, issuedBy: String, color: Color, revoked: Boolean = false, modifier: Modifier = Modifier) {
  val white = Color.White
  // A card's shape, as wide as it may be; taller only when larger text needs it (Android's font size), so its last line, who
  // issued it and until when, is never cut off.
  androidx.compose.foundation.layout.BoxWithConstraints(modifier.widthIn(max = CardWidth).fillMaxWidth()) {
  val shape = maxWidth / CardRatio
  Box(
    Modifier
      .fillMaxWidth()
      .heightIn(min = shape)
      .shadow(3.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black.copy(alpha = 0.12f))
      .clip(RoundedCornerShape(18.dp))
      .background(cardFill(card, color).copy(alpha = if (faded(card, revoked)) 0.4f else 1f))
      .drawBehind {
        // A ring at the top end corner, as the mascot's sweep: 130 across, nudged off the edge (the top left, in right to left).
        val x = bot.tafs.app.design.Directions.fromEndX(size.width, 30.dp.toPx(), layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl)
        drawCircle(Color.White.copy(alpha = 0.18f), radius = 65.dp.toPx(), center = Offset(x, 20.dp.toPx()), style = Stroke(1.dp.toPx()))
      }
      .clearAndSetSemantics { contentDescription = spokenCard(card, issuedBy, revoked) },
  ) {
    // Measured without a ceiling, so the spacers share only what the card's shape leaves over, never a taller parent's height.
    Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).heightIn(min = shape).padding(12.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(card.title, style = scaled(12, FontWeight.SemiBold, white, mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        card.environmentLabel?.let { EnvironmentPill(it) }
      }
      Spacer(Modifier.weight(1f))
      // A revoked Card is used by nothing: no "left today" on its face (a Card's figure is for one still in use).
      card.hero?.takeUnless { revoked }?.let { hero ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(hero.figure, style = scaled(34, FontWeight.Bold, white).copy(fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false)
          Text(hero.words, style = scaled(12, FontWeight.Medium, white), modifier = Modifier.alpha(0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      card.facts.forEach { fact ->
        Text(fact, style = scaled(11, color = white, mono = true), modifier = Modifier.alpha(0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (card.rules.isEmpty()) Text(Words.Card.noActionsShort, style = scaled(12, color = white, mono = true))
      Spacer(Modifier.weight(1f))
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
          Text(Words.Card.issuedBy, style = scaled(10, color = white), modifier = Modifier.alpha(0.75f))
          Text(issuedBy, style = scaled(12, FontWeight.SemiBold, white), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
          if (revoked) {
            // Revoked is the whole of it: no end date beneath, which would read as if it still ran.
            Text(Words.Card.revoked, style = scaled(12, FontWeight.SemiBold, white), modifier = Modifier.testTag(CARD_REVOKED))
          } else {
            // A Card with no end says so, never a far-off date (kiff/tafs#96).
            Text(
              if (!card.active) Words.Card.ended else if (card.expires == null) Words.Card.ends else Words.Card.until,
              style = scaled(10, color = white), modifier = Modifier.alpha(0.75f),
            )
            Text(card.expires?.let(::shownDate) ?: Words.Card.noEndDate, style = scaled(12, FontWeight.SemiBold, white))
          }
        }
      }
    }
  }
  }
}

/** The environment a Card is scoped to, when it isn't production. */
@Composable
fun EnvironmentPill(text: String) {
  Text(
    text,
    style = scaled(10, FontWeight.SemiBold, Color.White, mono = true, tracking = 0.6.sp),
    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

/**
 * A taf's Cards as a wallet stack (kiff/tafs#211): the Card in front whole, the others behind it
 * showing their top edge, each a shade darker, so their number and names read at once. Tapping one
 * brings it forward.
 */
@Composable
fun CardStack(
  cards: List<TafCard>,
  issuedBy: String,
  color: Color,
  front: String?,
  onFront: (String) -> Unit,
  revoked: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val shown = cards.firstOrNull { it.id == front } ?: cards.firstOrNull()
  // The furthest back on top, as in a wallet: the reverse of the order they're listed in.
  val behind = cards.filter { it.id != shown?.id }.reversed()
  val ordered = behind + listOfNotNull(shown)
  // Each Card 26 lower than the one behind it, the stack as tall as its Cards are: a face that larger text made taller pushes
  // what follows down rather than over it.
  androidx.compose.ui.layout.Layout(
    content = {
      ordered.forEachIndexed { index, card ->
        Box(Modifier.tapping { onFront(card.id) }) {
          TafCardFace(card, issuedBy, color, revoked)
          // Each Card behind a shade darker, so Cards of the same colour read as separate.
          val shade = behindShade(ordered.size - 1 - index)
          if (shade > 0f) Box(Modifier.matchParentSize().clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = shade)))
        }
      }
    },
    modifier = modifier.widthIn(max = CardWidth).fillMaxWidth(),
  ) { measurables, constraints ->
    val step = 26.dp.roundToPx()
    val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
    val height = placeables.withIndex().maxOfOrNull { (index, p) -> p.height + step * index } ?: 0
    layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
      placeables.forEachIndexed { index, p -> p.place(0, step * index) }
    }
  }
}

/** How much darker a Card [depth] places behind the front one is drawn: none in front, then deeper, never hiding its edge. */
fun behindShade(depth: Int): Float = if (depth <= 0) 0f else minOf(0.12f + 0.08f * (depth - 1), 0.32f)

/**
 * A taf's Cards in one line (kiff/tafs#211): the front Card's balance, its name, its environment
 * and how many Cards there are. Tapping it opens the Card tab.
 */
@Composable
fun CardBar(card: TafCard, count: Int, color: Color, revoked: Boolean = false, onOpen: () -> Unit) {
  val shape = RoundedCornerShape(14.dp)
  Row(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(Theme.panel)
      .border(BorderStroke(1.dp, Theme.line), shape)
      .tapping(onClick = onOpen)
      .padding(horizontal = 10.dp, vertical = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // A small Card, with the Card's mark on it: never an empty coloured block (the owner's Android, 19 Nov), as iOS's.
    Box(
      Modifier.size(width = 46.dp, height = 29.dp).clip(RoundedCornerShape(5.dp))
        .background(cardFill(card, color).copy(alpha = if (faded(card, revoked)) 0.4f else 1f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painterResource(R.drawable.ic_card), contentDescription = null, tint = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.size(16.dp).testTag(CARD_BAR_MARK),
      )
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      // Up to two lines each, so a long Card's name reads whole, as iOS's.
      Text(
        card.heroLine ?: card.title,
        style = scaled(15, FontWeight.SemiBold, Theme.ink).copy(fontFeatureSettings = "tnum"),
        maxLines = 2, overflow = TextOverflow.Ellipsis,
      )
      Text(details(card, count), style = scaled(12, color = Theme.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Theme.dim, modifier = Modifier.size(16.dp))
  }
}

/** The mark on a Card bar's small Card. */
internal const val CARD_BAR_MARK = "card.bar.mark"

/** "Refunds · 3 Cards": the Card's name, its environment when it isn't production, and the others. */
private fun details(card: TafCard, count: Int): String {
  val parts = mutableListOf(card.title)
  card.environmentLabel?.let { parts += it.lowercase().replaceFirstChar(Char::uppercase) }
  if (count > 1) parts += Words.Card.count(count = count)
  return parts.joinToString(" · ")
}

/**
 * Every action a Card covers, with its limits and what happens outside them: what the owner reads
 * in full on the Card tab and wherever they sign for a Card, since the face shows only its main
 * limit (kiff/tafs#211).
 */
@Composable
fun CardLines(rules: List<CardRule>) {
  Panel {
    rules.forEach { rule ->
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(rule.label, style = scaled(15, FontWeight.SemiBold, Theme.ink))
          Text(if (rule.outside == Outside.hold) Words.Card.outsideHeld else Words.Card.outsideRefused, style = scaled(12, color = Theme.dim))
        }
        Text(
          rule.summary,
          style = scaled(13, color = if (rule.never) Theme.no else Theme.ink, mono = true),
          textAlign = TextAlign.End,
          modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
      }
    }
  }
}

internal const val CARD_REVOKED = "card-revoked"
