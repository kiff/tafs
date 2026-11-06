package bot.tafs.app.views

import bot.tafs.app.drive.DriveSheet
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
 * An app's event on When, before it's approved: what it listens to (tafs cloud's words) and its filters, exactly as they'll be
 * subscribed, then one approval. Approving makes the connection listen; tafs cloud then says it's approved.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun EventApprovalSheet(event: bot.tafs.app.kiff.KnobWire.Item, busy: Boolean, error: String?, onApprove: () -> Unit, onClose: () -> Unit) {
  DriveSheet(
    onDismissRequest = onClose,
    sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(
      Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
      Text(event.label, style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
      Text(event.words, style = scaled(15, color = Theme.ink))
      val filters = event.approval?.config.orEmpty()
      if (filters.isNotEmpty()) {
        bot.tafs.app.design.Eyebrow(KnobWords.EVENT_FILTERS)
        // Each filter as tafs cloud gives it: its name and value, the same ones planque subscribes.
        filters.forEach { (name, value) ->
          val shown = (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()
          Text("$name · $shown", style = scaled(14, color = Theme.soft, mono = true))
        }
      }
      error?.let { Text(it, style = scaled(13, color = Theme.no)) }
      ApprovalGate {
        bot.tafs.app.design.PrimaryButton(
          if (busy) "Approving…" else KnobWords.approveWith(bot.tafs.app.design.LocalBiometry.current),
          onApprove,
          enabled = !busy,
        )
      }
      Text(
        bot.tafs.app.model.ConnectWords.CANCEL,
        style = scaled(15, FontWeight.SemiBold, Theme.dim),
        modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = !busy, onClick = onClose).padding(top = 10.dp),
      )
    }
  }
}
