package bot.tafs.wear

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Row 44, A: when the watch's recognizer gives up, the recording goes to the phone, which turns it into text on the phone itself.
 * Only the text comes back, to the review. The watch's copy is deleted once the text is back, on Cancel, and after 2 minutes at
 * most; with no on-device recognizer on the phone, nothing is transcribed and the watch says so.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class TranscribeOnPhoneTest {
  @Before fun words() {
    RuntimeEnvironment.setQualifiers("en")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  private val t = 1_763_244_000_000L

  private fun recording(): KeptAudio {
    val f = File.createTempFile("voice-", ".wav").apply { writeBytes(ByteArray(64)) }
    return KeptAudio(f, 1_000)
  }

  private class Phone(val reply: suspend (WatchRequest) -> Asked) : PhoneLink {
    val asked = mutableListOf<WatchRequest>()
    val sent = mutableListOf<String>()
    override suspend fun ask(request: WatchRequest): Asked = reply(request).also { asked += request }
    override suspend fun sendVoice(id: String, file: File): Boolean { sent += id; return file.exists() }
  }

  private fun listening(phone: Phone) =
    WatchModel(phone, now = { t }, unlocked = { true }, newClientId = { "w_00000001" }, reviewFirst = true).also { it.listen() }

  @Test fun theTextBackJoinsWhatWasHeardGoesToTheReviewAndTheRecordingIsDeleted() = runTest {
    val phone = Phone { Asked.Answered(WatchReply.Transcribed("to Thursday at ten")) }
    val m = listening(phone)
    val audio = recording()
    m.transcribeOnPhone(audio, "Move my call", "en")
    assertEquals(listOf("w_00000001"), phone.sent)
    assertEquals(listOf<WatchRequest>(WatchRequest.Transcribe(t, "w_00000001", "en")), phone.asked)
    assertEquals("Move my call to Thursday at ten", (m.voice.value as WatchModel.Voice.Review).text)
    assertFalse("deleted once the text is back", audio.file.exists())
  }

  @Test fun cancelDeletesTheRecordingAtOnceAndTheLateTextIsDropped() = runTest {
    val answer = CompletableDeferred<Asked>()
    val m = listening(Phone { answer.await() })
    val audio = recording()
    val job = async { m.transcribeOnPhone(audio, "", "en") }
    runCurrent()
    assertTrue(m.voice.value is WatchModel.Voice.Transcribing)
    m.closeVoice()
    assertFalse("deleted on Cancel, before the phone answers", audio.file.exists())
    answer.complete(Asked.Answered(WatchReply.Transcribed("too late")))
    job.await()
    assertEquals(null, m.voice.value)
  }

  @Test fun afterTwoMinutesTheRecordingIsDeletedWhateverThePhoneDoes() = runTest {
    val m = listening(Phone { awaitCancellation() })
    val audio = recording()
    val job = async { m.transcribeOnPhone(audio, "", "en") }
    advanceTimeBy(VOICE_KEEP_MS - 1)
    assertTrue(audio.file.exists())
    advanceTimeBy(2)
    job.await()
    assertFalse("deleted after 2 minutes at most", audio.file.exists())
    assertEquals(WatchModel.Voice.HeardNothing, m.voice.value)
  }

  @Test fun noOnDeviceRecognizerKeepsWhatWasHeardOrSaysSo() = runTest {
    val heard = listening(Phone { Asked.Answered(WatchReply.NoOfflineSpeech) })
    heard.transcribeOnPhone(recording(), "Move my call", "en")
    assertEquals("Move my call", (heard.voice.value as WatchModel.Voice.Review).text)

    val none = listening(Phone { Asked.Answered(WatchReply.NoOfflineSpeech) })
    none.transcribeOnPhone(recording(), "", "en")
    assertEquals(WatchModel.Voice.NotSent(Words.Watch.noOfflineSpeech), none.voice.value)
  }

  @Test fun aRecordingThatDidntReachThePhoneAsksNothingAndIsDeleted() = runTest {
    val phone = object : PhoneLink {
      val asked = mutableListOf<WatchRequest>()
      override suspend fun ask(request: WatchRequest): Asked = Asked.Unreachable.also { asked += request }
      override suspend fun sendVoice(id: String, file: File) = false
    }
    val m = WatchModel(phone, now = { t }, unlocked = { true }, newClientId = { "w_00000001" }, reviewFirst = true).also { it.listen() }
    val audio = recording()
    m.transcribeOnPhone(audio, "", "en")
    assertTrue(phone.asked.isEmpty())
    assertFalse(audio.file.exists())
    assertEquals(WatchModel.Voice.HeardNothing, m.voice.value)
  }

  @Test fun onlyTheRecognizersGivingUpFallsBackNeverTheMicrophoneOrAPermission() {
    assertTrue(Dictation.fallsBackToPhone(android.speech.SpeechRecognizer.ERROR_NETWORK))
    assertTrue(Dictation.fallsBackToPhone(android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
    assertFalse(Dictation.fallsBackToPhone(android.speech.SpeechRecognizer.ERROR_AUDIO))
    assertFalse(Dictation.fallsBackToPhone(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    assertFalse(Dictation.fallsBackToPhone(android.speech.SpeechRecognizer.ERROR_NO_MATCH))
  }
}
