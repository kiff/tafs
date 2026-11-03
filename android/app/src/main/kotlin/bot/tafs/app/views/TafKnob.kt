package bot.tafs.app.views

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.Knob
import bot.tafs.app.model.KnobFace
import bot.tafs.app.model.KnobRing
import bot.tafs.app.model.KnobWords
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The rings' and the rim's colours, from the approved design. */
object KnobColors {
  val wakesIt = Color(0xFF0B7DA4)
  val what = Color(0xFF2A64F5)
  val resultsGo = Color(0xFF7B4DFF)
  val done = Color(0xFF198056)
  val working = Color(0xFF2A64F5)
  val retrying = Color(0xFFFF9A2E)
  val needsYou = Color(0xFFFF8A1F)
  val heldAmount = Color(0xFFB35500)

  fun of(kind: KnobRing.Kind): Color = when (kind) {
    KnobRing.Kind.WakesIt -> wakesIt
    KnobRing.Kind.What -> what
    KnobRing.Kind.ResultsGo -> resultsGo
  }
}

/**
 * The taf's dial, as settled: the face holds its Why, and When, What and Where ring it, inside to out, each with its stops from
 * the top, clockwise. The rim counts today's work. Tap a word to choose it: its ring turns it under the pointer at the top and
 * keeps its colour, the other two dim, and the face says what it is. A ring past six stops shows the six that hold the chosen one.
 * Pressing the face is OK: the chosen stop's one action, or, with nothing chosen, the first stop.
 */
