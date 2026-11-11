package bot.tafs.app.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import bot.tafs.app.kiff.TafsCloud
import java.io.File

/**
 * Apps' logos, from tafs cloud only (`GET /v1/logos/{key}`, docs/android-app.md): tafs cloud fetches each from the catalog and the phone
 * never reaches a third party. Kept in memory, and on disk for a week, as tafs cloud's `max-age` says. A logo it doesn't have is null,
 * and the app's name shows alone.
 */
class LogoStore(private val dir: File?, private val fetch: suspend (String) -> ByteArray?, private val now: () -> Long = System::currentTimeMillis) {
  private val memory = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

  suspend fun bytes(path: String?): ByteArray? {
    if (!TafsCloud.isLogoPath(path)) return null
    val key = path!!.substringAfterLast('/')
    memory[key]?.let { return it }
    val file = dir?.let { File(it, "$key.png") }
    if (file != null && file.isFile && now() - file.lastModified() < MAX_AGE_MS) {
      return runCatching { file.readBytes() }.getOrNull()?.also { memory[key] = it }
    }
    val got = runCatching { fetch(path) }.getOrNull() ?: return null
    memory[key] = got
    if (file != null) runCatching { file.parentFile?.mkdirs(); file.writeBytes(got); file.setLastModified(now()) }
    return got
  }

  companion object {
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
  }
}

/** The logos for this screen; none in a screen test that gives none. */
val LocalLogos = staticCompositionLocalOf<LogoStore?> { null }

/** A logo's image from tafs cloud, once it's loaded; null before, and when there's none. */
@Composable
fun rememberLogo(logo: String?): ImageBitmap? {
  val store = LocalLogos.current
  var image by remember(logo) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(logo, store) {
    image = store?.bytes(logo)?.let { b -> runCatching { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
  }
  return image
}

/** An app as a job shows it: its logo, small and as it is, beside its name; the name alone when there's no logo. */
@Composable
fun AppWithLogo(name: String, logo: String?, modifier: Modifier = Modifier) {
  val image = rememberLogo(logo)
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    image?.let { Image(it, contentDescription = null, modifier = Modifier.size(18.dp)) }
    Text(name, style = scaled(13, color = Theme.soft))
  }
}
