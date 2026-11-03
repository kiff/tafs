package bot.tafs.app.views

import androidx.compose.foundation.Canvas
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
  val whenRing = Color(0xFF2A64F5)
  val resultsGo = Color(0xFF7B4DFF)
  val done = Color(0xFF198056)
  val working = Color(0xFF2A64F5)
  val retrying = Color(0xFFFF9A2E)
  val needsYou = Color(0xFFFF8A1F)
  val heldAmount = Color(0xFFB35500)

  fun of(kind: KnobRing.Kind): Color = when (kind) {
    KnobRing.Kind.WakesIt -> wakesIt
    KnobRing.Kind.When -> whenRing
    KnobRing.Kind.ResultsGo -> resultsGo
  }
}

/**
 * The taf's dial (K1 to K3): the face holds the job, and Wakes it, When and Results go ring it, inside to out, each with its
 * stops from the top, clockwise. The rim counts today's work. Tap a word to choose it: its ring keeps its colour, the other two
 * dim, and the face says what it is. Tap the face to act on what it shows.
 */
@Composable
fun TafKnob(
  knob: Knob,
  face: KnobFace,
  onChoose: (KnobRing.Kind, String) -> Unit,
  onFace: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val chosen = (face as? KnobFace.Chosen)
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
        val dim = chosen != null && chosen.ring != ring?.kind
        drawCircle(Color.White.copy(alpha = if (dim) 0.45f else 1f), mid, c, style = Stroke((outer - inner) * u))
        drawCircle(Theme.ink.copy(alpha = 0.08f), outer * u, c, style = Stroke(1f))
        drawCircle(Theme.ink.copy(alpha = 0.08f), inner * u, c, style = Stroke(1f))
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
      // The face.
      drawCircle(Brush.radialGradient(listOf(Color.White, Theme.raised), center = c - Offset(0f, 30f * u), radius = 90f * u), 64f * u, c)
      drawCircle(Theme.ink.copy(alpha = 0.10f), 64f * u, c, style = Stroke(1f))
    }
    // Each ring's words, from the top, clockwise.
    knob.rings.take(3).forEachIndexed { i, ring ->
      val (inner, outer) = bands[i]
      val mid = (inner + outer) / 2 * u
      val color = KnobColors.of(ring.kind)
      val dim = chosen != null && chosen.ring != ring.kind
      ring.stops.forEachIndexed { j, stop ->
        val fromTop = j * 360.0 / ring.stops.size
        val a = (-90 + fromTop) * PI / 180
        // Along the ring, as the design sets them; never upside down.
        val turn = (if (fromTop > 90 && fromTop < 270) fromTop - 180 else fromTop).toFloat()
        val isChosen = chosen?.ring == ring.kind && chosen.stop.id == stop.id
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
            .graphicsLayer { rotationZ = turn }
            .alpha(if (dim) 0.45f else 1f)
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .semantics {
              contentDescription = KnobWords.spoken(ring.kind, stop)
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
      KnobFace.Overview -> Quad(KnobWords.DOES, knob.does, KnobWords.TAP_A_RING, Theme.ink)
      is KnobFace.Chosen -> Quad(KnobWords.chosen(face.ring, face.stop), face.stop.state ?: face.stop.label, face.stop.tap, KnobColors.of(face.ring))
      is KnobFace.Held -> Quad(KnobWords.heldTop(face.call), face.call.proposed.amount?.figure ?: "", KnobWords.APPROVE_ONCE, KnobColors.heldAmount)
    }
    Box(
      Modifier.centerAt(c.x, c.y).size((128 * u / LocalDensity.current.density).dp).clip(CircleShape)
        .semantics { contentDescription = listOfNotNull(top, big, tap).joinToString(", ") }
        .let { m -> if (face == KnobFace.Overview) m else m.clickable(onClick = onFace).semantics { onClick(label = tap) { onFace(); true } } },
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(top, style = scaled(9, FontWeight.SemiBold, Theme.dim, mono = true), maxLines = 1, textAlign = TextAlign.Center)
        Text(big, style = scaled(if (face == KnobFace.Overview) 24 else 30, FontWeight.ExtraBold, bigColor, display = true), maxLines = 1)
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
 * The dial in a taf's Doors tab: [TafKnob] with what each tap does. Choosing a door on Wakes it picks that door for its settings
 * below ([onDoor]). The face's tap opens or closes that door, or runs the taf now, through their own routes (no new version); a
 * held call on the face is approved once. Under it: the chosen stop's sentence, or the job's, and today's work.
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
  val knob = wire.knob()
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var chosen by androidx.compose.runtime.saveable.rememberSaveable(taf.id) { androidx.compose.runtime.mutableStateOf<String?>(null) }
  var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val ready = LocalApprovalReadiness.current == bot.tafs.app.model.ApprovalReadiness.Ready
  // A held call that can be approved once takes the face while nothing is chosen.
  val held = state.heldCalls(taf.id).firstOrNull { it.approvableOnce && it.install == null }?.takeIf { state.canAnswer(it) && ready }
  val chosenStop = chosen?.let { id -> knob.rings.firstNotNullOfOrNull { ring -> ring.stops.firstOrNull { it.id == id }?.let { ring.kind to it } } }
  val face = when {
    chosenStop != null -> KnobFace.Chosen(chosenStop.first, chosenStop.second)
    held != null -> KnobFace.Held(held)
    else -> KnobFace.Overview
  }
  bot.tafs.app.design.Panel {
    TafKnob(
      knob, face,
      onChoose = { ring, id ->
        chosen = id
        if (ring == KnobRing.Kind.WakesIt) wire.wakes.firstOrNull { it.id == id }?.door?.let(onDoor)
      },
      onFace = {
        if (busy) return@TafKnob
        scope.launch {
          busy = true
          try {
            when (face) {
              is KnobFace.Held -> actions.approveOnce(face.call)
              is KnobFace.Chosen -> {
                val item = wire.wakes.firstOrNull { it.id == face.stop.id }
                val door = item?.door?.let { id -> doors.doors.firstOrNull { it.id == id } }
                when {
                  door != null -> { actions.setOpen(taf.id, door, !door.open); actions.loadKnob(taf.id) }
                  item?.kind == "run_now" -> actions.runNow(taf.id)
                  // If busy and where results go change how the taf works: the next version, to make live. Triggers change
                  // through Coach's draft for now.
                  face.ring != KnobRing.Kind.WakesIt && face.stop.tap != null && state.manages(taf.id) -> actions.changeKnob(taf.id, face.stop.id)
                }
              }
              KnobFace.Overview -> Unit
            }
          } finally {
            busy = false
          }
        }
      },
    )
    val sentence = when (face) {
      is KnobFace.Chosen -> face.stop.about
      is KnobFace.Held -> face.call.reason
      KnobFace.Overview -> knob.overview
    }
    if (sentence.isNotBlank()) Text(sentence, style = scaled(14, color = Theme.ink))
    knob.summary?.let { Text(it, style = scaled(13, color = Theme.dim)) }
    state.knobErrors[taf.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
    // A change made into a version: tafs cloud's words for it, and making it live, or not.
    state.knobChanges[taf.id]?.let { change ->
      bot.tafs.app.design.Eyebrow(KnobWords.changeTitle(change.version), Theme.amberText)
      change.words.forEach { Text(it, style = scaled(14, color = Theme.ink)) }
      androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        bot.tafs.app.design.OutlineButton(
          KnobWords.MAKE_LIVE,
          { scope.launch { busy = true; try { actions.confirmKnobChange(taf.id) } finally { busy = false } } },
          Modifier.weight(1f),
          enabled = !busy && taf.id !in state.switchingVersion,
        )
        bot.tafs.app.design.OutlineButton(KnobWords.NOT_NOW, { actions.dismissKnobChange(taf.id) }, Modifier.weight(1f), color = Theme.soft, enabled = !busy)
      }
    }
    if (chosen != null) {
      Text(
        "‹ ${KnobWords.OVERVIEW}",
        style = scaled(14, FontWeight.SemiBold, Theme.dim),
        modifier = Modifier.heightIn(min = 44.dp).clickable { chosen = null }.padding(top = 10.dp),
      )
    }
  }
}
