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
) {
  private var recognizer: SpeechRecognizer? = null
  @androidx.annotation.VisibleForTesting internal var tap: AudioTap? = null
  private var source: android.os.ParcelFileDescriptor? = null
  private var ready = false

  /**
   * The probe, for the internal build's line on the review: what the watch's recognizer did with the app's own audio. Evidence
   * for the owner's choice if it won't take it (PM 19 Nov 01:19); never sent anywhere.
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
    r.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        ready = true
        model.readyToHear()
      }
      override fun onPartialResults(partialResults: Bundle?) {
        first(partialResults)?.let(model::partial)
      }
      override fun onResults(results: Bundle?) {
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
    val started = runCatching { r.startListening(intent) }.isSuccess
    if (!started) endTap(keep = false)
    return started
  }

  /**
   * v2's Stop, or its 30 s limit: listening ends, and what was heard comes back as the result, to the review. With the app's own
   * audio, its microphone stops and the pipe closes first: the recognizer returns the words at the end of the audio, not at
   * `stopListening`.
   */
  fun stop() {
    tap?.endFeed()
    runCatching { recognizer?.stopListening() }
  }

  /** Stops listening; nothing heard is sent, and nothing recorded is kept. */
  fun cancel() {
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
