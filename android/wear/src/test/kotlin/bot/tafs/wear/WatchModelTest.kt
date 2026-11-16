package bot.tafs.wear

import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.DecisionLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.NotAccepted
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the watch shows from the phone's answers: its words as they come, and nothing it can't draw guessed at. */
class WatchModelTest {
  private val t = 1_763_244_000_000L
  private val call = HeldLine("hold_1", "Refunds", "Refund order 1042", "€64.00", "Over €50 each", refusable = true)
  private val needsYou = WatchReply.NeedsYou(1, "1 held call needs you", listOf(call))
  private val tafs = WatchReply.Tafs(listOf(TafLine("taf_1", "Refunds", "Waiting for you", halted = false)), allHalted = false)

  private class Phone(val answers: (WatchRequest) -> Asked) : PhoneLink {
    val asked = mutableListOf<WatchRequest>()
    override suspend fun ask(request: WatchRequest): Asked = answers(request).also { asked += request }
  }

  /** The all-clear (board 12) shows only when nothing waits: no count and no held call. */
  @Test fun theAllClearShowsOnlyWhenNothingWaits() {
    assertTrue(WatchModel.NeedsYouRead(0, "Nothing needs you", emptyList()).allClear)
    assertFalse(WatchModel.NeedsYouRead(1, "1 held call", listOf(call)).allClear)
    // tafs cloud counts something the watch can't draw (a step, say): not all clear.
    assertFalse(WatchModel.NeedsYouRead(2, "2 need you", emptyList()).allClear)
  }

