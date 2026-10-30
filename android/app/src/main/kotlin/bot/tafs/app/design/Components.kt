package bot.tafs.app.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bot.tafs.app.R
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafState

/** The tafs mascot. */
@Composable
fun Mascot(size: Dp = 40.dp, modifier: Modifier = Modifier) {
  Image(painterResource(R.drawable.mascot), contentDescription = null, modifier = modifier.size(size))
}

@Composable
fun TafAvatar(taf: Taf, size: Dp = 40.dp) {
  Box(
    Modifier
      .size(size)
      .clip(RoundedCornerShape(size * 0.3f))
      .background(Theme.tafColor(taf.colorIndex)),
    contentAlignment = Alignment.Center,
  ) {
    Text(taf.initial, style = scaled((size.value * 0.5f).toInt(), FontWeight.Bold, Color.White, display = true))
  }
}

@Composable
fun StateChip(state: TafState) {
  val color = when (state) {
    TafState.working -> Theme.ok
    TafState.waiting -> Theme.amber
    TafState.revoked, TafState.halted -> Theme.no
    TafState.idle, TafState.paused -> Theme.soft
  }
  Text(
    state.label,
    style = scaled(12, FontWeight.Medium, color),
    modifier = Modifier
      .clip(CircleShape)
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

@Composable
fun Meter(fraction: Double, color: Color = Theme.ok) {
  val f = fraction.coerceIn(0.0, 1.0).toFloat()
  Box(
    Modifier
      .fillMaxWidth()
      .height(5.dp)
      .clip(CircleShape)
      .background(Theme.ink.copy(alpha = 0.1f)),
  ) {
    Box(Modifier.fillMaxWidth(f).height(5.dp).clip(CircleShape).background(color))
  }
}

@Composable
fun Eyebrow(text: String, color: Color = Theme.dim) {
  Text(text.uppercase(), style = scaled(12, color = color, mono = true, tracking = 0.7.sp))
}

/** The screen title: iOS's 34pt rounded bold. */
@Composable
fun ScreenTitle(text: String) {
  Text(text, style = scaled(34, FontWeight.Bold, Theme.ink, display = true))
}

/** The main button: the brand's sweep, white text; a neutral fill while it can't be pressed. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  val shape = RoundedCornerShape(16.dp)
  Box(
    modifier
      .fillMaxWidth()
      .heightIn(min = 54.dp)
      .clip(shape)
      .then(if (enabled) Modifier.brandBackground() else Modifier.background(Theme.dim.copy(alpha = 0.22f)))
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 14.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      style = scaled(16, FontWeight.SemiBold, if (enabled) Theme.amberInk else Theme.soft),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Theme.ink, enabled: Boolean = true) {
  val shape = RoundedCornerShape(14.dp)
  Box(
    modifier
      .heightIn(min = 48.dp)
      .clip(shape)
      .border(BorderStroke(1.dp, color.copy(alpha = if (enabled) 0.4f else 0.16f)), shape)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 8.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      style = scaled(15, FontWeight.Medium, if (enabled) color else color.copy(alpha = 0.4f)),
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
fun Panel(
  modifier: Modifier = Modifier,
  stroke: Color = Theme.line,
  content: @Composable ColumnScope.() -> Unit,
) {
  val shape = RoundedCornerShape(18.dp)
  Column(
    modifier
      .fillMaxWidth()
      .clip(shape)
      .background(Theme.panel)
      .border(BorderStroke(1.dp, stroke), shape)
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    content = content,
  )
}

/** A segmented control: the options in a grey track, the chosen one a white pill, as iOS's picker. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Theme.ink.copy(alpha = 0.07f)).padding(2.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    options.forEach { option ->
      val on = option == selected
      Box(
        Modifier
          .weight(1f)
          .heightIn(min = 32.dp)
          .clip(RoundedCornerShape(7.dp))
          .background(if (on) Theme.panel else Color.Transparent)
          .clickable { onSelect(option) },
        contentAlignment = Alignment.Center,
      ) {
        Text(label(option), style = scaled(13, if (on) FontWeight.SemiBold else FontWeight.Medium, Theme.ink))
      }
    }
  }
}

/** A small filled dot, as the Record's status marks. */
@Composable
fun Dot(color: Color, size: Dp = 9.dp, modifier: Modifier = Modifier) {
  Box(modifier.size(size).clip(CircleShape).background(color))
}

/** The row of a label and its value, as the cards use. */
@Composable
fun SpacedRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { content() }
}
