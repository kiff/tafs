package bot.tafs.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The watch's own speech recognizer, in the app (docs/watch-wear.md, Ask tafs): it shows the words as they're heard, and when the
 * person stops, the text goes at once, with nothing to confirm. Only the text leaves the recognizer; the app keeps no audio.
 */
internal class Listener(private val context: Context, private val model: WatchModel, private val scope: CoroutineScope) {
  private var recognizer: SpeechRecognizer? = null

  /** Starts listening; false where the watch has no recognizer for apps, so the system's speech input is used instead. */
  fun start(replyTo: String?): Boolean {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
    val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()?.also { recognizer = it } ?: return false
    r.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) = model.readyToHear()
      override fun onPartialResults(partialResults: Bundle?) {
        first(partialResults)?.let(model::partial)
      }
      override fun onResults(results: Bundle?) {
        val heard = first(results)
        scope.launch { if (heard.isNullOrBlank()) model.heardNothing() else model.heard(heard) }
      }
      override fun onError(error: Int) = model.heardNothing()
      override fun onBeginningOfSpeech() = Unit
      override fun onRmsChanged(rmsdB: Float) = Unit
      override fun onBufferReceived(buffer: ByteArray?) = Unit
      override fun onEndOfSpeech() = Unit
      override fun onEvent(eventType: Int, params: Bundle?) = Unit
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Res.language)
    return runCatching { r.startListening(intent) }.isSuccess
  }

  /** Stops listening; nothing heard is sent. */
  fun cancel() {
    runCatching { recognizer?.cancel() }
  }

  fun close() {
    runCatching { recognizer?.destroy() }
    recognizer = null
  }

  private fun first(bundle: Bundle?): String? = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
}
