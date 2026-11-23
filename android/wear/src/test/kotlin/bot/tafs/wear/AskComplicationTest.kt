package bot.tafs.wear

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Ask tafs on the watch face (docs/watch-wear.md): a microphone, one tap into listening, as the tile's Ask tafs.
 * It shows nothing a held call says, so a locked or dimmed face shows only the microphone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WatchApplication::class)
class AskComplicationTest {
  private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Before fun words() = Res.install(context)

  @Test fun eachTypeTheFaceAsksForIsAMicrophoneThatListens() {
    for (type in ASK_TYPES) {
      val data = askComplication(context, type)
      assertNotNull("$type", data)
      val tap = data!!.tapAction
      assertNotNull("$type opens something", tap)
      val opened = shadowOf(tap).savedIntent
      assertEquals(WatchActivity::class.java.name, opened.component?.className)
      assertTrue("$type opens it listening", opened.getBooleanExtra(WatchActivity.EXTRA_LISTEN, false))
      val said = when (data) {
        is ShortTextComplicationData -> data.contentDescription
        is MonochromaticImageComplicationData -> data.contentDescription
        is SmallImageComplicationData -> data.contentDescription
        else -> null
      }
      assertEquals("$type is named for TalkBack", Words.Watch.ask, said?.getTextAt(context.resources, java.time.Instant.EPOCH).toString())
    }
  }

  /** Where the face shows text, it's the app's name beside the microphone: never a count, a taf or anything a call says. */
  @Test fun itSaysNothingButTafs() {
    val short = askComplication(context, ComplicationType.SHORT_TEXT) as ShortTextComplicationData
    assertEquals("tafs", short.text.getTextAt(context.resources, java.time.Instant.EPOCH).toString())
    assertNull(short.title)
    assertNotNull(short.monochromaticImage)
    assertNotNull((askComplication(context, ComplicationType.MONOCHROMATIC_IMAGE) as MonochromaticImageComplicationData).monochromaticImage)
    assertNotNull((askComplication(context, ComplicationType.SMALL_IMAGE) as SmallImageComplicationData).smallImage)
  }

  @Test fun aTypeItDoesntOfferIsNone() {
    assertNull(askComplication(context, ComplicationType.LONG_TEXT))
    assertNull(askComplication(context, ComplicationType.RANGED_VALUE))
  }

  /** The watch face finds it: declared with its types, bound only by the system's permission, never updated on a timer. */
  @Test fun theFaceFindsIt() {
    val info = context.packageManager.getServiceInfo(ComponentName(context, AskComplicationService::class.java), PackageManager.GET_META_DATA)
    assertEquals("com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER", info.permission)
    assertEquals("SHORT_TEXT,MONOCHROMATIC_IMAGE,SMALL_IMAGE", info.metaData.getString("android.support.wearable.complications.SUPPORTED_TYPES"))
    assertEquals(0, info.metaData.getInt("android.support.wearable.complications.UPDATE_PERIOD_SECONDS"))
  }
}
