package bot.tafs.app

import kotlinx.coroutines.flow.toList
import bot.tafs.app.kiff.StreamEvent
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
import kotlinx.coroutines.test.advanceUntilIdle
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

  /** A taf hands a setting to Coach with an `ask_coach` card (kiff/tafs#476, gap 15): it's in its chat at its time, and no other card is. */
  @Test fun aTafsAskCoachCardIsInItsChatAndNoOtherCardIs() = runTest {
    readsOf()
    val card = { type: String, id: String, at: String -> """{"at":"$at","session":"s1","run":"r1","component":{"type":"$type","id":"$id","text":"Send them from billing@acme.test"}}""" }
    transport.answer(conversation, 200,
      """{"messages":[${owner("send from billing", "2026-10-28T08:00:00Z")},${taf("That's Coach's to change.", "2026-10-28T08:00:05Z")}],
          "components":[${card("ask_coach", "ask_1", "2026-10-28T08:00:06Z")},${card("notice", "n_1", "2026-10-28T08:00:07Z")}]}""")
    val m = model()
    m.loadChat("refunds-1")
    val items = m.state.value.chatItems("refunds-1")
    assertEquals(listOf("send from billing", "That's Coach's to change."), items.filterIsInstance<ChatItem.Message>().map { it.message.text })
    val cards = items.filterIsInstance<ChatItem.Card>()
    assertEquals(listOf("ask_1"), cards.map { it.shown.component.id })
    assertEquals("Send them from billing@acme.test", cards.single().shown.component.text)
    assertTrue("after the taf's words", items.indexOf(cards.single()) > items.indexOfFirst { it is ChatItem.Message && it.message.from == ChatMessage.From.taf })
    // Coach's own chat keeps its cards apart: none of a taf's.
    assertTrue(m.state.value.coachComponents.isEmpty())
  }

  /**
   * A run that read one list page after page waits on the loop card (kiff/agents' `confirm` with `loop`): it's in the taf's own chat,
   * in tafs cloud's words, and Read on goes back to that run as the card's answer, through the taf's route, never Coach's.
   */
  @Test fun theLoopCardIsInTheTafsChatAndReadOnAnswersItsRun() = runTest {
    readsOf()
    val loop = """{"at":"2026-10-28T08:00:06Z","session":"s1","run":"r1","component":{"type":"confirm","id":"tafs-loop-12","waits":"owner","loop":{"tool":"list_disputes","pages":10},""" +
      """"title":"Still reading","text":"Read 10 pages of List disputes, and there's more.","options":[{"label":"Read on","value":"tafs_loop_continue"},{"label":"Stop here","value":"tafs_loop_summarise"}]}}"""
    val plain = """{"at":"2026-10-28T08:00:07Z","session":"s1","run":"r1","component":{"type":"confirm","id":"c1","options":[{"label":"Yes","value":"yes"}]}}"""
    transport.answer(conversation, 200, """{"messages":[${owner("open disputes?", "2026-10-28T08:00:00Z")}],"components":[$loop,$plain]}""")
    transport.answer(conversation, 200, """{"messages":[${owner("open disputes?", "2026-10-28T08:00:00Z")}],"components":[${loop.dropLast(1)},"answer":{"state":"answered","words":"Read on"}}]}""")
    transport.answer("POST /v1/tafs/taf_1/components/tafs-loop-12/answer", 200, """{"session":"s1","run":"r1","status":"running"}""")
    transport.answer("GET /v1/tafs/taf_1/runs/r1/stream", 200, "event: end\ndata: {\"status\":\"COMPLETED\",\"stop\":\"end_turn\"}\n")
    val m = model()
    m.loadChat("refunds-1")
    val cards = m.state.value.chatItems("refunds-1").filterIsInstance<ChatItem.Card>()
    // Only the loop card: a confirm without `loop` is Coach's, not the taf's.
    assertEquals(listOf("tafs-loop-12"), cards.map { it.shown.component.id })
    assertEquals("Still reading", cards.single().shown.component.title)
    assertEquals(listOf("Read on", "Stop here"), cards.single().shown.component.options.orEmpty().map { it.label })
    assertTrue(m.answerCard("confirm", "tafs-loop-12", "tafs_loop_continue", chat = "refunds-1"))
    repeat(20) { testScheduler.advanceTimeBy(100); testScheduler.runCurrent() }
    assertEquals(
      """{"answer":"tafs_loop_continue","session":"s1"}""",
      String(transport.requestsTo("POST /v1/tafs/taf_1/components/tafs-loop-12/answer").single().body!!),
    )
    assertTrue("never Coach's route", transport.requests.none { it.url.contains("/setup") && it.url.contains("/components/") })
    assertEquals("Read on", m.state.value.tafCards["refunds-1"]?.single()?.answer?.let(bot.tafs.app.model.CoachWords::answered))
    assertTrue(m.state.value.coachComponents.isEmpty())
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
    assertEquals(listOf("bxkwf7vi_list_charges", "create_refund", "x"), answer.work.steps.map { it.tool })
    assertEquals(listOf(false, true, false), answer.work.steps.map { it.error })
    assertEquals(listOf(false, false, true), answer.work.steps.map { it.notMade })
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
    // tafs cloud holds what the run doesn't take (docs/chat.md): the app sends it once.
    assertEquals("true", body.getValue("hold").jsonPrimitive.content)
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
  }

  // MARK: What the run doesn't take, held by tafs cloud (docs/chat.md)

  private val steer = "POST /v1/tafs/taf_1/steer"
  private val held = """{"text":"one more","at":"2026-10-28T09:00:05Z"}"""
  private fun steering(json: String) = ""","steering":$json"""

  @Test fun aMessageTheRunDidntTakeWaitsAtTafsCloudShownAsWaitingNeverResentNorShownAsSent() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = working))
    transport.answer(steer, 202, """{"held":$held}""")
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = working + steering("""{"run":"run_1","status":"RUNNING","untaken":[],"held":$held}""")))
    val m = model()
    m.loadChat("refunds-1")
    assertTrue(m.deliverChat("one more", "refunds-1"))
    assertEquals(1, transport.requestsTo(steer).size)
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages/stream").isEmpty())
    assertEquals("one more", m.state.value.chatSteering["refunds-1"]?.held?.text)
    assertTrue("shown as what waits, not as sent", m.messages().none { it.text == "one more" })
    assertTrue(m.state.value.chatSent["refunds-1"].isNullOrEmpty())
  }

  @Test fun aMessageTafsCloudSentAtOnceAsTheNextMessageIsTheThreadsNotSentAgain() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = working))
    transport.answer(steer, 202, """{"sent":{"run":"run_2","session":"s1"}}""")
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), owner("one more", "2026-10-28T09:00:05Z"), working = ""","working":{"session":"s1","run":"run_2"}"""))
    val m = model()
    m.loadChat("refunds-1")
    assertTrue(m.deliverChat("one more", "refunds-1"))
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages/stream").isEmpty())
    assertEquals("once, as the thread has it", 1, m.messages().count { it.text == "one more" })
    assertNull(m.state.value.chatSteering["refunds-1"])
  }

  @Test fun asMuchWaitingAsOneMessageCanBeSaysTafsCloudsWordsAndGivesTheWordsBackForLater() = runTest {
    readsOf()
    val full = "Up to 10 messages, 2,000 characters in all, can wait for the taf. Send this once it's done."
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = working))
    transport.answer(steer, 409, """{"error":"$full","code":"held_full","held":$held}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("and this", "refunds-1"))
    assertEquals(full, m.state.value.chatErrors["refunds-1"])
    // Not a "Not sent" row: the words go back to the box (sendChat's onNotSent), and nothing goes as a new message.
    assertNull(m.state.value.chatUnsent["refunds-1"])
    assertTrue(m.messages().none { it.text == "and this" })
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/messages/stream").isEmpty())
  }

  @Test fun aMessageRefusedForCreditsShowsTafsCloudsWordsAndGoesBackToTheBoxWithNoRetry() = runTest {
    val waits = "Your tafs are waiting: the spend limit is reached. An admin of this account can raise it."
    for (code in listOf("credits_used_up", "limit_reached", "no_plan")) {
      readsOf()
      transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z")))
      val refusal = """{"error":"waiting","code":"$code","words":"$waits"}"""
      transport.answer("POST /v1/tafs/taf_1/messages/stream", 409, refusal)
      transport.answer("POST /v1/tafs/taf_1/messages", 409, refusal)
      val m = model()
      m.loadChat("refunds-1")
      assertFalse(m.deliverChat("refund order 4402", "refunds-1"))
      assertEquals(code, waits, m.state.value.chatErrors["refunds-1"])
      // Not a "Not sent" row, so no Retry: sending again changes nothing until the tafs go on, and the words say how.
      assertNull(code, m.state.value.chatUnsent["refunds-1"])
      assertTrue(code, m.messages().none { it.text == "refund order 4402" })
    }
  }

  private val why = "You stopped it before it read this."
  private fun stopped(vararg texts: String) = steering(
    """{"run":"run_1","status":"CANCELLED","untaken":[],"not_sent":[${texts.joinToString(",") { """{"text":"$it","at":"2026-10-28T09:00:05Z","why":"$why"}""" }}]}""",
  )

  @Test fun whatAStoppedRunNeverReadBecomesItsOwnNotSentRowOnceInTafsCloudsWords() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = stopped("one more")))
    val m = model()
    m.loadChat("refunds-1")
    m.loadChat("refunds-1")
    val unsent = m.state.value.chatUnsent["refunds-1"]!!.single()
    assertEquals("one more", unsent.message.text)
    assertEquals(why, unsent.why)
    assertEquals("in the thread as written, once", 1, m.state.value.chatItems("refunds-1").count { (it as? ChatItem.Message)?.message?.text == "one more" })
    assertNull("nothing held: no waiting row", m.state.value.chatSteering["refunds-1"])
  }

  @Test fun editTakesANotSentRowForGoodEvenWhileTafsCloudStillListsIt() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = stopped("one more")))
    val m = model()
    m.loadChat("refunds-1")
    val unsent = m.state.value.chatUnsent["refunds-1"]!!.single()
    assertEquals("one more", m.editUnsent("refunds-1", unsent.message))
    m.loadChat("refunds-1")
    assertNull("remembered: it doesn't come back", m.state.value.chatUnsent["refunds-1"])
    // Nor after a new launch: the keys are kept, never the words.
    val again = model()
    again.loadChat("refunds-1")
    assertNull(again.state.value.chatUnsent["refunds-1"])
    assertFalse("one more" in secrets.load(AppModel.NOT_SENT_TAKEN).orEmpty())
  }

  @Test fun aNotSentRowNotYetTakenComesBackAfterARestartAndOneRetryTookDoesnt() = runTest {
    readsOf(); sent()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = stopped("one more")))
    val m = model()
    m.loadChat("refunds-1")
    assertEquals("one more", m.state.value.chatUnsent["refunds-1"]!!.single().message.text)
    // A restart before Retry or Edit: the same store, the same thread. The owner's words are back.
    val restarted = model()
    restarted.loadChat("refunds-1")
    val unsent = restarted.state.value.chatUnsent["refunds-1"]!!.single()
    assertEquals("one more", unsent.message.text)
    // Retry takes it, then another restart: it doesn't come back.
    assertTrue(restarted.resendUnsent("refunds-1", unsent.message))
    val again = model()
    again.loadChat("refunds-1")
    assertNull(again.state.value.chatUnsent["refunds-1"])
  }

  @Test fun deleteTakesANotSentRowForGoodAcrossARestartSendingNothing() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = stopped("one more")))
    val m = model()
    m.loadChat("refunds-1")
    val writes = transport.requests.count { it.method != "GET" }
    m.deleteUnsent("refunds-1", m.state.value.chatUnsent["refunds-1"]!!.single().message)
    assertEquals("nothing sent", writes, transport.requests.count { it.method != "GET" })
    assertNull(m.state.value.chatUnsent["refunds-1"])
    m.loadChat("refunds-1")
    assertNull("remembered: tafs cloud still lists it, and it doesn't come back", m.state.value.chatUnsent["refunds-1"])
    val restarted = model()
    restarted.loadChat("refunds-1")
    assertNull(restarted.state.value.chatUnsent["refunds-1"])
  }

  @Test fun deleteTakesAMessageThatWasntSentOffTheThread() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread())
    transport.answer("POST /v1/tafs/taf_1/messages", 503, """{"error":"unavailable"}""")
    transport.answer("POST /v1/tafs/taf_1/messages/stream", 503, """{"error":"unavailable"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("refund this one", "refunds-1"))
    val writes = transport.requests.count { it.method != "GET" }
    m.deleteUnsent("refunds-1", m.state.value.chatUnsent["refunds-1"]!!.single().message)
    assertNull(m.state.value.chatUnsent["refunds-1"])
    assertEquals("nothing sent", writes, transport.requests.count { it.method != "GET" })
  }

  @Test fun aRetryThatDidntSendLeavesTheNotSentRowOwedSoARestartBringsItBack() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = stopped("one more")))
    transport.answer("POST /v1/tafs/taf_1/messages", 503, """{"error":"unavailable"}""")
    transport.answer("POST /v1/tafs/taf_1/messages/stream", 503, """{"error":"unavailable"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.resendUnsent("refunds-1", m.state.value.chatUnsent["refunds-1"]!!.single().message))
    // Still Not sent, once, with the not_sent's key carried on.
    val kept = m.state.value.chatUnsent["refunds-1"]!!.single()
    assertEquals("one more", kept.message.text)
    assertNotNull(kept.notSentKey)
    m.loadChat("refunds-1")
    assertEquals("not added twice", 1, m.state.value.chatUnsent["refunds-1"]!!.size)
    // And still owed after a restart.
    val restarted = model()
    restarted.loadChat("refunds-1")
    assertEquals("one more", restarted.state.value.chatUnsent["refunds-1"]!!.single().message.text)
  }

  @Test fun retrySendsANotSentRowOnceAndASecondRetryFindsNothing() = runTest {
    readsOf(); sent()
    transport.answer(conversation, 200, thread(owner("go", "2026-10-28T08:00:00Z"), working = stopped("one more", "and this")))
    val m = model()
    m.loadChat("refunds-1")
    val first = m.state.value.chatUnsent["refunds-1"]!!.first()
    assertTrue(m.resendUnsent("refunds-1", first.message))
    assertFalse(m.resendUnsent("refunds-1", first.message))
    m.loadChat("refunds-1")
    assertEquals(listOf("and this"), m.state.value.chatUnsent["refunds-1"]!!.map { it.message.text })
  }

  @Test fun aRunThatEndedMeanwhileTakesTheMessageAsANewOne() = runTest {
    // An older tafs cloud holds nothing: 409 with no code, and the message goes as a new one.
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

  private val spent = "Your tafs have used this period's AI and are waiting for you. It starts again on 1 Nov."

  @Test fun aRunKiffAgentsRefusedSaysWhyInTafsCloudsWordsOnTheStream() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread())
    transport.answer("POST /v1/tafs/taf_1/messages/stream", 409, """{"error":"refused","code":"ai_fair_use","words":"$spent"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("hello", "refunds-1"))
    // Never "It changed in the meantime": tafs cloud's words for why.
    assertEquals("The message wasn't sent. $spent", m.state.value.chatUnsent["refunds-1"]!!.single().why)
  }

  @Test fun aRunKiffAgentsRefusedSaysWhyInTafsCloudsWordsOnThePlainRoute() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread())
    transport.answer("POST /v1/tafs/taf_1/messages/stream", 404, """{"error":"not_found"}""")
    transport.answer("POST /v1/tafs/taf_1/messages", 409, """{"error":"refused","code":"halted","words":"Refunds is halted. Resume it from Halt to go on."}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("hello", "refunds-1"))
    assertEquals("The message wasn't sent. Refunds is halted. Resume it from Halt to go on.", m.state.value.chatUnsent["refunds-1"]!!.single().why)
  }

  /** A message that doesn't go stays in the thread as written, marked not sent with why (kiff/tafs#476): nothing is lost. */
  @Test fun aMessageThatDoesNotGoStaysMarkedNotSentAndSaysWhy() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread())
    transport.answer("POST /v1/tafs/taf_1/messages", 500, """{"error":"boom"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("hello", "refunds-1"))
    assertTrue(m.state.value.chatSent.isEmpty())
    val unsent = m.state.value.chatUnsent["refunds-1"]!!.single()
    assertEquals("hello", unsent.message.text)
    assertEquals(bot.tafs.app.model.Unsent.SESSION, unsent.message.session)
    assertEquals("The message wasn't sent. It isn't working on our side right now. Nothing to do on your side; try again later.", unsent.why)
    assertNull("said under the message, not above the box", m.state.value.chatErrors["refunds-1"])
    // The thread shows it where it was written, and a reload keeps it.
    assertTrue(m.state.value.chatItems("refunds-1").any { it is bot.tafs.app.model.ChatItem.Message && it.message == unsent.message })
    m.loadChat("refunds-1")
    assertEquals(listOf(unsent), m.state.value.chatUnsent["refunds-1"])
    assertFalse(m.state.value.thinking("refunds-1"))
  }

  /** Retry sends a message that wasn't sent once, and it leaves "Not sent"; Edit gives its words back and takes it away. */
  @Test fun retrySendsANotSentMessageOnceAndEditGivesItsWordsBack() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread())
    transport.answer("POST /v1/tafs/taf_1/messages", 500, """{"error":"boom"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("hello", "refunds-1"))
    assertFalse(m.deliverChat("and this", "refunds-1"))
    val (first, second) = m.state.value.chatUnsent["refunds-1"]!!.map { it.message }
    transport.answer("POST /v1/tafs/taf_1/messages", 200, """{"session":"s1","run":"r1","message":{"id":"m_9"}}""")
    assertTrue(m.resendUnsent("refunds-1", first))
    assertFalse(m.resendUnsent("refunds-1", first)) // a second tap finds nothing left to send
    assertEquals(3, transport.requestsTo("POST /v1/tafs/taf_1/messages").size) // two refused, then one
    assertEquals(listOf(second), m.state.value.chatUnsent["refunds-1"]!!.map { it.message })
    assertEquals("and this", m.editUnsent("refunds-1", second))
    assertNull(m.editUnsent("refunds-1", second))
    assertNull(m.state.value.chatUnsent["refunds-1"])
  }

  @Test fun writingTooManyMessagesTooFastIsSaidKindly() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread())
    transport.answer("POST /v1/tafs/taf_1/messages", 429, """{"error":"rate_limited"}""")
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("hello", "refunds-1"))
    assertEquals("That's a lot of messages in a few minutes. Give Refunds a moment, then try again.", m.state.value.chatUnsent["refunds-1"]!!.single().why)
  }

  @Test fun anEmptyOrTooLongMessageIsNotSent() = runTest {
    readsOf()
    transport.answer(conversation, 200, thread())
    val m = model()
    m.loadChat("refunds-1")
    assertFalse(m.deliverChat("   ", "refunds-1"))
    assertFalse(m.deliverChat("x".repeat(2001), "refunds-1"))
    assertTrue(transport.requests.none { it.method == "POST" && it.url.contains("/tafs/") })
    assertEquals("That's longer than 2,000 characters. Say it in fewer words.", m.state.value.chatErrors["refunds-1"])
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

  // MARK: The same message twice (phone check: opening Coach crashed on a repeated key)

  @Test fun twoOwnerMessagesWithTheSameWordsAndTimeButTheirOwnIdsAreTwoItems() {
    val a = msg("Set up a Refunds taf on Stripe", ChatMessage.From.owner, "2026-10-31T02:31:04Z").copy(serverId = "run_1.in")
    val b = a.copy(serverId = "run_2.in")
    assertFalse(a.id == b.id)
    val s = AppState(mode = Mode.kiff, chats = mapOf("refunds-1" to listOf(a, b)))
    assertEquals(2, s.chatItems("refunds-1").map { it.id }.toSet().size)
  }

  @Test fun theSameMessageTwiceInAThreadShowsOnceAndNeverRepeatsAKey() {
    val m = msg("Set up a Refunds taf on Stripe", ChatMessage.From.owner, "2026-10-31T02:31:04Z")
    for (chat in listOf("refunds-1", bot.tafs.app.app.COACH_CHAT)) {
      // From the thread twice, with and without tafs cloud's id: each is one item, and every key is used once.
      for (twice in listOf(listOf(m, m), listOf(m.copy(serverId = "run_1.in"), m.copy(serverId = "run_1.in")))) {
        val items = AppState(mode = Mode.kiff, chats = mapOf(chat to twice)).chatItems(chat)
        assertEquals(chat, 1, items.size)
        assertEquals(items.size, items.map { it.id }.toSet().size)
      }
    }
  }

  @Test fun aPendingCopyTheThreadHasByItsIdIsDroppedNotKeptBeside() {
    // The send answered its id; the thread has the message under it, at the server's time.
    val sent = msg("Set up a Refunds taf on Stripe", ChatMessage.From.owner, "2026-10-31T02:31:03Z").copy(serverId = "run_1.in")
    val thread = listOf(sent.copy(at = Instant.parse("2026-10-31T02:31:04Z")))
    val (all, waiting) = SentMessages.merge(thread, listOf(sent), Instant.parse("2026-10-31T02:31:10Z"))
    assertEquals(1, all.size)
    assertTrue(waiting.isEmpty())
  }

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

  @Test fun aSentMessageWithTafsCloudsIdIsFoundByItsIdNeverByItsWords() {
    // Sent with the id its send answered: an earlier "Sí" in the thread, with another id, isn't it.
    val sent = msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:00Z").copy(serverId = "run_2.in")
    val earlier = msg("Sí", ChatMessage.From.owner, "2026-10-28T09:00:01Z").copy(serverId = "run_1.in")
    val (all, waiting) = SentMessages.merge(listOf(earlier), listOf(sent), t0.plusSeconds(5))
    assertEquals(listOf(sent), waiting)
    assertEquals(2, all.size)
    // Its own copy, whatever its words came back as.
    val own = msg("Sí, gracias", ChatMessage.From.owner, "2026-10-28T09:00:02Z").copy(serverId = "run_2.in")
    assertTrue(SentMessages.merge(listOf(earlier, own), listOf(sent), t0.plusSeconds(5)).second.isEmpty())
  }

  @Test fun aSendTakesTheIdTafsCloudAnswersAndTheThreadsCopyByItReplacesIt() = runTest {
    readsOf()
    transport.answer("POST /v1/tafs/taf_1/messages/stream", 404, """{"error":"not_found"}""")
    transport.answer("POST /v1/tafs/taf_1/messages", 200, """{"session":"s1","new_session":false,"run":"run_2","message":{"id":"run_2.in","at":"2026-10-28T09:00:00Z"}}""")
    // The thread has an older "Sí" and, later, the one just sent, by its id.
    transport.answer(conversation, 200, """{"messages":[{"id":"run_1.in","at":"2026-10-28T08:00:00Z","from":"owner","text":"Sí","session":"s1"}]}""")
    transport.answer(conversation, 200, """{"messages":[{"id":"run_1.in","at":"2026-10-28T08:00:00Z","from":"owner","text":"Sí","session":"s1"},{"id":"run_2.in","at":"2026-10-28T09:00:00Z","from":"owner","text":"Sí","session":"s1"}]}""")
    val m = model()
    m.loadChat("refunds-1")
    assertTrue(m.deliverChat("Sí", "refunds-1"))
    // Marked with its id, it waited while the thread had only the older "Sí"; the second read has it, and it goes.
    m.loadChat("refunds-1")
    assertTrue(m.state.value.chatSent["refunds-1"].isNullOrEmpty())
    assertEquals(listOf("run_1.in", "run_2.in"), m.state.value.chats["refunds-1"].orEmpty().map { it.serverId })
  }

  @Test fun theStreamsSessionEventCarriesTheMessagesId() = runTest {
    val events = StreamEvent.parse(kotlinx.coroutines.flow.flowOf("event: session", "data: {\"session\":\"s1\",\"new_session\":false,\"run\":\"run_2\",\"message\":{\"id\":\"run_2.in\",\"at\":\"2026-10-28T09:00:00Z\"}}", "")).toList()
    assertEquals(listOf(StreamEvent.Session("s1", false, "run_2", "run_2.in")), events)
  }

  @Test fun aMessageThatAnsweredACardSaysWhich() = runTest {
    readsOf()
    transport.answer(conversation, 200, """{"messages":[{"id":"r.3","at":"2026-10-28T08:00:00Z","from":"owner","text":"Use live","session":"s1","answers":"limits"}]}""")
    val m = model()
    m.loadChat("refunds-1")
    assertEquals("limits", m.state.value.chats["refunds-1"]!!.single().answers)
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
