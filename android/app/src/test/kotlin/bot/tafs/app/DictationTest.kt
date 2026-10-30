package bot.tafs.app

import bot.tafs.app.voice.Dictation
import bot.tafs.app.voice.SpeechEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dictating into the message box (docs/chat.md): words go in as they're heard, and nothing is ever sent by voice. */
class DictationTest {
  private class FakeSpeech(override var available: Boolean = true, override var mayListen: Boolean = true) : SpeechEngine {
    var listener: SpeechEngine.Listener? = null
    var listens = 0
    var stopped = 0
    override fun listen(listener: SpeechEngine.Listener) {
      this.listener = listener
      listens++
    }
    override fun stop() {
      stopped++
    }
  }

  private val speech = FakeSpeech()
  private val dictation = Dictation(speech)
  private var box = ""

  @Test fun wordsGoIntoTheBoxAfterWhatWasThereAsTheyreHeard() {
    box = "Refund Ana"
    dictation.start(box) { box = it }
    speech.listener!!.heard("for order")
    assertEquals("Refund Ana for order", box)
    speech.listener!!.heard("for order 2041")
    assertEquals("Refund Ana for order 2041", box)
    speech.listener!!.finished("for order 2041")
    assertEquals("Refund Ana for order 2041", box)
    // It listens on after a pause, and what comes next goes after.
    assertEquals(2, speech.listens)
    speech.listener!!.heard("today")
    assertEquals("Refund Ana for order 2041 today", box)
  }

  @Test fun stoppingKeepsWhatWasHeardInTheBoxUnsent() {
    dictation.start("") { box = it }
    speech.listener!!.heard("Pause the refunds")
    dictation.stop()
    assertEquals("Pause the refunds", box)
    assertFalse(dictation.listening)
    assertEquals(1, speech.stopped)
    // Late words from the recognizer after Stop change nothing.
    speech.listener!!.finished("Pause the refunds now")
    assertEquals("Pause the refunds", box)
  }

  @Test fun itStopsByItselfAfterAFewQuietPauses() {
    dictation.start("") { box = it }
    speech.listener!!.finished("")
    assertTrue(dictation.listening)
    speech.listener!!.finished("")
    assertFalse(dictation.listening)
  }

  @Test fun aPhoneThatCantRecogniseOnItselfDoesntDictate() {
    speech.available = false
    dictation.start("") { box = it }
    assertEquals(Dictation.State.Blocked(Dictation.NOT_ON_DEVICE), dictation.state.value)
    assertEquals(0, speech.listens)
    dictation.dismiss()
    assertEquals(Dictation.State.Idle, dictation.state.value)
  }

  @Test fun withoutTheMicrophoneItSaysHowToAllowIt() {
    speech.mayListen = false
    dictation.start("") { box = it }
    assertEquals(Dictation.State.Blocked(Dictation.NO_MICROPHONE, settings = true), dictation.state.value)
  }

  @Test fun aSpaceGoesBetweenOnlyWhereNoneIs() {
    assertEquals("a b", Dictation.join("a", " b "))
    assertEquals("a b", Dictation.join("a ", "b"))
    assertEquals("b", Dictation.join("", "b"))
    assertEquals("a", Dictation.join("a", "  "))
  }
}
