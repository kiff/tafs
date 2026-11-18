package bot.tafs.app

import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.Halt
import bot.tafs.app.push.AlertPayload
import bot.tafs.app.app.AppModel
import bot.tafs.app.watch.RefreshFloor
import bot.tafs.app.watch.WatchRelay
import bot.tafs.watchlink.NotAccepted
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's answers to the Wear OS watch (docs/watch-wear.md): only the watch's requests pass, the reads are the phone's own
 * words, and nothing from the wrist is signed or sent in this step.
 */
class WatchRelayTest {
  private val now = 1_763_244_000_000L

  private fun request(r: WatchRequest) = WatchLink.encode(r)

  @Test fun whatTheGateRefusesNeverReachesTheModel() = runTest {
    var asked = 0
    val relay = WatchRelay(model = { asked++; testModel() }, now = { now })
    assertEquals(WatchReply.Refused(NotAccepted.UNKNOWN_PATH), relay.answer(WatchLink.PREFIX + "approve", """{"made_at":$now,"hold_id":"h"}""".encodeToByteArray()))
    assertEquals(WatchReply.Refused(NotAccepted.MALFORMED), relay.answer(WatchLink.REFUSE, """{"made_at":$now}""".encodeToByteArray()))
    assertEquals(WatchReply.Refused(NotAccepted.TOO_OLD), relay.answer(WatchLink.HALT, """{"made_at":${now - 31_000},"all":true}""".encodeToByteArray()))
    assertEquals(0, asked)
  }

  @Test fun theSampleCantRefuseOrHaltFromTheWrist() = runTest {
    for (r in listOf(WatchRequest.Refuse(now, "hold_1"), WatchRequest.Halt(now, null))) {
      val (path, data) = request(r)
      assertEquals(WatchReply.SignedOut, WatchRelay(model = { testModel() }, now = { now }).answer(path, data))
    }
  }

  @Test fun withNoSessionTheWatchIsToldSignedOut() = runTest {
    val (path, data) = request(WatchRequest.NeedsYou(now))
    assertEquals(WatchReply.SignedOut, WatchRelay(model = { null }, now = { now }).answer(path, data))
    // The sample isn't an account: the watch shows nothing of it.
    assertEquals(WatchReply.SignedOut, WatchRelay(model = { testModel() }, now = { now }).answer(path, data))
    val (tPath, tData) = request(WatchRequest.Tafs(now))
    assertEquals(WatchReply.SignedOut, WatchRelay(model = { testModel() }, now = { now }).answer(tPath, tData))
  }

  @Test fun needsYouIsThePhonesOwn() {
    val state = testModel().state.value.copy(mode = Mode.kiff)
    assertTrue("the sample has held calls", state.held.isNotEmpty())
    val reply = WatchRelay.needsYou(state)
    assertEquals(state.needsYouCount, reply.count)
    assertEquals(state.needsYouWords, reply.words)
    assertEquals(state.held.map { it.id }, reply.calls.map { it.holdId })
    for ((call, line) in state.held.zip(reply.calls)) {
      assertEquals(state.taf(call.tafId)!!.name, line.taf)
      assertEquals(call.proposed.summary, line.wants)
      assertEquals(call.proposed.amount?.formatted, line.amount)
      assertEquals(call.reason, line.reason)
      assertEquals(call.kiffId != null && state.canAnswer(call), line.refusable)
    }
  }

  /**
   * A call tafs cloud holds alone (no KIFF id) is answered through the phone's own prompt, which nobody sees with the phone in a
   * pocket: the wrist never offers Refuse on it, though the phone itself may answer it.
   */
  @Test fun aCallTafsCloudHoldsAloneIsNotRefusableFromTheWrist() {
    val state = testModel().state.value.copy(mode = Mode.kiff)
    val call = state.held.first().copy(kiffId = null)
    val withIt = state.copy(held = listOf(call))
    assertTrue("the phone may answer it", withIt.canAnswer(call))
    assertFalse(WatchRelay.refusableFromWrist(withIt, call))
    assertFalse(WatchRelay.needsYou(withIt).calls.single().refusable)
    // One KIFF holds, with a version this phone signs over, is.
    val kiffs = call.copy(kiffId = "ce_1", accountingVersion = 1)
    val withKiffs = withIt.copy(held = listOf(kiffs), sessionHasKey = true)
    assertTrue(WatchRelay.refusableFromWrist(withKiffs, kiffs))
  }

  @Test fun yourTafsSaysWhichAreHalted() {
    val base = testModel().state.value.copy(mode = Mode.kiff)
    val first = base.tafs.first()
    val one = WatchRelay.tafs(base.copy(halts = mapOf(first.id to Halt(state = Halt.HALTED))))
    assertTrue(one.tafs.first { it.tafId == first.id }.halted)
    assertEquals("Halted", one.tafs.first { it.tafId == first.id }.state)
    assertEquals(base.tafs.drop(1).map { it.now }, one.tafs.drop(1).map { it.state })
    assertFalse(one.allHalted)
    val all = WatchRelay.tafs(base.copy(halts = mapOf("" to Halt(state = Halt.HALTED))))
    assertTrue(all.allHalted)
    assertTrue(all.tafs.all { it.halted })
  }

