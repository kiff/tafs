package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.DoneCall
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.StreamEvent
import bot.tafs.app.model.Chat
import bot.tafs.app.model.ChatEnd
import bot.tafs.app.model.LiveAnswer
import bot.tafs.app.model.LiveState
import bot.tafs.app.model.LiveStep
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A taf's chat, live (docs/chat.md): the answer streamed as it's written, its steps, a hold, Stop, and how a run ended. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStreamTest {
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val stream = "POST /v1/tafs/taf_1/messages/stream"
  private val conversation = "GET /v1/tafs/taf_1/conversation"

  private fun reads(capabilities: String = """"cards.manage","holds.answer"""", thread: String = """{"messages":[]}""") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[$capabilities]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer(conversation, 200, thread)
  }

  /** Lets the model's background work (the stream it follows) run on virtual time. */
  private fun TestScope.settle() = repeat(20) {
    testScheduler.advanceTimeBy(100)
    testScheduler.runCurrent()
  }

  private fun sse(vararg events: Pair<String, String>) = events.joinToString("\n") { (name, data) -> "event: $name\ndata: $data\n" }

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0.plusMillis(currentTime) }, start = false, scope = backgroundScope)
      .also { it.restore() }
  }

  // MARK: The stream's events

  @Test fun tafsCloudsEventsAreReadByNameAndAnUnknownOneIsSkipped() = runTest {
    val lines = """
      event: session
      data: {"session":"s1","new_session":true,"run":"run_1"}
      event: activity
      data: {"tool":"list_charges_ru7r","step":"Reading payments…","call_id":"c1"}
      event: step
      data: {"call_id":"c1","status":"not_made"}
      event: whatever
      data: {}
      event: held
      data: {"exception":"ce_1","invocation":"inv_1"}
      event: end
      data: {"status":"ERROR","stop":"error","done":[{"tool":"create_refund","resource":"ch_1","invocation":"inv_2"}]}
    """.trimIndent().lines()
    val events = StreamEvent.parse(flowOf(*lines.toTypedArray())).toList()
    assertEquals(
      listOf(
        StreamEvent.Session("s1", true, "run_1"),
        StreamEvent.Activity("list_charges_ru7r", "Reading payments…", "c1"),
        StreamEvent.Step("c1", "not_made"),
        StreamEvent.Held("ce_1", "inv_1"),
        StreamEvent.End("ERROR", "error", listOf(DoneCall("create_refund", "ch_1", "inv_2"))),
      ),
      events,
    )
  }

  @Test fun aStepTheStreamSendsAgainKeepsItsStatusAndAHeldCallEndsTheLatestRunning() {
    val a = LiveAnswer(t0).began("t", "Reading…", "c1").ended("c1", LiveStep.Status.done).began("t", "Reading…", "c1")
    assertEquals(listOf(LiveStep.Status.done), a.steps.map { it.status })
    val held = a.began("refund", "Refunding…", null).ended(null, LiveStep.Status.held)
    assertEquals(LiveStep.Status.held, held.steps.last().status)
    assertTrue(held.newPart().steps.isEmpty())
  }

  // MARK: Following a run

  @Test fun theAnswerStreamsAsWrittenThenTheThreadTakesItsPlace() = runTest {
    reads()
    transport.answer(stream, 200, sse(
      "session" to """{"session":"s1","run":"run_1"}""",
      "thinking" to """{"text":"I should look the payment up first."}""",
      "activity" to """{"tool":"list_charges","step":"Reading payments…","call_id":"c1"}""",
      "step" to """{"call_id":"c1","status":"done"}""",
      "text" to """{"text":"Refunded "}""",
      "text" to """{"text":"Ana."}""",
      "end" to """{"status":"COMPLETED","stop":"end_turn"}""",
    ))
    val m = model()
    assertTrue(m.deliverChat("Refund Ana", "refunds-1"))
    settle()
    val request = transport.requestsTo(stream).single()
    assertEquals("""{"text":"Refund Ana"}""", String(request.body!!))
    assertEquals("text/event-stream", request.headers["Accept"])
    // Over: the live answer gives way to the thread, KIFF is read again, and nothing is said under it.
    assertNull(m.state.value.chatLive["refunds-1"])
    assertNull(m.state.value.chatEnded["refunds-1"])
    assertTrue(transport.requestsTo("GET /v1/me/card-exceptions").size >= 2)
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
  }

  @Test fun aHoldInTheStreamReadsKiffAtOnce() = runTest {
    reads()
    transport.answer(stream, 200, sse(
      "session" to """{"session":"s1","run":"run_1"}""",
      "activity" to """{"tool":"create_refund","call_id":"c2"}""",
      "held" to """{"exception":"ce_9","invocation":"inv_9"}""",
      "end" to """{"status":"PAUSED","stop":"held"}""",
    ))
    val m = model()
    val before = transport.requestsTo("GET /v1/me/card-exceptions").size
    m.deliverChat("Refund Eve 45", "refunds-1")
    settle()
    assertTrue("KIFF was read for the hold's card", transport.requestsTo("GET /v1/me/card-exceptions").size > before)
  }

  @Test fun aRunThatFailedPartWaySaysWhatWentThroughWithRetry() = runTest {
    reads()
    transport.answer(stream, 200, sse(
      "session" to """{"session":"s1","run":"run_1"}""",
      "end" to """{"status":"ERROR","stop":"error","done":[{"tool":"create_refund","resource":"ch_1"}]}""",
    ))
    val m = model()
    m.deliverChat("Refund Ana", "refunds-1")
    settle()
    assertEquals(ChatEnd.Failed("Refund Ana", listOf(DoneCall("create_refund", "ch_1"))), m.state.value.chatEnded["refunds-1"])
    assertEquals("Refunds stopped part-way, after this went through: create refund on ch_1.", AppModel.failedWords("Refunds", listOf(DoneCall("create_refund", "ch_1"))))
  }

  @Test fun aRunStoppedAtACapSaysWhy() = runTest {
    reads()
    transport.answer(stream, 200, sse("end" to """{"status":"COMPLETED","stop":"turn_cap"}"""))
    val m = model()
    m.deliverChat("Do everything", "refunds-1")
    settle()
    assertEquals(ChatEnd.Capped("turn_cap"), m.state.value.chatEnded["refunds-1"])
    assertEquals("Refunds stopped: this task took more steps than its limit for one task. Ask for something narrower.", AppModel.capWords("turn_cap", "Refunds"))
  }

  @Test fun withoutTheStreamRouteTheMessageGoesPlainlyAsBefore() = runTest {
    reads()
    transport.answer(stream, 404, """{"error":"not_found"}""")
    transport.answer("POST /v1/tafs/taf_1/messages", 200, """{"session":"s1","new_session":false}""")
    val m = model()
    assertTrue(m.deliverChat("hello", "refunds-1"))
    assertEquals(1, transport.requestsTo("POST /v1/tafs/taf_1/messages").size)
    assertNull(m.state.value.chatLive["refunds-1"])
  }

  @Test fun aRefusalOnTheStreamIsSaidAndNothingIsSentPlainly() = runTest {
    reads()
    transport.answer(stream, 429, """{"error":"too many messages"}""")
    val m = model()
    assertTrue(!m.deliverChat("hello", "refunds-1"))
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
    assertTrue(m.state.value.chatErrors["refunds-1"]!!.startsWith("That's a lot of messages"))
  }

  @Test fun stopCancelsTheSessionsRunAndTheChatSaysSo() = runTest {
    reads(thread = """{"messages":[],"working":{"session":"s1","run":"run_1"}}""")
    transport.answer("GET /v1/tafs/taf_1/runs/run_1/stream", 404, """{"error":"not_found"}""")
    transport.answer("POST /v1/tafs/taf_1/stop", 200, """{"stopped":true}""")
    val m = model()
    m.loadChat("refunds-1")
    m.stopChat("refunds-1")
    assertEquals("""{"session":"s1"}""", String(transport.requestsTo("POST /v1/tafs/taf_1/stop").single().body!!))
    assertEquals(ChatEnd.Stopped, m.state.value.chatEnded["refunds-1"])
  }

  @Test fun aRunThatGoesOnAfterItsStreamPausedIsFollowedToItsEnd() = runTest {
    // Paused on a card tafs cloud answers itself (Coach's result): the stream ends, then the run goes on with no stream open.
    reads()
    transport.answer(stream, 200, sse(
      "session" to """{"session":"s1","run":"run_1"}""",
      "end" to """{"status":"PAUSED","stop":"waiting"}""",
    ))
    val m = model()
    transport.answer(conversation, 200, """{"messages":[],"working":{"session":"s1","run":"run_1"}}""")
    transport.answer(conversation, 200, """{"messages":[{"at":"2026-10-28T09:00:05Z","from":"taf","text":"Version 2 is ready to try.","session":"s1"}]}""")
    m.deliverChat("Improve Refunds", "refunds-1")
    settle()
    testScheduler.advanceTimeBy(AppModel.CHAT_POLL_MS * 3)
    testScheduler.runCurrent()
    // Its end shows in the open chat, and nothing says it's still at work.
    assertNull(m.state.value.chatWorking["refunds-1"])
    assertTrue(m.state.value.chats["refunds-1"].orEmpty().any { it.text == "Version 2 is ready to try." })
  }

  @Test fun aRunAtWorkIsPickedBackUpOnceWhenTheChatIsRead() = runTest {
    reads(thread = """{"messages":[],"working":{"session":"s1","run":"run_1"}}""")
    transport.answer("GET /v1/tafs/taf_1/runs/run_1/stream", 200, sse(
      "activity" to """{"tool":"list_charges","step":"Reading payments…","call_id":"c1"}""",
    ))
    val m = model()
    m.loadChat("refunds-1")
    m.loadChat("refunds-1")
    assertEquals(1, transport.requestsTo("GET /v1/tafs/taf_1/runs/run_1/stream").size)
    assertTrue(transport.requestsTo("GET /v1/tafs/taf_1/runs/run_1/stream").single().url.endsWith("?after=0"))
  }

  // MARK: The live line

  @Test fun theLiveLineIsTafsCloudsStateAndNoThinkingText() {
    val live = LiveAnswer(started = t0, thinking = "Ana wants a refund. I should look it up.")
    // Before tafs cloud sends its line: "Thinking…", never the model's thinking.
    assertEquals("Thinking…", Chat.liveLine(live))
    assertEquals("Listing your charges…", Chat.liveLine(live.copy(state = LiveState("working", "Listing your charges"))))
    assertEquals("Waiting for you", Chat.liveLine(live.copy(state = LiveState("waiting_for_you"))))
    assertEquals("Thinking…", Chat.liveLine(live.copy(state = LiveState("thinking"))))
    // Done: the line folds into the message's "Worked …".
    assertNull(Chat.liveLine(live.copy(state = LiveState("done"))))
    // Writing with no line from tafs cloud: just the words.
    assertNull(Chat.liveLine(live.copy(text = "Refunded.")))
  }

  @Test fun theStreamsStateEventIsRead() = runTest {
    val events = StreamEvent.parse(flowOf("event: state", "data: {\"state\":\"working\",\"words\":\"Listing your charges\"}", "", "event: state", "data: {\"state\":\"done\"}", "")).toList()
    assertEquals(listOf(StreamEvent.State("working", "Listing your charges"), StreamEvent.State("done", null)), events)
  }
}
