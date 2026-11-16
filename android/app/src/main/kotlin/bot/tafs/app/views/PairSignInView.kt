package bot.tafs.app.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.kiff.PairOffer
import bot.tafs.app.model.ThisDevice
import bot.tafs.app.model.Words
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Signing in with your other phone (docs/android-app.md, Signing in with your other phone): the QR the other phone scans in
 * Account → Devices → Add a device, the code both screens show, and the wait. [offer] is null while it's being made; [renewed]
 * says the last one expired and this is a new one; [problem] is why it stopped.
 */
@Composable
internal fun PairSignInContent(
  offer: PairOffer?,
  renewed: Boolean,
  problem: String?,
  onTryAgain: () -> Unit,
  // Back to KIFF's way; none where KIFF's way is already beside it (a tablet's two halves).
  onKiff: (() -> Unit)?,
  title: String = Words.SignIn.pairPrompt(device = ThisDevice.word),
  lead: String = Words.SignIn.pairHow(account = Words.Settings.title, devices = Words.Settings.Devices.title, add = Words.Settings.Devices.pair),
) {
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(title, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
    Text(lead, style = scaled(15, color = Theme.soft))
    if (renewed) Text(Words.SignIn.pairNewCode, style = scaled(14, color = Theme.amberText))
    offer?.let { o ->
      // Dark modules on white, with the quiet zone, whatever the theme: scanners read it that way.
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val image = remember(o.qr) { qrImage(o.qr) }
        Image(
          image,
          contentDescription = null,
          filterQuality = FilterQuality.None,
          modifier = Modifier
            .widthIn(max = 280.dp)
            .size(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .semantics { contentDescription = Words.SignIn.pairPrompt(device = ThisDevice.word) }
            .testTag(PAIR_QR),
        )
      }
      Panel(stroke = Theme.amber.copy(alpha = 0.4f)) {
        Text(Words.SignIn.pairCheck(device = ThisDevice.word, code = o.checkCode), style = scaled(15, color = Theme.ink), modifier = Modifier.testTag(PAIR_SIGN_IN_CODE))
        Text(o.checkCode, style = scaled(34, FontWeight.SemiBold, Theme.ink, mono = true))
      }
      if (problem == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(Modifier.size(20.dp), color = Theme.amber, strokeWidth = 2.dp)
          Text(Words.SignIn.pairWaiting, style = scaled(14, color = Theme.soft))
        }
      }
    }
    if (offer == null && problem == null) CircularProgressIndicator(Modifier.size(28.dp), color = Theme.amber, strokeWidth = 3.dp)
    problem?.let {
      Text(it, style = scaled(14, color = Theme.no))
      if (offer != null) bot.tafs.app.design.OutlineButton(Words.Common.tryAgain, onTryAgain, Modifier.fillMaxWidth())
    }
    if (onKiff != null) {
      Text(
        Words.SignIn.withKiff,
        style = scaled(15, FontWeight.SemiBold, Theme.amber),
        modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = onKiff).padding(vertical = 10.dp),
      )
    }
  }
}

/** The QR's modules, error correction M as the pairing contract asks, one pixel each with a 4-module quiet zone. */
internal fun qrModules(text: String): Array<BooleanArray> {
  val matrix = QRCodeWriter().encode(
    text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"),
  )
  return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
}

private fun qrImage(text: String): ImageBitmap {
  val modules = qrModules(text)
  val size = modules.size
  val pixels = IntArray(size * size) { i -> if (modules[i / size][i % size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
  return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

internal const val PAIR_QR = "sign_in.pair.qr"
internal const val PAIR_SIGN_IN_CODE = "sign_in.pair.code"
