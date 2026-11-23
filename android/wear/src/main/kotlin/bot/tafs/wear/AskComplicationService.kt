package bot.tafs.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Ask tafs on the watch face (docs/watch-wear.md): a microphone that opens the app listening, as the tile's Ask tafs
 * does ([WatchActivity.EXTRA_LISTEN]), so asking is one tap from the face. It shows nothing else: no count, no taf, nothing a
 * held call says, so a locked or dimmed face shows only the microphone. It never changes, so it's never updated.
 */
class AskComplicationService : SuspendingComplicationDataSourceService() {
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = askComplication(this, request.complicationType)

  override fun getPreviewData(type: ComplicationType): ComplicationData? = askComplication(this, type)
}

/** The types the face may ask for, in the manifest's order. */
internal val ASK_TYPES = listOf(ComplicationType.SHORT_TEXT, ComplicationType.MONOCHROMATIC_IMAGE, ComplicationType.SMALL_IMAGE)

/** What opening the complication does: the watch app, listening (the watch's lock is asked first, as for any ask). */
internal fun listenIntent(context: Context): Intent = Intent(context, WatchActivity::class.java)
  .putExtra(WatchActivity.EXTRA_LISTEN, true)
  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/** The complication for [type]: the microphone, "tafs" beside it where the face shows text, named "Ask tafs" for TalkBack. */
internal fun askComplication(context: Context, type: ComplicationType): ComplicationData? {
  val tap = PendingIntent.getActivity(context, 0, listenIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val said = PlainComplicationText.Builder(Words.Watch.ask).build()
  val icon = Icon.createWithResource(context, R.drawable.ic_mic)
  return when (type) {
    ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(context.getString(R.string.app_name)).build(), said)
      .setMonochromaticImage(MonochromaticImage.Builder(icon).build())
      .setTapAction(tap)
      .build()
    ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(MonochromaticImage.Builder(icon).build(), said)
      .setTapAction(tap)
      .build()
    ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(SmallImage.Builder(icon, SmallImageType.ICON).build(), said)
      .setTapAction(tap)
      .build()
    else -> null
  }
}
