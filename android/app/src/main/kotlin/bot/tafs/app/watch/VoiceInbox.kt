package bot.tafs.app.watch

import bot.tafs.watchlink.WatchLink
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Recordings from the Wear OS watch, held only until they're transcribed (row 44, A; docs/watch-wear.md, "When the watch's
 * recognizer gives up"): the watch sends one over a Data Layer channel, then asks for its text ([WatchRelay], `transcribe`).
 * Each is a file in the app's private cache, at most [WatchLink.MAX_VOICE_BYTES]; it's deleted once taken and transcribed, and
 * anything left (a channel with no request after it) is swept after [MAX_AGE_MS].
 */
class VoiceInbox(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
  private val arrived = ConcurrentHashMap<String, CompletableDeferred<File?>>()

  private fun slot(id: String) = arrived.getOrPut(id) { CompletableDeferred() }

  /** A recording's bytes, as they come over its channel: kept as [id]'s, or dropped if it's too long. Returns whether kept. */
  fun receive(id: String, input: InputStream): Boolean {
    sweep()
    if (!WatchLink.isVoiceId(id)) return false
    dir.mkdirs()
    val file = File(dir, "voice-$id.wav")
    var total = 0L
    val kept = runCatching {
      file.outputStream().use { out ->
        val buf = ByteArray(16 * 1024)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          total += n
          // Longer than any recording the watch makes: dropped.
          if (total > WatchLink.MAX_VOICE_BYTES) throw java.io.IOException()
          out.write(buf, 0, n)
        }
      }
    }.isSuccess && total > 44
    if (!kept) file.delete()
    slot(id).complete(if (kept) file else null)
    return kept
  }

  /** [id]'s recording once it has arrived, waiting up to [waitMs]; it's the caller's to delete. */
  suspend fun take(id: String, waitMs: Long): File? {
    val file = withTimeoutOrNull(waitMs) { slot(id).await() }
    arrived.remove(id)
    return file
  }

  /** Deletes any recording older than [MAX_AGE_MS]. */
  fun sweep() {
    dir.listFiles()?.filter { now() - it.lastModified() > MAX_AGE_MS }?.forEach { it.delete() }
  }

  companion object {
    const val MAX_AGE_MS = 2 * 60 * 1000L
  }
}

/** What the phone's on-device recognizer made of a recording. */
sealed interface Transcription {
  data class Text(val text: String) : Transcription

  /** No on-device recognizer for the language (or this Android): nothing transcribed, nothing sent to a server. */
  data object NoOffline : Transcription

  data object Failed : Transcription
}

/** Turns a WAV recording into text on this phone only. */
fun interface Transcriber {
  suspend fun transcribe(wav: File, language: String): Transcription
}

/**
 * The phone's **on-device** speech recognizer (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, Android 12+; fed the file through
 * `EXTRA_AUDIO_SOURCE`, Android 13+). Never the network recognizer: where there's no on-device model for the language, it's
 * [Transcription.NoOffline], and the watch says so.
 */
class OnDeviceTranscriber(private val context: android.content.Context) : Transcriber {
  override suspend fun transcribe(wav: File, language: String): Transcription {
    if (android.os.Build.VERSION.SDK_INT < 33) return Transcription.NoOffline
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
      if (!android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext Transcription.NoOffline
      val recognizer = android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
      try {
        recognize(recognizer, wav, language)
      } finally {
        recognizer.destroy()
      }
    }
  }

  @androidx.annotation.RequiresApi(33)
  private suspend fun recognize(recognizer: android.speech.SpeechRecognizer, wav: File, language: String): Transcription {
    val (read, write) = android.os.ParcelFileDescriptor.createPipe()
    val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, language)
      .putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
      .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
      .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
      .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
    val done = CompletableDeferred<Transcription>()
    recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
      override fun onResults(results: android.os.Bundle?) {
        val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        done.complete(Transcription.Text(text.trim()))
      }
      override fun onError(error: Int) {
        done.complete(
          when (error) {
            android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> Transcription.NoOffline
            android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Transcription.Text("")
            else -> Transcription.Failed
          },
        )
      }
      override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
      override fun onBeginningOfSpeech() = Unit
      override fun onRmsChanged(rmsdB: Float) = Unit
      override fun onBufferReceived(buffer: ByteArray?) = Unit
      override fun onEndOfSpeech() = Unit
      override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
      override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    })
    recognizer.startListening(intent)
    // The audio after the WAV header, then the end of the pipe: the recognizer answers at the end of the audio.
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runCatching {
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
          wav.inputStream().use { input ->
            input.skip(44)
            input.copyTo(out)
          }
        }
      }
    }
    return kotlinx.coroutines.withTimeoutOrNull(20_000) { done.await() } ?: Transcription.Failed.also { runCatching { read.close() } }
  }
}
