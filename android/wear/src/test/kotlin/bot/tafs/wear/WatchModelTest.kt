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

  /** Row 44, C: a chat's times are in the account's zone the phone names, as on the phone; signed out, the watch's own again. */
  @Test fun theWatchsChatTimesAreInTheZoneThePhoneNames() = runTest {
    var signedIn = true
    val zoned = tafs.copy(zone = "Pacific/Kiritimati")
    val model = WatchModel(Phone { if (!signedIn) Asked.Answered(WatchReply.SignedOut) else if (it is WatchRequest.NeedsYou) Asked.Answered(needsYou) else Asked.Answered(zoned) }, now = { t })
    try {
      model.refresh()
      assertEquals(java.time.ZoneId.of("Pacific/Kiritimati"), WatchChatTime.zone)
      // 23:30 in Paris on 19 Nov is already the 20th at 12:30 there: the header moves with the zone.
      val late = java.time.Instant.parse("2026-11-19T22:30:00Z")
      assertEquals(java.time.LocalDate.of(2026, 11, 20), WatchChatTime.dayOf(late))
      signedIn = false
      model.refresh()
      assertEquals(java.time.ZoneId.systemDefault(), WatchChatTime.zone)
    } finally {
      WatchChatTime.use(null)
    }
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
    val model = WatchModel(Phone { if (it is WatchRequest.NeedsYou) Asked.Answered(needsYou) else Asked.Unreachable() }, now = { t })
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
    val model = WatchModel(Phone { Asked.Unreachable() }, now = { t })
    model.refresh()
    assertEquals(WatchModel.Shown.PhoneUnreachable(), model.shown.value)
  }

  // The link's reason reaches the screen, whichever it is (row 44, D).
  @Test fun noPhoneSaysWhyItGotNoAnswer() = runTest {
    for (why in LinkFailure.entries) {
      val model = WatchModel(Phone { Asked.Unreachable(why) }, now = { t })
      model.refresh()
      assertEquals(why.toString(), WatchModel.Shown.PhoneUnreachable(why), model.shown.value)
    }
  }

  // No phone found: tafs is missing only when a phone is paired and none ever offered tafs; the first seconds after start, before
  // the capability shows, read as not in reach, never as tafs missing. The Data Layer's "not connected" is out of reach too.
  @Test fun noPhoneIsMissingTafsOnlyWhenAPairedPhoneNeverOfferedIt() {
    assertEquals(LinkFailure.NoApp, noPhone(paired = true, everOffered = false))
    assertEquals(LinkFailure.NotReachable, noPhone(paired = true, everOffered = true))
    assertEquals(LinkFailure.NotReachable, noPhone(paired = false, everOffered = false))
    val notConnected = com.google.android.gms.common.api.ApiException(com.google.android.gms.common.api.Status(com.google.android.gms.wearable.WearableStatusCodes.TARGET_NODE_NOT_CONNECTED))
    assertEquals(LinkFailure.NotReachable, failureOf(notConnected))
    assertEquals(LinkFailure.Link, failureOf(com.google.android.gms.common.api.ApiException(com.google.android.gms.common.api.Status(com.google.android.gms.common.api.CommonStatusCodes.INTERNAL_ERROR))))
    assertEquals(LinkFailure.Link, failureOf(IllegalStateException()))
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
      Asked.Unreachable() to WatchModel.Outcome.PhoneUnreachable(),
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
    assertEquals(WatchModel.Voice.Listening("Move my four o'clock", null, since = t), model.voice.value)
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

  /** No receipt: the linking screen sends it again by itself (row 30), then Retry, always under the same id. */
  @Test fun noReceiptOffersRetryWithTheSameId() = runTest {
    var answer: Asked = Asked.Unreachable()
    val phone = asker { answer }
    var ids = 0
    val model = WatchModel(phone, now = { t }, unlocked = { true }, newClientId = { "w_0000000${++ids}" })
    model.listen()
    model.heard("Move my call to six")
    assertEquals(WatchModel.Voice.Linking("Move my call to six", "w_00000001", null), model.voice.value)
    model.keepTrying(wait = {})
    assertTrue(phone.asked.filterIsInstance<WatchRequest.Ask>().all { it.clientId == "w_00000001" })
    assertEquals(WatchModel.Voice.Unsure("Move my call to six", "w_00000001", null), model.voice.value)
    // Retry with still no receipt: the linking screen again, trying by itself.
    answer = Asked.Answered(WatchReply.Unsure)
    model.retry()
    assertEquals(WatchModel.Voice.Linking("Move my call to six", "w_00000001", null), model.voice.value)
    model.keepTrying(wait = {})
    answer = Asked.Answered(WatchReply.AskTaken(taken))
    model.retry()
    assertEquals(WatchModel.Voice.Sent(taken), model.voice.value)
    // Every send (the watch's own tries and each Retry), one id: tafs cloud starts it once.
    assertEquals(listOf("w_00000001"), phone.asked.filterIsInstance<WatchRequest.Ask>().map { it.clientId }.distinct())
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
    val model = WatchModel(asker { Asked.Unreachable() }, now = { t }, unlocked = { true }, seen = mutableSetOf())
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
    for (answer in listOf(Asked.Answered(WatchReply.NoBrief), Asked.Answered(WatchReply.Refused(NotAccepted.UNKNOWN_PATH)), Asked.Unreachable(), Asked.NotUnderstood)) {
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

  // MARK: v2's microphone (docs/watch-wear.md, Version 2): nothing leaves the watch before Send.

  private fun v2(phone: PhoneLink, unlocked: () -> Boolean = { true }, ids: () -> String = { "w_00000001" }) =
    WatchModel(phone, now = { t }, unlocked = unlocked, newClientId = ids, reviewFirst = true)

  @Test fun v2sMicrophoneOpensReadyThenListensOnStart() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = v2(phone)
    model.readyToRecord()
    assertEquals(WatchModel.Voice.Ready(null), model.voice.value)
    assertTrue(model.listen())
    assertEquals(WatchModel.Voice.Listening("", null, since = t), model.voice.value)
    assertTrue(phone.asked.isEmpty())
  }

  /**
   * One tap to talk (row 44, the owner's Galaxy Watch review): Ask Coach, Reply, the tile and the complication open the screen
   * already listening, with no Start to tap. A locked watch, or one without a screen lock, still gets its line and never listens.
   */
  @Test fun oneTapOpensAlreadyListening() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = v2(phone)
    assertTrue(model.talk())
    assertEquals(WatchModel.Voice.Listening("", null, since = t), model.voice.value)
    assertTrue(model.talk(replyTo = "ask_1"))
    assertEquals(WatchModel.Voice.Listening("", "ask_1", since = t), model.voice.value)
    assertTrue("nothing is sent by opening", phone.asked.isEmpty())
    val locked = v2(phone, unlocked = { false })
    assertFalse(locked.talk())
    assertEquals(WatchModel.Voice.NeedsLock, locked.voice.value)
  }

  /**
   * The first time, the system asks for the microphone: the start's screen shows Ready behind its dialog, and listening, with its
   * 30 s, starts only once it's allowed (WatchActivity.talk: readyToRecord, then listen on the grant).
   */
  @Test fun aFirstTimeStartIsReadyUntilTheMicrophoneIsAllowed() = runTest {
    var clock = t
    val model = WatchModel(asker { Asked.Answered(WatchReply.AskTaken(taken)) }, now = { clock }, unlocked = { true }, newClientId = { "w_00000001" }, reviewFirst = true)
    model.readyToRecord(tafId = "taf_1")
    assertEquals(WatchModel.Voice.Ready(null), model.voice.value)
    clock += 4_000 // the person reads the dialog and allows it
    assertTrue(model.listen())
    assertEquals("the 30 s count from the grant, not the tap", WatchModel.Voice.Listening("", null, since = t + 4_000), model.voice.value)
  }

  /**
   * The owner's call, replacing W3's card that opened by itself: the answer to an ask sent from this watch lands in the
   * conversation under the words it answers, as in a chat, and no card opens over what the person is doing.
   */
  @Test fun v2sAnswerLandsInTheConversationAndNoCardOpensByItself() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = v2(phone)
    model.listen()
    model.heard("Move my call to six")
    model.sendReviewed()
    assertNull("back to the conversation", model.voice.value)
    model.took(working)
    model.took(done)
    assertNull(model.result.value)
    assertEquals(listOf(WatchModel.Said("Move my call to six", t) to done), model.thread())
  }

  /**
   * W4: what was said from this watch and its answer make one conversation, oldest first. Put away (the screen off) it stays;
   * signed out it goes (WatchKeptTest: kept across opens).
   */
  @Test fun v2sConversationIsWhatThisWatchSaidAndItsAnswers() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = v2(phone)
    model.listen()
    model.heard("Move my call to six")
    model.sendReviewed()
    assertEquals(listOf(WatchModel.Said("Move my call to six", t) to taken), model.thread())
    model.took(done)
    assertEquals(listOf(WatchModel.Said("Move my call to six", t) to done), model.thread())
    model.putAway()
    assertEquals("the screen off and on: still there", listOf(WatchModel.Said("Move my call to six", t) to done), model.thread())
    model.forget()
    assertTrue(model.thread().isEmpty())
  }

  @Test fun v2sWordsHeardGoToTheReviewAndOnlySendOnSend() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = v2(phone)
    model.listen()
    model.heard("  Move my four o'clock call to six ")
    assertEquals(WatchModel.Voice.Review("Move my four o'clock call to six", null), model.voice.value)
    assertTrue("nothing sent on stop", phone.asked.isEmpty())
    model.sendReviewed()
    assertEquals(listOf(WatchRequest.Ask(t, "w_00000001", "Move my four o'clock call to six")), phone.asked)
    // Back to the conversation, where the words are the newest turn.
    assertNull(model.voice.value)
    assertEquals(taken, model.thread().last().second)
  }

  @Test fun v2sDeleteSendsNothing() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = v2(phone)
    model.listen()
    model.heard("Move my call")
    model.deleteReviewed()
    assertEquals(WatchModel.Voice.Deleted, model.voice.value)
    model.sendReviewed()
    model.closeVoice()
    assertNull(model.voice.value)
    assertTrue(phone.asked.isEmpty())
  }

  @Test fun v2sReRecordDropsTheWordsAndKeepsTheChat() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = v2(phone)
    model.readyToRecord(replyTo = "ask_1")
    model.listen("ask_1")
    model.heard("Move my call")
    model.rerecord()
    assertEquals(WatchModel.Voice.Ready("ask_1"), model.voice.value)
    model.listen("ask_1")
    model.heard("Move it to six")
    model.sendReviewed()
    assertEquals(listOf(WatchRequest.Ask(t, "w_00000001", "Move it to six", "ask_1")), phone.asked)
  }

  @Test fun v2sTypedWordsAndQuickRepliesGoToTheSameReview() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = v2(phone)
    model.review("   ")
    assertNull("blank is nothing", model.voice.value)
    model.review(" What needs me today? ")
    assertEquals(WatchModel.Voice.Review("What needs me today?", null), model.voice.value)
    assertTrue(phone.asked.isEmpty())
    model.review("x".repeat(bot.tafs.watchlink.WatchLink.MAX_ASK_TEXT + 50))
    assertEquals(bot.tafs.watchlink.WatchLink.MAX_ASK_TEXT, (model.voice.value as WatchModel.Voice.Review).text.length)
  }

  /** As v1: nothing is asked from a watch with no lock, or a locked one, and it's checked again at Send. */
  @Test fun v2AsksNothingFromALockedWatch() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    var unlocked = false
    val model = v2(phone, unlocked = { unlocked })
    model.readyToRecord()
    assertEquals(WatchModel.Voice.NeedsLock, model.voice.value)
    model.review("Thanks")
    assertEquals(WatchModel.Voice.NeedsLock, model.voice.value)
    unlocked = true
    model.review("Thanks")
    unlocked = false
    model.sendReviewed()
    assertEquals(WatchModel.Voice.NeedsLock, model.voice.value)
    assertTrue(phone.asked.isEmpty())
  }

  @Test fun v2sReviewGoesOnlyToTheWordsBeingChecked() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = v2(phone)
    // No review open: Send, Re-record and Delete do nothing.
    model.sendReviewed()
    model.rerecord()
    model.deleteReviewed()
    assertNull(model.voice.value)
    assertTrue(phone.asked.isEmpty())
  }

  // MARK: Keep for later (v2): one message, text only, sent under its own id once the phone is back.

  private class Drafts : DraftStore {
    var kept: WatchModel.Draft? = null
    override fun read() = kept
    override fun write(draft: WatchModel.Draft) { kept = draft }
    override fun clear() { kept = null }
  }

  private suspend fun unsureThenKept(phone: PhoneLink, drafts: Drafts, unlocked: () -> Boolean = { true }) {
    val model = WatchModel(phone, now = { t }, unlocked = unlocked, newClientId = { "w_00000001" }, reviewFirst = true, drafts = drafts)
    model.listen()
    model.heard("Move my call to six")
    model.sendReviewed()
    // The linking screen, sending it again by itself: Keep for later is there too.
    assertEquals(WatchModel.Voice.Linking("Move my call to six", "w_00000001", null), model.voice.value)
    model.keepForLater()
    assertNull(model.voice.value)
    assertEquals(WatchModel.Draft("Move my call to six", "w_00000001", null), model.kept.value)
  }

  /** A watch whose screen lock is turned off after a message was kept: the next open or put-away deletes it, unsent (row 8). */
  @Test fun aKeptMessageIsDeletedUnsentWhenTheScreenLockIsTurnedOff() = runTest {
    val drafts = Drafts().apply { kept = WatchModel.Draft("Pause Rae", "w_00000001", null) }
    var secure = true
    val phone = Phone { reads(it) }
    val model = WatchModel(phone, now = { t }, unlocked = { secure }, secure = { secure }, reviewFirst = true, drafts = drafts)
    model.checkLock()
    assertEquals("a watch with a lock keeps it", "Pause Rae", model.kept.value?.text)
    secure = false
    model.clearInboxIfLocked() // put away
    assertNull(model.kept.value)
    assertNull(drafts.kept)
    assertTrue("nothing sent", phone.asked.none { it is WatchRequest.Ask })
    // A start on a watch with no lock: what was kept goes before anything shows.
    val again = Drafts().apply { kept = WatchModel.Draft("Pause Rae", "w_00000001", null) }
    val started = WatchModel(phone, now = { t }, unlocked = { false }, secure = { false }, reviewFirst = true, drafts = again)
    assertNull(started.kept.value)
    assertNull(again.kept)
    // A watch that's only locked keeps it, since it was kept to be sent.
    val locked = Drafts().apply { kept = WatchModel.Draft("Pause Rae", "w_00000001", null) }
    val onWrist = WatchModel(phone, now = { t }, unlocked = { false }, secure = { true }, reviewFirst = true, drafts = locked)
    onWrist.refresh()
    assertEquals("Pause Rae", onWrist.kept.value?.text)
  }

  @Test fun keptForLaterIsTextOnlyOnTheWatchAndGoesUnderItsOwnIdOnceThePhoneIsBack() = runTest {
    var reachable = false
    val phone = Phone {
      when {
        !reachable -> Asked.Unreachable()
        it is WatchRequest.Ask -> Asked.Answered(WatchReply.AskTaken(taken))
        else -> reads(it)
      }
    }
    val drafts = Drafts()
    unsureThenKept(phone, drafts)
    assertEquals("on disk: the words, the id and the chat, nothing else", WatchModel.Draft("Move my call to six", "w_00000001", null), drafts.kept)
    // A new model, as after the app was put away: the draft is still there.
    val later = WatchModel(phone, now = { t }, unlocked = { true }, reviewFirst = true, drafts = drafts)
    assertEquals(drafts.kept, later.kept.value)
    later.refresh()
    assertEquals("not while the phone is out of reach", drafts.kept, later.kept.value)
    reachable = true
    phone.asked.clear()
    later.refresh()
    assertEquals(listOf(WatchRequest.Ask(t, "w_00000001", "Move my call to six")), phone.asked.filterIsInstance<WatchRequest.Ask>())
    assertNull(later.kept.value)
    assertNull(drafts.kept)
    assertEquals(taken, later.asks.value[taken.id])
  }

  @Test fun aKeptMessageStaysWhenItsSendIsntTaken() = runTest {
    var answer: Asked = Asked.Unreachable()
    val phone = Phone { if (it is WatchRequest.Ask) answer else reads(it) }
    val drafts = Drafts()
    unsureThenKept(phone, drafts)
    val model = WatchModel(phone, now = { t }, unlocked = { true }, reviewFirst = true, drafts = drafts)
    for (a in listOf<Asked>(Asked.Answered(WatchReply.Unsure), Asked.Answered(WatchReply.Failed("Not now")), Asked.NotUnderstood)) {
      answer = a
      model.refresh()
      assertEquals(a.toString(), "w_00000001", model.kept.value?.clientId)
    }
    // Every try under the one id.
    assertEquals(setOf("w_00000001"), phone.asked.filterIsInstance<WatchRequest.Ask>().map { it.clientId }.toSet())
  }

  @Test fun aKeptMessageGoesOnlyFromAnUnlockedWatch() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val drafts = Drafts().apply { kept = WatchModel.Draft("Thanks", "w_00000009", "ask_1") }
    val model = WatchModel(phone, now = { t }, unlocked = { false }, reviewFirst = true, drafts = drafts)
    model.refresh()
    assertTrue(phone.asked.filterIsInstance<WatchRequest.Ask>().isEmpty())
    assertEquals(drafts.kept, model.kept.value)
  }

  @Test fun aKeptMessageIsDeletedOnDeleteAndOnSignOut() = runTest {
    val drafts = Drafts().apply { kept = WatchModel.Draft("Thanks", "w_00000009", null) }
    val model = WatchModel(asker { Asked.Unreachable() }, now = { t }, unlocked = { true }, reviewFirst = true, drafts = drafts)
    model.deleteKept()
    assertNull(model.kept.value)
    assertNull(drafts.kept)
    drafts.kept = WatchModel.Draft("Thanks", "w_00000009", null)
    val signedOut = WatchModel(Phone { Asked.Answered(WatchReply.SignedOut) }, now = { t }, unlocked = { true }, reviewFirst = true, drafts = drafts)
    signedOut.refresh()
    assertNull(signedOut.kept.value)
    assertNull("signed out: nothing of the account stays on the watch", drafts.kept)
  }

  @Test fun putAwayKeepsTheKeptMessage() = runTest {
    val drafts = Drafts().apply { kept = WatchModel.Draft("Thanks", "w_00000009", null) }
    val model = WatchModel(asker { Asked.Unreachable() }, now = { t }, unlocked = { true }, reviewFirst = true, drafts = drafts)
    model.forget()
    assertEquals(drafts.kept, model.kept.value)
  }

  @Test fun keepForLaterIsOnlyForAMessageThatMayNotHaveArrived() = runTest {
    val drafts = Drafts()
    val model = WatchModel(asker { Asked.Answered(WatchReply.AskTaken(taken)) }, now = { t }, unlocked = { true }, reviewFirst = true, drafts = drafts)
    model.review("Thanks")
    model.keepForLater()
    assertNull(drafts.kept)
    assertEquals(WatchModel.Voice.Review("Thanks", null), model.voice.value)
  }

  /** Two reads that reach the phone give one ask, since the draft is gone once it's taken. */
  @Test fun aKeptMessageIsAskedOnceAcrossTwoReads() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val drafts = Drafts().apply { kept = WatchModel.Draft("Thanks", "w_00000009", null) }
    val model = WatchModel(phone, now = { t }, unlocked = { true }, reviewFirst = true, drafts = drafts)
    model.refresh()
    model.refresh()
    assertEquals(listOf(WatchRequest.Ask(t, "w_00000009", "Thanks")), phone.asked.filterIsInstance<WatchRequest.Ask>())
  }

  // MARK: The recording (v2): on the watch only, erased the moment the review is left.

  private class Recording : VoiceAudio {
    var deleted = 0
    override val durationMs = 12_000L
    override fun delete() { deleted++ }
  }

  private suspend fun reviewed(model: WatchModel, audio: VoiceAudio, replyTo: String? = null) {
    model.listen(replyTo)
    model.heard("Move my call to six", audio)
    assertEquals(WatchModel.Voice.Review("Move my call to six", replyTo, audio), model.voice.value)
  }

  @Test fun theRecordingIsErasedOnSendAndNeverSent() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    val model = v2(phone)
    val audio = Recording()
    reviewed(model, audio)
    assertEquals("kept while the words are checked", 0, audio.deleted)
    model.sendReviewed()
    assertEquals(1, audio.deleted)
    // Only the words left the watch.
    assertEquals(listOf(WatchRequest.Ask(t, "w_00000001", "Move my call to six")), phone.asked)
  }

  @Test fun theRecordingIsErasedOnDelete() = runTest {
    val model = v2(asker { Asked.Answered(WatchReply.AskTaken(taken)) })
    val audio = Recording()
    reviewed(model, audio)
    model.deleteReviewed()
    assertEquals(1, audio.deleted)
  }

  @Test fun theRecordingIsErasedOnSignOut() = runTest {
    var signedIn = true
    val phone = Phone { if (!signedIn) Asked.Answered(WatchReply.SignedOut) else reads(it) }
    val model = v2(phone)
    val audio = Recording()
    reviewed(model, audio)
    signedIn = false
    model.refresh()
    assertEquals(1, audio.deleted)
    assertNull(model.voice.value)
  }

  @Test fun theRecordingIsErasedOnReRecordCancelPutAwayAndALockAtSend() = runTest {
    val phone = asker { Asked.Answered(WatchReply.AskTaken(taken)) }
    var unlocked = true
    val model = v2(phone, unlocked = { unlocked })
    for (leave in listOf<suspend () -> Unit>({ model.rerecord() }, { model.closeVoice() }, { model.forget() }, { unlocked = false; model.sendReviewed() })) {
      unlocked = true
      model.closeVoice()
      val audio = Recording()
      reviewed(model, audio)
      leave()
      assertEquals(1, audio.deleted)
    }
    assertTrue(phone.asked.isEmpty())
  }

  @Test fun v1KeepsNoRecording() = runTest {
    val model = WatchModel(asker { Asked.Answered(WatchReply.AskTaken(taken)) }, now = { t }, unlocked = { true }, newClientId = { "w_00000001" })
    model.listen()
    val audio = Recording()
    model.heard("Move my call", audio)
    assertEquals(1, audio.deleted)
    assertEquals(WatchModel.Voice.Sent(taken), model.voice.value)
  }

  @Test fun aRecordingWithNothingToCheckIsErased() = runTest {
    val model = v2(asker { Asked.Answered(WatchReply.AskTaken(taken)) })
    val unheard = Recording()
    model.listen()
    model.heard("  ", unheard)
    assertEquals(1, unheard.deleted)
    val late = Recording()
    model.closeVoice()
    model.heard("Too late", late)
    assertEquals(1, late.deleted)
  }

  @Test fun aWavHeaderSaysItsFormatAndLength() {
    val h = VoiceFormat.wavHeader(32_000)
    val b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    assertEquals(44, h.size)
    assertEquals("RIFF", String(h, 0, 4))
    assertEquals(36 + 32_000, b.getInt(4))
    assertEquals("WAVE", String(h, 8, 4))
    assertEquals(16_000, b.getInt(24))
    assertEquals(32_000, b.getInt(28))
    assertEquals(32_000, b.getInt(40))
    assertEquals(1_000L, VoiceFormat.durationMs(32_000))
  }

  @Test fun theSweepLeavesOnlyWhatsHeld() {
    val dir = kotlin.io.path.createTempDirectory("voice").toFile()
    val held = java.io.File(dir, "a.wav").apply { writeText("x") }
    java.io.File(dir, "b.wav").writeText("x")
    VoiceFiles.sweep(dir, keep = held)
    assertEquals(listOf("a.wav"), dir.list()!!.toList())
    VoiceFiles.sweep(dir)
    assertTrue(dir.list()!!.isEmpty())
  }

  // MARK: The Inbox (v2 step iv): kept on the watch until dismissed; cleared when locked and on sign-out.

  private class Inbox : InboxStore {
    var items: List<InboxItem> = emptyList()
    var gone: Set<String> = emptySet()
    override fun read() = items
    override fun write(items: List<InboxItem>) { this.items = items }
    override fun dismissed() = gone
    override fun writeDismissed(ids: Set<String>) { gone = ids }
    override fun clear() { items = emptyList(); gone = emptySet() }
  }

  private val answered = AskLine("wa_02", "w_00000002", "done", tafName = "Support replies", line = "Replied to maria@…", words = "Done", updated = "2026-11-19T08:00:00Z")

  @Test fun heldCallsAndAnswersAreKeptUnreadInTheInbox() = runTest {
    val store = Inbox()
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { true }, inboxStore = store)
    model.refresh()
    model.took(answered)
    assertEquals(listOf(InboxItem.askId("wa_02"), InboxItem.heldId("hold_1")), model.inbox.value.map { it.id })
    assertEquals(InboxItem(InboxItem.heldId("hold_1"), InboxItem.Kind.HELD, "Refunds", "Refund order 1042", t), model.inbox.value[1])
    assertEquals(2, model.unreadCount())
    // Read again: still one line each, never two.
    model.refresh()
    model.took(answered)
    assertEquals(2, model.inbox.value.size)
    assertEquals("kept on the watch", model.inbox.value, store.items)
  }

  /**
   * An answer is kept with no amount, as "never an amount" on the watch promises (docs/watch-wear.md, "What the watch keeps"),
   * and its state in tafs cloud's words (AskLine.words) the same when there's no answer line; opened while the app holds it, it shows whole.
   */
  @Test fun anAnswerIsKeptWithNoAmountAndShowsWholeWhileOpen() = runTest {
    val store = Inbox()
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { true }, inboxStore = store)
    val refunded = answered.copy(line = "Refunded €84 for order A-1039")
    model.took(refunded)
    val kept = store.items.single { it.id == InboxItem.askId("wa_02") }
    assertEquals("Refunded for order A-1039", kept.line)
    assertEquals("Refunded €84 for order A-1039", model.askOf(kept).line)
    model.took(answered.copy(id = "wa_03", line = null, words = "Refund 50 euros to Ana"))
    assertEquals("Refund to Ana", store.items.single { it.id == InboxItem.askId("wa_03") }.line)
  }

  @Test fun openingALineReadsItAndDismissAndClearReadTakeLinesAway() = runTest {
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { true }, inboxStore = Inbox())
    model.refresh()
    model.took(answered)
    model.open(model.askOf(model.inbox.value.first()))
    assertEquals(1, model.unreadCount())
    assertTrue(model.inbox.value.first { it.id == InboxItem.askId("wa_02") }.read)
    model.clearRead()
    assertEquals("unread lines stay", listOf(InboxItem.heldId("hold_1")), model.inbox.value.map { it.id })
    model.dismiss(InboxItem.heldId("hold_1"))
    assertTrue(model.inbox.value.isEmpty())
  }

  @Test fun theInboxSurvivesPutAwayAndANewStart() = runTest {
    val store = Inbox()
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { true }, inboxStore = store)
    model.took(answered)
    model.forget()
    assertEquals(1, model.inbox.value.size)
    val again = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { true }, inboxStore = store)
    assertEquals(model.inbox.value, again.inbox.value)
    // An answer kept from before opens its card from the line alone.
    assertEquals("Replied to maria@…", again.askOf(again.inbox.value.first()).line)
  }

  @Test fun aLockedWatchOrOneWithNoLockKeepsNoInbox() = runTest {
    val store = Inbox().apply { items = listOf(InboxItem(InboxItem.askId("wa_02"), InboxItem.Kind.ASK, "Support replies", "Replied", t)) }
    var unlocked = false
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { unlocked }, inboxStore = store)
    model.refresh()
    assertTrue(store.items.isEmpty())
    assertTrue("nothing added while locked, the held call included", model.inbox.value.isEmpty())
    model.took(answered)
    assertTrue(model.inbox.value.isEmpty())
    unlocked = true
    model.refresh()
    assertEquals(listOf(InboxItem.heldId("hold_1")), model.inbox.value.map { it.id })
  }

  @Test fun signedOutTheInboxIsGone() = runTest {
    var signedIn = true
    val store = Inbox()
    val model = WatchModel(Phone { if (!signedIn) Asked.Answered(WatchReply.SignedOut) else reads(it) }, now = { t }, unlocked = { true }, inboxStore = store)
    model.refresh()
    assertEquals(1, store.items.size)
    signedIn = false
    model.refresh()
    assertTrue(store.items.isEmpty())
    assertTrue(model.inbox.value.isEmpty())
  }

  // #1404: Dismiss and Clear read stick while the phone still lists the line.

  @Test fun aDismissedHeldCallStaysGoneWhileNeedsYouStillListsIt() = runTest {
    val store = Inbox()
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { true }, inboxStore = store)
    model.refresh()
    model.dismiss(InboxItem.heldId("hold_1"))
    model.refresh()
    assertTrue(model.inbox.value.isEmpty())
    // A new start reads the same store: still gone.
    val again = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { true }, inboxStore = store)
    again.refresh()
    assertTrue(again.inbox.value.isEmpty())
  }

  @Test fun aClearedAnswerStaysGoneAfterPutAwayAndTheAsksRead() = runTest {
    val phone = Phone { if (it is WatchRequest.Asks) Asked.Answered(WatchReply.AskList(listOf(answered))) else reads(it) }
    val model = WatchModel(phone, now = { t }, unlocked = { true }, inboxStore = Inbox())
    model.readAsks()
    model.open(model.askOf(model.inbox.value.first()))
    model.clearRead()
    model.forget()
    model.readAsks()
    assertTrue(model.inbox.value.none { it.id == InboxItem.askId("wa_02") })
  }

  @Test fun aDismissedIdGoesOnceThePhoneNoLongerListsIt() = runTest {
    var calls = listOf(call)
    val store = Inbox()
    val model = WatchModel(Phone { if (it is WatchRequest.NeedsYou) Asked.Answered(WatchReply.NeedsYou(calls.size, "x", calls)) else reads(it) }, now = { t }, unlocked = { true }, inboxStore = store)
    model.refresh()
    model.dismiss(InboxItem.heldId("hold_1"))
    assertEquals(setOf(InboxItem.heldId("hold_1")), store.gone)
    calls = emptyList()
    model.refresh()
    assertTrue("pruned: nothing kept of a call that's gone", store.gone.isEmpty())
  }

  // Row 21: read and dismissed, in step across the person's watches, by id alone.

  @Test fun marksMadeHereGoToThePhoneByIdAloneInChunks() = runTest {
    val phone = Phone { r ->
      when (r) {
        is WatchRequest.Asks -> Asked.Answered(WatchReply.AskList(listOf(answered)))
        is WatchRequest.InboxMark -> Asked.Answered(WatchReply.InboxMarks())
        else -> reads(r)
      }
    }
    val model = WatchModel(phone, now = { t }, unlocked = { true }, inboxStore = Inbox())
    model.refresh()
    model.readAsks()
    model.markRead(InboxItem.heldId("hold_1"))
    model.open(model.askOf(model.inbox.value.first { it.kind == InboxItem.Kind.ASK }))
    model.sendMarks()
    assertEquals(listOf(WatchRequest.InboxMark(t, read = listOf("hold_1", "wa_02"))), phone.asked.filterIsInstance<WatchRequest.InboxMark>())
    phone.asked.clear()
    model.clearRead()
    model.sendMarks()
    assertEquals("in the Inbox's order, newest first", listOf(WatchRequest.InboxMark(t, dismissed = listOf("wa_02", "hold_1"))), phone.asked)
    // Sent once: nothing new, nothing sent.
    phone.asked.clear()
    model.sendMarks()
    assertTrue(phone.asked.isEmpty())
  }

  @Test fun moreThanAHundredGoInChunksAndAnIdTafsCloudCantTakeIsLeftHere() = runTest {
    val calls = List(150) { HeldLine("h$it", "Refunds", "Refund $it", null, "Over", refusable = true) } + HeldLine("hold:x", "Refunds", "Odd", null, "Over", refusable = false)
    val phone = Phone { r -> if (r is WatchRequest.NeedsYou) Asked.Answered(WatchReply.NeedsYou(calls.size, "x", calls)) else if (r is WatchRequest.InboxMark) Asked.Answered(WatchReply.InboxMarks()) else reads(r) }
    val model = WatchModel(phone, now = { t }, unlocked = { true }, inboxStore = Inbox())
    model.refresh()
    for (item in model.inbox.value) model.dismiss(item.id)
    model.sendMarks()
    val sent = phone.asked.filterIsInstance<WatchRequest.InboxMark>()
    assertEquals(listOf(100, 50), sent.map { it.dismissed.size })
    assertTrue(sent.flatMap { it.dismissed }.none { it == "hold:x" })
  }

  @Test fun marksFromTheOtherWatchesReadAndDismissHereAndNothingComesBack() = runTest {
    val second = HeldLine("hold_2", "Bills", "Pay bill 0412", "€120.00", "Over €100", refusable = true)
    val listed = WatchReply.NeedsYou(2, "2 need you", listOf(call, second))
    val store = Inbox()
    val phone = Phone { r ->
      when (r) {
        is WatchRequest.NeedsYou -> Asked.Answered(listed)
        is WatchRequest.Asks -> Asked.Answered(WatchReply.AskList(listOf(answered)))
        is WatchRequest.InboxMarks -> Asked.Answered(WatchReply.InboxMarks(read = listOf("wa_02"), dismissed = listOf("hold_1", "gone_long_ago")))
        else -> reads(r)
      }
    }
    val model = WatchModel(phone, now = { t }, unlocked = { true }, inboxStore = store)
    model.refresh()
    model.readAsks()
    model.syncInbox()
    assertEquals(listOf(InboxItem.askId("wa_02"), InboxItem.heldId("hold_2")), model.inbox.value.map { it.id })
    assertTrue("read on another watch", model.inbox.value.first().read)
    assertFalse(model.inbox.value[1].read)
    assertEquals("only a line this watch had is kept away", setOf(InboxItem.heldId("hold_1")), store.gone)
    // The phone still lists hold_1: it stays away.
    model.refresh()
    assertEquals(listOf(InboxItem.askId("wa_02"), InboxItem.heldId("hold_2")), model.inbox.value.map { it.id })
  }

  @Test fun aPhoneFromBeforeTheMarksOrNoAnswerLeavesTheLines() = runTest {
    for (answer in listOf<Asked>(Asked.Answered(WatchReply.Refused(NotAccepted.UNKNOWN_PATH)), Asked.Unreachable(), Asked.NotUnderstood)) {
      val phone = Phone { r -> if (r is WatchRequest.InboxMarks || r is WatchRequest.InboxMark) answer else reads(r) }
      val model = WatchModel(phone, now = { t }, unlocked = { true }, inboxStore = Inbox())
      model.refresh()
      model.markRead(InboxItem.heldId("hold_1"))
      model.syncInbox()
      assertEquals(listOf(InboxItem.heldId("hold_1")), model.inbox.value.map { it.id })
      assertTrue("this watch keeps its own", model.inbox.value.single().read)
    }
  }

  @Test fun noInboxKeptHereReadsOrSendsNoMarks() = runTest {
    var unlocked = true
    val phone = Phone(::reads)
    val model = WatchModel(phone, now = { t }, unlocked = { unlocked }, inboxStore = Inbox())
    model.refresh()
    model.dismiss(InboxItem.heldId("hold_1"))
    unlocked = false
    phone.asked.clear()
    model.syncInbox()
    model.sendMarks()
    assertTrue(phone.asked.isEmpty())
    // v1, with no Inbox: nothing either.
    val v1 = Phone(::reads)
    WatchModel(v1, now = { t }, unlocked = { true }).apply { refresh(); syncInbox() }
    assertTrue(v1.asked.none { it is WatchRequest.InboxMarks || it is WatchRequest.InboxMark })
  }

  @Test fun putAwayLockedClearsTheInboxThen() = runTest {
    var unlocked = true
    val store = Inbox()
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { unlocked }, inboxStore = store)
    model.refresh()
    model.clearInboxIfLocked()
    assertEquals(1, store.items.size)
    unlocked = false
    model.clearInboxIfLocked()
    assertTrue(store.items.isEmpty())
    assertTrue(model.inbox.value.isEmpty())
  }

  /** Steps (iv) and (vi) together: signing out on this watch forgets the Inbox too, and nothing is added to it until signed in. */
  @Test fun signingOutOnTheWatchForgetsTheInbox() = runTest {
    val store = Inbox()
    val signIn = object : WatchSignIn {
      var out = false
      override fun signedOut() = out
      override fun signOut() { out = true }
      override fun signIn() { out = false }
    }
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { true }, inboxStore = store, signIn = signIn)
    model.refresh()
    model.dismiss(InboxItem.heldId("hold_1"))
    model.took(answered)
    assertEquals(1, store.items.size)
    model.signOutHere()
    assertTrue(store.items.isEmpty())
    assertTrue(store.gone.isEmpty())
    assertTrue(model.inbox.value.isEmpty())
    model.took(answered)
    model.refresh()
    assertTrue("nothing added while signed out here", model.inbox.value.isEmpty())
  }

  // MARK: The batch's pre-review (#1408): what holds where steps (iv)–(vi) meet.

  private fun signIn() = object : WatchSignIn {
    var out = false
    var waiting: Pair<String, Long>? = null
    override fun signedOut() = out
    override fun signOut() { out = true; waiting = null }
    override fun signIn() { out = false; waiting = null }
    override fun asked() = waiting
    override fun ask(id: String, at: Long) { waiting = id to at }
    override fun forgetAsk() { waiting = null }
  }

  /** No screen lock: the Inbox isn't kept, and the screen shows what the phone answered now instead of "Nothing new". */
  @Test fun aWatchThatCantKeepTheInboxSaysSoToTheScreen() = runTest {
    var unlocked = false
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { unlocked }, inboxStore = Inbox())
    model.refresh()
    assertFalse(model.inboxKept.value)
    assertEquals("the call is still read, for the screen to draw", listOf(call), (model.shown.value as WatchModel.Shown.Read).needsYou.calls)
    unlocked = true
    model.refresh()
    assertTrue(model.inboxKept.value)
  }

  @Test fun v1KeepsNoInboxEvenInMemory() = runTest {
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { true })
    model.refresh()
    model.took(answered)
    assertTrue(model.inbox.value.isEmpty())
    assertFalse(model.inboxKept.value)
  }

  @Test fun aLockKeepsWhatWasDismissedGone() = runTest {
    var unlocked = true
    val store = Inbox()
    val model = WatchModel(Phone { reads(it) }, now = { t }, unlocked = { unlocked }, inboxStore = store)
    model.refresh()
    model.dismiss(InboxItem.heldId("hold_1"))
    unlocked = false
    model.clearInboxIfLocked()
    model.refresh()
    assertTrue("the lines go", store.items.isEmpty())
    assertEquals("the bare ids stay", setOf(InboxItem.heldId("hold_1")), store.gone)
    unlocked = true
    model.refresh()
    assertTrue("not back, unread", model.inbox.value.isEmpty())
  }

  @Test fun anOutcomeThatLandsAfterSigningOutHereIsDropped() = runTest {
    val signIn = signIn()
    lateinit var model: WatchModel
    val phone = Phone {
      if (it is WatchRequest.Refuse) {
        model.signOutHere()
        Asked.Answered(WatchReply.Done("Refused"))
      } else reads(it)
    }
    model = WatchModel(phone, now = { t }, unlocked = { true }, signIn = signIn)
    model.refresh()
    model.refuse("hold_1")
    assertNull(model.outcome.value)
    // Signed in again (the phone's Allow for this watch's ask, row 18): the old outcome never shows.
    signIn.ask("w_00000003", t)
    model.signInAnswered(bot.tafs.watchlink.KeptSignIn(t, "w_00000003", allowed = true))
    assertNull("never shown after Sign in", model.outcome.value)
  }

  @Test fun severalHaltsLandingAfterSigningOutHereAreDropped() = runTest {
    val signIn = signIn()
    val two = WatchReply.Tafs(listOf(TafLine("taf_1", "Refunds", "Ready", halted = false), TafLine("taf_2", "Invoices", "Ready", halted = false),
      TafLine("taf_3", "Parcels", "Ready", halted = false)), allHalted = false)
    lateinit var model: WatchModel
    val phone = Phone {
      when (it) {
        is WatchRequest.Halt -> { model.signOutHere(); Asked.Answered(WatchReply.Done("Halted")) }
        is WatchRequest.NeedsYou -> Asked.Answered(needsYou)
        else -> Asked.Answered(two)
      }
    }
    model = WatchModel(phone, now = { t }, unlocked = { true }, signIn = signIn, reviewFirst = true)
    model.refresh()
    model.pickToHalt()
    model.togglePick("taf_1")
    model.togglePick("taf_2")
    model.askPicked()
    model.haltPicked()
    assertNull(model.outcome.value)
  }

  @Test fun signedOutHereNoTafPageIsRead() = runTest {
    val signIn = signIn()
    val phone = Phone { reads(it) }
    val model = WatchModel(phone, now = { t }, unlocked = { true }, signIn = signIn)
    model.signOutHere()
    phone.asked.clear()
    model.openTaf("taf_1")
    assertTrue(phone.asked.isEmpty())
    assertNull(model.taf.value)
  }

  @Test fun thePhoneSignedOutClosesWhatAnOpenScreenRead() = runTest {
    var signedIn = true
    val phone = Phone {
      when {
        !signedIn -> Asked.Answered(WatchReply.SignedOut)
        it is WatchRequest.Taf -> Asked.Answered(WatchReply.TafPage("taf_1", "Refunds", "Ready", false, "Refunds orders"))
        else -> reads(it)
      }
    }
    val model = WatchModel(phone, now = { t }, unlocked = { true }, reviewFirst = true)
    model.refresh()
    model.openTaf("taf_1")
    assertTrue(model.taf.value != null)
    signedIn = false
    model.refresh()
    assertNull(model.taf.value)
    assertNull(model.haltPick.value)
    assertNull(model.calendar.value)
    assertNull(model.account.value)
  }
}
