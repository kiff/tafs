package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.app.ChatRun
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.model.ChatItem
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.SentMessages
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A taf's chat in KIFF mode, read from and written to tafs cloud. A fake tafs cloud stands in: no real taf is written to. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatKiffTest {
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""
  private val conversation = "GET /v1/tafs/taf_1/conversation"

  private fun home(extra: String = "") =
    """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}$extra],
        "others":[{"name":"Billing bot","kiff_agent":"billing-x","color":2,"state":"idle","card":{"ids":["c9"],"active":true}}],"removing":[]}"""

  private fun readsOf(home: String = home(), exceptions: String = "") {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$exceptions]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private fun owner(text: String, at: String, session: String = "s1") = """{"at":"$at","from":"owner","text":"$text","session":"$session"}"""
  private fun taf(text: String, at: String, session: String = "s1", work: String = "") =
    """{"at":"$at","from":"taf","text":"$text","session":"$session"$work}"""

  private fun thread(vararg messages: String, working: String = "") =
    """{"messages":[${messages.joinToString(",")}]$working}"""

  private val working = ""","working":{"session":"s1","run":"run_1"}"""

  /** A signed-in model on virtual time, following chats in the test's background. */
  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0.plusMillis(currentTime) }, start = false, scope = backgroundScope)
      .also { it.restore() }
  }

  private fun AppModel.messages(id: String = "refunds-1") = state.value.chats[id].orEmpty()

  // MARK: Who can be chatted with

  @Test fun aTafRunningOnTafsCloudCanBeChattedWithAndAnAgentItDoesNotRunCannot() = runTest {
    readsOf()
    val s = model().state.value
    assertTrue(s.canChat("refunds-1"))
    assertFalse(s.canChat("billing-x"))
    assertFalse(s.canChat("nobody"))
    assertEquals("taf_1", s.taf("refunds-1")!!.cloudId)
    assertNull(s.taf("billing-x")!!.cloudId)
    assertTrue(AppState(mode = Mode.sample).canChat("anything"))
  }

  // MARK: Reading

  @Test fun aThreadIsReadFromTafsCloudUnderTheTafsOwnId() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("hello", "2026-10-28T08:00:00Z"), taf("Hi!", "2026-10-28T08:00:05Z")))
    val m = model()
    m.loadChat("refunds-1")
    assertEquals(listOf("hello" to ChatMessage.From.owner, "Hi!" to ChatMessage.From.taf), m.messages().map { it.text to it.from })
    val request = transport.requestsTo(conversation).single()
    assertEquals("https://cloud.tafs.kiff.dev/v1/tafs/taf_1/conversation", request.url)
    assertEquals("Bearer at_1", request.headers["Authorization"])
  }

  @Test fun aRunsKickoffAndWhatATafDidAreKept() = runTest {
    readsOf()
    transport.answer(
      conversation, 200,
      thread(
        """{"at":"2026-10-28T08:00:00Z","from":"owner","kind":"kickoff","text":"Check today's refunds","session":"s2"}""",
        taf("Done.", "2026-10-28T08:00:09Z", "s2", work = ""","work":{"ms":4200,"steps":[{"tool":"bxkwf7vi_list_charges"},{"tool":"create_refund","error":true},{"tool":"x","not_made":true}]}"""),
      ),
    )
    val m = model()
    m.loadChat("refunds-1")
    val (kickoff, answer) = m.messages()
    assertEquals(ChatMessage.Kind.kickoff, kickoff.kind)
    assertEquals(ChatMessage.Kind.message, answer.kind)
    assertEquals(4200, answer.work!!.ms)
    assertEquals(listOf("bxkwf7vi_list_charges", "create_refund", "x"), answer.work!!.steps.map { it.tool })
    assertEquals(listOf(false, true, false), answer.work!!.steps.map { it.error })
    assertEquals(listOf(false, false, true), answer.work!!.steps.map { it.notMade })
  }

  @Test fun aMessageFromNeitherTheOwnerNorTheTafIsLeftOutNotTheThread() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread("""{"at":"2026-10-28T08:00:00Z","from":"system","text":"x","session":"s"}""", taf("Hi", "2026-10-28T08:00:05Z")))
    val m = model()
    m.loadChat("refunds-1")
    assertEquals(listOf("Hi"), m.messages().map { it.text })
  }

  @Test fun aThreadThatCannotBeReadSaysSoAndKeepsWhatWasShown() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(taf("Hi", "2026-10-28T08:00:05Z")))
    val m = model()
    m.loadChat("refunds-1")
    transport.answer(conversation, 500, """{"error":"boom"}""")
    m.loadChat("refunds-1")
    assertEquals("The thread couldn't be loaded. It isn't working on our side right now. Nothing to do on your side; try again later.", m.state.value.chatErrors["refunds-1"])
    assertEquals(listOf("Hi"), m.messages().map { it.text })
  }

  @Test fun theErrorGoesOnceTheThreadIsRead() = runTest {
    readsOf()
    transport.answer(conversation, 500, """{"error":"boom"}""")
    transport.answer(conversation, 200, thread(taf("Hi", "2026-10-28T08:00:05Z")))
    val m = model()
    m.loadChat("refunds-1")
    assertNotNull(m.state.value.chatErrors["refunds-1"])
    m.loadChat("refunds-1")
    assertNull(m.state.value.chatErrors["refunds-1"])
  }

  @Test fun aTafWithoutACloudIdReadsNothing() = runTest {
    readsOf()
    val m = model()
    m.loadChat("billing-x")
    assertTrue(transport.requests.none { it.url.contains("/conversation") })
  }

  @Test fun theThreadAndTheCallsWaitingForTheOwnerAreOneTimeline() = runTest {
    readsOf(exceptions = """{"id":"ce_1","subject":"refunds-1","action":"x","entity_id":"e","created_at":"2026-10-28T08:30:00Z"}""")
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), taf("Waiting for your approval.", "2026-10-28T08:00:10Z")))
    val m = model()
    m.loadChat("refunds-1")
    val items = m.state.value.chatItems("refunds-1")
    assertEquals(listOf("Message", "Message", "Held"), items.map { it::class.simpleName })
    assertTrue(items.last() is ChatItem.Held)
  }

  // MARK: Following a run

  @Test fun aRunAtWorkIsFollowedUntilItEndsAndTheTafShowsThinkingMeanwhile() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = working))
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), taf("part", "2026-10-28T08:00:03Z"), working = working))
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), taf("Done.", "2026-10-28T08:00:06Z")))
    val m = model()
    m.loadChat("refunds-1")
    assertTrue(m.state.value.thinking("refunds-1"))
    assertEquals(ChatRun("s1", "run_1"), m.state.value.chatWorking["refunds-1"])
    advanceTimeBy(AppModel.CHAT_POLL_MS + 1); runCurrent()
    assertTrue(m.state.value.thinking("refunds-1"))
    assertEquals("part", m.messages().last().text)
    advanceTimeBy(AppModel.CHAT_POLL_MS); runCurrent()
    assertFalse(m.state.value.thinking("refunds-1"))
    assertEquals("Done.", m.messages().last().text)
    // It stopped: no further reads.
    val reads = transport.requestsTo(conversation).size
    advanceTimeBy(AppModel.CHAT_POLL_MS * 5); runCurrent()
    assertEquals(3, reads)
    assertEquals(reads, transport.requestsTo(conversation).size)
  }

  @Test fun anIdleTafIsReadOnceAndNotPolled() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(taf("Hi", "2026-10-28T08:00:05Z")))
    val m = model()
    m.loadChat("refunds-1")
    advanceTimeBy(AppModel.CHAT_POLL_MS * 10); runCurrent()
    assertEquals(1, transport.requestsTo(conversation).size)
    assertFalse(m.state.value.thinking("refunds-1"))
  }

  @Test fun openingTheChatTwiceFollowsItOnce() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = working))
    val m = model()
    m.loadChat("refunds-1")
    m.loadChat("refunds-1")
    // Two opens read twice; one follower polls: after one interval, one more read, not two.
    val before = transport.requestsTo(conversation).size
    advanceTimeBy(AppModel.CHAT_POLL_MS + 1); runCurrent()
    assertEquals(before + 1, transport.requestsTo(conversation).size)
  }

  @Test fun signingOutStopsFollowing() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = working))
    val m = model()
    m.loadChat("refunds-1")
    m.signOut()
    val reads = transport.requestsTo(conversation).size
    advanceTimeBy(AppModel.CHAT_POLL_MS * 5); runCurrent()
    assertEquals(reads, transport.requestsTo(conversation).size)
  }

  // MARK: Sending

  private fun messageBody() = transport.requestsTo("POST /v1/tafs/taf_1/messages").single().body!!.let { String(it) }
  private fun sent() = transport.answer("POST /v1/tafs/taf_1/messages", 200, """{"session":"s1","new_session":false}""")

  @Test fun aMessageGoesToTheTafsChatAndTheThreadIsReadAgain() = runTest {
    readsOf(); sent()
    transport.answer(conversation, 200, thread())
    transport.answer(conversation, 200, thread(owner("hello", "2026-10-28T09:00:00Z"), working = working))
    val m = model()
    m.loadChat("refunds-1")
    assertTrue(m.deliverChat("  hello  ", "refunds-1"))
    assertEquals("hello", transport.requestsTo("POST /v1/tafs/taf_1/messages").single().body!!.let { KiffClientJson.parse(it).getValue("text").jsonPrimitive.content })
    assertEquals("Bearer at_1", transport.requestsTo("POST /v1/tafs/taf_1/messages").single().headers["Authorization"])
    // The thread has it now: one copy, not two, and the taf shows at work.
    assertEquals(listOf("hello"), m.messages().map { it.text })
    assertTrue(m.state.value.thinking("refunds-1"))
    assertTrue(m.state.value.chatSent.isEmpty())
  }

  @Test fun aMessageTheThreadDoesNotHaveYetStaysOnScreenDimmed() = runTest {
    readsOf(); sent()
    transport.answer(conversation, 200, thread())
    val m = model()
    m.loadChat("refunds-1")
    assertTrue(m.deliverChat("hello", "refunds-1"))
    val shown = m.messages().single()
    assertEquals("hello", shown.text)
    assertEquals("pending", shown.session)
    assertEquals(1, m.state.value.chatSent["refunds-1"]!!.size)
    // Nothing answers, and the taf reads as thinking until it has, or two minutes pass.
    assertTrue(m.state.value.thinking("refunds-1"))
  }

  @Test fun theWaitEndsOnceTheTafHasAnsweredAfterTheOwnerWrote() = runTest {
    readsOf(); sent()
    transport.answer(conversation, 200, thread())
    transport.answer(conversation, 200, thread())
    transport.answer(conversation, 200, thread(owner("hello", "2026-10-28T09:00:00Z"), taf("Hi!", "2026-10-28T09:00:04Z")))
    val m = model()
    m.loadChat("refunds-1")
    m.deliverChat("hello", "refunds-1")
    assertTrue(m.state.value.thinking("refunds-1"))
    advanceTimeBy(AppModel.CHAT_POLL_MS + 1); runCurrent()
    assertFalse(m.state.value.thinking("refunds-1"))
    assertEquals(listOf("hello", "Hi!"), m.messages().map { it.text })
  }

  @Test fun theWaitEndsAfterTwoMinutesWithNoSign() = runTest {
    readsOf(); sent()
    transport.answer(conversation, 200, thread())
    val m = model()
    m.loadChat("refunds-1")
    m.deliverChat("hello", "refunds-1")
    assertTrue(m.state.value.thinking("refunds-1"))
    advanceTimeBy(125_000); runCurrent()
    assertFalse(m.state.value.thinking("refunds-1"))
    // And the unmatched message is dropped from the screen with it.
    assertTrue(m.messages().isEmpty())
  }

  @Test fun aMessageWrittenWhileARunIsAtWorkIsSteeredNotSentAsANewOne() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = working))
    transport.answer("POST /v1/tafs/taf_1/steer", 200, """{"run":"run_1","status":"RUNNING"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertTrue(m.deliverChat("also check #2", "refunds-1"))
    val body = KiffClientJson.parse(transport.requestsTo("POST /v1/tafs/taf_1/steer").single().body!!)
    assertEquals("run_1", body.getValue("run").jsonPrimitive.content)
    assertEquals("also check #2", body.getValue("text").jsonPrimitive.content)
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
  }

  @Test fun aRunThatEndedMeanwhileTakesTheMessageAsANewOne() = runTest {
    readsOf(); sent()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = working))
    transport.answer("POST /v1/tafs/taf_1/steer", 409, """{"error":"run_over"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertTrue(m.deliverChat("one more", "refunds-1"))
    assertEquals(1, transport.requestsTo("POST /v1/tafs/taf_1/steer").size)
    assertEquals(1, transport.requestsTo("POST /v1/tafs/taf_1/messages").size)
  }

  @Test fun aSteerTafsCloudRefusesForAnotherReasonIsNotSentAsANewMessageToo() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = working))
    transport.answer("POST /v1/tafs/taf_1/steer", 500, """{"error":"boom"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("one more", "refunds-1"))
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
  }

  @Test fun aMessageThatDoesNotGoIsTakenOffTheScreenAndSaysWhy() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread())
    transport.answer("POST /v1/tafs/taf_1/messages", 500, """{"error":"boom"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("hello", "refunds-1"))
    assertTrue(m.messages().isEmpty())
    assertTrue(m.state.value.chatSent.isEmpty())
    assertEquals("The message wasn't sent. It isn't working on our side right now. Nothing to do on your side; try again later.", m.state.value.chatErrors["refunds-1"])
    assertFalse(m.state.value.thinking("refunds-1"))
  }

  @Test fun writingTooManyMessagesTooFastIsSaidKindly() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread())
    transport.answer("POST /v1/tafs/taf_1/messages", 429, """{"error":"rate_limited"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("hello", "refunds-1"))
    assertEquals("That's a lot of messages in a few minutes. Give Refunds a moment, then try again.", m.state.value.chatErrors["refunds-1"])
  }

  @Test fun anEmptyOrTooLongMessageIsNotSent() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread())
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("   ", "refunds-1"))
    assertFalse(m.deliverChat("x".repeat(2001), "refunds-1"))
    assertTrue(transport.requests.none { it.method == "POST" && it.url.contains("/tafs/") })
    assertEquals("That's longer than 2000 characters. Say it in fewer words.", m.state.value.chatErrors["refunds-1"])
  }

  @Test fun anAgentTafsDoesNotRunCannotBeMessaged() = runTest {
    readsOf()
    val m = model()
    assertFalse(m.deliverChat("hello", "billing-x"))
    assertEquals("This taf isn't running on tafs cloud, so it can't be messaged.", m.state.value.chatErrors["billing-x"])
    assertTrue(transport.requests.none { it.method == "POST" && it.url.contains("/tafs/") })
  }

  @Test fun theSamplesChatStillAnswersFromItsOwnRecord() = runTest {
    val m = testModel(transport = transport)
    val id = m.state.value.tafs.first().id
    m.loadChat(id)
    assertTrue(m.deliverChat("what did you do", id))
    assertTrue(transport.requests.isEmpty())
  }

  // MARK: What the owner sent that the thread doesn't have yet

  private fun msg(text: String, from: ChatMessage.From, at: String) = ChatMessage(Instant.parse(at), from, text, "s")

  @Test fun aSentMessageGoesOnceTheThreadHasIt() {
    val sent = msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:00Z")
    val (all, waiting) = SentMessages.merge(listOf(msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:01Z")), listOf(sent), t0.plusSeconds(5))
    assertEquals(1, all.size)
    assertTrue(waiting.isEmpty())
  }

  @Test fun aSentMessageTheThreadDoesNotHaveYetStaysAfterTheThreadsMessages() {
    val sent = msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:00Z")
    val (all, waiting) = SentMessages.merge(listOf(msg("Hola", ChatMessage.From.taf, "2026-10-28T08:59:00Z")), listOf(sent), t0.plusSeconds(5))
    assertEquals(listOf("Hola", "Sí"), all.map { it.text })
    assertEquals(listOf(sent), waiting)
  }

  @Test fun twoMessagesSentCloseTogetherStayTwoUntilTheThreadHasBoth() {
    val a = msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:00Z")
    val b = msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:02Z")
    val one = listOf(msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:03Z"))
    val (all, waiting) = SentMessages.merge(one, listOf(a, b), t0.plusSeconds(10))
    assertEquals(2, all.size)
    assertEquals(listOf(b), waiting)
    val both = one + msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:04Z")
    assertTrue(SentMessages.merge(both, listOf(a, b), t0.plusSeconds(10)).second.isEmpty())
  }

  @Test fun aSentMessageNobodyHasAfterTwoMinutesIsGiven() {
    val sent = msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:00Z")
    assertTrue(SentMessages.merge(emptyList(), listOf(sent), t0.plusSeconds(121)).second.isEmpty())
    assertEquals(1, SentMessages.merge(emptyList(), listOf(sent), t0.plusSeconds(119)).second.size)
  }

  @Test fun aTafsOwnWordsNeverTakeTheOwnersMessagePlace() {
    val sent = msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:00Z")
    val (_, waiting) = SentMessages.merge(listOf(msg("Sí", ChatMessage.From.taf, "2026-10-28T09:00:01Z")), listOf(sent), t0.plusSeconds(5))
    assertEquals(1, waiting.size)
  }
}

private object KiffClientJson {
  fun parse(bytes: ByteArray) = bot.tafs.app.kiff.KiffClient.json.parseToJsonElement(String(bytes)).jsonObject
}
