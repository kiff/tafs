package bot.tafs.app

import bot.tafs.app.app.SampleStore
import bot.tafs.app.model.Answer
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.TafState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppModelTest {
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private fun model(auth: FakeAuthenticator = FakeAuthenticator(), store: SampleStore? = null, clock: () -> Instant = { t0 }) =
    testModel(auth, store, clock)

  @Test fun theSampleStartsFiveTafsWithAMorningOfWork() {
    val s = model().state.value
    assertEquals(5, s.tafs.size)
    assertEquals(setOf("Chargebacks", "Deploys", "Access", "Bill pay", "Refunds"), s.tafs.map { it.name }.toSet())
    assertTrue(s.record.any { it.kind == RecordEntry.Kind.done })
    assertTrue(s.held.isNotEmpty())
  }

  @Test fun aTafWithAHeldCallIsWaitingOnTheOwner() {
    val s = model().state.value
    for (call in s.held) assertEquals(TafState.waiting, s.taf(call.tafId)!!.state)
  }

  @Test fun approvingOnceCountsAgainstTheCardAndKeepsTheSignature() = runTest {
    val m = model()
    val call = m.state.value.held.first()
    assertTrue(m.answer(call, Answer.ApproveOnce))
    val s = m.state.value
    assertTrue(s.held.none { it.id == call.id })
    val line = s.record.first { it.kind == RecordEntry.Kind.approved }
    assertEquals(true, line.ownerApproved)
    assertNotNull(line.signature)
    assertTrue(line.title.startsWith("Approved once by you"))
  }

  @Test fun refusingTakesTheCallOffWithoutDrawingOnTheCard() = runTest {
    val m = model()
    val call = m.state.value.held.first()
    val usedBefore = m.state.value.taf(call.tafId)!!.used
    assertTrue(m.answer(call, Answer.Refuse))
    val s = m.state.value
    assertTrue(s.held.none { it.id == call.id })
    assertEquals(usedBefore, s.taf(call.tafId)!!.used)
    assertTrue(s.record.any { it.kind == RecordEntry.Kind.refused && it.title.startsWith("Refused by you") })
  }

  @Test fun aCallNobodyAnsweredInTimeIsRefusedAsExpired() = runTest {
    var now = t0
    val m = model(clock = { now })
    val call = m.state.value.held.first()
    now = call.expiresAt.plusSeconds(1)
    m.answer(call, Answer.ApproveOnce)
    val s = m.state.value
    assertTrue(s.held.none { it.id == call.id })
    assertTrue(s.record.any { it.title.startsWith("Expired:") })
    assertTrue(s.record.none { it.kind == RecordEntry.Kind.approved })
  }

  @Test fun anExpiredCallIsNeverAskedAbout() = runTest {
    var now = t0
    val auth = FakeAuthenticator()
    val m = model(auth, clock = { now })
    val call = m.state.value.held.first()
    now = call.expiresAt.plusSeconds(1)
    m.answer(call, Answer.ApproveOnce)
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun theNextRoundPlaysTheNextScriptedActions() {
    val m = model()
    val before = m.state.value.record.size
    m.playNextDemoActions()
    assertTrue(m.state.value.record.size > before)
  }

  @Test fun theSampleIsKeptBetweenLaunches() = runTest {
    val file = File.createTempFile("sample", ".json").also { it.delete() }
    try {
      val first = model(store = SampleStore(file))
      val call = first.state.value.held.first()
      first.answer(call, Answer.Refuse)
      assertNotNull(SampleStore(file).load())
      val second = model(store = SampleStore(file))
      assertEquals(first.state.value.tafs, second.state.value.tafs)
      assertEquals(first.state.value.held, second.state.value.held)
      assertEquals(first.state.value.record, second.state.value.record)
    } finally {
      file.delete()
    }
  }

  // MARK: Confirming

  @Test fun everyAnswerAsksTheOwnerFirstNamingWhatItIsFor() = runTest {
    val auth = FakeAuthenticator()
    val m = model(auth)
    val call = m.state.value.held.first()
    m.answer(call, Answer.Refuse)
    assertEquals(listOf("Answer ${call.proposed.summary}"), auth.asked)
  }

  @Test fun anAnswerTheOwnerDidNotConfirmChangesNothingAndSaysWhy() = runTest {
    val auth = FakeAuthenticator(confirms = false)
    val m = model(auth)
    val before = m.state.value
    val call = before.held.first()
    assertFalse(m.answer(call, Answer.ApproveOnce))
    val s = m.state.value
    assertEquals(before.held, s.held)
    assertEquals(before.record, s.record)
    assertEquals(before.tafs, s.tafs)
    assertEquals("Not sent: it wasn't confirmed.", s.lastError)
  }

  @Test fun theCallStillWaitsAndCanBeAnsweredOnceConfirmed() = runTest {
    val auth = FakeAuthenticator(confirms = false)
    val m = model(auth)
    val call = m.state.value.held.first()
    m.answer(call, Answer.ApproveOnce)
    auth.confirms = true
    assertTrue(m.answer(call, Answer.ApproveOnce))
    assertTrue(m.state.value.held.none { it.id == call.id })
  }

  @Test fun aPhoneWithNoScreenLockCannotAnswer() = runTest {
    val m = model(FakeAuthenticator(available = false))
    val call = m.state.value.held.first()
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertTrue(m.state.value.held.any { it.id == call.id })
    assertNotNull(m.state.value.lastError)
  }

  @Test fun theErrorGoesOnceItIsRead() = runTest {
    val m = model(FakeAuthenticator(confirms = false))
    m.answer(m.state.value.held.first(), Answer.Refuse)
    assertNotNull(m.state.value.lastError)
    m.dismissError()
    assertNull(m.state.value.lastError)
  }

  @Test fun issuingACardAsksTheOwnerAndStartsTheTaf() = runTest {
    val auth = FakeAuthenticator()
    val m = model(auth)
    val before = m.state.value.tafs.size
    val taf = m.start(bot.tafs.app.model.TafTemplates.draft("refund"))
    assertNotNull(taf)
    assertEquals(before + 1, m.state.value.tafs.size)
    assertEquals(listOf("Issue Refunds's Card"), auth.asked)
  }

  @Test fun aCardTheOwnerDidNotConfirmIsNotIssued() = runTest {
    val m = model(FakeAuthenticator(confirms = false))
    val before = m.state.value
    assertNull(m.start(bot.tafs.app.model.TafTemplates.draft("refund")))
    assertEquals(before.tafs, m.state.value.tafs)
    assertEquals(before.record, m.state.value.record)
    assertEquals("The Card was not issued: it wasn't confirmed.", m.state.value.lastError)
  }
}
