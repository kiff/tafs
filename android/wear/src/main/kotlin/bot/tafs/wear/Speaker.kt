package bot.tafs.wear

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Hear: a result read aloud by the watch's own text-to-speech, in the watch's language; nothing is sent anywhere. */
internal class Speaker(context: Context) {
  private var ready = false
  private var waiting: String? = null
  private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
    ready = status == TextToSpeech.SUCCESS
    if (ready) {
      runCatching { tts.language = Locale.forLanguageTag(Res.language) }
      waiting?.let { speak(it) }
      waiting = null
    }
  }

  fun say(text: String) {
    if (ready) speak(text) else waiting = text
  }

  private fun speak(text: String) {
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tafs-result")
  }

  fun close() {
    runCatching { tts.stop(); tts.shutdown() }
  }
}
