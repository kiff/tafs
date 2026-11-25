package bot.tafs.app.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One layout on every app (docs/parity.md, "One layout on every app"): on a wide window each page is one column, centred, at
 * most as wide as its kind of page allows. The caps are the iPad's and the Mac's, kept here, in one place: no page sets its own.
 */
object PageWidth {
  /** Account, Devices, People, Needs you, Connect apps, Plan & usage, a taf's chat, and a card opened from the brief. */
  val Reading: Dp = 720.dp
  /** Overview, your tafs, sharing. */
  val List: Dp = 980.dp
  /** The recipe gallery. */
  val Gallery: Dp = 640.dp
  /** Your brief (the Needs you tab, when the brief is on). */
  val Brief: Dp = 1100.dp
  /** The Calendar. */
  val Calendar: Dp = 1180.dp
}

/**
 * A page's column: as wide as the window up to [max], centred in it. A window narrower than [max] is unchanged. Text and rows
 * inside stay aligned to the start: only the column is centred.
 */
fun Modifier.pageColumn(max: Dp): Modifier =
  fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()