@Composable
fun TafKnob(
  knob: Knob,
  face: KnobFace,
  onChoose: (KnobRing.Kind, String) -> Unit,
  onFace: () -> Unit,
  modifier: Modifier = Modifier,
  /** Door stops a message just arrived at, by stop id: each glows for a moment. */
  arrived: Set<String> = emptySet(),
) {
  val chosen = (face as? KnobFace.Chosen)
  // A message just arrived at a door: its light, for a moment.
  val glow = if (arrived.isNotEmpty()) {
    androidx.compose.animation.core.rememberInfiniteTransition(label = "arrival").animateFloat(
      0.15f, 0.55f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "light",
    ).value
  } else 0f
  // Each ring: the stops it shows (one page of them), and its turn, which puts the chosen stop under the pointer.
  val rings = knob.rings.take(3).map { ring ->
    val index = if (chosen?.ring?.kind == ring.kind) ring.stops.indexOfFirst { it.id == chosen.stop.id } else -1
    val page = bot.tafs.app.model.DialMath.page(index)
    val shown = ring.stops.drop(page * bot.tafs.app.model.DialMath.PAGE).take(bot.tafs.app.model.DialMath.PAGE)
    val target = bot.tafs.app.model.DialMath.ringTurn(if (index < 0) 0 else index % bot.tafs.app.model.DialMath.PAGE, shown.size)
    // The short way round from where it stands.
    val turn = androidx.compose.runtime.remember(ring.kind) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(ring.kind, target) {
      turn.animateTo(turn.value + bot.tafs.app.model.DialMath.delta(turn.value.toDouble(), target).toFloat(), androidx.compose.animation.core.tween(260))
    }
    Triple(ring, shown, turn.value.toDouble())
  }
  BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f)) {
    val px = with(LocalDensity.current) { maxWidth.toPx() }
    // The design is drawn on a 360 grid; everything scales from it.
    val u = px / 360f
    val c = Offset(px / 2, px / 2)
    // Inner to outer: the band each ring fills, in grid units.
    val bands = listOf(68f to 104f, 108f to 140f, 144f to 170f)
    Canvas(Modifier.matchParentSize()) {
      bands.forEachIndexed { i, (inner, outer) ->
        val mid = (inner + outer) / 2 * u
        val ring = knob.rings.getOrNull(i)
        val dim = chosen != null && chosen.ring.kind != ring?.kind
        drawCircle(Color.White.copy(alpha = if (dim) 0.45f else 1f), mid, c, style = Stroke((outer - inner) * u))
        drawCircle(Theme.ink.copy(alpha = 0.08f), outer * u, c, style = Stroke(1f))
        drawCircle(Theme.ink.copy(alpha = 0.08f), inner * u, c, style = Stroke(1f))
      }
      // Each door on When: its arc on the ring's outer edge, under its word, as tafs cloud says it looks.
      rings.firstOrNull { it.first.kind == KnobRing.Kind.WakesIt }?.let { (_, shown, turn) ->
        val r = 104f * u
        val span = (360f / shown.size.coerceAtLeast(1) * 0.7f).coerceAtMost(48f)
        shown.forEachIndexed { j, stop ->
          val look = stop.look ?: return@forEachIndexed
          val at = (-90.0 + j * 360.0 / shown.size + turn).toFloat()
          val color = when (look) {
            "knock" -> KnobColors.needsYou
            "resting" -> Theme.dim
            "open" -> KnobColors.wakesIt
            else -> Theme.ink.copy(alpha = 0.25f)
          }
          drawArc(
            color, at - span / 2, span, false, topLeft = c - Offset(r, r), size = Size(r * 2, r * 2),
            style = Stroke(
              (if (look == "open" || look == "knock") 3.5f else 2f) * u,
              cap = androidx.compose.ui.graphics.StrokeCap.Round,
              pathEffect = if (look == "closed") androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f * u, 5f * u)) else null,
            ),
          )
          val a = Math.toRadians(at.toDouble())
          val p = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * r
          if (stop.id in arrived) drawCircle(KnobColors.wakesIt.copy(alpha = glow), 9f * u, p)
          if (look == "knock") drawCircle(KnobColors.needsYou, 3.5f * u, p)
        }
      }
      // The rim: today's work, done to needs you, clockwise from the top.
      val rim = knob.rim
      if (rim.total > 0) {
        var at = -90f
        val gap = 2f
        listOf(rim.done to KnobColors.done, rim.working to KnobColors.working, rim.retrying to KnobColors.retrying, rim.needsYou to KnobColors.needsYou)
          .filter { it.first > 0 }
          .forEach { (n, color) ->
            val sweep = 360f * n / rim.total
            val r = 176f * u
            drawArc(color, at + gap / 2, (sweep - gap).coerceAtLeast(1f), false, topLeft = c - Offset(r, r), size = Size(r * 2, r * 2), style = Stroke(4f * u))
            at += sweep
          }
      }
      // The pointer at the top: the chosen stop turns under it.
      val tip = androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - 6f * u, c.y - 180f * u)
        lineTo(c.x + 6f * u, c.y - 180f * u)
        lineTo(c.x, c.y - 171f * u)
        close()
      }
      drawPath(tip, Theme.ink)
      // The face.
      drawCircle(Brush.radialGradient(listOf(Color.White, Theme.raised), center = c - Offset(0f, 30f * u), radius = 90f * u), 64f * u, c)
      drawCircle(Theme.ink.copy(alpha = 0.10f), 64f * u, c, style = Stroke(1f))
    }
    // Each ring's words, from the top, clockwise, turned with their ring.
    rings.forEachIndexed { i, (ring, shown, turn) ->
      val (inner, outer) = bands[i]
      val mid = (inner + outer) / 2 * u
      val color = KnobColors.of(ring.kind)
      val dim = chosen != null && chosen.ring.kind != ring.kind
      shown.forEachIndexed { j, stop ->
        val fromTop = j * 360.0 / shown.size + turn
        val a = (-90 + fromTop) * PI / 180
        // Along the ring, and always upright: the lower half turns half a turn more.
        val upright = bot.tafs.app.model.DialMath.upright(fromTop).toFloat()
        val isChosen = chosen?.ring?.kind == ring.kind && chosen.stop.id == stop.id
        val tint = when {
          isChosen -> color
          chosen != null -> Theme.soft
          else -> color
        }
        Text(
          stop.label,
          style = scaled(12, if (isChosen) FontWeight.Bold else FontWeight.Medium, tint).copy(fontSize = with(LocalDensity.current) { (12 * u).toSp() }),
          maxLines = 1,
          softWrap = false,
          modifier = Modifier
            .centerAt(c.x + cos(a).toFloat() * mid, c.y + sin(a).toFloat() * mid)
            .graphicsLayer { rotationZ = upright }
            .alpha(if (dim) 0.45f else 1f)
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .semantics {
              contentDescription = KnobWords.spoken(ring, stop)
              role = Role.Button
              selected = isChosen
            }
            .clickable { onChoose(ring.kind, stop.id) }
            .padding(horizontal = 6.dp, vertical = 7.dp),
        )
      }
    }
    // The face: what it shows, and what a tap there does.
    val (top, big, tap, bigColor) = when (face) {
      KnobFace.Overview -> Quad(KnobWords.WHY, knob.does, KnobWords.TAP_A_RING.takeIf { knob.stops.isNotEmpty() }, Theme.ink)
      is KnobFace.Chosen -> Quad(
        KnobWords.chosen(face.ring, face.stop), face.stop.state ?: face.stop.size ?: face.stop.label,
        face.stop.tap ?: if (face.stop.size != null) KnobWords.USE_SIZE else KnobWords.ASK_COACH_TO_CHANGE, KnobColors.of(face.ring.kind),
      )
      is KnobFace.Held -> Quad(KnobWords.heldTop(face.call), face.call.proposed.amount?.figure ?: "", KnobWords.APPROVE_ONCE, KnobColors.heldAmount)
    }
    Box(
      Modifier.centerAt(c.x, c.y).size((128 * u / LocalDensity.current.density).dp).clip(CircleShape)
        .semantics { contentDescription = listOfNotNull(top, big, tap).joinToString(", ") }
        .clickable(onClick = onFace).semantics { onClick(label = tap) { onFace(); true } },
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(top, style = scaled(9, FontWeight.SemiBold, Theme.dim, mono = true), maxLines = 1, textAlign = TextAlign.Center)
        // The Why takes two lines, as Coach writes it in a few words; a stop's value is one big word.
        if (face == KnobFace.Overview) {
          Text(big, style = scaled(17, FontWeight.ExtraBold, bigColor, display = true), maxLines = 2, textAlign = TextAlign.Center)
        } else {
          Text(big, style = scaled(30, FontWeight.ExtraBold, bigColor, display = true), maxLines = 1)
        }
        tap?.let { Text(it, style = scaled(10, color = Theme.dim), maxLines = 1) }
      }
    }
  }
}

