package bot.tafs.app.design

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics

/*
 * The owner's illustrations for the phone's empty, first-run and waiting states (boards 01 to 10), drawn as shapes: never an
 * image file, and never a letter or a digit (the pills are placeholders for words, not words). Each is decorative, so TalkBack
 * skips it and reads the screen's own words.
 *
 * Every length is the board's own pixels: each art keeps its board's numbers and the box ([Frame]) its drawing fills on the
 * board, with the board's empty margin cropped away, and [Board] scales them to the size it's given. The art has no ground of
 * its own and sits on the screen's. Cards take the theme's panel and the placeholder pills the theme's ink at the board's
 * strength, so both read in dark; the halos are a low-alpha amber, which reads on either ground.
 */

/** The box on a board, in its pixels, that one art fills: its drawing's bounds, soft shadows included. */
private class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width get() = right - left
  val height get() = bottom - top
}

/** A canvas in a board's pixels: [x], [y] and [d] turn the board's numbers into this canvas's. */
private class Board(val scope: DrawScope, private val frame: Frame) {
  private val k = scope.size.width / frame.width

  fun x(v: Float) = (v - frame.left) * k
  fun y(v: Float) = (v - frame.top) * k
  fun d(v: Float) = v * k
  fun at(px: Float, py: Float) = Offset(x(px), y(py))
}

/** One art: its frame's shape, scaled to the width it's given, hidden from TalkBack. */
@Composable
private fun Art(modifier: Modifier, frame: Frame, draw: Board.() -> Unit) {
  Canvas(modifier.aspectRatio(frame.width / frame.height).clearAndSetSemantics {}) { Board(this, frame).draw() }
}

/** The colours the boards draw with that aren't the theme's own. */
private object Ink {
  /** The shadows' colour: the light theme's ink, and black on the dark ground (the ink would lighten it). */
  val shade get() = if (Theme.dark) Color.Black else Color(0xFF1B1726)
  val green = Color(0xFF2E9E5B)
  val deepAmber = Color(0xFFB35C00)
  val sun = Color(0xFFFF9330)
  val blue = Color(0xFF4A7BF6)
  val ringBlue = Color(0xFF648CEF)

  /** Brand's sweep, dimmed, on a halted taf's tile (board 04). */
  val haltedStops = listOf(Color(0xFF7C6349), Color(0xFF6D2A38), Color(0xFF516390))

  // Confetti.
  val tan = Color(0xFFCF9A5F)
  val sand = Color(0xFFE1C19B)
  val wheat = Color(0xFFDEB98F)
  val cornflower = Color(0xFF88A6F2)
  val mist = Color(0xFFB2C4F1)
  val rose = Color(0xFFF17B94)
  val blush = Color(0xFFF4A1AF)
  val pinkDot = Color(0xFFF394A6)
}

/** The peach halo behind an art: amber, faint, so it reads on the light ground and the dark. */
private val halo get() = Theme.amber.copy(alpha = 0.165f)

/** A placeholder pill in the theme's ink at [alpha]: 0.85 a title, 0.15 to 0.2 a line of text. */
private fun ink(alpha: Float) = Theme.ink.copy(alpha = alpha)

// The drawing kit. Each takes the board's pixels.

/** A rounded rectangle, starting at the top edge after its top-left corner and going clockwise (where a dash starts). */
private fun Board.roundRect(l: Float, t: Float, r: Float, b: Float, tl: Float, tr: Float = tl, br: Float = tl, bl: Float = tl): Path {
  val (L, T, R, B) = listOf(x(l), y(t), x(r), y(b))
  val (a, c, e, f) = listOf(d(tl), d(tr), d(br), d(bl))
  return Path().apply {
    moveTo(L + a, T)
    lineTo(R - c, T)
    if (c > 0) arcTo(Rect(R - 2 * c, T, R, T + 2 * c), -90f, 90f, false)
    lineTo(R, B - e)
    if (e > 0) arcTo(Rect(R - 2 * e, B - 2 * e, R, B), 0f, 90f, false)
    lineTo(L + f, B)
    if (f > 0) arcTo(Rect(L, B - 2 * f, L + 2 * f, B), 90f, 90f, false)
    lineTo(L, T + a)
    if (a > 0) arcTo(Rect(L, T, L + 2 * a, T + 2 * a), 180f, 90f, false)
    close()
  }
}

