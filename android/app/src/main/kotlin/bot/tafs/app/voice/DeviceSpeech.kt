package bot.tafs.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * The phone's own speech recognizer, on the phone only (`createOnDeviceSpeechRecognizer`, Android 12 and later): no audio
 * leaves it. A phone without one doesn't dictate, rather than fall back to a recognizer that sends the voice away. It
 * dictates in the phone's language.
 */
class DeviceSpeech(private val context: Context) : SpeechEngine {
  override val available: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

  override val mayListen: Boolean
    get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private var recognizer: SpeechRecognizer? = null

  override fun listen(listener: SpeechEngine.Listener) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return listener.failed(Dictation.NOT_ON_DEVICE)
    val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
    r.setRecognitionListener(object : RecognitionListener {
      override fun onPartialResults(partialResults: Bundle?) = listener.heard(first(partialResults))
      override fun onResults(results: Bundle?) = listener.finished(first(results))

      override fun onError(error: Int) = when (error) {
        // A pause with nothing said: it listens again, or stops after a few.
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.finished("")
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.failed(Dictation.NO_MICROPHONE)
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
          listener.failed("This phone can't dictate in ${Locale.getDefault().displayLanguage} on the phone itself.")
        else -> listener.failed("Dictation stopped. Tap the mic to start again.")
      }

      override fun onReadyForSpeech(params: Bundle?) {}
      override fun onBeginningOfSpeech() {}
      override fun onRmsChanged(rmsdB: Float) {}
      override fun onBufferReceived(buffer: ByteArray?) {}
      override fun onEndOfSpeech() {}
      override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    r.startListening(
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()),
    )
  }

  override fun stop() {
    recognizer?.run {
      stopListening()
      destroy()
    }
    recognizer = null
  }

  private fun first(results: Bundle?): String = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}
