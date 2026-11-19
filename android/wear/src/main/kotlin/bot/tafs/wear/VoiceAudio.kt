package bot.tafs.wear

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * v2's recording, kept on the watch for Play and Pause until the person sends or deletes it (docs/watch-wear.md, Version 2). It
 * never leaves the watch: only the words heard go to the phone. Deleted on Send, Delete, Re-record, sign-out and when the app is
 * put away ([WatchModel]), and any left over is swept when the app starts or stops ([VoiceFiles.sweep]).
 */
interface VoiceAudio {
  /** How long it is, in milliseconds. */
  val durationMs: Long

  /** Erases it from the watch. */
  fun delete()
}

/** A recording in the app's private files, as a WAV the watch's own player opens. */
class KeptAudio(val file: File, override val durationMs: Long) : VoiceAudio {
  override fun delete() {
    file.delete()
  }
}

/** Where recordings are kept, and the sweep of any left over (a crash, a kill) that no screen holds. */
object VoiceFiles {
  /** The app's private folder for recordings: nothing else is written there. */
  fun dir(context: android.content.Context): File = File(context.filesDir, "voice")

  /** Deletes every recording in [dir] but [keep]. */
  fun sweep(dir: File, keep: File? = null) {
    dir.listFiles()?.forEach { if (it != keep) it.delete() }
  }
}

/** The recording's format: what the watch's recognizer takes from an app (`EXTRA_AUDIO_SOURCE`), 16 kHz mono 16-bit PCM. */
object VoiceFormat {
  const val RATE = 16_000
  const val CHANNELS = 1
  const val BITS = 16
  const val BYTES_PER_SECOND = RATE * CHANNELS * BITS / 8

  /** A WAV header for [dataBytes] of this format's PCM: 44 bytes, little-endian. */
  fun wavHeader(dataBytes: Long): ByteArray {
    val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(CHANNELS.toShort()).putInt(RATE).putInt(BYTES_PER_SECOND)
      .putShort((CHANNELS * BITS / 8).toShort()).putShort(BITS.toShort())
    b.put("data".toByteArray()).putInt(dataBytes.toInt())
    return b.array()
  }

  fun durationMs(dataBytes: Long): Long = dataBytes * 1000 / BYTES_PER_SECOND
}

/**
 * The microphone, opened by the app itself: what it hears is written to a private file, for Play, and handed to the watch's
 * recognizer through a pipe (`RecognizerIntent.EXTRA_AUDIO_SOURCE`, Wear OS 4 and later), for the words. One capture, so the
 * recording and the words are the same speech. [peak] is the loudest sample, for the internal build's probe line: a recording
 * that stays silent means the microphone was given to someone else.
 */
internal class AudioTap(private val dir: File) {
  private var record: AudioRecord? = null
  private var file: File? = null
  private var out: RandomAccessFile? = null
  private var pipe: ParcelFileDescriptor? = null
  private var thread: Thread? = null
  @Volatile private var running = false
  @Volatile private var bytes = 0L
  @Volatile var peak = 0
    private set

