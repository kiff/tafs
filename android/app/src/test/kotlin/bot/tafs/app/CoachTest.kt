package bot.tafs.app

import bot.tafs.app.kiff.SetupStep
import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.SetupQuestion
import bot.tafs.app.kiff.SetupChoice
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.ChatItem
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.CoachAnswer
import bot.tafs.app.model.CoachWords
import bot.tafs.app.model.QuestionAnswers
import bot.tafs.app.model.QuestionSelection
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coach, the account's Setup agent: its chat with its cards, and the owner's answers to them. A fake KIFF and tafs cloud
 * stand in: no real Coach conversation is touched.
 */
class CoachTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val conversation = "GET /v1/tafs/setup_1/conversation"

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay","capabilities":["members.manage"]}"""
  private val home = """{"tafs":[],"others":[],"removing":[]}"""

  private fun reads(me: String = this.me, access: String = """{"agents":{}}""") {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, access)
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  private fun msg(from: String, text: String, at: String, session: String = "s1") =
    """{"at":"$at","from":"$from","text":${kotlinx.serialization.json.JsonPrimitive(text)},"session":"$session"}"""

  private fun shown(component: String, at: String, session: String = "s1", run: String = "r1") =
    """{"at":"$at","session":"$session","run":"$run","component":$component}"""

  private fun thread(messages: List<String>, components: List<String> = emptyList()) =
    """{"messages":[${messages.joinToString(",")}],"components":[${components.joinToString(",")}]}"""

  // MARK: Who may use Coach

  @Test fun anAdminMayUseCoachByDefaultAndKiffsAnswerForTheSetupAgentDecides() = runTest {
    reads()
    assertTrue("members.manage is the default", model().state.value.mayUseCoach)
    val other = FakeTransport().also {
      it.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["holds.answer"]}""")
      it.answer("GET /v1/home", 200, home); it.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
      it.answer("GET /v1/me/activity", 200, """{"calls":[]}"""); it.answer("GET /v1/me/access", 200, """{"agents":{"setup":{"use":true}}}""")
    }
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    val m = testModel(transport = other, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
    assertTrue("KIFF says this person may use it", m.state.value.mayUseCoach)
  }

  @Test fun aPersonKiffDoesNotLetUseCoachSeesNoCoach() = runTest {
    reads(access = """{"agents":{"setup":{"use":false}}}""")
    assertFalse(model().state.value.mayUseCoach)
  }

  @Test fun theSampleHasNoCoach() = runTest {
    assertFalse(testModel(transport = transport).state.value.mayUseCoach)
    assertFalse(AppState(mode = Mode.signedOut).mayUseCoach)
  }

  @Test fun anAccountThatSaysNothingAndHasNoCapabilityGetsNoCoach() {
    val s = AppState(mode = Mode.kiff, kiffAccount = Account("t", "acme", capabilities = listOf("holds.answer")))
    assertFalse(s.mayUseCoach)
    assertTrue(s.copy(access = mapOf("setup" to Access(use = true))).mayUseCoach)
  }

  // MARK: Finding Coach

  @Test fun coachIsFoundOnTafsCloudOnceStarted() = runTest {
    reads()
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach"}}""")
    val m = model()
    m.loadCoach()
    assertEquals("setup_1", m.state.value.coachCloudId)
    assertTrue(m.state.value.coachChecked)
    assertEquals("https://cloud.tafs.kiff.dev/v1/setup", transport.requestsTo("GET /v1/setup").single().url)
    val shown = m.state.value.taf(COACH_CHAT)!!
    assertEquals("Coach", shown.name)
    assertEquals("setup_1", shown.cloudId)
    assertTrue(m.state.value.canChat(COACH_CHAT))
  }

  @Test fun coachNotStartedYetIsSaidAsSuchAndHasNoChat() = runTest {
    reads()
    transport.answer("GET /v1/setup", 200, """{"setup":null}""")
    val m = model()
    m.loadCoach()
    assertNull(m.state.value.coachCloudId)
    assertTrue(m.state.value.coachChecked)
    assertNull(m.state.value.taf(COACH_CHAT))
    assertFalse(m.state.value.canChat(COACH_CHAT))
  }

  @Test fun coachThatCannotBeReachedSaysSo() = runTest {
    reads()
    transport.answer("GET /v1/setup", 502, """{"error":"later"}""")
    val m = model()
    m.loadCoach()
    assertTrue(m.state.value.coachChecked)
    assertEquals("Coach couldn't be reached. It isn't working on our side right now. Nothing to do on your side; try again later.", m.state.value.chatErrors[COACH_CHAT])
  }

  // MARK: Its thread

  private val plan = """{"type":"steps","id":"plan","title":"Plan","steps":[{"label":"Connect Stripe","status":"done"},{"label":"Draft the Card","status":"active"},{"label":"Start the taf","status":"pending"}]}"""
  private val notice = """{"type":"notice","id":"n1","title":"Heads up","text":"Test mode only.","tone":"warning"}"""
  private val confirm = """{"type":"confirm","id":"c1","title":"Refund up to?","text":"Pick one.","options":[{"value":"50","label":"50 a refund"},{"value":"100","label":"100 a refund"}]}"""
  private val questions = """{"type":"questions","id":"q1","title":"A few questions","questions":[
      {"id":"mode","text":"Which mode?","options":[{"value":"test","label":"Test mode"},{"value":"live","label":"Live"}]},
      {"id":"which","text":"Which refunds?","multi":true,"options":[{"value":"small","label":"Small"},{"value":"big","label":"Big"}]}]}"""
  private val approval = """{"type":"approval","id":"a1","grant":"g1","service":"mcp.stripe.com","title":"Connect Stripe","tools":["list_charges"]}"""

  private suspend fun coach(thread: String): AppModel {
    reads()
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach"}}""")
    transport.answer(conversation, 200, thread)
    val m = model()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    return m
  }

  @Test fun coachsThreadIsReadFromItsOwnConversationWithItsCards() = runTest {
    val m = coach(thread(listOf(msg("taf", "Let's plan it.", "2026-10-28T08:00:00Z")), listOf(shown(plan, "2026-10-28T08:00:01Z"), shown(notice, "2026-10-28T08:00:02Z"))))
    assertEquals(listOf("Let's plan it."), m.state.value.chats[COACH_CHAT]!!.map { it.text })
    assertEquals(listOf("plan", "n1"), m.state.value.coachComponents.map { it.id })
    assertEquals("https://cloud.tafs.kiff.dev/v1/tafs/setup_1/conversation", transport.requestsTo(conversation).single().url)
  }

  @Test fun aCardTheAppDoesNotKnowOrCannotReadIsLeftOutAndTheThreadStillReads() = runTest {
    val m = coach(thread(
      listOf(msg("taf", "Hello", "2026-10-28T08:00:00Z")),
      listOf(shown("""{"type":"hologram","id":"x"}""", "2026-10-28T08:00:01Z"), shown("""{"type":"notice"}""", "2026-10-28T08:00:02Z"), shown(notice, "2026-10-28T08:00:03Z"), """{"nonsense":true}"""),
    ))
    assertEquals(listOf("n1"), m.state.value.coachComponents.map { it.id })
    assertEquals(1, m.state.value.chats[COACH_CHAT]!!.size)
  }

  @Test fun aCardShownAgainReplacesItInPlaceAtWhereItFirstAppeared() = runTest {
    val changed = confirm.replace("Refund up to?", "Refund up to what?")
    val m = coach(thread(emptyList(), listOf(shown(confirm, "2026-10-28T08:00:01Z"), shown(notice, "2026-10-28T08:00:02Z"), shown(changed, "2026-10-28T08:05:00Z"))))
    val cards = m.state.value.coachComponents
    assertEquals(listOf("c1", "n1"), cards.map { it.id })
    assertEquals("Refund up to what?", cards[0].component.title)
    assertEquals(Instant.parse("2026-10-28T08:00:01Z"), cards[0].firstAt)
  }

  @Test fun thePlanIsPinnedAndNotInTheThread() = runTest {
    val m = coach(thread(listOf(msg("taf", "Here is the plan.", "2026-10-28T08:00:00Z")), listOf(shown(plan, "2026-10-28T08:00:01Z"), shown(notice, "2026-10-28T08:00:02Z"))))
    val pinned = CoachWords.plan(m.state.value.coachComponents)!!
    assertEquals(listOf("done", "active", "pending"), pinned.component.steps!!.map { it.status })
    val items = m.state.value.chatItems(COACH_CHAT)
    assertTrue(items.none { it is ChatItem.Card && it.shown.id == "plan" })
    assertTrue(items.any { it is ChatItem.Card && it.shown.id == "n1" })
  }

  @Test fun aCardThatAsksComesAfterWhatCoachWroteWithIt() = runTest {
    val m = coach(thread(
      listOf(msg("taf", "Which limit?", "2026-10-28T08:00:05Z"), msg("owner", "hello", "2026-10-28T07:59:00Z")),
      listOf(shown(confirm, "2026-10-28T08:00:01Z")),
    ))
    val kinds = m.state.value.chatItems(COACH_CHAT).map { if (it is ChatItem.Card) "card" else (it as ChatItem.Message).message.text }
    assertEquals(listOf("hello", "Which limit?", "card"), kinds)
  }

  @Test fun aNoticeKeepsItsOwnTimeAndAnOwnersAnswerBetweenSplitsTheWords() = runTest {
    val m = coach(thread(
      listOf(msg("taf", "Before", "2026-10-28T08:00:00Z"), msg("taf", "After", "2026-10-28T08:10:00Z")),
      listOf(shown(notice, "2026-10-28T08:05:00Z")),
    ))
    val kinds = m.state.value.chatItems(COACH_CHAT).map { if (it is ChatItem.Card) "card" else (it as ChatItem.Message).message.text }
    assertEquals(listOf("Before", "card", "After"), kinds)
  }

  // MARK: Answering

  @Test fun anAnswerToACardIsOneMessageInTheContractsForm() = runTest {
    val m = coach(thread(emptyList(), listOf(shown(confirm, "2026-10-28T08:00:01Z"))))
    transport.answer("POST /v1/tafs/setup_1/messages", 200, """{"session":"s2","new_session":false}""")
    assertTrue(m.deliverChat(CoachAnswer.message("confirm", "c1", "50"), COACH_CHAT))
    val text = KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/tafs/setup_1/messages").single().body!!)).jsonObject.getValue("text").jsonPrimitive.content
    assertEquals("[confirm c1] 50", text)
  }

  @Test fun theOwnersAnswerReadsAsWhatTheyChoseAndNeverAsTheContractsForm() = runTest {
    val m = coach(thread(
      listOf(
        msg("owner", "[confirm c1] 100", "2026-10-28T08:01:00Z"),
        msg("owner", """[questions q1] {"mode":{"value":"test"},"which":{"values":["small","big"]}}""", "2026-10-28T08:02:00Z"),
        msg("owner", "[approval a1] approved", "2026-10-28T08:03:00Z"),
        msg("owner", "[approval a1] declined", "2026-10-28T08:04:00Z"),
        msg("owner", "[approval a1] failed: planque said no", "2026-10-28T08:05:00Z"),
        msg("owner", "[sign_in s1] signed in", "2026-10-28T08:06:00Z"),
        msg("owner", "[sign_in s1] not now", "2026-10-28T08:07:00Z"),
        msg("owner", "plain words", "2026-10-28T08:08:00Z"),
      ),
      listOf(shown(confirm, "2026-10-28T08:00:01Z"), shown(questions, "2026-10-28T08:00:02Z")),
    ))
    val words = m.state.value.chatItems(COACH_CHAT).filterIsInstance<ChatItem.Message>().map { it.message.text }
    assertEquals(
      listOf("100 a refund", "Test mode · Small, Big", "Approve", "Decline", "Approve · it didn't go through", "Signed in", "Not now", "plain words"),
      words,
    )
  }

  @Test fun aCardsAnswerReadsInKiffAgentsWordsWrittenInsteadOrNone() {
    assertEquals("Approved", CoachWords.answered(bot.tafs.app.kiff.CardAnswer(state = "answered", words = "Approved")))
    assertEquals("Approve · it didn't go through: closed", CoachWords.answered(bot.tafs.app.kiff.CardAnswer(words = "Approve · it didn't go through: closed")))
    assertEquals("You answered in your own words.", CoachWords.answered(bot.tafs.app.kiff.CardAnswer(state = "wrote", words = "Use live")))
    assertEquals("It stopped waiting, with no answer.", CoachWords.answered(bot.tafs.app.kiff.CardAnswer(state = "no_answer")))
    assertEquals("Answered.", CoachWords.answered(bot.tafs.app.kiff.CardAnswer()))
  }

  @Test fun anAnswerIsParsedByItsTypeAndId() {
    assertEquals(CoachAnswer("confirm", "c1", "50"), CoachAnswer.parse("[confirm c1] 50"))
    assertEquals(CoachAnswer("questions", "q 1", "{}"), CoachAnswer.parse("[questions q 1] {}"))
    assertNull("not a card's type", CoachAnswer.parse("[hologram h1] x"))
    assertNull(CoachAnswer.parse("[confirm] x"))
    assertNull(CoachAnswer.parse("hello"))
    assertTrue(CoachAnswer.parse("[approval a1] failed: no")!!.failed)
    assertFalse(CoachAnswer.parse("[approval a1] approved")!!.failed)
  }

  // MARK: Questions

  private val which = SetupQuestion("which", "Which refunds?", listOf(SetupChoice("small", "Small"), SetupChoice("big", "Big")), multi = true)
  private val mode = SetupQuestion("mode", "Which mode?", listOf(SetupChoice("test", "Test mode"), SetupChoice("live", "Live")))

  @Test fun answersAreOneJsonMessageWithSortedKeys() {
    val text = QuestionAnswers.encode(mapOf(
      "which" to QuestionAnswers.Answer.Choices(listOf("small", "big")),
      "mode" to QuestionAnswers.Answer.Choice("test"),
      "note" to QuestionAnswers.Answer.Own("300 on weekdays"),
    ))
    assertEquals("""{"mode":{"value":"test"},"note":{"own":"300 on weekdays"},"which":{"values":["small","big"]}}""", text)
    assertEquals(
      mapOf(
        "which" to QuestionAnswers.Answer.Choices(listOf("small", "big")),
        "mode" to QuestionAnswers.Answer.Choice("test"),
        "note" to QuestionAnswers.Answer.Own("300 on weekdays"),
      ),
      QuestionAnswers.decode(text),
    )
    assertNull(QuestionAnswers.decode("not json"))
  }

  @Test fun theAnswersReadInTheCardsOrderWithTheOwnersOwnWordsQuoted() {
    val text = QuestionAnswers.encode(mapOf(
      "which" to QuestionAnswers.Answer.Choices(listOf("small", "big")),
      "mode" to QuestionAnswers.Answer.Choice("test"),
      "extra" to QuestionAnswers.Answer.Own("on weekdays"),
    ))
    assertEquals("Test mode · Small, Big · In your words: “on weekdays”", QuestionAnswers.words(text, listOf(mode, which)))
    assertNull(QuestionAnswers.words("nope", listOf(mode)))
    assertEquals("an answer to a question the card no longer has reads by its value", "live", QuestionAnswers.words("""{"gone":{"value":"live"}}""", listOf(mode)))
  }

  @Test fun aSingleQuestionTakesOnePickAndAMultiQuestionSeveralInTheAgentsOrder() {
    var s = QuestionSelection()
    s = s.pick(mode, "test").pick(mode, "live")
    assertEquals(QuestionAnswers.Answer.Choice("live"), s.answer(mode))
    s = s.pick(which, "big").pick(which, "small")
    assertEquals(QuestionAnswers.Answer.Choices(listOf("small", "big")), s.answer(which))
    s = s.pick(which, "small")
    assertEquals(QuestionAnswers.Answer.Choices(listOf("big")), s.answer(which))
    s = s.pick(which, "big")
    assertNull("nothing picked is no answer", s.answer(which))
  }

  @Test fun somethingElseReplacesThePicksAndPickingClosesIt() {
    var s = QuestionSelection().pick(mode, "test").toggleOwn(mode)
    assertNull("opened with nothing written", s.answer(mode))
    s = s.write(mode, "  both  ")
    assertEquals(QuestionAnswers.Answer.Own("both"), s.answer(mode))
    s = s.pick(mode, "live")
    assertEquals(QuestionAnswers.Answer.Choice("live"), s.answer(mode))
    assertFalse(mode.id in s.ownOpen)
    assertNull(QuestionSelection().toggleOwn(mode).write(mode, "   ").answer(mode))
  }

  @Test fun theCardSendsOnlyOnceEveryQuestionIsAnswered() {
    val questions = listOf(mode, which)
    var s = QuestionSelection().pick(mode, "test")
    assertFalse(s.complete(questions))
    s = s.pick(which, "big")
    assertTrue(s.complete(questions))
    assertEquals("""{"mode":{"value":"test"},"which":{"values":["big"]}}""", s.message(questions))
    assertFalse(QuestionSelection().complete(emptyList()))
  }

  // MARK: Words

  @Test fun theStepsKeepTheirMarks() {
    assertEquals("✓", CoachWords.stepMark("done"))
    assertEquals("●", CoachWords.stepMark("active"))
    assertEquals("✕", CoachWords.stepMark("failed"))
    assertEquals("–", CoachWords.stepMark("skipped"))
    assertEquals("○", CoachWords.stepMark("pending"))
    assertEquals("○", CoachWords.stepMark("something new"))
  }

  @Test fun theFoldedPlanShowsTheStepItIsAt() {
    fun at(vararg s: String) = CoachWords.currentStep(s.mapIndexed { i, st -> SetupStep("s$i", st) })?.label
    assertEquals("s1", at("done", "active", "pending"))
    assertEquals("s1", at("done", "pending", "pending"))
    assertEquals("s1", at("done", "failed", "pending"))
    assertEquals("s1", at("done", "done"))
    assertEquals(null, at())
  }

  @Test fun aSignInGoesWhereTheCardsServiceSaysNeverWhereItsWordsSay() {
    val c = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"sign_in","id":"s","service":"mcp.stripe.com","title":"Go to evil.example"}""")
    assertEquals("mcp.stripe.com", c.destination)
    assertEquals("the vendor's page", KiffClient.json.decodeFromString<SetupComponent>("""{"type":"sign_in","id":"s"}""").destination)
  }

  @Test fun theHandOverNamesTheTaf() {
    val c = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"result","id":"r","taf":{"agent":"refunds-1","name":"Refunds","job":"refunds","improves":"taf_1","note":"Raises the limit","brand_new":1}}""")
    assertEquals("Refunds", c.taf!!.name)
    assertEquals("taf_1", c.taf!!.improves)
    assertNotNull(ShownComponent(t0, "s", "r", c).component.taf)
  }
}