  @Test fun theFloorLetsOneReadThroughEveryTenSeconds() {
    val floor = RefreshFloor()
    assertTrue(floor.due(now))
    assertFalse(floor.due(now + 1))
    assertFalse(floor.due(now + 9_999))
    assertTrue(floor.due(now + 10_000))
    assertFalse(floor.due(now + 10_500))
    // A clock set back reads again rather than waiting.
    assertTrue(floor.due(now - 60_000))
  }

  /** A watch asking in a loop reads tafs cloud and KIFF once per floor; every ask is still answered, from that read. */
  @Test fun aWatchAskingInALoopReadsTafsCloudOncePerFloor() = runTest {
    val transport = FakeTransport()
    val secrets = bot.tafs.app.kiff.InMemorySecretStore()
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    val model = testModel(transport = transport, secrets = secrets, start = false, scope = backgroundScope)
    model.restore(onScreen = false)
    val before = transport.requestsTo("GET /v1/home").size
    var t = now
    val relay = WatchRelay(model = { model }, now = { t }, floor = RefreshFloor())
    repeat(5) {
      val (path, data) = request(WatchRequest.NeedsYou(t))
      assertTrue(relay.answer(path, data) is WatchReply.NeedsYou)
      t += 1_000
    }
    assertEquals("five asks in 5 s: one read", 1, transport.requestsTo("GET /v1/home").size - before)
    t = now + 10_000
    val (path, data) = request(WatchRequest.NeedsYou(t))
    assertTrue(relay.answer(path, data) is WatchReply.NeedsYou)
    assertEquals("past the floor: read again", 2, transport.requestsTo("GET /v1/home").size - before)
  }

  @Test fun theWatchsOpenOnPhoneOpensNeedsYouAndNothingElse() {
    assertEquals(AlertPayload.NEEDS_YOU, AlertPayload.openedFromWatch("tafs://open/needs-you"))
    assertNull(AlertPayload.openedFromWatch("tafs://open/approve"))
    assertNull(AlertPayload.openedFromWatch("tafs://planque/signed-in"))
    assertNull(AlertPayload.openedFromWatch(null))
  }

  /** A taf's page is the phone's Card tab: the taf's state and job, each Card's face and rules, and what of it waits. */
  @Test fun aTafsPageIsThePhonesOwn() {
    val state = testModel().state.value.copy(mode = Mode.kiff)
    val taf = state.tafs.first { state.held.any { h -> h.tafId == it.id } }
    val page = WatchRelay.tafPage(state, taf.id)!!
    assertEquals(taf.name, page.name)
    assertEquals(taf.job, page.job)
    assertEquals(taf.shownCards.map { it.title }, page.cards.map { it.title })
    assertEquals(taf.shownCards.first().hero?.figure, page.cards.first().figure)
    assertEquals(taf.card.rules.size, page.cards.first().rules.size)
    assertEquals(state.held.count { it.tafId == taf.id }, page.waiting)
    assertNull("a taf the phone doesn't list", WatchRelay.tafPage(state, "no_such_taf"))
  }

  /** A calendar day: tafs cloud's line, and that day's events alone, in time order, with each taf's name. */
  @Test fun aCalendarDayIsTafsCloudsRead() {
    val read = bot.tafs.app.kiff.KiffClient.json.decodeFromString(
      bot.tafs.app.kiff.CalendarRead.serializer(),
      """{"events":[
        {"taf":"t2","name":"Late parcels","at":"2026-11-19T15:00:00Z","day":"2026-11-19","time":"16:00","minute":960,"words":"Every day at 16:00"},
        {"taf":"t1","name":"Morning inbox","at":"2026-11-19T08:00:00Z","day":"2026-11-19","time":"09:00","minute":540,"words":"Every day at 09:00"},
        {"taf":"t1","name":"Morning inbox","at":"2026-11-20T08:00:00Z","day":"2026-11-20","time":"09:00","minute":540,"words":"Every day at 09:00"}
      ],"days":["2026-11-19","2026-11-20"],"words":"2 to come"}""",
    )
    val day = WatchRelay.calendarDay("2026-11-19", read)
    assertEquals("2 to come", day.words)
    assertEquals(listOf("Morning inbox", "Late parcels"), day.events.map { it.taf })
    assertEquals(listOf("09:00", "16:00"), day.events.map { it.time })
  }

  /** Signed out, none of v2's reads tells the watch anything. */
  @Test fun v2sReadsSaySignedOutWithNoSession() = runTest {
    for (r in listOf(WatchRequest.Taf(now, "taf_1"), WatchRequest.Calendar(now), WatchRequest.Account(now))) {
      val (path, data) = request(r)
      assertEquals(WatchReply.SignedOut, WatchRelay(model = { null }, now = { now }).answer(path, data))
      assertEquals(WatchReply.SignedOut, WatchRelay(model = { testModel() }, now = { now }).answer(path, data))
    }
  }
}
