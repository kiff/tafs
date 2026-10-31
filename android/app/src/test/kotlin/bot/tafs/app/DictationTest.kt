package bot.tafs.app

import bot.tafs.app.voice.Dictation
import bot.tafs.app.voice.LanguageMemory
import bot.tafs.app.voice.SpeechEngine
import bot.tafs.app.voice.SpeechLanguage
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** The language each listen was in. */
    val heardIn = mutableListOf<String?>()
    override val languages = MutableStateFlow<List<String>>(emptyList())
    override fun listen(listener: SpeechEngine.Listener, language: String?) {
      this.listener = listener
      listens++
      heardIn += language
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

  // MARK: The language, a switch among the owner's own (as iOS's)

  private class Kept(var tag: String? = null) : LanguageMemory {
    override fun load() = tag
    override fun save(tag: String) { this.tag = tag }
  }

  @Test fun thePhonesOwnLanguagesTheOwnersFirst() {
    val installed = listOf("en-US", "en-GB", "es-ES", "es-MX", "fr-FR", "fr-CA", "de-DE")
    assertEquals(listOf("es-ES", "fr-CA", "de-DE", "en-GB"), SpeechLanguage.ordered(installed, listOf("es-CO", "fr_CA")))
    assertEquals(listOf("fr-FR"), SpeechLanguage.ordered(listOf("fr-FR"), listOf("es-CO", "en-US")))
    assertEquals(emptyList<String>(), SpeechLanguage.ordered(emptyList(), listOf("es-CO")))
    // The owner's own form missing: the same language's best form (English: British, then American; another: its own country's).
    assertEquals("en-GB", SpeechLanguage.bestForm("en", listOf("en-AU", "en-GB", "en-US")))
    assertEquals("en-US", SpeechLanguage.bestForm("en", listOf("en-AU", "en-US")))
    assertEquals(listOf("en-GB"), SpeechLanguage.ordered(listOf("en-AU", "en-GB", "en-US"), listOf("en-FR")))
    // Their keyboards first, then the phone's languages; only those, so the switch goes ES, FR, EN, not every language it has.
    assertEquals(listOf("es-ES", "fr-FR", "en-US"), SpeechLanguage.owners(installed, keyboards = listOf("es-ES", "fr-FR"), preferred = listOf("en-US")))
    assertEquals("none of theirs on the phone: every language it has", listOf("de-DE"), SpeechLanguage.owners(listOf("de-DE"), listOf("es-ES"), listOf("en-US")))
    assertEquals("FR", SpeechLanguage.label("fr-FR"))
    assertEquals("Français", SpeechLanguage.name("fr-FR"))
    assertEquals("Español", SpeechLanguage.name("es-MX"))
  }

  @Test fun switchingLanguageListensAgainInTheOtherAfterWhatWasHeard() {
    val kept = Kept()
    val d = Dictation(speech, kept)
    speech.languages.value = listOf("en-GB", "fr-FR")
    assertEquals("en-GB", d.current())
    d.start("Refund Ana") { box = it }
    speech.listener!!.finished("for order 2041")
    d.switchLanguage(box) { box = it }
    // Stopped and started again in French, after what was heard; nothing sent.
    assertTrue(d.listening)
    assertEquals("fr-FR", speech.heardIn.last())
    assertEquals("en-GB", speech.heardIn.first())
    speech.listener!!.heard("merci")
    assertEquals("Refund Ana for order 2041 merci", box)
    // Kept for next time, and round again to English.
    assertEquals("fr-FR", kept.tag)
    d.switchLanguage(box) { box = it }
    assertEquals("en-GB", d.current())
  }

  @Test fun switchingWhileNotDictatingOnlyChoosesAndTheChoiceIsKept() {
    val kept = Kept()
    speech.languages.value = listOf("en-GB", "es-ES", "fr-FR")
    val d = Dictation(speech, kept)
    d.switchLanguage("") { box = it }
    assertFalse(d.listening)
    assertEquals(0, speech.listens)
    assertEquals("es-ES", d.current())
    // A new chat starts in the language chosen last.
    assertEquals("es-ES", Dictation(speech, kept).current())
  }

  @Test fun aLanguageChosenThatThePhoneNoLongerHasGivesWayToTheOwnersFirst() {
    speech.languages.value = listOf("en-GB", "fr-FR")
    assertEquals("en-GB", Dictation(speech, Kept("de-DE")).current())
  }

  @Test fun oneLanguageHasNothingToSwitch() {
    speech.languages.value = listOf("en-GB")
    val d = Dictation(speech, Kept())
    d.switchLanguage("") { box = it }
    assertEquals("en-GB", d.current())
    // Not known yet (before Android 13, or still asking): the phone's own language, chosen by the recognizer.
    speech.languages.value = emptyList()
    assertEquals(null, d.current())
  }
}
