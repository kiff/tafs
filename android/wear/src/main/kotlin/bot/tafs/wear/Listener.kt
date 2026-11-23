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
 * The watch's own speech recognizer, in the app (docs/watch-wear.md, Ask tafs): it shows the words as they're heard. In v1, when
 * the person stops, the text goes at once and the app keeps no audio. In v2 ([keepAudio]) the app records what it hears itself,
 * hands the same audio to the recognizer, and keeps the recording on the watch for Play until Send or Delete; only text leaves.
 */
internal class Listener(
  private val context: Context,
  private val model: WatchModel,
  private val scope: CoroutineScope,
  /** v2: the app records what it hears itself, kept for Play until Send or Delete, and hands the same audio to the recognizer. */
  private val keepAudio: Boolean = false,
  /** v2: when the recognizer gives up, record the rest and have the phone turn it into text (row 44, A). */
  private val phoneFallback: Boolean = false,
) {
  /** The rest of what the person says, recorded after the recognizer gave up, for the phone. */
  private var forPhone: AudioTap? = null
  private var heardBefore = ""

  private var recognizer: SpeechRecognizer? = null
  @androidx.annotation.VisibleForTesting internal var tap: AudioTap? = null
  private var source: android.os.ParcelFileDescriptor? = null
  private var ready = false

  /** Across the recognizer's own stretches, so a pause doesn't end it (row 44, A): with the watch's microphone, not the app's audio. */
  private val dictation = Dictation(now = android.os.SystemClock::elapsedRealtime)
  /** The recognizer is in a stretch now; between two, a Stop ends at once with what was heard. */
  private var hearing = false
  private var intent: Intent? = null

  /**
   * The probe, for the internal build's line on the review: what the watch's recognizer did with the app's own audio. Evidence
   * for the owner's choice if it won't take it; never sent anywhere.
   */
  val probe = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

  /** Starts listening; false where the watch has no recognizer for apps, so the system's speech input is used instead. */
  fun start(replyTo: String?): Boolean {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
    val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()?.also { recognizer = it } ?: return false
    // v2, Wear OS 4 and later, unless this watch's recognizer already refused the app's audio: the app's own microphone.
    val newTap = if (keepAudio && android.os.Build.VERSION.SDK_INT >= 33 && !refused) AudioTap(VoiceFiles.dir(context)) else null
    val pipe = newTap?.start()
    tap = newTap?.takeIf { pipe != null }
    source = pipe
    ready = false
    if (keepAudio && pipe == null) probe.value = if (refused) "audio: off (refused earlier)" else "audio: microphone not opened"
    dictation.begin()
    // Joined across stretches only with the watch's microphone: the app's own audio (a test build) is one stretch, as before.
    val continuous = pipe == null
    r.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        ready = true
        model.readyToHear()
      }
      override fun onPartialResults(partialResults: Bundle?) {
        val words = first(partialResults)
        if (continuous) model.partial(dictation.partial(words)) else words?.let(model::partial)
      }
      override fun onResults(results: Bundle?) {
        hearing = false
        if (continuous) return next(dictation.ended(first(results)))
        val heard = first(results)
        val audio = endTap(keep = true)
        if (audio != null || pipe != null) probe.value = "audio: ${audio?.let { "kept ${it.durationMs} ms" } ?: "none"} · peak ${newTap?.peak ?: 0}"
        scope.launch {
          if (heard.isNullOrBlank()) {
            audio?.delete()
            model.heardNothing()
          } else {
            model.heard(heard, audio)
          }
        }
      }
      override fun onError(error: Int) {
        hearing = false
        if (continuous && phoneFallback && Dictation.fallsBackToPhone(error) && recordForPhone()) return
        if (continuous) return next(if (Dictation.isQuiet(error)) dictation.ended(null) else dictation.failed())
        endTap(keep = false)
        // Refused before it was ready to listen, with the app's audio: this recognizer doesn't take it. The next Start uses the
        // watch's microphone as v1 does, and the probe says why.
        if (pipe != null && !ready) {
          refused = true
          probe.value = "audio: recognizer refused it (error $error)"
        } else if (pipe != null) {
          probe.value = "audio: recognizer error $error"
        }
        model.heardNothing()
      }
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
    if (pipe != null && android.os.Build.VERSION.SDK_INT >= 33) {
      intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, VoiceFormat.CHANNELS)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceFormat.RATE)
    }
    this.intent = intent
    val started = runCatching { r.startListening(intent) }.isSuccess
    hearing = started
    if (!started) endTap(keep = false)
    return started
  }

  /** After a stretch: listen on, or hand over everything heard (to the review in v2, sent in v1), or that nothing was. */
  private fun next(next: Dictation.Next) {
    when (next) {
      Dictation.Next.Listen -> {
        val r = recognizer
        val i = intent
        hearing = r != null && i != null && runCatching { r.startListening(i) }.isSuccess
        if (!hearing) next(dictation.failed())
      }
      is Dictation.Next.Done -> finish(next.text)
      Dictation.Next.Cancelled -> Unit
    }
  }

  /**
   * The recognizer gave up: the app records the rest itself, with the watch's microphone, until ✓ or the 30 s limit (row 44, A).
   * False when the microphone won't open, and it ends as before.
   */
  private fun recordForPhone(): Boolean {
    val tap = AudioTap(VoiceFiles.dir(context), feedOf = { object : java.io.OutputStream() { override fun write(b: Int) = Unit } })
    val pipe = tap.start() ?: return false
    runCatching { pipe.close() }
    heardBefore = dictation.heardSoFar()
    forPhone = tap
    return true
  }

  private fun finish(text: String) {
    scope.launch { if (text.isBlank()) model.heardNothing() else model.heard(text) }
  }

  /**
   * v2's Stop, or its 30 s limit: listening ends, and what was heard comes back as the result, to the review. With the app's own
   * audio, its microphone stops and the pipe closes first: the recognizer returns the words at the end of the audio, not at
   * `stopListening`.
   */
  fun stop() {
    dictation.stopping = true
    // Recording for the phone: ✓ ends it, and the recording goes to the phone with what was heard before.
    forPhone?.let { t ->
      forPhone = null
      val audio = t.finish()
      val before = heardBefore
      scope.launch { if (audio != null) model.transcribeOnPhone(audio, before, Res.language) else finish(before) }
      return
    }
    tap?.endFeed()
    // Between two stretches there's nothing to stop: it ends now, with what was heard.
    if (!hearing && tap == null) return next(dictation.ended(null))
    runCatching { recognizer?.stopListening() }
  }

  /** Stops listening; nothing heard is sent, and nothing recorded is kept. */
  fun cancel() {
    forPhone?.discard()
    forPhone = null
    dictation.cancel()
    hearing = false
    runCatching { recognizer?.cancel() }
    endTap(keep = false)
  }

  fun close() {
    runCatching { recognizer?.destroy() }
    recognizer = null
    endTap(keep = false)
  }

  /** Closes the app's microphone: the recording, kept for the review, or erased. */
  private fun endTap(keep: Boolean): KeptAudio? {
    val t = tap ?: return null
    tap = null
    runCatching { source?.close() }
    source = null
    return if (keep) t.finish() else {
      t.discard()
      null
    }
  }

  companion object {
    /** This watch's recognizer refused the app's audio once: for the process's life, Start uses the watch's microphone. */
    @Volatile private var refused = false
  }

  private fun first(bundle: Bundle?): String? = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
}