  @Test fun showsNeedsYouAndYourTafsInThePhonesWords() = runTest {
    val phone = Phone { if (it is WatchRequest.NeedsYou) Asked.Answered(needsYou) else Asked.Answered(tafs) }
    val model = WatchModel(phone, now = { t })
    model.refresh()
    assertEquals(WatchModel.Shown.Read(WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call)), WatchModel.TafsRead(tafs.tafs, false)), model.shown.value)
    // Reads only, each made now: nothing on this screen asks the phone to sign.
    assertEquals(listOf(WatchRequest.NeedsYou(t), WatchRequest.Tafs(t)), phone.asked)
  }

  @Test fun needsYouStaysWhenYourTafsDoesntAnswer() = runTest {
    val model = WatchModel(Phone { if (it is WatchRequest.NeedsYou) Asked.Answered(needsYou) else Asked.Unreachable }, now = { t })
    model.refresh()
    assertEquals(WatchModel.Shown.Read(WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call)), null), model.shown.value)
  }

  @Test fun aSignedOutPhoneShowsNothingKept() = runTest {
    var signedIn = true
    val model = WatchModel(Phone { if (!signedIn) Asked.Answered(WatchReply.SignedOut) else if (it is WatchRequest.NeedsYou) Asked.Answered(needsYou) else Asked.Answered(tafs) }, now = { t })
    model.refresh()
    assertTrue(model.shown.value is WatchModel.Shown.Read)
    signedIn = false
    model.refresh()
    assertEquals(WatchModel.Shown.SignedOut, model.shown.value)
  }

  @Test fun noPhoneSaysSo() = runTest {
    val model = WatchModel(Phone { Asked.Unreachable }, now = { t })
    model.refresh()
    assertEquals(WatchModel.Shown.PhoneUnreachable, model.shown.value)
  }

  @Test fun whatTheWatchCantDrawOpensOnThePhone() = runTest {
    for (answer in listOf(Asked.NotUnderstood, Asked.Answered(WatchReply.Refused(NotAccepted.TOO_OLD)), Asked.Answered(WatchReply.Done("Refused")))) {
      val model = WatchModel(Phone { answer }, now = { t })
      model.refresh()
      assertEquals(answer.toString(), WatchModel.Shown.OpenOnPhone, model.shown.value)
    }
  }

  @Test fun putAwayItForgetsWhatItShowed() = runTest {
    val model = WatchModel(Phone { if (it is WatchRequest.NeedsYou) Asked.Answered(needsYou) else Asked.Answered(tafs) }, now = { t })
    model.refresh()
    model.forget()
    assertEquals(WatchModel.Shown.Asking, model.shown.value)
  }

  // MARK: Refuse and Halt

  private fun reads(r: WatchRequest): Asked = if (r is WatchRequest.NeedsYou) Asked.Answered(needsYou) else Asked.Answered(tafs)

  @Test fun aLockedWatchOrOneWithNoLockSendsNothing() = runTest {
    val phone = Phone(::reads)
    val model = WatchModel(phone, now = { t }, unlocked = { false })
    model.refuse("hold_1")
    model.halt("taf_1")
    model.askHaltAll()
    model.confirmHaltAll()
    assertEquals(WatchModel.Outcome.NeedsLock, model.outcome.value)
    assertTrue("nothing reached the phone", phone.asked.isEmpty())
  }

  @Test fun aRefusalShowsThePhonesResultThenTheListsAsTheyStand() = runTest {
    val phone = Phone { if (it is WatchRequest.Refuse) Asked.Answered(WatchReply.Done("Refused")) else reads(it) }
    val model = WatchModel(phone, now = { t }, unlocked = { true })
    model.refuse("hold_1")
    assertEquals(WatchModel.Outcome.Done("Refused"), model.outcome.value)
    assertEquals(listOf(WatchRequest.Refuse(t, "hold_1"), WatchRequest.NeedsYou(t), WatchRequest.Tafs(t)), phone.asked)
  }

  @Test fun haltAllIsAskedOnTheWatchBeforeAnythingIsSent() = runTest {
    val phone = Phone { if (it is WatchRequest.Halt) Asked.Answered(WatchReply.Done("All your tafs halted")) else reads(it) }
    val model = WatchModel(phone, now = { t }, unlocked = { true })
    model.confirmHaltAll()
    model.askHaltAll()
    assertTrue(model.confirmingHaltAll.value)
    model.cancelHaltAll()
    model.confirmHaltAll()
    assertTrue("not asked, or cancelled: nothing sent", phone.asked.none { it is WatchRequest.Halt })
    model.askHaltAll()
    model.confirmHaltAll()
    assertEquals(listOf(WatchRequest.Halt(t, null)), phone.asked.filterIsInstance<WatchRequest.Halt>())
    assertEquals(false, model.confirmingHaltAll.value)
  }

  @Test fun haltOneAndHaltAllNameWhatTheyHalt() = runTest {
    val phone = Phone { if (it is WatchRequest.Halt) Asked.Answered(WatchReply.Done("Halted")) else reads(it) }
    val model = WatchModel(phone, now = { t }, unlocked = { true })
    model.halt("taf_1")
    model.askHaltAll()
    model.confirmHaltAll()
    assertEquals(listOf(WatchRequest.Halt(t, "taf_1"), WatchRequest.Halt(t, null)), phone.asked.filterIsInstance<WatchRequest.Halt>())
    assertEquals(WatchModel.Outcome.Done("Halted"), model.outcome.value)
  }

  @Test fun whatDidntGoThroughSaysSo() = runTest {
    val cases = mapOf<Asked, WatchModel.Outcome>(
      Asked.Answered(WatchReply.AlreadyAnswered) to WatchModel.Outcome.AlreadyAnswered,
      Asked.Answered(WatchReply.Failed("Not refused: KIFF didn't answer.")) to WatchModel.Outcome.NotDone("Not refused: KIFF didn't answer."),
      Asked.Answered(WatchReply.Refused(NotAccepted.TOO_OLD)) to WatchModel.Outcome.NotDone(null),
      Asked.Unreachable to WatchModel.Outcome.PhoneUnreachable,
      Asked.NotUnderstood to WatchModel.Outcome.NotDone(null),
    )
    for ((answer, outcome) in cases) {
      val model = WatchModel(Phone { if (it is WatchRequest.Refuse) answer else reads(it) }, now = { t }, unlocked = { true })
      model.refuse("hold_1")
      assertEquals(answer.toString(), outcome, model.outcome.value)
    }
  }

  // MARK: Ask tafs

  private val taken = AskLine("wa_01", "w_00000001", AskLine.ACCEPTED, words = "Sent", updated = "2026-11-16T19:00:00Z")
  private val working = taken.copy(state = AskLine.WORKING, tafName = "Calendar", words = "Working", updated = "2026-11-16T19:00:05Z")
  private val done = working.copy(state = AskLine.DONE, line = "Moved your call to 18:00.", words = "Done", updated = "2026-11-16T19:01:00Z")

  private fun asker(answer: (WatchRequest.Ask) -> Asked) = Phone { if (it is WatchRequest.Ask) answer(it) else reads(it) }

  @Test fun whatsHeardGoesAtOnceWithNothingToConfirm() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = WatchModel(phone, now = { t }, unlocked = { true }, newClientId = { "w_00000001" })
    assertTrue(model.listen())
    model.partial("Move my four o'clock")
    assertEquals(WatchModel.Voice.Listening("Move my four o'clock", null), model.voice.value)
    assertTrue("nothing sent while listening", phone.asked.isEmpty())
    model.heard("  Move my four o'clock call to six ")
    assertEquals(listOf(WatchRequest.Ask(t, "w_00000001", "Move my four o'clock call to six")), phone.asked)
    assertEquals(WatchModel.Voice.Sent(taken), model.voice.value)
  }

  @Test fun silenceSendsNothing() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = WatchModel(phone, now = { t }, unlocked = { true })
    model.listen()
    model.heard("   ")
    assertEquals(WatchModel.Voice.HeardNothing, model.voice.value)
    model.closeVoice()
    model.heard("Too late")
    assertTrue(phone.asked.isEmpty())
  }

  /** An ask is an instruction to a taf: as Refuse and Halt, nothing goes from a watch with no lock, or a locked one. */
  @Test fun aWatchWithNoLockOrLockedAsksNothing() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = WatchModel(phone, now = { t })
    assertFalse(model.listen())
    assertEquals(WatchModel.Voice.NeedsLock, model.voice.value)
    model.heard("Move my call")
    assertTrue(phone.asked.isEmpty())
  }

  @Test fun noReceiptOffersRetryWithTheSameId() = runTest {
    var answer: Asked = Asked.Unreachable
    val phone = asker { answer }
    var ids = 0
    val model = WatchModel(phone, now = { t }, unlocked = { true }, newClientId = { "w_0000000${++ids}" })
    model.listen()
    model.heard("Move my call to six")
    assertEquals(WatchModel.Voice.Unsure("Move my call to six", "w_00000001", null), model.voice.value)
    answer = Asked.Answered(WatchReply.Unsure)
    model.retry()
    assertEquals(WatchModel.Voice.Unsure("Move my call to six", "w_00000001", null), model.voice.value)
    answer = Asked.Answered(WatchReply.AskTaken(taken))
    model.retry()
    assertEquals(WatchModel.Voice.Sent(taken), model.voice.value)
    // Three sends, one id: tafs cloud starts it once.
    assertEquals(listOf("w_00000001"), phone.asked.filterIsInstance<WatchRequest.Ask>().map { it.clientId }.distinct())
    assertEquals(3, phone.asked.filterIsInstance<WatchRequest.Ask>().size)
  }

  @Test fun theResultOpensItsCardAndAReplyContinuesTheAsk() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    var ids = 0
    val model = WatchModel(phone, now = { t }, unlocked = { true }, newClientId = { "w_0000000${++ids}" }, seen = mutableSetOf())
    model.listen()
    model.heard("Move my call to six")
    model.took(working)
    assertEquals(WatchModel.Voice.Sent(working), model.voice.value)
    assertEquals(listOf(working), model.working())
    model.took(done)
    assertEquals(null, model.voice.value)
    assertEquals(done, model.result.value)
    // Opened: not "ready" any more.
    assertTrue(model.ready().isEmpty())
    // An older state crossing a newer one never replaces it.
    model.took(working)
    assertEquals(done, model.asks.value["wa_01"])
    assertTrue(model.listen(replyTo = "wa_01"))
    model.heard("Make it seven")
    assertEquals(WatchRequest.Ask(t, "w_00000002", "Make it seven", replyTo = "wa_01"), phone.asked.filterIsInstance<WatchRequest.Ask>().last())
  }

  @Test fun aResultNotOpenedIsReady() = runTest {
    val model = WatchModel(asker { Asked.Unreachable }, now = { t }, unlocked = { true }, seen = mutableSetOf())
    model.took(done)
    model.took(working.copy(id = "wa_02", clientId = "w_00000002"))
    assertEquals(listOf("wa_01"), model.ready().map { it.id })
    assertEquals(listOf("wa_02"), model.working().map { it.id })
    model.forget()
    assertTrue("nothing kept once put away", model.asks.value.isEmpty() && model.ready().isEmpty())
  }

  @Test fun whatWasntSentSaysWhy() = runTest {
    val cases = mapOf<Asked, WatchModel.Voice>(
      Asked.Answered(WatchReply.Failed("Before you ask tafs, allow AI use in tafs on your phone.")) to
        WatchModel.Voice.NotSent("Before you ask tafs, allow AI use in tafs on your phone."),
      Asked.Answered(WatchReply.Refused(NotAccepted.MALFORMED)) to WatchModel.Voice.NotSent(null),
      Asked.NotUnderstood to WatchModel.Voice.NotSent(null),
    )
    for ((answer, voice) in cases) {
      val model = WatchModel(asker { answer }, now = { t }, unlocked = { true })
      model.listen()
      model.heard("Move my call")
      assertEquals(answer.toString(), voice, model.voice.value)
    }
  }

  @Test fun theTimingReadoutCountsEachStep() = runTest {
    var clock = t
    val model = WatchModel(asker { clock += 900; Asked.Answered(WatchReply.AskTaken(taken)) }, now = { clock }, unlocked = { true }, newClientId = { "w_00000001" })
    model.listen()
    clock += 400
    model.readyToHear()
    clock += 3_000
    model.heard("Move my call")
    clock += 20_000
    model.took(done)
    assertEquals(WatchModel.Timing(listenMs = 400, receiptMs = 900, resultMs = 20_000), model.timing("w_00000001"))
  }

  @Test fun haltShowsHaltingUntilThePhoneAnswers() = runTest {
    lateinit var model: WatchModel
    var during: WatchModel.Outcome? = null
    val phone = Phone {
      if (it is WatchRequest.Halt) { during = model.outcome.value; Asked.Answered(WatchReply.Done("Halted")) } else reads(it)
    }
    model = WatchModel(phone, now = { t }, unlocked = { true })
    model.refresh()
    model.halt("taf_1")
    assertEquals(WatchModel.Outcome.Halting("Refunds"), during)
    assertEquals(WatchModel.Outcome.Done("Halted"), model.outcome.value)
  }

  // MARK: Your brief (behind a switch)

  private val payDecision = DecisionLine("d-pay", "Pay bill 0412?", "Bill pay", "by Fri 12:00", refKind = "hold", refId = "ce_1", holdId = "hold_1")
  private val signIn = DecisionLine("d-bank", "Sign in to the bank again?", urgent = true, cantWait = "Bill pay can't run until then.", refKind = "connection", refId = "bank")
  private val brief = WatchReply.Brief(
    title = "Your brief",
    whenLine = "Morning brief · 08:00 · next at 18:00",
    decisions = listOf(signIn, payDecision),
    urgent = "Urgent",
    done = "Done since last evening",
    doneCases = listOf("Atelier Dubois", "Brio"),
    rest = "14 more wait and need nothing now",
  )

  private fun briefReads(r: WatchRequest): Asked = when (r) {
    is WatchRequest.NeedsYou -> Asked.Answered(needsYou)
    is WatchRequest.Brief -> Asked.Answered(brief)
    else -> Asked.Answered(tafs)
  }

  /** The switch is off, and a model told nothing about it is off too: the watch asks and shows exactly what it did before. */
  @Test fun withTheSwitchOffNothingChanges() = runTest {
    assertFalse("Your brief on the watch waits for the owner's yes", BRIEF_ON_WATCH)
    for (model in listOf<(PhoneLink) -> WatchModel>({ WatchModel(it, now = { t }) }, { WatchModel(it, now = { t }, briefOn = false) })) {
      val phone = Phone(::briefReads)
      val watch = model(phone)
      watch.refresh()
      assertEquals(listOf(WatchRequest.NeedsYou(t), WatchRequest.Tafs(t)), phone.asked)
      assertEquals(WatchModel.Shown.Read(WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call)), WatchModel.TafsRead(tafs.tafs, false)), watch.shown.value)
    }
  }

  @Test fun withTheSwitchOnTheBriefIsReadAndShownInNeedsYousPlace() = runTest {
    val phone = Phone(::briefReads)
    val model = WatchModel(phone, now = { t }, briefOn = true)
    model.refresh()
    // Reads only: Needs you (for the tile and Refuse), the brief, then Your tafs.
    assertEquals(listOf(WatchRequest.NeedsYou(t), WatchRequest.Brief(t), WatchRequest.Tafs(t)), phone.asked)
    val shown = model.shown.value as WatchModel.Shown.Read
    assertEquals(brief, shown.brief)
    assertEquals(WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call)), shown.needsYou)
    // In memory only: put away, it's forgotten.
    model.forget()
    assertEquals(WatchModel.Shown.Asking, model.shown.value)
  }

  /** No brief, or a phone that can't say (an older one refuses the path), and Needs you shows as today. */
  @Test fun withNoBriefNeedsYouStays() = runTest {
    for (answer in listOf(Asked.Answered(WatchReply.NoBrief), Asked.Answered(WatchReply.Refused(NotAccepted.UNKNOWN_PATH)), Asked.Unreachable, Asked.NotUnderstood)) {
      val model = WatchModel(Phone { if (it is WatchRequest.Brief) answer else reads(it) }, now = { t }, briefOn = true)
      model.refresh()
      assertEquals(answer.toString(), WatchModel.Shown.Read(WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call)), WatchModel.TafsRead(tafs.tafs, false)), model.shown.value)
    }
  }

  /** A signed-out phone shows nothing kept, brief or not; the brief isn't asked for then. */
  @Test fun aSignedOutPhoneIsNotAskedForTheBrief() = runTest {
    val phone = Phone { Asked.Answered(WatchReply.SignedOut) }
    val model = WatchModel(phone, now = { t }, briefOn = true)
    model.refresh()
    assertEquals(WatchModel.Shown.SignedOut, model.shown.value)
    assertEquals(listOf(WatchRequest.NeedsYou(t)), phone.asked)
  }

  /** Refuse on a decision only where Needs you's own read offers it, matched by hold id; every other decision is Open on phone. */
  @Test fun refuseIsOfferedOnlyWhereNeedsYouOffersIt() {
    val read = WatchModel.Shown.Read(WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call)), null, brief)
    assertEquals("hold_1", read.refusable(payDecision))
    assertNull("not a held call", read.refusable(signIn))
    // The phone said the same call can't be refused from the wrist: no Refuse.
    val notRefusable = read.copy(needsYou = WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call.copy(refusable = false))))
    assertNull(notRefusable.refusable(payDecision))
    // A held call Needs you doesn't list (answered meanwhile): no Refuse.
    assertNull(read.refusable(payDecision.copy(holdId = "hold_2")))
    assertNull(read.refusable(payDecision.copy(holdId = null)))
  }

  /** The brief's Refuse is Needs you's own: the same request, sent only from a watch with a lock, unlocked. */
  @Test fun theBriefsRefuseIsNeedsYousOwn() = runTest {
    val locked = Phone(::briefReads)
    val lockedModel = WatchModel(locked, now = { t }, unlocked = { false }, briefOn = true)
    lockedModel.refresh()
    lockedModel.refuse((lockedModel.shown.value as WatchModel.Shown.Read).refusable(payDecision)!!)
    assertEquals(WatchModel.Outcome.NeedsLock, lockedModel.outcome.value)
    assertTrue("nothing to refuse sent", locked.asked.none { it is WatchRequest.Refuse })

    val phone = Phone { if (it is WatchRequest.Refuse) Asked.Answered(WatchReply.Done("Refused")) else briefReads(it) }
    val model = WatchModel(phone, now = { t }, unlocked = { true }, briefOn = true)
    model.refresh()
    phone.asked.clear()
    model.refuse((model.shown.value as WatchModel.Shown.Read).refusable(payDecision)!!)
    assertEquals(WatchModel.Outcome.Done("Refused"), model.outcome.value)
    assertEquals(listOf(WatchRequest.Refuse(t, "hold_1"), WatchRequest.NeedsYou(t), WatchRequest.Brief(t), WatchRequest.Tafs(t)), phone.asked)
  }
}
