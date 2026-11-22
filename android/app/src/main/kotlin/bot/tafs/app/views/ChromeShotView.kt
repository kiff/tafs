package bot.tafs.app.views

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.kiff.ChromeScreenshot
import bot.tafs.app.model.Words
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException

const val CHROME_SHOT_SHEET = "chrome-shot-sheet"
const val CHROME_SHOT_IMAGE = "chrome-shot-image"

/** Where the sheet is: reading, a checked picture, none kept, or a read that failed or didn't check out. */
sealed interface ChromeShotPhase {
  data object Loading : ChromeShotPhase
  class Shown(val shot: ChromeScreenshot) : ChromeShotPhase
  data object None : ChromeShotPhase
  data object Failed : ChromeShotPhase
}

/** [load]'s answer as the sheet's phase: a picture, none kept (null), or failed, for any refusal or a picture that didn't check out. */
suspend fun chromeShotPhase(load: suspend () -> ChromeScreenshot?): ChromeShotPhase = try {
  load()?.let { ChromeShotPhase.Shown(it) } ?: ChromeShotPhase.None
} catch (e: CancellationException) {
  throw e
} catch (_: Exception) {
  ChromeShotPhase.Failed
}

/**
 * The page as a Chrome showed it just before a taf's step acted, kept for 30 days (docs/chrome.md, "Screenshots of a run's
 * acts"), as iOS's `ChromeShotView`. [load] is planque's read for the step (`Planque.chromeScreenshot`), which checks the
 * bytes before they reach here. Nothing opens it yet: a taf's Activity row opens it once tafs cloud names the row's Chrome
 * step. planque keeps no picture while its switch is off, and the sheet then says so plainly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChromeShotSheet(load: suspend () -> ChromeScreenshot?, onClose: () -> Unit) {
  var phase by remember { mutableStateOf<ChromeShotPhase>(ChromeShotPhase.Loading) }
  LaunchedEffect(Unit) { phase = chromeShotPhase(load) }
  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().testTag(CHROME_SHOT_SHEET),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(Words.Chrome.shotTitle, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text(Words.Common.done, style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = onClose).padding(top = 8.dp))
      }
      when (val p = phase) {
        ChromeShotPhase.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Theme.amber) }
        is ChromeShotPhase.Shown -> {
          val bitmap = remember(p.shot) { BitmapFactory.decodeByteArray(p.shot.image, 0, p.shot.image.size) }
          if (bitmap != null) {
            Image(
              bitmap.asImageBitmap(), contentDescription = Words.Chrome.shotTitle, contentScale = ContentScale.FillWidth,
              modifier = Modifier.fillMaxWidth().border(1.dp, Theme.dim.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).testTag(CHROME_SHOT_IMAGE),
            )
          }
          Text(takenAtWords(p.shot), style = scaled(13, color = Theme.dim))
          Text(Words.Chrome.shotKept, style = scaled(13, color = Theme.dim))
        }
        ChromeShotPhase.None -> Text(Words.Chrome.shotNone, style = scaled(15, color = Theme.dim))
        ChromeShotPhase.Failed -> Text(Words.Chrome.shotFailed, style = scaled(15, color = Theme.no))
      }
    }
  }
}

/** When the picture was taken, in this phone's zone and language, as iOS's abbreviated date with the time. */
private fun takenAtWords(shot: ChromeScreenshot): String =
  DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(shot.takenAt)
