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
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.launch
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
    // The words, and the message's one id (a repeat of it gets the same run).
    assertEquals("""{"text":"Refund Ana","client_message_id":"${idOf(request)}"}""", String(request.body!!))
    assertEquals("text/event-stream", request.headers["Accept"])
    // Over: the live answer gives way to the thread, KIFF is read again, and nothing is said under it.
    assertNull(m.state.value.chatLive["refunds-1"])
    assertNull(m.state.value.chatEnded["refunds-1"])
    assertTrue(transport.requestsTo("GET /v1/me/card-exceptions").size >= 2)
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
  }

  @Test fun theAnswerTakesTheLiveRowsPlaceInOneUpdateNeverBothNorNeither() = runTest {
    reads()
    transport.answer(stream, 200, sse(
      "session" to """{"session":"s1","run":"run_1"}""",
      "text" to """{"text":"Refunded Ana."}""",
      "end" to """{"status":"COMPLETED","stop":"end_turn"}""",
    ))
    // The thread read at the end holds the answer.
    transport.answer(conversation, 200, """{"messages":[{"from":"owner","text":"Refund Ana","at":"2026-10-28T09:00:00Z","session":"s1","id":"s1.m1"},
      {"from":"taf","text":"Refunded Ana.","at":"2026-10-28T09:00:01Z","session":"s1","id":"run_1.out"}]}""")
    val m = model()
    // Every state the chat shows, as it changes: the end's swap is one of them, never two.
    val seen = mutableListOf<bot.tafs.app.app.AppState>()
    backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.state.collect { seen += it } }
    assertTrue(m.deliverChat("Refund Ana", "refunds-1"))
    settle()
    fun answered(s: bot.tafs.app.app.AppState) = s.chats["refunds-1"].orEmpty().any { it.from == bot.tafs.app.model.ChatMessage.From.taf }
    fun live(s: bot.tafs.app.app.AppState) = s.chatLive["refunds-1"]?.text?.isNotEmpty() == true
    assertTrue("the answer landed", answered(seen.last()))
    // Never the answer twice (in the thread and still live), and never neither once it was written.
    assertTrue("the answer showed twice", seen.none { answered(it) && live(it) })
    val wrote = seen.indexOfFirst { live(it) }
    assertTrue(wrote >= 0)
    assertTrue("the live answer went before the thread had it", seen.drop(wrote).none { !answered(it) && !live(it) })
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
    assertEquals(ChatEnd.Failed("Refund Ana", listOf(DoneCall("create_refund", "ch_1")), run = "run_1"), m.state.value.chatEnded["refunds-1"])
    assertEquals("Refunds stopped part-way, after this went through: create refund on ch_1.", AppModel.failedWords("Refunds", listOf(DoneCall("create_refund", "ch_1"))))
  }

  @Test fun aRunTafsCloudNamesSaysItsWordsWithRetry() = runTest {
    reads()
    val words = "Our AI provider isn't serving us right now, so this stopped and used none of your credits. It's on our side, not yours: try again a little later."
    transport.answer(stream, 200, sse(
      "session" to """{"session":"s1","run":"run_1"}""",
      "end" to """{"status":"ERROR","stop":"error","done":[],"code":"ai_unavailable","words":"$words"}""",
    ))
    val m = model()
    m.deliverChat("Refund Ana", "refunds-1")
    settle()
    val end = m.state.value.chatEnded["refunds-1"] as ChatEnd.Failed
    assertEquals(ChatEnd.Failed("Refund Ana", emptyList(), words, run = "run_1"), end)
    assertEquals(words, AppModel.failedWords("Refunds", end.done, end.words))
    // What went through before it stopped still follows the words.
    assertEquals("$words This went through: create refund on ch_1.", AppModel.failedWords("Refunds", listOf(DoneCall("create_refund", "ch_1")), words))
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

  @Test fun aReplyOutOfRoomOrDeclinedSaysSoNeverDotDotDot() = runTest {
    reads()
    transport.answer(stream, 200, sse("end" to """{"status":"COMPLETED","stop":"max_token"}"""))
    val m = model()
    m.deliverChat("Write it all", "refunds-1")
    settle()
    assertEquals(ChatEnd.Capped("max_token"), m.state.value.chatEnded["refunds-1"])
    assertEquals("Refunds stopped: its reply ran out of room. Ask for something shorter, or for one part at a time.", AppModel.capWords("max_token", "Refunds"))
    assertEquals("Refunds stopped: the AI declined to continue. Try asking another way.", AppModel.capWords("refused", "Refunds"))
  }

  // A run paused on a card its person answers: Needs you (Home) is read when the stream closes, even with no end line, so the
  // waiting row and the badge come while the chat is open. A run that went on past the card asks no extra read.
  @Test fun aStreamThatClosesWaitingOnYouReadsNeedsYou() = runTest {
    reads()
    transport.answer(stream, 200, sse("session" to """{"session":"s1","run":"run_1"}""", "state" to """{"state":"waiting_for_you","words":"Waiting for you"}"""))
    val m = model()
    settle()
    val before = transport.requestsTo("GET /v1/home").size
    m.deliverChat("Remind me every Friday", "refunds-1")
    settle()
    assertTrue("Home was read for Needs you", transport.requestsTo("GET /v1/home").size > before)
  }

  @Test fun whetherARunStillWaitsOnItsPersonFollowsItsLastLine() {
    assertTrue(AppModel.stillWaitsOnYou(false, "waiting_for_you"))
    assertTrue("done keeps the pause before it", AppModel.stillWaitsOnYou(true, "done"))
    assertEquals(false, AppModel.stillWaitsOnYou(false, "done"))
    assertEquals("a line after the card: it went on", false, AppModel.stillWaitsOnYou(true, "working"))
  }

  @Test fun withoutTheStreamRouteTheMessageGoesPlainlyAsBefore() = runTest {
    reads()
    transport.answer(stream, 404, """{"error":"not_found"}""")
    transport.answer("POST /v1/tafs/taf_1/messages", 200, """{"session":"s1","new_session":false}""")
    val m = model()
    assertTrue(m.deliverChat("hello", "refunds-1"))
    assertEquals(1, transport.requestsTo("POST /v1/tafs/taf_1/messages").size)
    // tafs cloud words its lines for people in the app's language.
    assertEquals("en", transport.requestsTo("POST /v1/tafs/taf_1/messages").single().headers["Accept-Language"])
    assertNull(m.state.value.chatLive["refunds-1"])
  }

  /** The message's one id, as its request carried it. */
  private fun idOf(request: bot.tafs.app.kiff.HttpRequest): String? =
    bot.tafs.app.kiff.KiffClient.json.parseToJsonElement(String(request.body!!)).let { (it as kotlinx.serialization.json.JsonObject)["client_message_id"] }
      ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }

  @Test fun theStreamAndThePlainSendThatTakesItsPlaceCarryTheMessagesOneId() = runTest {
    // The stream fails after tafs cloud may have taken the message (a 503): the plain send is the same message, under the same id,
    // so tafs cloud gives it the run it already started, never a second one.
    reads()
    transport.answer(stream, 503, """{"error":"unavailable"}""")
    transport.answer("POST /v1/tafs/taf_1/messages", 200, """{"session":"s1","new_session":false}""")
    val m = model()
    assertTrue(m.deliverChat("Refund Ana", "refunds-1"))
    val streamed = idOf(transport.requestsTo(stream).single())
    val plain = idOf(transport.requestsTo("POST /v1/tafs/taf_1/messages").single())
    assertTrue("a UUID", streamed != null && java.util.UUID.fromString(streamed).toString() == streamed)
    assertEquals(streamed, plain)
    // The next message the owner writes is another message, with another id.
    assertTrue(m.deliverChat("And Ben", "refunds-1"))
    val next = idOf(transport.requestsTo(stream).last())
    assertTrue(next != null && next != streamed)
  }

  @Test fun aRetryOfAMessageNotSentGoesUnderItsOwnIdAgain() = runTest {
    // The plain send drops with no answer: it may have reached tafs cloud. Retry sends it under the same id, so if it did, tafs
    // cloud answers with that run, not a second.
    reads()
    transport.answer(stream, 503, """{"error":"unavailable"}""")
    transport.drop("POST /v1/tafs/taf_1/messages")
    transport.answer("POST /v1/tafs/taf_1/messages", 200, """{"session":"s1","new_session":false}""")
    val m = model()
    assertTrue(!m.deliverChat("Refund Ana", "refunds-1"))
    val first = idOf(transport.requestsTo(stream).first())
    val unsent = m.state.value.chatUnsent["refunds-1"]!!.single()
    assertEquals(first, unsent.messageId)
    assertTrue(m.resendUnsent("refunds-1", unsent.message))
    assertEquals(first, idOf(transport.requestsTo(stream).last()))
    assertEquals(first, idOf(transport.requestsTo("POST /v1/tafs/taf_1/messages").last()))
  }

  @Test fun aRetryTafsCloudAnswersAsARepeatIsOneMessageInTheThread() = runTest {
    // The first try reached tafs cloud before the network dropped: Retry, under the same id, is answered with that send's run and
    // message (`repeat: true`), and the thread holds the message once, never twice.
    reads(thread = """{"messages":[{"id":"run_1.in","at":"2026-10-28T08:00:00Z","from":"owner","text":"Refund Ana","session":"s1"}]}""")
    transport.answer(stream, 503, """{"error":"unavailable"}""")
    transport.drop("POST /v1/tafs/taf_1/messages")
    transport.answer(stream, 503, """{"error":"unavailable"}""")
    transport.answer("POST /v1/tafs/taf_1/messages", 200, """{"session":"s1","new_session":false,"run":"run_1","message":{"id":"run_1.in","at":"2026-10-28T08:00:00Z"},"repeat":true}""")
    val m = model()
    assertTrue(!m.deliverChat("Refund Ana", "refunds-1"))
    val unsent = m.state.value.chatUnsent["refunds-1"]!!.single()
    assertTrue(m.resendUnsent("refunds-1", unsent.message))
    m.loadChat("refunds-1")
    testScheduler.runCurrent()
    val ids = transport.requestsTo("POST /v1/tafs/taf_1/messages").map(::idOf)
    assertEquals(listOf(unsent.messageId, unsent.messageId), ids)
    assertTrue("no message left as not sent", m.state.value.chatUnsent["refunds-1"].isNullOrEmpty())
    val shown = m.state.value.chats["refunds-1"].orEmpty().filter { it.text == "Refund Ana" }
    assertEquals("one message, not two: $shown", 1, shown.size)
  }

  @Test fun anIdAlreadyUsedForOtherWordsSaysSoNotThatSomethingChanged() = runTest {
    // tafs cloud's 409 `dedup_key_conflict`, which carries no words of its own: the not-sent row says the message went with other
    // words, in the app's line for it, and nothing is sent plainly in its place.
    reads()
    transport.answer(stream, 409, """{"error":"this client_message_id was already used for another message","code":"dedup_key_conflict"}""")
    val m = model()
    assertTrue(!m.deliverChat("Refund Ana", "refunds-1"))
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
    val unsent = m.state.value.chatUnsent["refunds-1"]!!.single()
    assertTrue(unsent.why, unsent.why.contains(bot.tafs.app.model.Words.Error.sentWithOtherText))
    // The id is given up: Retry is a new message, under a new id, as the line says.
    assertNull(unsent.messageId)
    val first = idOf(transport.requestsTo(stream).single())
    transport.answer(stream, 200, sse("done" to "{}"))
    m.resendUnsent("refunds-1", unsent.message)
    val again = idOf(transport.requestsTo(stream).last())
    assertTrue("a new id: $first then $again", again != null && again != first)
  }

  @Test fun aMessageRefusedOutrightRetriesAsANewMessageAndOneNotAnsweredKeepsItsId() = runTest {
    // tafs cloud's contract for Retry (docs/api.md): a 4xx gives the id up, a 5xx or a 408 keeps it, as no answer does.
    for ((status, keeps) in listOf(400 to false, 409 to false, 429 to false, 408 to true, 500 to true, 503 to true)) {
      transport.reset()
      reads()
      transport.answer(stream, 503, """{"error":"unavailable"}""")
      transport.answer("POST /v1/tafs/taf_1/messages", status, """{"error":"refused"}""")
      val m = model()
      assertTrue(!m.deliverChat("Refund Ana", "refunds-1"))
      val unsent = m.state.value.chatUnsent["refunds-1"]!!.single()
      val sent = idOf(transport.requestsTo("POST /v1/tafs/taf_1/messages").single())
      assertEquals("$status", if (keeps) sent else null, unsent.messageId)
    }
  }

  @Test fun aRefusalOnTheStreamIsSaidAndNothingIsSentPlainly() = runTest {
    reads()
    transport.answer(stream, 429, """{"error":"too many messages"}""")
    val m = model()
    assertTrue(!m.deliverChat("hello", "refunds-1"))
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
    assertTrue(m.state.value.chatUnsent["refunds-1"]!!.single().why.startsWith("That's a lot of messages"))
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

  /** run_1's resumed stream left open and silent after its first event, as a stream whose run ended unsaid. */
  private fun quietStream() {
    transport.openStream = { request ->
      if (!request.url.contains("/runs/run_1/stream")) null
      else {
        transport.send(request)
        bot.tafs.app.kiff.StreamedResponse(200, kotlinx.coroutines.flow.flow {
          sse("activity" to """{"tool":"list_charges","step":"Reading payments…","call_id":"c1"}""").lines().forEach { emit(it) }
          kotlinx.coroutines.awaitCancellation()
        })
      }
    }
  }

  @Test fun aStreamThatWentQuietAfterItsRunEndedLeavesNoDotsAndTheThreadShowsTheEnd() = runTest {
    reads(thread = """{"messages":[],"working":{"session":"s1","run":"run_1"}}""")
    transport.answer("GET /v1/tafs/taf_1/runs/run_1/stream", 200, "")
    quietStream()
    val m = model()
    m.loadChat("refunds-1")
    settle()
    assertTrue("the live answer while the stream is open", m.state.value.chatLive.containsKey("refunds-1"))
    // The run ends, and the stream says nothing more. The thread says so at the next check.
    transport.answer(conversation, 200, """{"messages":[{"at":"2026-10-28T09:00:05Z","from":"taf","text":"Done: 3 refunds.","session":"s1"}]}""")
    testScheduler.advanceTimeBy(AppModel.STREAM_CHECK_MS + 1)
    testScheduler.runCurrent()
    settle()
    assertTrue("no …", m.state.value.chatLive.isEmpty())
    assertNull("no Stop", m.state.value.chatWorking["refunds-1"])
    assertTrue(m.state.value.chats["refunds-1"].orEmpty().any { it.text == "Done: 3 refunds." })
  }

  /**
   * The owner, 18 Nov (P0): on a phone the stream's lines come through `flowOn`, so the stream's own reads run in a child of its
   * job. A read the stream made once the run looked quiet (nothing at work, the live answer over 15 s old) cancelled the stream
   * and that read with it: the run's end never showed, and a card stayed on "Reading what it asks for…". Here the lines go
   * through a buffer, as on a phone, and the run's end still shows.
   */
  @Test fun aStreamsOwnReadNeverCancelsTheStream() = runTest {
    reads()
    transport.openStream = { request ->
      if (!request.url.contains("/messages/stream")) null
      else {
        transport.send(request)
        bot.tafs.app.kiff.StreamedResponse(200, kotlinx.coroutines.flow.flow {
          sse("session" to """{"session":"s1","new_session":false,"run":"run_1","message_id":"m1"}""").lines().forEach { emit(it) }
          kotlinx.coroutines.delay(AppModel.STALE_LIVE_MS + 1_000)
          sse("said" to """{}""").lines().forEach { emit(it) }
          sse("end" to """{"status":"ERROR","stop":"error","done":[]}""").lines().forEach { emit(it) }
        }.buffer())
      }
    }
    val m = model()
    // The run is over by the time it says something: tafs cloud has the message and nothing at work.
    transport.answer(conversation, 200, """{"messages":[{"id":"m1","at":"2026-10-28T09:00:00Z","from":"owner","text":"Refund Ana","session":"s1"}]}""")
    m.deliverChat("Refund Ana", "refunds-1")
    testScheduler.advanceTimeBy(AppModel.STALE_LIVE_MS + 2_000)
    testScheduler.runCurrent()
    settle()
    assertTrue("the stream read on to its end", m.state.value.chatEnded["refunds-1"] is ChatEnd.Failed)
  }

  /**
   * A stream that went quiet on a phone (its lines through `flowOn`, so its 30 s check runs under its job) is still closed by
   * that check: the chat no longer looks at work, and a later run in it, one this phone didn't start, is followed by its own
   * stream. A check that could never close it left the chat unfollowed, the same "stops updating" by another road.
   */
  @Test fun aQuietStreamOnAPhoneIsClosedByItsCheckAndALaterRunIsFollowed() = runTest {
    reads(thread = """{"messages":[],"working":{"session":"s1","run":"run_1"}}""")
    transport.openStream = { request ->
      if (!request.url.contains("/runs/run_1/stream")) null
      else {
        transport.send(request)
        bot.tafs.app.kiff.StreamedResponse(200, kotlinx.coroutines.flow.flow {
          sse("activity" to """{"tool":"list_charges","step":"Reading payments…","call_id":"c1"}""").lines().forEach { emit(it) }
          kotlinx.coroutines.awaitCancellation()
        }.buffer())
      }
    }
    transport.answer("GET /v1/tafs/taf_1/runs/run_2/stream", 200, sse("end" to """{"status":"COMPLETED","stop":"answered"}"""))
    val m = model()
    m.loadChat("refunds-1")
    settle()
    assertTrue("the live answer while the stream is open", m.state.value.chatLive.containsKey("refunds-1"))
    transport.answer(conversation, 200, """{"messages":[{"at":"2026-10-28T09:00:05Z","from":"taf","text":"Done: 3 refunds.","session":"s1"}]}""")
    testScheduler.advanceTimeBy(AppModel.STREAM_CHECK_MS + 1)
    testScheduler.runCurrent()
    settle()
    assertTrue("closed by its check: no …", m.state.value.chatLive.isEmpty())
    // A run this phone didn't start goes on in the chat: its stream is opened.
    transport.answer(conversation, 200, """{"messages":[],"working":{"session":"s1","run":"run_2"}}""")
    m.loadChat("refunds-1")
    settle()
    assertEquals("the later run is followed", 1, transport.requestsTo("GET /v1/tafs/taf_1/runs/run_2/stream").size)
  }

  @Test fun aLongRunIsReadUntilItEndsPastTheFastReads() = runTest {
    // A run at work whose stream can't open: read every few seconds, then every quarter minute, until it ends.
    reads(thread = """{"messages":[],"working":{"session":"s1","run":"run_1"}}""")
    transport.answer("GET /v1/tafs/taf_1/runs/run_1/stream", 404, """{"error":"not_found"}""")
    val m = model()
    m.loadChat("refunds-1")
    testScheduler.advanceTimeBy(AppModel.CHAT_POLL_MS * AppModel.CHAT_POLL_BUDGET + AppModel.CHAT_SLOW_POLL_MS * 3)
    testScheduler.runCurrent()
    assertTrue("still followed past the fast reads", m.state.value.chatWorking.containsKey("refunds-1"))
    transport.answer(conversation, 200, """{"messages":[{"at":"2026-10-28T09:20:00Z","from":"taf","text":"Set up.","session":"s1"}]}""")
    testScheduler.advanceTimeBy(AppModel.CHAT_SLOW_POLL_MS + 1)
    testScheduler.runCurrent()
    assertNull(m.state.value.chatWorking["refunds-1"])
    assertTrue(m.state.value.chats["refunds-1"].orEmpty().any { it.text == "Set up." })
  }

  @Test fun aChatIsReadLessAndLessWithJitterNeverLaterThanBefore() {
    assertEquals(AppModel.CHAT_POLL_MS, AppModel.chatFollowDelay(0, 0.0))
    assertEquals(5_000L, AppModel.chatFollowDelay(60_000, 0.0))
    assertEquals(10_000L, AppModel.chatFollowDelay(180_000, 0.0))
    assertEquals(AppModel.CHAT_SLOW_POLL_MS, AppModel.chatFollowDelay(360_000, 0.0))
    // Jitter only shortens, by a fifth at most: phones don't read in step, and a reply is never later than the step.
    assertEquals((AppModel.CHAT_POLL_MS * 0.8).toLong(), AppModel.chatFollowDelay(0, -1.0))
    assertEquals(AppModel.CHAT_POLL_MS, AppModel.chatFollowDelay(0, 1.0))
    // At 10,000 users: a run followed by reads for its whole half hour takes about 167 reads where it took 250.
    var waited = 0L
    var reads = 0
    while (waited < AppModel.CHAT_FOLLOW_MS) { waited += AppModel.chatFollowDelay(waited, 0.0); reads++ }
    assertTrue("fewer reads for the same follow: $reads", reads < AppModel.CHAT_POLL_BUDGET + AppModel.CHAT_SLOW_POLL_BUDGET)
  }

  @Test fun aChatIsNotReadWhileTheAppIsInTheBackgroundAndIsReadOnItsReturn() = runTest {
    reads(thread = """{"messages":[],"working":{"session":"s1","run":"run_1"}}""")
    transport.answer("GET /v1/tafs/taf_1/runs/run_1/stream", 404, """{"error":"not_found"}""")
    val m = model()
    m.chatJitter = { 0.0 }
    m.loadChat("refunds-1")
    testScheduler.advanceTimeBy(AppModel.CHAT_POLL_MS + 1)
    testScheduler.runCurrent()
    m.wentToBackground()
    val before = transport.requestsTo(conversation).size
    testScheduler.advanceTimeBy(AppModel.CHAT_SLOW_POLL_MS * 20)
    testScheduler.runCurrent()
    assertEquals("nothing read in the background", before, transport.requestsTo(conversation).size)
    // Back on screen: read once, at once, with no wait first, so a run that ended meanwhile shows as ended.
    m.cameToForeground()
    testScheduler.runCurrent()
    assertEquals("read once, at once, on return", before + 1, transport.requestsTo(conversation).size)
    // Then the back-off goes on.
    testScheduler.advanceTimeBy(AppModel.CHAT_SLOW_POLL_MS + 1)
    testScheduler.runCurrent()
    assertTrue("read again after its wait", transport.requestsTo(conversation).size > before + 1)
  }

  @Test fun aRunThatStartsWhileTheChatIsReadIsFollowedByItsStream() = runTest {
    // Followed by reads (run_1's stream can't open); then a new run, run_2, goes on with no stream open: its stream is picked up.
    reads(thread = """{"messages":[],"working":{"session":"s1","run":"run_1"}}""")
    transport.answer("GET /v1/tafs/taf_1/runs/run_1/stream", 404, """{"error":"not_found"}""")
    transport.answer("GET /v1/tafs/taf_1/runs/run_2/stream", 200, sse("end" to """{"status":"COMPLETED","stop":"answered"}"""))
    val m = model()
    m.loadChat("refunds-1")
    transport.answer(conversation, 200, """{"messages":[],"working":{"session":"s1","run":"run_2"}}""")
    testScheduler.advanceTimeBy(AppModel.CHAT_POLL_MS + 1)
    testScheduler.runCurrent()
    settle()
    assertEquals(1, transport.requestsTo("GET /v1/tafs/taf_1/runs/run_2/stream").size)
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

  @Test fun aResumeThatLandsAfterAnotherSignInStartsNothing() = runTest {
    reads(thread = """{"messages":[],"working":{"session":"s1","run":"run_1"}}""")
    transport.answer("GET /v1/tafs/taf_1/runs/run_1/stream", 200, sse("activity" to """{"tool":"list_charges","step":"Reading payments…","call_id":"c1"}"""))
    val m = model()
    transport.hold("GET /v1/tafs/taf_1/runs/run_1/stream")
    val read = launch { m.loadChat("refunds-1") }
    settle()
    // Another sign-in while the resume was on its way: what it brings back belongs to the session that asked.
    m.useSession("at_2", "t_01")
    val reads = transport.requestsTo(conversation).size
    transport.release("GET /v1/tafs/taf_1/runs/run_1/stream")
    read.join()
    settle()
    assertTrue(m.state.value.chatLive.isEmpty())
    assertEquals("the old resume follows nothing in the new session", reads, transport.requestsTo(conversation).size)
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
