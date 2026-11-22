package bot.tafs.app.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Directions
import bot.tafs.app.design.Theme
import bot.tafs.app.design.tapping
import bot.tafs.app.model.Words

/** The narrowest the panel is, and what it opens at the first time: a third of the screen, as the iPad's. */
internal const val COACH_PANEL_NARROWEST = 1f / 3

/**
 * Coach on a tablet, as the iPad's `CoachPanel`: it slides in from the trailing edge over the whole app, which stays in view,
 * dimmed a little; a tap on that closes it. It opens at a third of the screen, or the width the person last chose; its leading
 * edge drags it wider, up to the whole screen, and back, never narrower than a third, and dragged past that it closes. [content]
 * gets whether it's full and the way to switch (⤢ in Coach's bar). The width and the full screen are kept on this device.
 */
@Composable
internal fun CoachPanel(open: Boolean, onClose: () -> Unit, content: @Composable (full: Boolean, toggleFull: () -> Unit) -> Unit) {
  val prefs = LocalContext.current.getSharedPreferences("coach_panel", android.content.Context.MODE_PRIVATE)
  var share by remember { mutableFloatStateOf(prefs.getFloat("width", COACH_PANEL_NARROWEST).coerceIn(COACH_PANEL_NARROWEST, 1f)) }
  var full by remember { mutableStateOf(prefs.getBoolean("full", false)) }
  var drag by remember { mutableFloatStateOf(0f) }
  val density = LocalDensity.current
  // The trailing edge is the left in right to left: the slide and the drag follow it (a slide's and a drag's pixels don't mirror).
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val screen = with(density) { maxWidth.toPx() }
    val chosen = if (full) screen else screen * share
    val width = (chosen - drag).coerceIn(0f, screen)
    // The app behind, dimmed while the panel is narrower than the screen: a tap on it closes Coach.
    AnimatedVisibility(open && !full, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
      Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)).tapping(onClick = onClose)
          .semantics { contentDescription = Words.Coach.closeCoachMode }.testTag("coach-scrim"),
      )
    }
    AnimatedVisibility(
      open,
      modifier = Modifier.align(Alignment.CenterEnd),
      enter = slideInHorizontally(tween(220)) { Directions.offEndX(it, rtl) },
      exit = slideOutHorizontally(tween(220)) { Directions.offEndX(it, rtl) },
    ) {
      Row(
        Modifier.width(with(density) { width.toDp() }).fillMaxHeight()
          .shadow(24.dp, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
          .background(Theme.background).testTag("coach-panel"),
      ) {
        // The leading edge's handle: drag it towards the start to widen the panel, back to narrow it; past the narrowest, it closes.
        Box(
          Modifier.width(16.dp).fillMaxHeight()
            .pointerInput(screen, full, share) {
              detectHorizontalDragGestures(
                onDragEnd = {
                  val now = (if (full) screen else screen * share) - drag
                  drag = 0f
                  if (now < screen * COACH_PANEL_NARROWEST - 60.dp.toPx()) { onClose(); return@detectHorizontalDragGestures }
                  full = now >= screen - 40.dp.toPx()
                  if (!full) share = (now / screen).coerceIn(COACH_PANEL_NARROWEST, 1f)
                  prefs.edit().putFloat("width", share).putBoolean("full", full).apply()
                },
                onDragCancel = { drag = 0f },
              ) { _, amount -> drag += Directions.towardsEnd(amount, rtl) }
            }
            .semantics {
              contentDescription = Words.Coach.coachModeWidth
              stateDescription = Words.Coach.dragMakeWiderNarrower
              role = androidx.compose.ui.semantics.Role.Button
              // Without a drag (TalkBack, a keyboard): a tap switches between the third and the whole screen.
              onClick(Words.Coach.fullScreen) { full = !full; prefs.edit().putBoolean("full", full).apply(); true }
            },
          contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(5.dp, 44.dp).clip(RoundedCornerShape(3.dp)).background(Theme.dim.copy(alpha = 0.5f))) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
          content(full) { full = !full; prefs.edit().putBoolean("full", full).apply() }
        }
      }
    }
  }
}
