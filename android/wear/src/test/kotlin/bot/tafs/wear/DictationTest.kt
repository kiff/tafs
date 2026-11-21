package bot.tafs.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row 44, A: dictation isn't cut off by a pause; it ends on ✓ or about 8 s of quiet, with every stretch's words. */
class DictationTest {
  private var clock = 0L
  private val d = Dictation(now = { clock }).also { it.begin() }

  @Test fun aPauseMidSentenceListensOnAndJoinsTheWords() {
    assertEquals("Move my call", d.partial("Move my call"))
    clock += 2_000
    assertEquals(Dictation.Next.Listen, d.ended("Move my call"))
    clock += 3_000
    assertEquals("Move my call with Nord", d.partial("with Nord"))
    clock += 1_000
    assertEquals(Dictation.Next.Listen, d.ended("with Nord"))
  }

  @Test fun theRecognizersNoMatchIsAQuietStretchNotTheEnd() {
    d.ended("Move my call")
    clock += 4_000
    assertEquals(Dictation.Next.Listen, d.ended(null))
    assertTrue(Dictation.isQuiet(android.speech.SpeechRecognizer.ERROR_NO_MATCH))
    assertTrue(Dictation.isQuiet(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    assertFalse(Dictation.isQuiet(android.speech.SpeechRecognizer.ERROR_NETWORK))
  }

  @Test fun aboutEightSecondsOfQuietEndsItWithEverythingHeard() {
    d.ended("Move my call")
    clock += 2_000
    d.ended("to Thursday")
    clock += Dictation.QUIET_MS
    assertEquals(Dictation.Next.Done("Move my call to Thursday"), d.ended(null))
  }

  @Test fun theTickEndsItAtOnce() {
    d.ended("Move my call")
    d.stopping = true
    assertEquals(Dictation.Next.Done("Move my call to Thursday"), d.ended("to Thursday"))
  }

  @Test fun nothingSaidForEightSecondsIsNothingHeard() {
    clock += Dictation.QUIET_MS
    assertEquals(Dictation.Next.Done(""), d.ended(null))
  }

  /** Cancel sends nothing, even when the recognizer answers late: a result, or its error on a cancel. */
  @Test fun afterCancelALateResultOrErrorSendsNothing() {
    d.ended("Move my call")
    d.cancel()
    assertEquals(Dictation.Next.Cancelled, d.ended("to Thursday"))
    assertEquals(Dictation.Next.Cancelled, d.failed())
    assertEquals(Dictation.Next.Cancelled, d.ended(null))
  }

  @Test fun aFailureEndsItWithWhatWasHeardSoFar() {
    d.ended("Move my call")
    assertEquals(Dictation.Next.Done("Move my call"), d.failed())
  }
}
