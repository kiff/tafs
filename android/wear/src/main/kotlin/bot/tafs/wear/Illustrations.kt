package bot.tafs.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * The owner's illustration for the watch (board 12, "Wear OS, small, all clear"): a green disc with a white check, above
 * Needs you's own words when nothing waits. Drawn as shapes, never an image file, and with no text in it (docs/watch-wear.md,
 * Illustrations); the words under it are the screen's, in the watch's language. Decorative, so it's hidden from TalkBack,
 * which reads the words.
 */
internal object AllClear {
  /** The disc's width, as a share of the screen's (152 of the board's 400 px face). */
  const val SHARE = 0.38f

  /** The board's green and the check's white. */
  val green = Color(0xFF2E9E5B)
  val check = Color.White

  /** The check's three points and its stroke, as shares of the disc's width, measured from the board. */
  val start = Offset(0.342f, 0.513f)
  val corner = Offset(0.441f, 0.605f)
  val end = Offset(0.658f, 0.408f)
  const val STROKE = 0.059f
}

/** [AllClear], centred, its disc [AllClear.SHARE] of the width it's given. */
@Composable
internal fun AllClearIllustration(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxWidth(AllClear.SHARE).aspectRatio(1f)) {
      val d = size.minDimension
      drawCircle(AllClear.green, radius = d / 2)
      val tick = Path().apply {
        moveTo(AllClear.start.x * d, AllClear.start.y * d)
        lineTo(AllClear.corner.x * d, AllClear.corner.y * d)
        lineTo(AllClear.end.x * d, AllClear.end.y * d)
      }
      drawPath(tick, AllClear.check, style = Stroke(width = AllClear.STROKE * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
  }
}
