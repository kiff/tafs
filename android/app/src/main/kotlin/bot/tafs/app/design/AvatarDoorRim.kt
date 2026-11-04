package bot.tafs.app.design

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import bot.tafs.app.model.AvatarRim

/** Travels around the rounded tile; the cloud supplies its state and length. */
@Composable
fun AvatarDoorRim(rim: AvatarRim?, color: Color, modifier: Modifier = Modifier) {
  if (rim?.visible != true) return
  val animate = ValueAnimator.areAnimatorsEnabled()
  val phase = if (animate) {
    val transition = rememberInfiniteTransition(label = "Door rim")
    transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4500, easing = LinearEasing), RepeatMode.Restart), label = "Along the rim").value
  } else 0f
  val tint = when (rim.tint) { "knock" -> Theme.amber; "resting" -> Theme.dim; else -> color }
  Canvas(modifier) {
    val gap = 4.dp.toPx()
    val path = Path().apply { addRoundRect(RoundRect(-gap, -gap, size.width + gap, size.height + gap, CornerRadius(size.width * .3f + gap))) }
    val measure = PathMeasure().apply { setPath(path, false) }
    val length = measure.length
    val end = phase + rim.length.coerceIn(0f, 1f)
    val segment = Path()
    measure.getSegment(phase * length, end.coerceAtMost(1f) * length, segment)
    if (end > 1f) measure.getSegment(0f, (end - 1f) * length, segment)
    drawPath(segment, tint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
  }
}
