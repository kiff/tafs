package bot.tafs.app

import bot.tafs.app.watch.Transcriber
import bot.tafs.app.watch.Transcription
import bot.tafs.app.watch.VoiceInbox
import bot.tafs.app.watch.WatchRelay
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Row 44, A, the phone's half: a recording from the watch is kept only until it's turned into text by the phone's on-device
 * recognizer, then deleted whatever the outcome; only the text goes back, and with no on-device recognizer, nothing is transcribed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class VoiceTranscribeTest {
  private val t = 1_763_244_000_000L
  private val dir: File = Files.createTempDirectory("voice").toFile()
  private val id = "w_00000001"
  private fun wav(bytes: Int = 1_000) = ByteArrayInputStream(ByteArray(bytes))

  private fun relay(inbox: VoiceInbox, transcriber: Transcriber?) = WatchRelay(model = { null }, now = { t }, voice = inbox, transcriber = transcriber)

  private suspend fun ask(relay: WatchRelay, language: String = "en"): WatchReply {
    val (path, data) = WatchLink.encode(WatchRequest.Transcribe(t, id, language))
    return relay.answer(path, data)
  }

  @Test fun theTextGoesBackAndTheRecordingIsDeleted() = runTest {
    val inbox = VoiceInbox(dir) { t }
    assertTrue(inbox.receive(id, wav()))
    var read: Pair<File, String>? = null
    val reply = ask(relay(inbox) { file, language -> read = file to language; Transcription.Text("Move my call to Thursday") })
    assertEquals(WatchReply.Transcribed("Move my call to Thursday"), reply)
    assertEquals("en", read!!.second)
    assertFalse("deleted once transcribed", read!!.first.exists())
  }

  @Test fun noOnDeviceRecognizerIsSaidAndTheRecordingIsDeletedAllTheSame() = runTest {
    val inbox = VoiceInbox(dir) { t }
    inbox.receive(id, wav())
    assertEquals(WatchReply.NoOfflineSpeech, ask(relay(inbox) { _, _ -> Transcription.NoOffline }))
    assertTrue(dir.listFiles()!!.isEmpty())
    // A phone with no transcriber at all says the same: never a server.
    inbox.receive(id, wav())
    assertEquals(WatchReply.NoOfflineSpeech, ask(relay(inbox, null)))
    assertTrue(dir.listFiles()!!.isEmpty())
  }

  @Test fun aFailedTranscriptionDeletesTheRecordingToo() = runTest {
    val inbox = VoiceInbox(dir) { t }
    inbox.receive(id, wav())
    assertTrue(ask(relay(inbox) { _, _ -> Transcription.Failed }) is WatchReply.Failed)
    assertTrue(dir.listFiles()!!.isEmpty())
  }

  @Test fun aRecordingTooLongIsNotKept() {
    val inbox = VoiceInbox(dir) { t }
    assertFalse(inbox.receive(id, wav((WatchLink.MAX_VOICE_BYTES + 1).toInt())))
    assertTrue(dir.listFiles()!!.isEmpty())
  }

  @Test fun aBadIdOrLanguageIsRefusedBeforeAnything() = runTest {
    val inbox = VoiceInbox(dir) { t }
    assertFalse(inbox.receive("../x", wav()))
    val (path, data) = WatchLink.encode(WatchRequest.Transcribe(t, id, "en; rm"))
    assertTrue(relay(inbox) { _, _ -> error("never") }.answer(path, data) is WatchReply.Refused)
  }

  @Test fun aRecordingLeftWithNoRequestGoesAfterTwoMinutes() = runTest {
    var now = t
    val inbox = VoiceInbox(dir) { now }
    inbox.receive(id, wav())
    val file = dir.listFiles()!!.single()
    file.setLastModified(t)
    now = t + VoiceInbox.MAX_AGE_MS + 1
    inbox.sweep()
    assertFalse(file.exists())
    assertNull(inbox.take("w_00000002", waitMs = 1))
  }
}