private data class Quad(val top: String, val big: String, val tap: String?, val color: Color)

/** Places the content centred on ([x], [y]) in its parent, in pixels: taps still reach it. */
private fun Modifier.centerAt(x: Float, y: Float): Modifier = layout { measurable, constraints ->
  val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
  layout(p.width, p.height) { p.place((x - p.width / 2f).roundToInt(), (y - p.height / 2f).roundToInt()) }
}

/**
 * The settled dial in a taf's Doors tab: Coach builds it, and this only shows it, with a remote's controls.
 * - Above it: "Ask Coach or find anything", and "Showing ▾" (All services, This taf, each connection) to see one input's stops.
 * - The dial: tap a word to choose its stop; "‹ Why" goes back to the Why. Pressing the face is OK, the stop's one action as
 *   tafs cloud names it (`ok`: open or close a door, run once, remove a follow-up, Coach), or approve a held call.
 * - The remote: ◀ ▶ step through the stops, − + move the chosen stop's one size (its choice's setting, read back as the next
 *   version), ⏻ pauses the taf. A stop with no size says "Ask Coach to change".
 * - A change waiting to go live (tafs cloud's `pending`): "DRAFT · Vn · NOT LIVE" over the dial, and "Make N changes live",
 *   the usual version switch.
 * - Under it: today's work ("12 done today ›"), which opens the request the rim points at.
 */
