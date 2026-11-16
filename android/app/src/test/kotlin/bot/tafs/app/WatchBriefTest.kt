@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.signing.ApprovalSigning
import bot.tafs.app.watch.RefreshFloor
import bot.tafs.app.watch.WatchRelay
import bot.tafs.watchlink.DecisionLine
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Your brief for the Wear OS watch (docs/watch-wear.md, Your brief): the phone answers it from the brief it holds (`AppState.brief`),
 * in tafs cloud's words, read with Needs you behind the same 10 s floor and never on its own; a decision about a held call names it
 * as Needs you does, so the wrist's Refuse is Needs you's own. A fake tafs cloud and KIFF stand in.
 */
class WatchBriefTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer: ApprovalSigning = softwareSigner(auth)
  private val now = 1_763_244_000_000L

  private val brief = """{"decisions":[
    {"id":"d1","question":"Pay 3 supplier bills due Friday, €1,240?","answers":[{"words":"Pay the 3","answer":"approve"},{"words":"Not now","answer":"refuse","decline":true}],
     "deadline_words":"by Fri 12:00","urgent":true,"now_why":"They're due before the next brief.","ref":{"kind":"hold","id":"ce_1","taf":"id_a-1"}},
    {"id":"d2","question":"Sign in to the bank again?","answers":[{"words":"Sign in again","answer":"sign_in"}],"ref":{"kind":"connection","id":"bank","taf":"id_a-1"}}],
    "more":{"count":2,"words":"2 more decisions"},
    "done":[{"mission":"Money","cases":[{"name":"Atelier Dubois","words":"Reminder sent","state":"waiting"},{"name":"Brio","words":"Paid","state":"closed"},
      {"name":"Fern","words":"Asked again","state":"waiting"}]}],
    "rest":{"count":14,"words":"14 more wait and need nothing now"},
    "words":{"title":"Your brief","when":"Morning brief · 08:00 · next at 18:00","urgent":"Urgent","done":"Done since last evening"}}"""

  private val held = """{"id":"ce_1","subject":"a-1","action":"create_refund","title":"Refund a payment","entity_id":"tc-ce_1","proposal_id":"prop_ce_1",
    "accounting_version":1,"breaches":[{"outcome":"per_action_exceeded","reason":"over","quantity":"sum(amount)","limit":10000,"unit":"EUR","scale":2}],
    "parameters":{"amount":15000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},"created_at":"2026-10-28T09:00:00Z","expires_at":"2099-01-01T00:00:00Z"}"""

  private fun reads(withBrief: Boolean = true) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["holds.answer"]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"id_a-1","name":"Bill pay","kiff_agent":"a-1","color":1,"state":"waiting","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$held]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    if (withBrief) transport.answer("GET /v1/brief", 200, brief)
  }

  private suspend fun kotlinx.coroutines.test.TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, start = false, scope = backgroundScope).also { it.restore() }
  }

  private suspend fun WatchRelay.ask(r: WatchRequest): WatchReply = WatchLink.encode(r).let { (path, data) -> answer(path, data) }

  @Test fun theBriefIsThePhonesOwnInTafsCloudsWords() = runTest {
    reads()
    val m = model()
    val relay = WatchRelay(model = { m }, now = { now })
    val reply = relay.ask(WatchRequest.Brief(now)) as WatchReply.Brief
    assertEquals("Your brief", reply.title)
    assertEquals("Morning brief · 08:00 · next at 18:00", reply.whenLine)
    assertEquals("2 more decisions", reply.more)
    assertEquals("Urgent", reply.urgent)
    assertEquals("Done since last evening", reply.done)
    assertEquals("the first two, by name", listOf("Atelier Dubois", "Brio"), reply.doneCases)
    assertEquals("14 more wait and need nothing now", reply.rest)
    assertEquals(m.state.value.briefNeedsYouLine, reply.needsYou)
    // In tafs cloud's order, the urgent first as it sends them.
    assertEquals(listOf("d1", "d2"), reply.decisions.map { it.id })
    val call = m.state.value.held.single()
    assertEquals(
      DecisionLine(
        "d1", "Pay 3 supplier bills due Friday, €1,240?", taf = "Bill pay", deadline = "by Fri 12:00", urgent = true,
        cantWait = "They're due before the next brief.", refKind = "hold", refId = "ce_1", holdId = call.id,
      ),
      reply.decisions[0],
    )
    // Not a held call: nothing to refuse, Open on phone.
    assertNull(reply.decisions[1].holdId)
    assertEquals("connection", reply.decisions[1].refKind)
  }

  /** The decision names its held call as Needs you's line does: the watch matches the two by hold id, and Refuse is Needs you's. */
  @Test fun aHeldCallsDecisionNamesItAsNeedsYouDoes() = runTest {
    reads()
    val m = model()
    val relay = WatchRelay(model = { m }, now = { now })
    val needsYou = relay.ask(WatchRequest.NeedsYou(now)) as WatchReply.NeedsYou
    val decision = (relay.ask(WatchRequest.Brief(now)) as WatchReply.Brief).decisions.first { it.refKind == "hold" }
    val line = needsYou.calls.single { it.holdId == decision.holdId }
    assertTrue("KIFF holds it and this phone can answer it: refusable from the wrist", line.refusable)
    // A call the brief names that the phone no longer holds names none.
    val gone = m.state.value.copy(held = emptyList())
    assertNull((WatchRelay.brief(gone) as WatchReply.Brief).decisions.first { it.refKind == "hold" }.holdId)
  }

  @Test fun aDecisionSetAsideOnThePhoneIsntSent() = runTest {
    reads()
    val m = model()
    val state = m.state.value.copy(briefSetAside = setOf("d1"))
    assertEquals(listOf("d2"), (WatchRelay.brief(state) as WatchReply.Brief).decisions.map { it.id })
  }

  @Test fun withNoBriefTheWatchIsToldSo() = runTest {
    reads(withBrief = false)
    val m = model()
    assertNull(m.state.value.brief)
    assertEquals(WatchReply.NoBrief, WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Brief(now)))
  }

  @Test fun withNoSessionTheWatchIsToldSignedOut() = runTest {
    assertEquals(WatchReply.SignedOut, WatchRelay(model = { null }, now = { now }).ask(WatchRequest.Brief(now)))
    // The sample isn't an account, even with its brief.
    assertEquals(WatchReply.SignedOut, WatchRelay(model = { testModel() }, now = { now }).ask(WatchRequest.Brief(now)))
  }

  /**
   * The brief is read with Needs you (`AppModel.refresh`), behind the same floor: Needs you then the brief, as the watch asks, read
   * tafs cloud once, and the brief has no read of its own.
   */
  @Test fun theBriefSharesTheFloorAndHasNoReadOfItsOwn() = runTest {
    reads()
    val m = model()
    val homes = transport.requestsTo("GET /v1/home").size
    val briefs = transport.requestsTo("GET /v1/brief").size
    var t = now
    val relay = WatchRelay(model = { m }, now = { t }, floor = RefreshFloor())
    assertTrue(relay.ask(WatchRequest.NeedsYou(t)) is WatchReply.NeedsYou)
    t += 1_000
    assertTrue(relay.ask(WatchRequest.Brief(t)) is WatchReply.Brief)
    t += 1_000
    assertTrue(relay.ask(WatchRequest.Brief(t)) is WatchReply.Brief)
    assertEquals("one read for the three asks", 1, transport.requestsTo("GET /v1/home").size - homes)
    assertEquals("the brief read with it, once", 1, transport.requestsTo("GET /v1/brief").size - briefs)
    t = now + 10_000
    assertTrue(relay.ask(WatchRequest.Brief(t)) is WatchReply.Brief)
    assertEquals("past the floor: read again", 2, transport.requestsTo("GET /v1/home").size - homes)
    assertEquals(2, transport.requestsTo("GET /v1/brief").size - briefs)
  }
}
