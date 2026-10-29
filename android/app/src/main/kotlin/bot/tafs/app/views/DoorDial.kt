package bot.tafs.app.views

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.Door
import bot.tafs.app.kiff.Knock
import bot.tafs.app.model.DialMath
import bot.tafs.app.model.Taf
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The dial (as iOS's DoorDial): the taf in the middle, its doors on a ring that turns, a pointer at the top. Each stop is a
 * door; the last is always "Add a door". Drag or tap a stop to turn it; press the taf to open or close the door at the top.
 * TalkBack reads it as one adjustable control: swipe up or down to pick a door, double-tap to open or close it.
 */
@Composable
fun DoorDial(
  taf: Taf,
  doors: List<Door>,
  knocks: List<Knock>,
  selected: Int,
  onSelect: (Int) -> Unit,
  onPress: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val stops = doors.size + 1
  val step = DialMath.step(stops)
  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current
  val context = LocalContext.current
  // Animations off in the phone's settings: the dial jumps and the glow holds still.
  val reduceMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
  val rotation = remember { Animatable((selected * step).toFloat()) }
  val current by rememberUpdatedState(selected)
  val select by rememberUpdatedState(onSelect)
  val color = Theme.tafColor(taf.colorIndex)
  val anyOpen = doors.any { it.open }
  val breath = if (anyOpen && !reduceMotion) {
    rememberInfiniteTransition(label = "breathe").animateFloat(0.12f, 0.22f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "glow").value
  } else if (anyOpen) 0.12f else 0.04f

  fun snap(raw: Int) {
    val target = (raw * step).toFloat()
    val now = DialMath.stop(target.toDouble(), stops)
    if (now != current) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    select(now)
    scope.launch {
      if (reduceMotion) rotation.snapTo(target) else rotation.animateTo(target, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
    }
  }
  /** Turns the shortest way to stop [i]. */
  fun turnTo(i: Int) {
    val at = (rotation.value / step).roundToInt()
    snap(at + DialMath.shortest(at, i, stops))
  }
  // A door added or removed: the dial keeps to a stop that exists.
  LaunchedEffect(stops) { snap(minOf(current, stops - 1)) }

  val label = doors.getOrNull(selected)?.let { "${it.label} door, ${if (it.open) "open" else "closed"}, ${selected + 1} of $stops" }
    ?: "Add a door, $stops of $stops"
  val hint = doors.getOrNull(selected)?.let { "${if (it.open) "Close" else "Open"} ${it.label}" } ?: "Add a door"

  BoxWithConstraints(
    modifier
      .fillMaxWidth()
      .height(300.dp)
      .clearAndSetSemantics {
        contentDescription = "Door dial"
        stateDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(selected.toFloat(), 0f..(stops - 1).toFloat(), steps = maxOf(stops - 2, 0))
        setProgress { v -> turnTo(v.roundToInt().coerceIn(0, stops - 1)); true }
        customActions = listOf(
          CustomAccessibilityAction("Next door") { turnTo((current + 1) % stops); true },
          CustomAccessibilityAction("Previous door") { turnTo((current - 1 + stops) % stops); true },
        )
        onClick(label = hint) { onPress(); true }
      },
  ) {
    val density = LocalDensity.current
    val w = with(density) { maxWidth.toPx() }
    val h = with(density) { maxHeight.toPx() }
    val dp = density.density
    val r = min(w, h) * 0.36f
    val c = Offset(w / 2, h / 2 + 6 * dp)
    fun angleAt(p: Offset) = atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble()) * 180 / PI
    val rot = rotation.value

    Box(
      Modifier.matchParentSize().pointerInput(stops) {
        var startAngle = 0.0
        var startRotation = 0f
        detectDragGestures(
          onDragStart = { p -> startAngle = angleAt(p); startRotation = rotation.value },
          onDrag = { change, _ ->
            val turned = startRotation - DialMath.delta(startAngle, angleAt(change.position)).toFloat()
            scope.launch { rotation.snapTo(turned) }
            val now = DialMath.stop(turned.toDouble(), stops)
            if (now != current) {
              select(now)
              haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
          },
          onDragEnd = { snap((rotation.value / step).roundToInt()) },
          onDragCancel = { snap((rotation.value / step).roundToInt()) },
        )
      },
    ) {
      Canvas(Modifier.matchParentSize()) {
        // The taf's glow: it breathes while a door is open.
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = breath), Color.Transparent), center = c, radius = r * 1.3f), r * 1.3f, c)
        // The knob, with grip ticks that turn with it.
        val knob = r - 14 * dp
        drawCircle(Brush.linearGradient(listOf(Theme.panel, Theme.raised), c - Offset(knob, knob), c + Offset(knob, knob)), knob, c)
        drawCircle(Theme.line, knob, c, style = Stroke(1 * dp))
        for (i in 0 until 60) {
          val a = (-90 + i * 6 - rot) * PI / 180
          val len = (if (i % 5 == 0) 8 else 4) * dp
          val mid = r - 26 * dp
          val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
          drawLine(Theme.ink.copy(alpha = if (i % 5 == 0) 0.16f else 0.07f), c + dir * (mid - len / 2), c + dir * (mid + len / 2), 1 * dp)
        }
        drawCircle(Theme.line, r, c, style = Stroke(1 * dp))
        // Each door's arc: lit while open, dashed while closed, grey while resting.
        val span = min(step * 0.76, 56.0).toFloat()
        doors.forEachIndexed { i, door ->
          val at = DialMath.angle(i, stops, rot.toDouble()).toFloat()
          val arc = if (door.open) (if (door.resting) Theme.dim else color) else Theme.ink.copy(alpha = 0.22f)
          drawArc(
            arc, at - span / 2, span, useCenter = false, topLeft = c - Offset(r, r), size = Size(r * 2, r * 2),
            style = Stroke(
              width = (if (door.open) 4 else 2) * dp,
              cap = StrokeCap.Round,
              pathEffect = if (door.open) null else PathEffect.dashPathEffect(floatArrayOf(3 * dp, 5 * dp)),
            ),
          )
        }
        // The pointer.
        val py = c.y - r - 20 * dp
        drawPath(
          Path().apply {
            moveTo(c.x - 6 * dp, py - 4.5f * dp); lineTo(c.x + 6 * dp, py - 4.5f * dp); lineTo(c.x, py + 4.5f * dp); close()
          },
          Theme.ink,
        )
      }
      for (i in 0 until stops) {
        val a = DialMath.angle(i, stops, rot.toDouble()) * PI / 180
        val isSelected = i == selected
        val door = doors.getOrNull(i)
        val knocking = door != null && knocks.any { it.door == door.id && !it.isLine }
        val size = if (isSelected) 30 else 24
        Box(Modifier.centerAt(c.x + cos(a).toFloat() * r, c.y + sin(a).toFloat() * r).clickable { turnTo(i) }) {
          if (door != null) DoorBadge(door.app, door.open, size)
          else {
            Box(
              Modifier.size(size.dp).clip(CircleShape).background(Theme.panel)
                .border(BorderStroke(1.5.dp, color), CircleShape),
              contentAlignment = Alignment.Center,
            ) { Text("+", style = scaled(14, FontWeight.Bold, color)) }
          }
          if (knocking) Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(CircleShape).background(Theme.amber))
        }
        // The name outside the ring; the one at the top says it under the taf instead.
        if (!(isSelected && abs(sin(a) + 1) < 0.05)) {
          Text(
            (door?.label ?: "Add").uppercase(),
            style = scaled(if (isSelected) 11 else 10, if (isSelected) FontWeight.SemiBold else FontWeight.Normal, if (isSelected) Theme.ink else Theme.dim, mono = true),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.centerAt(c.x + cos(a).toFloat() * (r + 40 * dp), c.y + sin(a).toFloat() * (r + 30 * dp)),
          )
        }
      }
      Box(Modifier.centerAt(c.x, c.y - 6 * dp).clip(CircleShape).clickable(onClick = onPress)) { TafAvatar(taf, 56.dp) }
      val line = doors.getOrNull(selected)?.let { "${it.label.uppercase()} · ${if (it.open) "OPEN" else "CLOSED"}" } ?: "ADD A DOOR"
      val lineColor = doors.getOrNull(selected)?.let { if (it.open) color else Theme.dim } ?: color
      Text(line, style = scaled(10, FontWeight.SemiBold, lineColor, mono = true), maxLines = 1, softWrap = false, modifier = Modifier.centerAt(c.x, c.y + 38 * dp))
    }
  }
}

/** Places the content centred on ([x], [y]) in its parent, in pixels, as Modifier.offset does: taps still reach it. */
private fun Modifier.centerAt(x: Float, y: Float): Modifier = layout { measurable, constraints ->
  val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
  layout(p.width, p.height) { p.place((x - p.width / 2f).roundToInt(), (y - p.height / 2f).roundToInt()) }
}
