package bot.tafs.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's own speech recognizer, on the phone only (`createOnDeviceSpeechRecognizer`, Android 12 and later): no audio
 * leaves it. A phone without one doesn't dictate, rather than fall back to a recognizer that sends the voice away. It
 * dictates in the phone's language, in the variant the recognizer has ([SpeechLanguage]), and gets it first when it can.
 */
class DeviceSpeech(private val context: Context) : SpeechEngine {
  override val available: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

  override val mayListen: Boolean
    get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private var recognizer: SpeechRecognizer? = null

  private val owned = MutableStateFlow<List<String>>(emptyList())

  /** The owner's languages the phone dictates in, known once the recognizer says what it has (Android 13 and later). */
  override val languages: StateFlow<List<String>> = owned

  init {
    // Asked once: which languages are on the phone, of the owner's (their keyboards', then the phone's).
    if (available && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val asking = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
      asking.checkRecognitionSupport(
        intent(null),
        ContextCompat.getMainExecutor(context),
        object : RecognitionSupportCallback {
          override fun onSupportResult(support: RecognitionSupport) {
            owned.value = SpeechLanguage.owners(support.installedOnDeviceLanguages, keyboards(), preferred())
            asking.destroy()
          }

          override fun onError(error: Int) = asking.destroy()
        },
      )
    }
  }

  /** The languages of the keyboards the owner set up. */
  private fun keyboards(): List<String> = runCatching {
    val imm = context.getSystemService(InputMethodManager::class.java)
    imm.enabledInputMethodList.flatMap { imm.getEnabledInputMethodSubtypeList(it, true) }
      .mapNotNull { it.languageTag.takeIf(String::isNotEmpty) }
  }.getOrDefault(emptyList())

  /** The phone's own languages, in its order. */
  private fun preferred(): List<String> = android.os.LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }

  /** The language chosen for this phone, once known: it's asked of the recognizer once. */
  private var phoneLanguage: String? = null

  override fun listen(listener: SpeechEngine.Listener, language: String?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return listener.failed(Dictation.NOT_ON_DEVICE)
    val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
    // One of the owner's languages, chosen with the switch: it's on the phone already.
    if (language != null) return start(r, language, listener)
    val known = phoneLanguage
    // Before Android 13 the recognizer can't say which languages it has: it's asked for the phone's own.
    if (known != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return start(r, known ?: Locale.getDefault().toLanguageTag(), listener)
    r.checkRecognitionSupport(
      intent(null),
      ContextCompat.getMainExecutor(context),
      object : RecognitionSupportCallback {
        override fun onSupportResult(support: RecognitionSupport) {
          val name = Locale.getDefault().displayLanguage
          when (val choice = SpeechLanguage.choose(Locale.getDefault(), support.installedOnDeviceLanguages, support.supportedOnDeviceLanguages)) {
            is SpeechLanguage.Choice.Use -> {
              phoneLanguage = choice.tag
              start(r, choice.tag, listener)
            }
            is SpeechLanguage.Choice.Download -> {
              r.triggerModelDownload(intent(choice.tag))
              listener.failed(SpeechLanguage.downloading(name))
            }
            SpeechLanguage.Choice.None -> listener.failed(SpeechLanguage.cannot(name))
          }
        }

        override fun onError(error: Int) = start(r, Locale.getDefault().toLanguageTag(), listener)
      },
    )
  }

  private fun intent(tag: String?) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    .apply { tag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }

  private fun start(r: SpeechRecognizer, tag: String, listener: SpeechEngine.Listener) {
    r.setRecognitionListener(object : RecognitionListener {
      override fun onPartialResults(partialResults: Bundle?) = listener.heard(first(partialResults))
      override fun onResults(results: Bundle?) = listener.finished(first(results))

      override fun onError(error: Int) = when (error) {
        // A pause with nothing said: it listens again, or stops after a few.
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.finished("")
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.failed(Dictation.NO_MICROPHONE)
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
          listener.failed(SpeechLanguage.cannot(Locale.getDefault().displayLanguage))
        else -> listener.failed("Dictation stopped. Tap the mic to start again.")
      }

      override fun onReadyForSpeech(params: Bundle?) {}
      override fun onBeginningOfSpeech() {}
      override fun onRmsChanged(rmsdB: Float) {}
      override fun onBufferReceived(buffer: ByteArray?) {}
      override fun onEndOfSpeech() {}
      override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    r.startListening(intent(tag))
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

/** The dictation language chosen last, in the app's own preferences. */
class SavedLanguage(context: Context) : LanguageMemory {
  private val prefs = context.getSharedPreferences("dictation", Context.MODE_PRIVATE)

  override fun load(): String? = prefs.getString("language", null)

  override fun save(tag: String) = prefs.edit().putString("language", tag).apply()
}