/** [path] blurred ([sigma], the board's pixels) and dropped by [dy]: a soft shadow or a glow. Never under [clipOut]. */
private fun Board.blurred(path: Path, color: Color, sigma: Float, dy: Float, clipOut: Path? = null) {
  val paint = Paint().apply { this.color = color }
  // Skia's blur radius for a sigma (SkBlurMask::ConvertRadiusToSigma, inverted).
  val radius = ((d(sigma) - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
  paint.asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
  scope.drawIntoCanvas { canvas ->
    canvas.save()
    if (clipOut != null) canvas.clipPath(clipOut, ClipOp.Difference)
    canvas.translate(0f, d(dy))
    canvas.drawPath(path, paint)
    canvas.restore()
  }
}

/** A card's shadow, as the boards': soft, a little below, a little inside its edges. */
private fun Board.cardShadow(l: Float, t: Float, r: Float, b: Float, radius: Float, clipOut: Path? = null) {
  val spread = 8f
  blurred(roundRect(l + spread, t + spread, r - spread, b - spread, radius - spread), Ink.shade.copy(alpha = 0.18f), sigma = 36f, dy = 28f, clipOut)
}

/** A card on its shadow: the theme's panel. A see-through card ([alpha] under 1) has no shadow under itself, as a CSS box's. */
private fun Board.card(
  l: Float, t: Float, r: Float, b: Float, radius: Float = 44f, alpha: Float = 1f,
  tl: Float = radius, tr: Float = radius, br: Float = radius, bl: Float = radius,
) {
  val shape = roundRect(l, t, r, b, tl, tr, br, bl)
  cardShadow(l, t, r, b, radius, clipOut = if (alpha < 1f) shape else null)
  scope.drawPath(shape, Theme.panel.copy(alpha = alpha))
}

/** A filled circle. */
private fun Board.disc(cx: Float, cy: Float, r: Float, color: Color) = scope.drawCircle(color, d(r), at(cx, cy))

/** A filled circle with its own colour's glow under it, as the boards' status discs. */
private fun Board.glowingDisc(cx: Float, cy: Float, r: Float, color: Color, glow: Color = color) {
  blurred(Path().apply { addOval(Rect(at(cx, cy), d(r))) }, glow.copy(alpha = 0.35f), sigma = 26f, dy = 22f)
  disc(cx, cy, r, color)
}

/** A placeholder line: a fully rounded bar. */
private fun Board.pill(l: Float, t: Float, r: Float, b: Float, color: Color) {
  val h = d(b - t)
  scope.drawRoundRect(color, at(l, t), Size(d(r - l), h), CornerRadius(h / 2))
}

/** A filled rounded rectangle. */
private fun Board.block(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Color) =
  scope.drawPath(roundRect(l, t, r, b, radius), color)

/** A three-colour sweep from [start] to [end], its middle colour a little past halfway, as the boards' tiles and sparkles. */
private fun Board.sweep(stops: List<Color>, start: Offset, end: Offset) =
  Brush.linearGradient(0f to stops[0], 0.55f to stops[1], 1f to stops[2], start = start, end = end)

/** The app tile: a rounded square in the brand's sweep, top-left to bottom-right ([stops] for another sweep). */
private fun Board.tile(l: Float, t: Float, side: Float, radius: Float, stops: List<Color> = Theme.BRAND_STOPS) {
  scope.drawPath(roundRect(l, t, l + side, t + side, radius), sweep(stops, at(l, t), at(l + side, t + side)))
}

/** A rounded rectangle outlined in dashes, the stroke centred on its edges. */
private fun Board.dashedRect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Color, width: Float = 8f, dash: Float = 16f, gap: Float = 8f) {
  scope.drawPath(
    roundRect(l, t, r, b, radius), color,
    style = Stroke(d(width), pathEffect = PathEffect.dashPathEffect(floatArrayOf(d(dash), d(gap)))),
  )
}

/** The white check on a status disc: the wear board's own, as shares of the disc's diameter. */
private fun Board.check(cx: Float, cy: Float, r: Float) {
  val size = 2 * r
  fun p(fx: Float, fy: Float) = at(cx - r + fx * size, cy - r + fy * size)
  val (s, c, e) = listOf(p(0.342f, 0.513f), p(0.441f, 0.605f), p(0.658f, 0.408f))
  val tick = Path().apply { moveTo(s.x, s.y); lineTo(c.x, c.y); lineTo(e.x, e.y) }
  scope.drawPath(tick, Color.White, style = Stroke(d(0.059f * size), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A status disc with a check: done, nothing waiting. */
private fun Board.checkDisc(cx: Float, cy: Float, r: Float, color: Color) {
  glowingDisc(cx, cy, r, color)
  check(cx, cy, r)
}

/** The ink disc with two bars: paused. */
private fun Board.pauseDisc(cx: Float, cy: Float, r: Float) {
  glowingDisc(cx, cy, r, Theme.ink, glow = Ink.shade)
  block(cx - 28f, cy - 36f, cx - 8f, cy + 36f, 6f, Theme.panel)
  block(cx + 8f, cy - 36f, cx + 28f, cy + 36f, 6f, Theme.panel)
}

/** A four-point sparkle: four tips [radius] out, its sides curved in. */
private fun Board.sparkle(cx: Float, cy: Float, radius: Float, brush: Brush) {
  val c = at(cx, cy)
  val r = d(radius)
  val (n, m) = 0.086f * r to 0.33f * r
  val star = Path().apply {
    moveTo(c.x, c.y - r)
    cubicTo(c.x + n, c.y - m, c.x + m, c.y - n, c.x + r, c.y)
    cubicTo(c.x + m, c.y + n, c.x + n, c.y + m, c.x, c.y + r)
    cubicTo(c.x - n, c.y + m, c.x - m, c.y + n, c.x - r, c.y)
    cubicTo(c.x - m, c.y - n, c.x - n, c.y - m, c.x, c.y - r)
    close()
  }
  scope.drawPath(star, brush)
}

/** A sparkle in the brand's sweep, over its own bounds. */
private fun Board.brandSparkle(cx: Float, cy: Float, radius: Float) =
  sparkle(cx, cy, radius, sweep(Theme.BRAND_STOPS, at(cx - radius, cy - radius), at(cx + radius, cy + radius)))

/** A ring's track: a full circle stroked [width] wide. */
private fun Board.ring(cx: Float, cy: Float, r: Float, width: Float, color: Color) =
  scope.drawCircle(color, d(r), at(cx, cy), style = Stroke(d(width)))

/** Progress on a ring: from twelve o'clock, clockwise, [sweep] degrees, its ends rounded. */
private fun Board.arc(cx: Float, cy: Float, r: Float, width: Float, sweep: Float, brush: Brush) =
  scope.drawArc(brush, -90f, sweep, false, at(cx - r, cy - r), Size(d(2 * r), d(2 * r)), style = Stroke(d(width), cap = StrokeCap.Round))

/** A bit of confetti: a dot. */
private fun Board.confettiDot(cx: Float, cy: Float, r: Float, color: Color) = disc(cx, cy, r, color)

/** A bit of confetti: a rounded square, turned [degrees] clockwise. */
private fun Board.confettiSquare(cx: Float, cy: Float, side: Float, degrees: Float, color: Color) {
  scope.rotate(degrees, at(cx, cy)) { block(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2, 6f, color) }
}

/** A row of [count] dots, [step] apart from [x0]: a link between two things. */
private fun Board.dotRow(x0: Float, cy: Float, count: Int, step: Float, r: Float, color: Color) {
  for (i in 0 until count) disc(x0 + i * step, cy, r, color)
}

/** Whatever [draw] draws, at [alpha] as one: a faded card with its shadow. */
private fun Board.faded(alpha: Float, draw: () -> Unit) {
  scope.drawIntoCanvas { canvas ->
    canvas.saveLayer(Rect(Offset.Zero, scope.size).inflate(scope.size.maxDimension), Paint().apply { this.alpha = alpha })
    draw()
    canvas.restore()
  }
}

// The ten arts.

/** Board 01, "First run": a taf card with its app tile and an amber button, on a halo with confetti; Welcome before any taf. */
@Composable
fun FirstRunArt(modifier: Modifier = Modifier) = Art(modifier, Frame(440f, 240f, 1156f, 900f)) {
  disc(800f, 540f, 300f, halo)
  confettiDot(458f, 278f, 17.5f, Ink.tan)
  confettiSquare(1136f, 356f, 32f, 20f, Ink.cornflower)
  confettiSquare(494f, 854f, 28f, -20f, Ink.rose)
  confettiDot(1100f, 880f, 20f, Ink.sand)
  card(600f, 300f, 1000f, 800f)
  tile(648f, 348f, 104f, 24f)
  pill(648f, 496f, 888f, 516f, ink(0.85f))
  pill(648f, 540f, 828f, 560f, ink(0.18f))
  pill(648f, 584f, 928f, 604f, ink(0.18f))
  pill(648f, 684f, 952f, 764f, Theme.amber)
}

/** Board 02, "No tafs yet": an empty dashed slot with an amber add disc, two faint cards below; Tafs when there are none. */
@Composable
fun NoTafsArt(modifier: Modifier = Modifier) = Art(modifier, Frame(435f, 220f, 1165f, 885f)) {
  disc(800f, 520f, 300f, halo)
  dashedRect(584f, 324f, 1032f, 732f, 44f, ink(0.35f))
  card(460f, 760f, 700f, 816f, radius = 28f, alpha = 0.6f)
  card(900f, 760f, 1140f, 816f, radius = 28f, alpha = 0.6f)
  glowingDisc(800f, 520f, 64f, Theme.amber, glow = Ink.deepAmber)
  val plus = Stroke(d(12f), cap = StrokeCap.Round)
  scope.drawLine(Color.White, at(771f, 520f), at(829f, 520f), plus.width, plus.cap)
  scope.drawLine(Color.White, at(800f, 491f), at(800f, 549f), plus.width, plus.cap)
}

/** Board 03, "Brief, nothing to do": a sun over a card of green, done rows; the Brief when nothing needs doing. */
@Composable
fun BriefClearArt(modifier: Modifier = Modifier) = Art(modifier, Frame(447f, 120f, 1153f, 1021f)) {
  disc(800f, 300f, 180f, halo)
  disc(800f, 300f, 120f, Ink.sun)
  card(500f, 500f, 1100f, 940f)
  pill(556f, 556f, 836f, 576f, ink(0.85f))
  disc(590f, 666f, 33f, Ink.green)
  pill(652f, 656f, 952f, 676f, ink(0.15f))
  disc(590f, 770f, 33f, Ink.green)
  pill(652f, 760f, 892f, 780f, ink(0.15f))
}

/** Board 04, "All halted": a taf card with a dimmed tile and a pause disc, two faint cards behind; Tafs when every taf is halted. */
@Composable
fun AllHaltedArt(modifier: Modifier = Modifier) = Art(modifier, Frame(446f, 240f, 1152f, 860f)) {
  disc(800f, 540f, 300f, ink(0.06f))
  scope.rotate(-6f, at(650f, 590f)) { card(500f, 400f, 800f, 780f, radius = 40f, alpha = 0.72f) }
  scope.rotate(5f, at(950f, 570f)) { card(800f, 380f, 1100f, 760f, radius = 40f, alpha = 0.72f) }
  card(650f, 350f, 950f, 750f)
  tile(690f, 390f, 80f, 24f, Ink.haltedStops)
  pill(690f, 506f, 870f, 526f, ink(0.30f))
  pill(690f, 546f, 830f, 566f, ink(0.15f))
  pauseDisc(956f, 356f, 76f)
}

/** Board 05, "App connected": the app's tile and the connected app, linked by an amber dashed line through a check; a new connection. */
@Composable
fun AppConnectedArt(modifier: Modifier = Modifier) = Art(modifier, Frame(287f, 375f, 1313f, 841f)) {
  scope.drawLine(
    Theme.amber, at(700f, 581f), at(900f, 581f), d(10f),
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(d(20f), d(10f))),
  )
  card(340f, 400f, 700f, 760f)
  tile(430f, 490f, 180f, 48f)
  card(900f, 400f, 1260f, 760f)
  scope.drawPath(roundRect(903f, 403f, 1257f, 757f, 41f), Theme.ink, style = Stroke(d(6f)))
  disc(1080f, 580f, 80f, Ink.blue)
  checkDisc(800f, 580f, 64f, Ink.deepAmber)
}

/** Board 06, "Needs you, nothing": a green check over an empty card, on a halo with confetti; Needs you when nothing waits. */
@Composable
fun NeedsYouClearArt(modifier: Modifier = Modifier) = Art(modifier, Frame(456f, 200f, 1170f, 881f)) {
  disc(800f, 500f, 300f, halo)
  confettiDot(1136f, 376f, 16f, Ink.wheat)
  confettiDot(468f, 612f, 12f, Ink.mist)
  confettiDot(1160f, 710f, 9f, Ink.blush)
  card(540f, 500f, 1060f, 800f, radius = 52f)
  pill(660f, 660f, 940f, 680f, ink(0.12f))
  pill(710f, 704f, 890f, 724f, ink(0.08f))
  checkDisc(800f, 380f, 80f, Ink.green)
}

/** Board 07, "Coach, nothing yet": Coach's sparkle, linked by dots to a suggested taf and an empty slot; Coach before it suggests. */
@Composable
fun CoachStartArt(modifier: Modifier = Modifier) = Art(modifier, Frame(280f, 260f, 1456f, 841f)) {
  disc(540f, 520f, 260f, halo)
  brandSparkle(540f, 520f, 140f)
  sparkle(730f, 360f, 40f, SolidColor(Theme.amber.copy(alpha = 0.6f)))
  dotRow(765f, 581f, 6, 18f, 4.5f, Theme.amber.copy(alpha = 0.75f))
  card(880f, 400f, 1180f, 760f)
  tile(920f, 440f, 80f, 24f)
  pill(920f, 556f, 1100f, 576f, ink(0.85f))
  pill(920f, 596f, 1060f, 616f, ink(0.18f))
  pill(920f, 676f, 1140f, 736f, Theme.amber.copy(alpha = 0.25f))
  dashedRect(1224f, 434f, 1452f, 742f, 40f, ink(0.22f))
}

/** Board 08, "New taf, small": a request, linked by dots to the taf it makes, with a sparkle; making a new taf. */
@Composable
fun NewTafArt(modifier: Modifier = Modifier) = Art(modifier, Frame(87f, 40f, 773f, 480f)) {
  disc(460f, 260f, 220f, halo)
  card(140f, 140f, 440f, 332f, radius = 48f, bl = 12f)
  pill(180f, 180f, 380f, 200f, ink(0.70f))
  pill(180f, 220f, 320f, 240f, ink(0.20f))
  pill(180f, 260f, 352f, 280f, ink(0.20f))
  dotRow(460f, 234f, 5, 16f, 3.5f, Theme.amber)
  card(540f, 140f, 720f, 372f)
  tile(568f, 168f, 56f, 17f)
  pill(568f, 248f, 672f, 264f, ink(0.80f))
  pill(568f, 280f, 648f, 296f, ink(0.18f))
  sparkle(714f, 138f, 28f, SolidColor(Theme.amber))
}

/** Board 09, "Coach loading": Coach's sparkle in two progress rings over three status cards; Coach while it works. */
@Composable
fun CoachLoadingArt(modifier: Modifier = Modifier) = Art(modifier, Frame(394f, 110f, 1601f, 1101f)) {
  confettiDot(614f, 234f, 13.5f, Ink.pinkDot)
  confettiDot(1398f, 198f, 17.5f, Ink.wheat)
  ring(1000f, 400f, 280f, 20f, ink(0.07f))
  arc(1000f, 400f, 280f, 20f, 225f, SolidColor(Theme.amber))
  ring(1000f, 400f, 216f, 16f, ink(0.06f))
  arc(1000f, 400f, 216f, 16f, 137f, SolidColor(Ink.ringBlue))
  brandSparkle(1000f, 400f, 120f)
  faded(0.9f) {
    card(440f, 800f, 780f, 1020f)
    disc(506f, 866f, 30f, Ink.green)
    pill(476f, 928f, 696f, 948f, ink(0.15f))
  }
  card(830f, 800f, 1170f, 1020f)
  disc(896f, 866f, 30f, Theme.amber)
  pill(866f, 928f, 1086f, 948f, ink(0.15f))
  faded(0.5f) {
    card(1220f, 800f, 1560f, 1020f)
    disc(1286f, 866f, 30f, ink(0.17f))
    pill(1256f, 928f, 1476f, 948f, ink(0.12f))
  }
}

/** Board 10, "Overview": a progress ring, a rising chart and three taf cards; the overview of a person's tafs. */
@Composable
fun OverviewArt(modifier: Modifier = Modifier) = Art(modifier, Frame(127f, 135f, 1873f, 1121f)) {
  card(180f, 160f, 900f, 680f)
  ring(540f, 378f, 120f, 32f, ink(0.07f))
  arc(540f, 378f, 120f, 32f, 245f, Brush.linearGradient(Theme.BRAND_STOPS, at(404f, 514f), at(676f, 242f)))
  pill(400f, 572f, 680f, 592f, ink(0.15f))

  card(960f, 160f, 1820f, 680f)
  pill(1016f, 216f, 1256f, 236f, ink(0.80f))
  val line = Path().apply {
    moveTo(x(1016f), y(543.5f))
    cubicTo(x(1097f), y(527.3f), x(1148f), y(463f), x(1219f), y(463f))
    cubicTo(x(1240.6f), y(463f), x(1267.6f), y(470.5f), x(1273f), y(470.5f))
    cubicTo(x(1355.5f), y(470.5f), x(1413.7f), y(396f), x(1467f), y(396f))
    cubicTo(x(1531f), y(396f), x(1529f), y(411.5f), x(1547f), y(411.5f))
    cubicTo(x(1644.7f), y(411.5f), x(1666.4f), y(335f), x(1764f), y(335f))
  }
  val area = Path().apply {
    addPath(line)
    lineTo(x(1764f), y(604f))
    lineTo(x(1016f), y(604f))
    close()
  }
  scope.drawPath(area, Theme.amber.copy(alpha = 0.12f))
  scope.clipRect(x(1016f), 0f, x(1764f), scope.size.height) {
    drawPath(line, Theme.amber, style = Stroke(d(12f), join = StrokeJoin.Round))
  }
  disc(1516f, 404f, 20f, Ink.deepAmber)

  card(180f, 740f, 700f, 1040f)
  tile(224f, 784f, 76f, 24f)
  pill(224f, 900f, 504f, 920f, ink(0.75f))
  pill(224f, 940f, 424f, 960f, ink(0.15f))
  card(740f, 740f, 1260f, 1040f)
  disc(822f, 822f, 37f, Ink.green)
  pill(784f, 900f, 1024f, 920f, ink(0.75f))
  pill(784f, 940f, 1084f, 960f, ink(0.15f))
  card(1300f, 740f, 1820f, 1040f)
  disc(1382f, 822f, 37f, Ink.blue)
  pill(1344f, 900f, 1604f, 920f, ink(0.75f))
  pill(1344f, 940f, 1524f, 960f, ink(0.15f))
}
