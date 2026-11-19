package bot.tafs.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.io.File

/** Stop with the app's own audio (v2): the microphone stops feeding the recognizer, and the recording stays for Play. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class AudioTapTest {
  private val dir = File(RuntimeEnvironment.getApplication().cacheDir, "voice-test")

  @After fun tidy() {
    ShadowAudioRecord.clearSource()
    dir.deleteRecursively()
  }

  /** A microphone that hears a steady tone, a few milliseconds at a time, as a real one does. */
  private fun microphone() = ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
    override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
      // 10 ms of 16 kHz mono 16-bit audio a read, in about real time, so the 30 s limit is never what ends a test's feed.
      val n = minOf(sizeInBytes, 320)
      Thread.sleep(10)
      for (i in offsetInBytes until offsetInBytes + n) audioData[i] = if (i % 2 == 0) 0x10 else 0x08
      return n
    }
  })

  private fun waitFor(what: () -> Boolean) {
    val until = System.currentTimeMillis() + 5_000
    while (!what() && System.currentTimeMillis() < until) Thread.sleep(10)
    assertTrue(what())
  }

  /**
   * A recognizer that has stopped reading: the app's write to it blocks until its side of the pipe is closed, as a real pipe's does
   * once full. Stop has to end the feed all the same, by closing the pipe.
   */
  private fun stalled(pipe: android.os.ParcelFileDescriptor) = object : java.io.OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
      while (pipe.fileDescriptor.valid()) Thread.sleep(5)
      throw java.io.IOException("pipe closed")
    }
  }

  @Test fun stopEndsTheFeedEvenWhenTheRecognizerHasStoppedReading() {
    microphone()
    val tap = AudioTap(dir, feedOf = ::stalled)
    assertNotNull(tap.start())
    waitFor { tap.recorded > 0 }
    assertTrue(tap.feeding)

    tap.endFeed()
    // The pipe closed, so the blocked write ended and the feed with it: the recognizer reaches the end of the audio.
    waitFor { !tap.feeding }
    assertNotNull(tap.finish()?.also { it.delete() })
  }

  /** Listener's Stop ends the app's audio first: without it the recognizer waits on a pipe the app goes on filling. */
  @Test fun listenersStopEndsTheFeed() {
    microphone()
    val listener = Listener(
      RuntimeEnvironment.getApplication(), WatchModel(PhoneLink { Asked.Unreachable }), kotlinx.coroutines.MainScope(), keepAudio = true,
    )
    val tap = AudioTap(dir)
    assertNotNull(tap.start())
    listener.tap = tap
    waitFor { tap.recorded > 0 }

    listener.stop()
    waitFor { !tap.feeding }
    val atStop = tap.recorded
    Thread.sleep(200)
    assertEquals(atStop, tap.recorded)
    listener.cancel()
  }

  @Test fun stopEndsTheFeedAndKeepsTheRecording() {
    microphone()
    val tap = AudioTap(dir)
    assertNotNull(tap.start())
    waitFor { tap.recorded > 0 }

    tap.endFeed()
    val atStop = tap.recorded
    Thread.sleep(200)
    // Nothing more is heard after Stop: the recognizer's audio has ended.
    assertEquals(atStop, tap.recorded)

    val kept = tap.finish()
    assertNotNull(kept)
    assertEquals(VoiceFormat.durationMs(atStop), kept!!.durationMs)
    assertTrue(kept.file.length() > 44)
    kept.delete()
  }
}
