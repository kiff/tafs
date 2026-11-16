package bot.tafs.wear

import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.NotAccepted
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  // MARK: Coach

  @Test fun theWordsHeardAreShownAndSentOnlyOnSend() = runTest {
    val phone = Phone { if (it is WatchRequest.Coach) Asked.Answered(WatchReply.Coach("Done. Refunds pause until Monday.", long = false)) else reads(it) }
    val model = WatchModel(phone, now = { t }, unlocked = { true })
    model.heard("  Pause refunds until Monday ")
    assertEquals(WatchModel.Talk.Heard("Pause refunds until Monday"), model.talk.value)
    assertTrue("nothing sent before Send", phone.asked.none { it is WatchRequest.Coach })
    model.sendToCoach()
    assertEquals(listOf(WatchRequest.Coach(t, "Pause refunds until Monday")), phone.asked.filterIsInstance<WatchRequest.Coach>())
    assertEquals(WatchModel.Talk.Answered("Done. Refunds pause until Monday.", long = false), model.talk.value)
    model.closeTalk()
    assertEquals(null, model.talk.value)
  }

  @Test fun cancelledOrSilentNothingIsSent() = runTest {
    val phone = Phone(::reads)
    val model = WatchModel(phone, now = { t }, unlocked = { true })
    model.heard("   ")
    assertEquals(null, model.talk.value)
    model.sendToCoach()
    model.heard("Pause refunds")
    model.closeTalk()
    model.sendToCoach()
    assertTrue(phone.asked.none { it is WatchRequest.Coach })
  }

  /** Coach acts on what it's told: as Refuse and Halt, nothing goes to it from a watch with no lock, or a locked one. */
  @Test fun aWatchWithNoLockOrLockedSendsNothingToCoach() = runTest {
    val locked = Phone { if (it is WatchRequest.Coach) Asked.Answered(WatchReply.Coach("Done.", long = false)) else reads(it) }
    val model = WatchModel(locked, now = { t }, unlocked = { false })
    model.heard("Pause refunds until Monday")
    model.sendToCoach()
    assertEquals(WatchModel.Talk.NeedsLock, model.talk.value)
    assertTrue("nothing sent", locked.asked.none { it is WatchRequest.Coach })
    // The default is closed: a model told nothing about the lock sends nothing either.
    val phone = Phone(::reads)
    val unknown = WatchModel(phone, now = { t })
    unknown.heard("Pause refunds")
    unknown.sendToCoach()
    assertEquals(WatchModel.Talk.NeedsLock, unknown.talk.value)
    assertTrue(phone.asked.none { it is WatchRequest.Coach })
  }

  @Test fun whatCoachCantSayHereIsOnThePhone() = runTest {
    val cases = mapOf<Asked, WatchModel.Talk>(
      Asked.Answered(WatchReply.Coach("The rest is on your phone.", long = true)) to WatchModel.Talk.Answered("The rest is on your phone.", long = true),
      Asked.Answered(WatchReply.Failed("Before you talk to Coach, allow AI use in tafs on your phone.")) to
        WatchModel.Talk.NotSent("Before you talk to Coach, allow AI use in tafs on your phone."),
      Asked.Unreachable to WatchModel.Talk.NotSent(null),
      Asked.NotUnderstood to WatchModel.Talk.NotSent(null),
    )
    for ((answer, talk) in cases) {
      val model = WatchModel(Phone { if (it is WatchRequest.Coach) answer else reads(it) }, now = { t }, unlocked = { true })
      model.heard("Pause refunds")
      model.sendToCoach()
      assertEquals(answer.toString(), talk, model.talk.value)
    }
  }
}
