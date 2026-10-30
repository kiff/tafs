package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.Door
import bot.tafs.app.kiff.DoorKey
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Knock
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.DoorMark
import bot.tafs.app.model.DoorNames
import bot.tafs.app.model.DoorWords
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
 * A taf's doors: read, opened, closed, paired and removed, and the knocks of strangers answered. A fake KIFF and tafs
 * cloud stand in: no real door is opened or closed and no real knock is answered.
 */
class DoorsTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val doors = "GET /v1/tafs/taf_1/doors"
  private val base = "/v1/tafs/taf_1/doors"

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay","email":"you@company.example"}"""

  private fun home(offered: String = """,{"doors_offered":["email"]}""".removePrefix(",")) = ""
  private val homeBody =
    """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],
        "others":[{"name":"Billing bot","kiff_agent":"billing-x","color":2,"state":"idle","card":{"ids":["c9"],"active":true}}],"removing":[],"doors_offered":["email"]}"""

  private val doorJson =
    """{"id":"door_1","app":"email","kind":"email","label":"Email","open":true,"address":"refunds@tafs.example","proof":"proven",
        "how":"It checks the sender's server signed the message",
        "keys":[{"id":"you@company.example","name":"You","auto":true},{"id":"ana@company.example","name":"Ana"}],
        "today":3,"limit":20,"resting":false,"others_today":1,"others_limit":10}"""

  private val knockJson =
    """{"id":"k1","door":"door_1","app":"email","from":"mara@customer.example","name":"Mara","proof":"pass","subject":"Refund request",
        "text":"Hi, I'd like 15 back on order T-3001.","at":"2026-10-28T08:50:00Z","state":"open"}"""

  private val apps =
    """[{"id":"email","label":"Email","kind":"email","proof":"proven","how":"It checks the sender's server signed the message"},
        {"id":"whatsapp","label":"WhatsApp","kind":"whatsapp","proof":"proven","how":"WhatsApp gives the sender's number"}]"""

  private fun doorsBody(doors: String = doorJson, knocks: String = knockJson, apps: String = this.apps) =
    """{"doors":[$doors],"apps":$apps,"knocks":[$knocks]}"""

  private fun reads(manage: Boolean = true, home: String = homeBody) {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":$manage}}}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  private fun sent(key: String) = transport.requestsTo(key)

  private fun body(key: String) = KiffClient.json.parseToJsonElement(String(sent(key).single().body!!)).jsonObject

  // MARK: Where doors show

  @Test fun doorsShowOnlyWhereTafsCloudOffersOneForATafThisPersonManages() = runTest {
    reads()
    val s = model().state.value
    assertEquals(listOf("email"), s.doorsOffered)
    assertTrue(s.hasDoors("refunds-1"))
    assertFalse("an agent tafs doesn't run", s.hasDoors("billing-x"))
    assertFalse(s.hasDoors("nobody"))
  }

  @Test fun aPersonWhoOnlyUsesTheTafSeesNoDoors() = runTest {
    reads(manage = false)
    assertFalse(model().state.value.hasDoors("refunds-1"))
  }

  @Test fun aDeploymentThatOffersNoDoorShowsNone() = runTest {
    reads(home = homeBody.replace(""","doors_offered":["email"]""", ""))
    val s = model().state.value
    assertTrue(s.doorsOffered.isEmpty())
    assertFalse(s.hasDoors("refunds-1"))
  }

  // MARK: Reading

  @Test fun theDoorsTheAppsAndTheKnocksAreRead() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    val m = model()
    m.loadDoors("refunds-1")
    val d = m.state.value.doors["refunds-1"]!!
    assertTrue(d.loaded)
    assertEquals(listOf("door_1"), d.doors.map { it.id })
    assertEquals(listOf("email", "whatsapp"), d.apps.map { it.id })
    assertEquals(listOf("k1"), d.openKnocks.map { it.id })
    assertEquals("Bearer at_1", sent(doors).single().headers["Authorization"])
    assertEquals("https://cloud.tafs.kiff.dev/v1/tafs/taf_1/doors", sent(doors).single().url)
  }

  @Test fun oneDoorOfEachAppPerTafSoTheAddableAreTheOnesWithNone() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    val m = model()
    m.loadDoors("refunds-1")
    assertEquals(listOf("whatsapp"), m.state.value.doors["refunds-1"]!!.addable.map { it.id })
  }

  @Test fun aReadThatFailsIsTriedAgainQuietly() = runTest {
    reads()
    transport.answer(doors, 500, """{"error":"asleep"}""")
    transport.answer(doors, 200, doorsBody())
    val m = model()
    m.loadDoors("refunds-1")
    assertEquals(2, sent(doors).size)
    assertTrue(m.state.value.doors["refunds-1"]!!.loaded)
    assertNull(m.state.value.doors["refunds-1"]!!.error)
  }

  @Test fun aReadThatKeepsFailingSaysSoInPlainWords() = runTest {
    reads()
    transport.answer(doors, 500, """{"error":"down"}""")
    val m = model()
    m.loadDoors("refunds-1")
    assertEquals("one read and two more tries", 3, sent(doors).size)
    val d = m.state.value.doors["refunds-1"]!!
    assertFalse(d.loaded)
    assertEquals("The doors couldn't be read just now. They're tried again when you come back here.", d.error)
  }

  // MARK: Opening, closing, adding, removing

  @Test fun aDoorIsOpenedAndClosedWithAPatchAndTheAnswerReplacesIt() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    transport.answer("PATCH $base/door_1", 200, """{"door":${doorJson.replace("\"open\":true", "\"open\":false")}}""")
    val m = model()
    m.loadDoors("refunds-1")
    val door = m.state.value.doors["refunds-1"]!!.doors.single()
    m.setDoorOpen("refunds-1", door, false)
    assertEquals("false", body("PATCH $base/door_1").getValue("open").jsonPrimitive.content)
    assertFalse(m.state.value.doors["refunds-1"]!!.doors.single().open)
    assertFalse(m.state.value.doors["refunds-1"]!!.busy)
  }

  @Test fun anEmailDoorIsAddedAtOnceAndAppended() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody(doors = "", knocks = ""))
    transport.answer("POST $base", 200, """{"door":$doorJson}""")
    val m = model()
    m.loadDoors("refunds-1")
    val email = m.state.value.doors["refunds-1"]!!.apps.first { it.id == "email" }
    val added = m.addDoor("refunds-1", email)
    assertEquals("door_1", added!!.id)
    assertEquals("email", body("POST $base").getValue("app").jsonPrimitive.content)
    assertEquals(listOf("door_1"), m.state.value.doors["refunds-1"]!!.doors.map { it.id })
  }

  @Test fun aWhatsAppDoorIsNotAddedFromHereAndNothingIsSent() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody(doors = "", knocks = ""))
    val m = model()
    m.loadDoors("refunds-1")
    val whatsapp = m.state.value.doors["refunds-1"]!!.apps.first { it.id == "whatsapp" }
    assertNull(m.addDoor("refunds-1", whatsapp))
    assertTrue(sent("POST $base").isEmpty())
    assertTrue(m.state.value.doors["refunds-1"]!!.error!!.contains("WhatsApp"))
  }

  @Test fun aDoorIsRemovedAndLeavesTheList() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    transport.answer("DELETE $base/door_1", 200, """{"removed":"door_1"}""")
    val m = model()
    m.loadDoors("refunds-1")
    m.removeDoor("refunds-1", m.state.value.doors["refunds-1"]!!.doors.single())
    assertEquals(1, sent("DELETE $base/door_1").size)
    assertTrue(m.state.value.doors["refunds-1"]!!.doors.isEmpty())
  }

  @Test fun aChangeTafsCloudRefusesSaysWhyAndLeavesTheDoorAsItWas() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    transport.answer("PATCH $base/door_1", 403, """{"error":"Your role on this account doesn't allow this."}""")
    val m = model()
    m.loadDoors("refunds-1")
    m.setDoorOpen("refunds-1", m.state.value.doors["refunds-1"]!!.doors.single(), false)
    val d = m.state.value.doors["refunds-1"]!!
    assertEquals("Your role on this account doesn't allow this.", d.error)
    assertTrue(d.doors.single().open)
    assertFalse(d.busy)
    m.dismissDoorsError("refunds-1")
    assertNull(m.state.value.doors["refunds-1"]!!.error)
  }

  // MARK: Keys

  @Test fun aSenderIsPairedWithTheirNameAndTheDoorReplacesItself() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    val paired = doorJson.replace("""{"id":"ana@company.example","name":"Ana"}""", """{"id":"ana@company.example","name":"Ana"},{"id":"lee@company.example"}""")
    transport.answer("POST $base/door_1/keys", 200, """{"door":$paired}""")
    val m = model()
    m.loadDoors("refunds-1")
    m.pairDoor("refunds-1", m.state.value.doors["refunds-1"]!!.doors.single(), "  lee@company.example ", "")
    val sent = body("POST $base/door_1/keys")
    assertEquals("lee@company.example", sent.getValue("id").jsonPrimitive.content)
    assertNull("no name: none sent", sent["name"])
    assertEquals(3, m.state.value.doors["refunds-1"]!!.doors.single().keys.size)
  }

  @Test fun aNameGoesWithThePairedSenderAndABlankSenderPairsNobody() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    transport.answer("POST $base/door_1/keys", 200, """{"door":$doorJson}""")
    val m = model()
    m.loadDoors("refunds-1")
    val door = m.state.value.doors["refunds-1"]!!.doors.single()
    m.pairDoor("refunds-1", door, "   ", "Nobody")
    assertTrue(sent("POST $base/door_1/keys").isEmpty())
    m.pairDoor("refunds-1", door, "lee@company.example", " Lee ")
    assertEquals("Lee", body("POST $base/door_1/keys").getValue("name").jsonPrimitive.content)
  }

  @Test fun aKeyIsUnpairedByItsSender() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    transport.answer("DELETE $base/door_1/keys/ana@company.example", 200, """{"door":${doorJson.replace("""{"id":"ana@company.example","name":"Ana"}""", "").replace(",]", "]")}}""")
    val m = model()
    m.loadDoors("refunds-1")
    val door = m.state.value.doors["refunds-1"]!!.doors.single()
    m.unpairDoor("refunds-1", door, DoorKey("ana@company.example", "Ana"))
    assertEquals(1, sent("DELETE $base/door_1/keys/ana@company.example").size)
    assertEquals(1, m.state.value.doors["refunds-1"]!!.doors.single().keys.size)
  }

  // MARK: Knocks

  @Test fun doItSendsTheOwnersWordsAndTheKnockLeaves() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    transport.answer("POST $base/knocks/k1", 200, """{"state":"done"}""")
    val m = model()
    m.loadDoors("refunds-1")
    m.answerKnock("refunds-1", m.state.value.doors["refunds-1"]!!.knocks.single(), "do", "Refund 15, within your Card.")
    val sent = body("POST $base/knocks/k1")
    assertEquals("do", sent.getValue("action").jsonPrimitive.content)
    assertEquals("Refund 15, within your Card.", sent.getValue("say").jsonPrimitive.content)
    assertTrue(m.state.value.doors["refunds-1"]!!.knocks.isEmpty())
    assertFalse(m.state.value.doors["refunds-1"]!!.busy)
  }

  @Test fun ignoreSendsNoWordsAndTheKnockLeaves() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    transport.answer("POST $base/knocks/k1", 200, """{"state":"ignored"}""")
    val m = model()
    m.loadDoors("refunds-1")
    m.answerKnock("refunds-1", m.state.value.doors["refunds-1"]!!.knocks.single(), "ignore")
    assertEquals("ignore", body("POST $base/knocks/k1").getValue("action").jsonPrimitive.content)
    assertNull(body("POST $base/knocks/k1")["say"])
    assertTrue(m.state.value.doors["refunds-1"]!!.openKnocks.isEmpty())
  }

  @Test fun pairingFromAKnockKeepsTheMessageWaitingAndReadsTheDoorsAgain() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    transport.answer(doors, 200, doorsBody(knocks = knockJson.replace("\"state\":\"open\"", "\"state\":\"open\",\"paired\":true")))
    transport.answer("POST $base/knocks/k1", 200, """{"state":"open"}""")
    val m = model()
    m.loadDoors("refunds-1")
    m.answerKnock("refunds-1", m.state.value.doors["refunds-1"]!!.knocks.single(), "pair")
    assertEquals(2, sent(doors).size)
    val knock = m.state.value.doors["refunds-1"]!!.openKnocks.single()
    assertTrue("it waits, with Do it and Ignore", knock.paired)
    assertNull("no more Pair for it", DoorWords.pairLabel(knock))
  }

  @Test fun tooManyAnswersInAFewMinutesSaysItInTheAppsWords() = runTest {
    reads()
    transport.answer(doors, 200, doorsBody())
    transport.answer("POST $base/knocks/k1", 429, """{"error":"rate limited"}""")
    val m = model()
    m.loadDoors("refunds-1")
    m.answerKnock("refunds-1", m.state.value.doors["refunds-1"]!!.knocks.single(), "do", "go")
    assertEquals("That's a lot of messages in a few minutes. Wait a little, then send it again: nothing was sent.", m.state.value.doors["refunds-1"]!!.error)
    assertEquals("the knock still waits", 1, m.state.value.doors["refunds-1"]!!.knocks.size)
  }

  @Test fun aTafTafsCloudDoesNotRunHasNothingToRead() = runTest {
    reads()
    val m = model()
    m.loadDoors("billing-x")
    assertTrue(transport.requests.none { it.url.contains("/doors") })
  }

  // MARK: The chat's door marks

  @Test fun aDoorsMessageAndItsAnswerCarryTheirMarks() = runTest {
    reads()
    transport.answer(
      "GET /v1/tafs/taf_1/conversation", 200,
      """{"messages":[
          {"at":"2026-10-28T08:00:00Z","from":"owner","text":"refund order 4502","session":"s1","door":{"app":"email","from":"you@company.example","subject":"Refund"}},
          {"at":"2026-10-28T08:00:09Z","from":"taf","text":"Done.","session":"s1","door":{"app":"email","reply":"sent"}},
          {"at":"2026-10-28T08:01:00Z","from":"owner","text":"plain","session":"s2"}]}""",
    )
    val m = model()
    m.loadChat("refunds-1")
    val (first, second, third) = m.state.value.chats["refunds-1"]!!
    assertEquals(DoorMark("email", "you@company.example", "Refund"), first.door)
    assertEquals("sent", second.door!!.reply)
    assertNull(third.door)
  }

  @Test fun aMarkTheAppCannotReadLeavesTheMessagePlainAndTheThreadRead() = runTest {
    reads()
    transport.answer(
      "GET /v1/tafs/taf_1/conversation", 200,
      """{"messages":[{"at":"2026-10-28T08:00:00Z","from":"owner","text":"hello","session":"s1","door":{"reply":"sent"}},
                      {"at":"2026-10-28T08:00:05Z","from":"taf","text":"Hi","session":"s1","door":"nonsense"}]}""",
    )
    val m = model()
    m.loadChat("refunds-1")
    val thread = m.state.value.chats["refunds-1"]!!
    assertEquals(listOf("hello", "Hi"), thread.map { it.text })
    assertTrue(thread.all { it.door == null })
  }

  // MARK: Words

  private fun door(app: String = "email", keys: List<DoorKey> = emptyList(), proof: String = "proven", open: Boolean = true, resting: Boolean = false, today: Int = 3) =
    Door("d", app, app, DoorNames.label(app).replaceFirstChar { it.uppercase() }, open, "a@b", proof, "It checks the sender's server signed the message", keys, today, 20, resting, 1, 10)

  private fun knock(proof: String = "pass", name: String? = "Mara", subject: String? = "Refund", rest: String? = null, dropped: Int = 0, attachments: Int = 0, paired: Boolean = false) =
    Knock("k", "d", "email", "mara@customer.example", name, proof, subject, "text", attachments, t0, "open", rest, dropped, paired)

  @Test fun aChatsKnocksSitAtTheirTimeSoItsNewestIsLast() {
    val msg = { at: Long, text: String -> bot.tafs.app.model.ChatItem.Message(bot.tafs.app.model.ChatMessage(t0.plusSeconds(at), bot.tafs.app.model.ChatMessage.From.taf, text, "s1")) }
    val items = listOf(msg(0, "earlier"), msg(120, "the reply, newest"))
    val k = knock().copy(id = "k1", at = t0.plusSeconds(60))
    val rows = bot.tafs.app.model.Chat.withKnocks(items, listOf(k))
    assertEquals(listOf("item", "knock", "item"), rows.map { if (it is bot.tafs.app.model.Chat.Row.Knock) "knock" else "item" })
    // The newest message is last, not the older knock under it.
    assertEquals(1, (rows.last() as bot.tafs.app.model.Chat.Row.Item).index)
    // A knock newer than every message goes last.
    val late = knock().copy(id = "k2", at = t0.plusSeconds(600))
    assertTrue(bot.tafs.app.model.Chat.withKnocks(items, listOf(late)).last() is bot.tafs.app.model.Chat.Row.Knock)
  }

  @Test fun appsAreNamedAndMarked() {
    assertEquals("email", DoorNames.label("email"))
    assertEquals("WhatsApp", DoorNames.label("whatsapp"))
    assertEquals("Signal", DoorNames.label("signal"))
    assertEquals("@", DoorNames.mark("email"))
    assertEquals("W", DoorNames.mark("whatsapp"))
    assertEquals("S", DoorNames.mark("signal"))
  }

  @Test fun aDoorSaysWhereItStandsAndItsDay() {
    assertEquals("Open", DoorWords.status(door()))
    assertEquals("Closed", DoorWords.status(door(open = false)))
    assertEquals("Resting until tomorrow", DoorWords.status(door(resting = true)))
    assertTrue(DoorWords.dayLine(door()).startsWith("3 of 20 from you and paired senders today."))
    assertEquals("20 of 20 from you and paired senders today. More are dropped until tomorrow.", DoorWords.dayLine(door(resting = true)))
    assertEquals("1 of 10 from anyone else today.", DoorWords.othersLine(door()))
    assertNull(DoorWords.othersLine(door().copy(othersToday = null)))
  }

  @Test fun youMarksOnlyThisPersonsOwnPairedKey() {
    val own = DoorKey("you@company.example", "You", auto = true)
    assertEquals("You" to "you@company.example", DoorWords.keyName(own, "YOU@company.example"))
    assertEquals("someone else's own: its address", "you@company.example" to null, DoorWords.keyName(own, "other@company.example"))
    assertEquals("Ana" to "ana@company.example", DoorWords.keyName(DoorKey("ana@company.example", "Ana"), "you@company.example"))
    assertEquals("a name that is only the address shows it once", "ana@company.example" to null, DoorWords.keyName(DoorKey("ana@company.example", "ANA@company.example"), null))
    assertEquals("ana@company.example" to null, DoorWords.keyName(DoorKey("ana@company.example"), null))
  }

  @Test fun aDoorThatCannotProveWhoWroteHoldsNoKeys() {
    assertEquals("Paired · can give work", DoorWords.keysTitle(door()))
    assertEquals("No one is paired here", DoorWords.keysTitle(door(proof = "none")))
    assertTrue(DoorWords.noKeys(door(proof = "none"), "Refunds").endsWith("so no one here can give Refunds work. Everything that comes through knocks."))
    assertEquals("Email address", DoorWords.pairHint(door()))
    assertEquals("Phone number, with its country code", DoorWords.pairHint(door("whatsapp")))
    assertEquals("Who, as Signal names them", DoorWords.pairHint(door("signal")))
  }

  @Test fun aKnockSaysWhoWroteAndWhatItAsksTheOwnerToDecide() {
    assertEquals("● KNOCK · BY EMAIL", DoorWords.knockTitle(knock()))
    assertEquals("● KNOCK · BY EMAIL · DIDN'T CHECK OUT", DoorWords.knockTitle(knock(proof = "fail")))
    // A paired sender's message, kept from its knock, reads as paired, not as a stranger waiting on the owner.
    assertEquals("PAIRED · BY EMAIL", DoorWords.knockTitle(knock(paired = true)))
    assertEquals("Paired · Do it or Ignore this message", DoorWords.PAIRED_LINE)
    assertEquals("Mara (mara@customer.example) wrote about \"Refund\". Nothing was done.", DoorWords.wrote(knock()))
    assertEquals("mara@customer.example wrote. Nothing was done.", DoorWords.wrote(knock(name = null, subject = null)))
    assertEquals("It had 2 attachments, not opened.", DoorWords.attachments(knock(attachments = 2)))
    assertEquals("It had 1 attachment, not opened.", DoorWords.attachments(knock(attachments = 1)))
    assertNull(DoorWords.attachments(knock()))
  }

  @Test fun theOwnerIsOfferedOnlyWhatTheDoorCanBackUp() {
    assertTrue(DoorWords.canDoIt(knock("pass")))
    assertTrue(DoorWords.canDoIt(knock("none")))
    assertFalse("a sender check that failed is a stranger's", DoorWords.canDoIt(knock("fail")))
    assertEquals("Pair Mara", DoorWords.pairLabel(knock("pass")))
    assertEquals("Pair them", DoorWords.pairLabel(knock("pass", name = null)))
    assertNull(DoorWords.pairLabel(knock("none")))
    assertNull(DoorWords.pairLabel(knock("fail")))
    assertNull(DoorWords.pairLabel(knock("pass", paired = true)))
    assertNotNull(DoorWords.proofNote(knock("fail")))
    assertEquals("This door can't prove who wrote it.", DoorWords.proofNote(knock("none")))
    assertNull(DoorWords.proofNote(knock("pass")))
  }

  @Test fun theDayTheDoorHadItsMessagesIsOneLineWithHowManyWereDropped() {
    assertEquals("This door had its messages from you and paired senders for today. 1 more was dropped.", DoorWords.restLine(knock(rest = "keys", dropped = 1)))
    assertEquals("This door had its messages from people who aren't paired for today. 4 more were dropped.", DoorWords.restLine(knock(rest = "others", dropped = 4)))
    assertTrue(knock(rest = "keys").isLine)
    assertFalse(knock().isLine)
  }

  @Test fun theStampSaysWhereAMessageCameFromAndHowItsAnswerWentBack() {
    assertEquals("BY EMAIL · you@company.example", DoorWords.stamp(DoorMark("email", "you@company.example"), fromOwner = true))
    assertEquals("BY WHATSAPP", DoorWords.stamp(DoorMark("whatsapp"), fromOwner = true))
    assertEquals("↩ SENT BACK BY EMAIL · SAME THREAD", DoorWords.stamp(DoorMark("email", reply = "sent"), fromOwner = false))
    assertEquals("NOT SENT BACK · past WhatsApp's 24 hours", DoorWords.stamp(DoorMark("whatsapp", reply = "not_sent", why = "past WhatsApp's 24 hours"), fromOwner = false))
    assertEquals("NOT SENT BACK", DoorWords.stamp(DoorMark("email", reply = "not_sent"), fromOwner = false))
    assertEquals("GOES BACK BY EMAIL WHEN DONE", DoorWords.stamp(DoorMark("email", reply = "pending"), fromOwner = false))
    assertEquals("GOES BACK BY EMAIL WHEN DONE", DoorWords.stamp(DoorMark("email"), fromOwner = false))
  }

  @Test fun theOwnersWordsAreTheInstructionAndTheKnocksIsInformation() {
    assertEquals("Tell Refunds what to do", DoorWords.doItTitle("Refunds"))
    assertEquals("Tell your taf what to do", DoorWords.doItTitle(null))
    val intro = DoorWords.doItIntro(knock(), "Refunds")
    assertTrue(intro, intro.startsWith("Mara (mara@customer.example)'s message goes with your words, as information."))
    assertTrue(intro, intro.endsWith("doesn't write back to them unless you ask."))
  }

  @Test fun theFooterSaysAnyoneCanKnockAndOnlyPairedPeopleGiveWork() {
    assertEquals("Anyone can write to a door. Only people you pair can give Refunds work, and its Card decides the rest.", DoorWords.footer("Refunds"))
    assertEquals("Refunds has no door yet. Someone who manages it can add one.", DoorWords.noDoorYet("Refunds"))
  }

  @Test fun aChatMessageWithADoorMarkKeepsItsId() {
    val plain = ChatMessage(t0, ChatMessage.From.taf, "Done.", "s1")
    assertEquals(plain.id, plain.copy(door = DoorMark("email", reply = "sent")).id)
  }
}
