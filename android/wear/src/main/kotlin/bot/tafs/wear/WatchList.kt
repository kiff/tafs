package bot.tafs.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold

/**
 * Every screen of the watch that scrolls (docs/watch-wear.md, The rules it's built to; Wear OS app quality): Wear's own scroll
 * indicator at the edge while it scrolls ([ScreenScaffold]), and the list held in from a round face's edge, so no line or button is
 * cut where the face curves, at the default font size and the largest. A screen that sets its own top and foot ([top], [bottom])
 * keeps them; the sides always come from the screen's width.
 */
@Composable
internal fun WatchList(
  modifier: Modifier = Modifier.fillMaxWidth(),
  state: ScalingLazyListState = rememberScalingLazyListState(initialCenterItemIndex = 0),
  top: Dp? = null,
  bottom: Dp? = null,
  autoCentering: AutoCenteringParams? = AutoCenteringParams(),
  horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
  verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(4.dp),
  /** No shrinking or fading at the edges: for one screen drawn as a single item ([Centered]). */
  flat: Boolean = false,
  content: ScalingLazyListScope.() -> Unit,
) {
  val screen = LocalConfiguration.current
  ScreenScaffold(scrollState = state, contentPadding = roundPadding(screen.screenWidthDp.dp, screen.screenHeightDp.dp, top, bottom)) { padding ->
    ScalingLazyColumn(
      modifier,
      state = state,
      contentPadding = padding,
      autoCentering = autoCentering,
      horizontalAlignment = horizontalAlignment,
      verticalArrangement = verticalArrangement,
      scalingParams = if (flat) ScalingLazyColumnDefaults.scalingParams(edgeScale = 1f, edgeAlpha = 1f) else ScalingLazyColumnDefaults.scalingParams(),
      content = content,
    )
  }
}

/**
 * A list's padding on a watch face [width] by [height]: the sides at 5.2 % of the width, as Wear's lists are, and the top and foot at
 * 10 % of the height unless the screen sets its own ([top], [bottom]).
 */
internal fun roundPadding(width: Dp, height: Dp, top: Dp? = null, bottom: Dp? = null): PaddingValues = PaddingValues(
  start = width * SIDE_SHARE,
  end = width * SIDE_SHARE,
  top = top ?: (height * END_SHARE),
  bottom = bottom ?: (height * END_SHARE),
)

/** Wear's list margin on a round face, as a share of its width. */
internal const val SIDE_SHARE = 0.052f

/** The room above the first item and under the last, as a share of the height, where a round face is narrowest. */
internal const val END_SHARE = 0.1f