  /** Opens the microphone; the pipe's read end, for the recognizer. Null when the microphone can't be opened. */
  @SuppressLint("MissingPermission")
  fun start(): ParcelFileDescriptor? {
    val min = AudioRecord.getMinBufferSize(VoiceFormat.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (min <= 0) return null
    val r = runCatching {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VoiceFormat.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(min, VoiceFormat.BYTES_PER_SECOND / 2))
    }.getOrNull() ?: return null
    if (r.state != AudioRecord.STATE_INITIALIZED) {
      r.release()
      return null
    }
    dir.mkdirs()
    val f = File(dir, "voice-${System.nanoTime()}.wav")
    val raf = RandomAccessFile(f, "rw").apply { setLength(0); write(VoiceFormat.wavHeader(0)) }
    val (read, write) = ParcelFileDescriptor.createPipe()
    record = r; file = f; out = raf; pipe = write; bytes = 0; peak = 0
    running = true
    r.startRecording()
    thread = Thread({
      val buf = ByteArray(min)
      var toRecognizer: java.io.OutputStream? = ParcelFileDescriptor.AutoCloseOutputStream(write)
      // At most the limit, whatever happens to Stop.
      val most = VoiceFormat.BYTES_PER_SECOND.toLong() * (LISTEN_LIMIT_MS / 1000 + 1)
      while (running && bytes < most) {
        val n = r.read(buf, 0, buf.size)
        if (n <= 0) continue
        raf.write(buf, 0, n)
        bytes += n
        for (i in 0 until n - 1 step 2) peak = maxOf(peak, kotlin.math.abs((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)))
        // The recognizer may stop reading before the person stops: the file goes on.
        if (toRecognizer != null && runCatching { toRecognizer!!.write(buf, 0, n) }.isFailure) toRecognizer = null
      }
      runCatching { toRecognizer?.close() }
    }, "tafs-voice").apply { isDaemon = true; start() }
    return read
  }

  /** What has been recorded so far, in bytes of audio. */
  internal val recorded: Long get() = bytes

  /**
   * Stop: the microphone stops and the recognizer's pipe closes, so the recognizer reaches the end of the audio and returns the
   * words. The recording stays, for [finish]. Without it the recognizer waits on a pipe the app goes on filling to the 30 s limit,
   * and Stop ends in "Didn't catch that" (the owner's Galaxy Watch 5, 0.4.0-test).
   */
  fun endFeed() {
    running = false
    // A write blocked on a recognizer that no longer reads ends when its pipe closes.
    runCatching { pipe?.close() }
    runCatching { thread?.join(500) }
    runCatching { record?.stop() }
  }

  /** Stops the microphone and keeps the recording; null when nothing was recorded. */
  fun finish(): KeptAudio? {
    val f = file ?: return null
    stop()
    val length = bytes
    if (length <= 0) {
      f.delete()
      return null
    }
    runCatching { RandomAccessFile(f, "rw").use { it.seek(0); it.write(VoiceFormat.wavHeader(length)) } }
    return KeptAudio(f, VoiceFormat.durationMs(length))
  }

  /** Stops the microphone and erases what it recorded. */
  fun discard() {
    val f = file
    stop()
    f?.delete()
  }

  private fun stop() {
    running = false
    // A write blocked on a recognizer that no longer reads ends when its pipe closes.
    runCatching { pipe?.close() }
    runCatching { record?.stop() }
    runCatching { thread?.join(500) }
    runCatching { record?.release() }
    runCatching { out?.close() }
    record = null; out = null; pipe = null; thread = null; file = null
  }
}

/** Play and Pause for a kept recording, from where it was paused; nothing leaves the watch. */
interface Playback {
  val playing: StateFlow<Boolean>
  fun toggle(audio: VoiceAudio)
  /** Where it is now, in milliseconds. */
  fun positionMs(): Long
  fun release()
}

/** [Playback] with the watch's own player, over the private file. */
internal class FilePlayback : Playback {
  private val _playing = MutableStateFlow(false)
  override val playing: StateFlow<Boolean> = _playing.asStateFlow()
  private var player: MediaPlayer? = null
  private var file: File? = null

  override fun toggle(audio: VoiceAudio) {
    val f = (audio as? KeptAudio)?.file ?: return
    if (file != f) {
      release()
      player = runCatching {
        MediaPlayer().apply {
          setDataSource(f.path)
          setOnCompletionListener { _playing.value = false; seekTo(0) }
          prepare()
        }
      }.getOrNull() ?: return
      file = f
    }
    val p = player ?: return
    // Pause keeps the place; Play goes on from it.
    if (p.isPlaying) {
      p.pause()
      _playing.value = false
    } else {
      p.start()
      _playing.value = true
    }
  }

  override fun positionMs(): Long = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0

  override fun release() {
    runCatching { player?.release() }
    player = null
    file = null
    _playing.value = false
  }
}