@Composable
fun TafDialPanel(
  taf: bot.tafs.app.model.Taf,
  wire: bot.tafs.app.kiff.KnobWire,
  state: bot.tafs.app.app.AppState,
  doors: bot.tafs.app.app.TafDoors,
  actions: DoorActions,
  onDoor: (String) -> Unit,
) {
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var showing by androidx.compose.runtime.saveable.rememberSaveable(taf.id) { androidx.compose.runtime.mutableStateOf<String?>(null) }
  var showingOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  var chosen by androidx.compose.runtime.saveable.rememberSaveable(taf.id) { androidx.compose.runtime.mutableStateOf<String?>(null) }
  var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val knob = wire.knob(showing)
  val manages = state.manages(taf.id)
  val ready = LocalApprovalReadiness.current == bot.tafs.app.model.ApprovalReadiness.Ready
  // A held call that can be approved once takes the face while nothing is chosen.
  val held = state.heldCalls(taf.id).firstOrNull { it.approvableOnce && it.install == null }?.takeIf { state.canAnswer(it) && ready }
  val stops = knob.stops
  val chosenStop = chosen?.let { id -> stops.firstOrNull { it.second.id == id } }
  val face = when {
    chosenStop != null -> KnobFace.Chosen(chosenStop.first, chosenStop.second)
    held != null -> KnobFace.Held(held)
    else -> KnobFace.Overview
  }
  fun choose(id: String?) {
    chosen = id
    // A door on When picks that door for its settings below.
    id?.let { wire.wakes.firstOrNull { w -> w.id == it }?.door }?.let(onDoor)
  }
  fun work(block: suspend () -> Unit) {
    if (busy) return
    scope.launch { busy = true; try { block() } finally { busy = false } }
  }
  val item = chosen?.let { wire.item(it) }
  val behind = taf.id in state.knobBehind
  // A door whose count today went up since it was last drawn: lit for a few seconds.
  val seen = androidx.compose.runtime.remember(taf.id) { mutableMapOf<String, Int>() }
  var arrivals by androidx.compose.runtime.remember(taf.id) { androidx.compose.runtime.mutableStateOf(emptySet<String>()) }
  // Each door's light has its own three seconds, outside the effect, so a newer arrival doesn't leave an older one lit.
  val glows = androidx.compose.runtime.remember(taf.id) { mutableMapOf<String, kotlinx.coroutines.Job>() }
  androidx.compose.runtime.LaunchedEffect(doors.doors.map { it.id to it.today }) {
    val fresh = doors.doors.filter { d -> seen[d.id]?.let { d.today > it } == true }.map { it.id }
    doors.doors.forEach { seen[it.id] = it.today }
    fresh.forEach { id ->
      glows.remove(id)?.cancel()
      arrivals = arrivals + id
      glows[id] = scope.launch {
        kotlinx.coroutines.delay(3000)
        arrivals = arrivals - id
        glows.remove(id)
      }
    }
  }
  val arrived = wire.wakes.filter { it.door != null && it.door in arrivals }.map { it.id }.toSet()

  Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
    // Above the dial: Coach, and which input's stops show.
    Text(
      "⌕  " + KnobWords.SEARCH,
      style = scaled(15, color = Theme.dim),
      modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
        .background(Theme.panel).clickable { actions.askCoach(taf.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
    )
    wire.pending?.let { Text(KnobWords.notLive(it.version), style = scaled(11, FontWeight.SemiBold, Theme.amberText, mono = true)) }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f)) {
        val name = when (showing) {
          null -> KnobWords.ALL
          bot.tafs.app.kiff.KnobWire.THIS_TAF -> KnobWords.THIS_TAF
          else -> wire.connections.firstOrNull { it.grant == showing }?.name ?: KnobWords.ALL
        }
        Text(
          KnobWords.SHOWING + " " + name + " ▾",
          style = scaled(14, FontWeight.SemiBold, Theme.ink),
          modifier = Modifier.heightIn(min = 44.dp).clickable { showingOpen = true }.padding(vertical = 12.dp),
        )
        androidx.compose.material3.DropdownMenu(showingOpen, { showingOpen = false }) {
          val options = listOf<Pair<String?, String>>(null to KnobWords.ALL, bot.tafs.app.kiff.KnobWire.THIS_TAF to KnobWords.THIS_TAF) +
            wire.connections.map { it.grant to it.name }
          options.forEach { (key, label) ->
            // Each connection with how many stops it has on the dial.
            val connection = key != null && key != bot.tafs.app.kiff.KnobWire.THIS_TAF
            androidx.compose.material3.DropdownMenuItem(
              text = { Text(if (connection) KnobWords.withStops(label, wire.knob(key).stops.size) else label) },
              onClick = { showing = key; showingOpen = false; choose(null) },
            )
          }
        }
      }
      if (face is KnobFace.Chosen) {
        Text(
          KnobWords.BACK_TO_WHY,
          style = scaled(14, FontWeight.SemiBold, Theme.soft),
          modifier = Modifier.heightIn(min = 44.dp).clickable { choose(null) }.padding(horizontal = 8.dp, vertical = 12.dp),
        )
      }
    }
    TafKnob(
      knob, face,
      arrived = arrived,
      onChoose = { _, id -> choose(id) },
      onFace = {
        when (face) {
          // Nothing chosen: OK goes to the first stop.
          KnobFace.Overview -> stops.firstOrNull()?.let { choose(it.second.id) }
          is KnobFace.Held -> work { actions.approveOnce(face.call) }
          // What OK does is tafs cloud's to say, the same in both apps.
          is KnobFace.Chosen -> when (item?.ok) {
            "door" -> item.door?.let { id -> doors.doors.firstOrNull { it.id == id } }?.let { door ->
              work { actions.setOpen(taf.id, door, !item.on); actions.loadKnob(taf.id) }
            }
            "run" -> work { actions.runNow(taf.id) }
            "remove_follow_up" -> item.followUp?.let { fid -> work { actions.removeFollowUp(taf.id, fid) } }
            "coach" -> actions.askCoach(taf.id)
            else -> Unit
          }
        }
      },
    )
    if (face == KnobFace.Overview) Text(KnobWords.CAPTION, style = scaled(12, color = Theme.dim))
    val sentence = when (face) {
      is KnobFace.Chosen -> face.stop.about
      is KnobFace.Held -> face.call.reason
      KnobFace.Overview -> knob.overview
    }
    if (sentence.isNotBlank()) Text(sentence, style = scaled(14, color = Theme.ink))
    val size = item?.size
    // The chosen stop's one size, or Coach.
    if (face is KnobFace.Chosen) {
      val now = size?.now
      if (now != null) {
        Text(size.label + " · " + now.label, style = scaled(13, FontWeight.SemiBold, Theme.soft, mono = true))
      } else {
        Text(
          KnobWords.ASK_COACH_TO_CHANGE,
          style = scaled(13, FontWeight.SemiBold, Theme.amber),
          modifier = Modifier.heightIn(min = 44.dp).clickable { actions.askCoach(taf.id) }.padding(top = 12.dp),
        )
      }
    }
    // The remote.
    val at = stops.indexOfFirst { it.second.id == chosen }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
      RemoteKey("◀", KnobWords.BACK, enabled = stops.isNotEmpty()) { choose(stops[if (at <= 0) stops.size - 1 else at - 1].second.id) }
      RemoteKey("−", KnobWords.LOWER, enabled = manages && !busy && !behind && size?.step(-1) != null) {
        size?.step(-1)?.let { c -> work { actions.changeKnobSize(taf.id, c.setting) } }
      }
      RemoteKey(POWER, if (taf.paused) KnobWords.RESUME else KnobWords.PAUSE, enabled = manages && !busy) { work { actions.setPaused(taf.id, !taf.paused); actions.loadKnob(taf.id) } }
      RemoteKey("+", KnobWords.RAISE, enabled = manages && !busy && !behind && size?.step(1) != null) {
        size?.step(1)?.let { c -> work { actions.changeKnobSize(taf.id, c.setting) } }
      }
      RemoteKey("▶", KnobWords.NEXT, enabled = stops.isNotEmpty()) { choose(stops[(at + 1) % stops.size].second.id) }
    }
    state.knobErrors[taf.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
    if (behind) {
      Text(
        KnobWords.NOT_READ_BACK,
        style = scaled(13, color = Theme.amberText),
        modifier = Modifier.clickable(enabled = !busy) { work { actions.loadKnob(taf.id) } },
      )
    }
    // The change waiting to go live (− and +, or Coach's draft), as tafs cloud counts it, made live in one switch.
    val pending = wire.pending
    if (pending != null && manages) {
      ApprovalGate {
        bot.tafs.app.design.PrimaryButton(
          KnobWords.makeLive(pending.changes, bot.tafs.app.design.LocalBiometry.current),
          { work { actions.confirmKnobChange(taf.id) } },
          enabled = !busy && taf.id !in state.switchingVersion,
        )
      }
    }
    // Under it: today's work, which opens the request the rim points at.
    knob.summary?.let { line ->
      Text(
        "$line ›",
        style = scaled(13, color = Theme.dim),
        modifier = Modifier.heightIn(min = 44.dp).clickable { scope.launch { actions.openRimRequest(taf.id) } }.padding(top = 12.dp),
      )
    }
  }
}

/** One key of the remote: its symbol, and its name in small mono under it. */
@Composable
private fun RemoteKey(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
  Column(
    Modifier.heightIn(min = 56.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick)
      .semantics { contentDescription = label }.padding(horizontal = 10.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    val tint = if (enabled) Theme.ink else Theme.dim.copy(alpha = 0.5f)
    if (symbol == POWER) {
      // The power mark isn't in the phone's font: drawn, the size of the other keys' symbols.
      Canvas(Modifier.size(26.dp).padding(3.dp)) {
        val stroke = Stroke(2.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawArc(tint, -60f, 300f, false, style = stroke)
        drawLine(tint, Offset(size.width / 2, -1.dp.toPx()), Offset(size.width / 2, size.height / 2), 2.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
      }
    } else {
      Text(symbol, style = scaled(22, FontWeight.Bold, tint))
    }
    Text(label.uppercase(), style = scaled(9, color = Theme.dim, mono = true))
  }
}

/** The remote's Pause key, drawn as the power mark. */
private const val POWER = "⏻"

