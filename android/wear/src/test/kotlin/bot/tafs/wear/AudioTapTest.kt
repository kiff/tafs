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
      Thread.sleep(5)
      for (i in offsetInBytes until offsetInBytes + sizeInBytes) audioData[i] = if (i % 2 == 0) 0x10 else 0x08
      return sizeInBytes
    }
  })

  private fun waitFor(what: () -> Boolean) {
    val until = System.currentTimeMillis() + 5_000
    while (!what() && System.currentTimeMillis() < until) Thread.sleep(10)
    assertTrue(what())
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
