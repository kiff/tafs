package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coach's conversations (docs/coach.md): a new one, reopening and archiving earlier ones, improving a taf, and the news of
 * what Coach handed tafs. A fake KIFF and tafs cloud stand in: no real Coach conversation is touched.
 */
class CoachConversationsTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val send = "POST /v1/tafs/setup_1/messages"

  /** KIFF's access: by default this person manages Refunds, as its owner does. */
  private val manager = """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true}}}"""

  private fun reads(conversation: String = """{"messages":[]}""", access: String = manager) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, access)
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"}}""")
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, conversation)
  }

  private suspend fun model(opened: java.io.File = java.nio.file.Files.createTempDirectory("opened").toFile()): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false, openedFiles = opened).also {
      it.restore()
      it.loadCoach()
    }
  }

  private fun body(key: String, i: Int = 0) = KiffClient.json.parseToJsonElement(String(transport.requestsTo(key)[i].body!!)).jsonObject

  @Test fun aNewConversationStartsWithItsFirstMessageAndIsTheOneShownAfter() = runTest {
    reads()
    transport.answer(send, 200, """{"session":"s9","new_session":true}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s9", 200, """{"messages":[]}""")
    val m = model()
    m.newCoachChat()
    assertTrue(m.state.value.coachFresh)
    assertTrue(m.deliverChat("Make sure customers can get their refunds", COACH_CHAT))
    assertEquals("true", body(send).getValue("new_chat").jsonPrimitive.content)
    assertFalse(m.state.value.coachFresh)
    assertEquals("s9", m.state.value.coachChat)
    // From then on, that conversation is read alone.
    assertTrue(transport.requestsTo("GET /v1/tafs/setup_1/conversation/s9").isNotEmpty())
  }

  @Test fun finishSettingUpOpensCoachOnTheChatWhereItStopped() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/conversation/s7", 200, """{"messages":[{"at":"2026-10-27T09:00:00Z","from":"owner","text":"Invoicing day test","session":"s7"}]}""")
    val m = model()
    m.finishSetup(bot.tafs.app.kiff.HomeFinish(taf = "setup_1", session = "s7", words = "Finish setting up"))
    // Coach opens on that chat; reading it is what makes the taf, at tafs cloud. The app makes nothing itself.
    assertTrue(m.state.value.coachRequested)
    assertEquals("s7", m.state.value.coachChat)
    assertEquals(1, transport.requestsTo("GET /v1/tafs/setup_1/conversation/s7").size)
    assertEquals(listOf("Invoicing day test"), m.state.value.chats[COACH_CHAT].orEmpty().map { it.text })
  }

  // A new job asked in a finished Coach chat went on under the last one's plan: tafs cloud starts a new chat for it, and the app
  // follows it, with the message on its way and nothing of the finished chat.
  @Test fun aNewChatTafsCloudStartsAfterAHandOverIsTheOneShown() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/conversation/s4", 200, """{"messages":[{"at":"2026-10-27T09:00:00Z","from":"owner","text":"A weekly summary","session":"s4"}]}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s10", 200, """{"messages":[]}""")
    transport.answer(send, 200, """{"session":"s10","new_session":true}""")
    val m = model()
    m.openCoachChat("s4")
    assertTrue(m.deliverChat("Remind me each Monday", COACH_CHAT))
    // This app says it follows a new chat.
    assertEquals("s4", body(send).getValue("session").jsonPrimitive.content)
    assertEquals("true", body(send).getValue("new_chat_after_handover").jsonPrimitive.content)
    assertEquals("s10", m.state.value.coachChat)
    assertFalse("nothing of the finished chat", m.state.value.chats[COACH_CHAT].orEmpty().any { it.text == "A weekly summary" })
  }

  @Test fun aReopenedConversationIsReadAloneAndMessagesGoOnInIt() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/conversation/s4", 200, """{"messages":[{"at":"2026-10-27T09:00:00Z","from":"owner","text":"Hi","session":"s4"}]}""")
    transport.answer(send, 200, """{"session":"s4","new_session":false}""")
    val m = model()
    m.openCoachChat("s4")
    assertEquals(listOf("Hi"), m.state.value.chats[COACH_CHAT].orEmpty().map { it.text })
    m.deliverChat("And now?", COACH_CHAT)
    assertEquals("s4", body(send).getValue("session").jsonPrimitive.content)
    assertFalse(body(send).containsKey("new_chat"))
  }

  @Test fun improvingATafStartsWithTheImproveRouteAndTakesNoFiles() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/improve", 200, """{"session":"s7","new_session":true}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s7", 200, """{"messages":[]}""")
    val m = model()
    m.improveTaf("refunds-1")
    assertEquals("refunds-1", m.state.value.coachImproving)
    assertFalse("the improve route takes words only", m.state.value.canAttach(COACH_CHAT))
    assertTrue(m.deliverChat("How is it going?", COACH_CHAT))
    assertEquals("How is it going?", body("POST /v1/tafs/taf_1/improve").getValue("text").jsonPrimitive.content)
    assertTrue(transport.requestsTo(send).isEmpty())
    assertEquals("s7", m.state.value.coachChat)
    assertNull(m.state.value.coachImproving)
  }

  // The owner tapped Coach on a taf's page, then the pencil, and stayed on "Improve Client email drafts".
  @Test fun thePencilOnAnEmptyChatAboutATafOpensAPlainOne() = runTest {
    reads()
    val m = model()
    m.newCoachChat()
    assertTrue("an empty plain chat stays", bot.tafs.app.model.CoachWords.newChatStays(m.state.value))
    m.improveTaf("refunds-1")
    assertFalse("an empty chat about a taf gives way", bot.tafs.app.model.CoachWords.newChatStays(m.state.value))
    m.newCoachChat()
    assertNull(m.state.value.coachImproving)
    assertNull(m.state.value.coachContext)
  }

  @Test fun coachFromATafsPageIsThatTafsConversationAndElsewhereTheOneOpen() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/improve", 200, """{"session":"s7","new_session":true}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s7", 200, """{"messages":[]}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s4", 200, """{"messages":[]}""")
    val m = model()
    m.improveTaf("refunds-1")
    m.deliverChat("How is it going?", COACH_CHAT)
    // Back on Refunds' page, or from another screen: the conversation about Refunds stays open.
    m.openCoachOn("refunds-1")
    assertEquals("s7", m.state.value.coachChat)
    m.openCoachOn(null)
    assertEquals("s7", m.state.value.coachChat)
    // A conversation about something else, then Coach from Refunds' page: a new one about Refunds.
    m.openCoachChat("s4")
    m.openCoachOn("refunds-1")
    assertNull(m.state.value.coachChat)
    assertTrue(m.state.value.coachFresh)
    assertEquals("refunds-1", m.state.value.coachImproving)
  }

  @Test fun coachFromAPageOfATafThePersonDoesNotManageIsCoachAsItIs() = runTest {
    // Uses Refunds, doesn't manage it: Coach opens as it was, never on Improve Refunds, which tafs cloud would refuse.
    reads(access = """{"agents":{"refunds-1":{"use":true}}}""")
    val m = model()
    m.openCoachOn("refunds-1")
    assertNull(m.state.value.coachImproving)
    assertFalse(m.state.value.mayAskCoachAbout("refunds-1"))
  }

  @Test fun aReadOfTheConversationLeftDoesNotReplaceTheNewTafsOne() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/conversation/s4", 200,
      """{"messages":[{"at":"2026-10-27T09:00:00Z","from":"owner","text":"About something else","session":"s4"}]}""")
    val m = model()
    transport.hold("GET /v1/tafs/setup_1/conversation/s4")
    val read = launch { m.openCoachChat("s4") }
    testScheduler.runCurrent()
    // Coach from Refunds' page before that read came back: a new conversation about Refunds, which the late read leaves alone.
    m.openCoachOn("refunds-1")
    transport.release("GET /v1/tafs/setup_1/conversation/s4")
    read.join()
    assertTrue(m.state.value.chats[COACH_CHAT].orEmpty().isEmpty())
    assertTrue(m.state.value.coachFresh)
    assertEquals("refunds-1", m.state.value.coachImproving)
  }

  @Test fun aSendToTheConversationLeftDoesNotBindTheNewOne() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/improve", 200, """{"session":"s7","new_session":true}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s7", 200, """{"messages":[]}""")
    val m = model()
    m.improveTaf("refunds-1")
    transport.hold("POST /v1/tafs/taf_1/improve")
    val send = launch { m.deliverChat("How is it going?", COACH_CHAT) }
    testScheduler.runCurrent()
    // Another conversation before tafs cloud answered: the late answer goes on at tafs cloud, and leaves this one alone.
    m.newCoachChat()
    transport.release("POST /v1/tafs/taf_1/improve")
    send.join()
    assertNull(m.state.value.coachChat)
    assertTrue(m.state.value.coachFresh)
    assertTrue(m.state.value.chats[COACH_CHAT].orEmpty().isEmpty())
    assertFalse("the new conversation can be written in", COACH_CHAT in m.state.value.chatSending)
  }

  @Test fun changingTheLimitInCoachModeOpensCoachOnTheTaf() = runTest {
    reads()
    val m = model()
    m.improveInCoach("refunds-1")
    assertTrue(m.state.value.coachRequested)
    assertEquals("refunds-1", m.state.value.coachImproving)
    m.coachOpened()
    assertFalse(m.state.value.coachRequested)
  }

  @Test fun atLaunchCoachOpensOnItsNewestConversationAlone() = runTest {
    reads()
    // Two conversations, listed in any order: the one updated last is opened, alone.
    transport.answer("GET /v1/tafs/setup_1/chats", 200, """{"chats":[{"session":"s3","started":"2026-10-26T09:00:00Z","updated":"2026-10-26T10:00:00Z","title":"Refunds"},{"session":"s4","started":"2026-10-27T09:00:00Z","updated":"2026-10-27T10:00:00Z","title":"Chargebacks"}]}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s4", 200, """{"messages":[{"at":"2026-10-27T09:00:00Z","from":"owner","text":"Set up Chargebacks","session":"s4"}]}""")
    val m = model()
    m.loadChat(COACH_CHAT)
    assertEquals("s4", m.state.value.coachChat)
    assertEquals(listOf("Set up Chargebacks"), m.state.value.chats[COACH_CHAT]!!.map { it.text })
    // Never the latest conversations together; the earlier one is under Previous conversations.
    assertTrue(transport.requestsTo("GET /v1/tafs/setup_1/conversation").isEmpty())
    assertEquals(listOf("s3", "s4"), m.state.value.coachChats.map { it.session })
  }

  // An audit, 14 Nov: a chat set aside on another install came back as the open conversation.
  @Test fun aChatSetAsideIsNeverOpenedAsTheNewest() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, """{"chats":[{"session":"s3","started":"2026-10-26T09:00:00Z","updated":"2026-10-26T10:00:00Z","title":"Refunds"},{"session":"s4","started":"2026-10-27T09:00:00Z","updated":"2026-10-27T10:00:00Z","title":"Chargebacks","set_aside":true}]}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s3", 200, """{"messages":[{"at":"2026-10-26T09:00:00Z","from":"owner","text":"Set up Refunds","session":"s3"}]}""")
    val m = model()
    m.loadChat(COACH_CHAT)
    assertEquals("s3", m.state.value.coachChat)
  }

  @Test fun withEveryChatSetAsideCoachOpensANewConversation() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, """{"chats":[{"session":"s4","started":"2026-10-27T09:00:00Z","updated":"2026-10-27T10:00:00Z","title":"Chargebacks","set_aside":true}]}""")
    val m = model()
    m.loadChat(COACH_CHAT)
    assertNull(m.state.value.coachChat)
    assertTrue(m.state.value.coachFresh)
    assertTrue(transport.requestsTo("GET /v1/tafs/setup_1/conversation/s4").isEmpty())
  }

  @Test fun withNoConversationYetCoachsThreadReadsAsBefore() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, """{"chats":[]}""")
    val m = model()
    m.loadChat(COACH_CHAT)
    assertEquals(null, m.state.value.coachChat)
    assertEquals(1, transport.requestsTo("GET /v1/tafs/setup_1/conversation").size)
  }

  @Test fun aNewConversationIsntReplacedByTheNewestAtItsFirstRead() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, """{"chats":[{"session":"s4","started":"2026-10-27T09:00:00Z","updated":"2026-10-27T10:00:00Z","title":"Chargebacks"}]}""")
    val m = model()
    m.newCoachChat()
    m.loadChat(COACH_CHAT)
    assertEquals(null, m.state.value.coachChat)
    assertTrue(m.state.value.coachFresh)
  }

  @Test fun earlierConversationsAreListedAndOneArchivedLeavesTheListAndTheScreen() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, """{"chats":[{"session":"s4","started":"2026-10-27T09:00:00Z","updated":"2026-10-27T10:00:00Z","title":"Refunds for Acme"},{"session":"s3","started":"2026-10-26T09:00:00Z","updated":"2026-10-26T10:00:00Z","title":"Connect Stripe"}]}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s4", 200, """{"messages":[]}""")
    transport.answer("POST /v1/tafs/setup_1/chats/s4/archive", 200, """{"archived":true}""")
    val m = model()
    m.loadCoachChats()
    assertEquals(listOf("Refunds for Acme", "Connect Stripe"), m.state.value.coachChats.map { it.title })
    m.openCoachChat("s4")
    assertTrue(m.archiveCoachChat("s4"))
    assertEquals(listOf("s3"), m.state.value.coachChats.map { it.session })
    // The one shown was archived: a new conversation takes its place.
    assertTrue(m.state.value.coachFresh)
  }

  @Test fun anArchiveTafsCloudRefusedSaysSo() = runTest {
    reads()
    transport.answer("POST /v1/tafs/setup_1/chats/s4/archive", 503, """{"error":"unavailable"}""")
    val m = model()
    assertFalse(m.archiveCoachChat("s4"))
    assertEquals("Not archived. It isn't working on our side right now. Try again later.", m.state.value.lastError)
  }

  private val twoChats = """{"chats":[{"session":"s4","started":"2026-10-27T09:00:00Z","updated":"2026-10-27T10:00:00Z","title":"Refunds for Acme"},{"session":"s3","started":"2026-10-26T09:00:00Z","updated":"2026-10-26T10:00:00Z","title":"Connect Stripe"}]}"""

  @Test fun aDeletedConversationLeavesBothListsAndTheScreen() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, twoChats)
    transport.answer("GET /v1/tafs/setup_1/chats/archived", 200, """{"chats":[{"session":"s2","started":"2026-10-25T09:00:00Z","updated":"2026-10-25T10:00:00Z","title":"Old"}]}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s4", 200, """{"messages":[]}""")
    transport.answer("DELETE /v1/tafs/setup_1/chats/s4", 200, """{"session":"s4","deleted":true}""")
    transport.answer("DELETE /v1/tafs/setup_1/chats/s2", 200, """{"session":"s2","deleted":true}""")
    val m = model()
    m.loadCoachChats()
    m.loadArchivedCoachChats()
    m.openCoachChat("s4")
    assertTrue(m.deleteCoachChat("s4"))
    assertEquals(listOf("s3"), m.state.value.coachChats.map { it.session })
    // The one shown was deleted: a new conversation takes its place, and nothing of the old one is left on screen.
    assertTrue(m.state.value.coachFresh)
    assertEquals(null, m.state.value.chats[bot.tafs.app.app.COACH_CHAT])
    // An archived one goes from Archived too.
    assertTrue(m.deleteCoachChat("s2"))
    assertTrue(m.state.value.coachArchived.isEmpty())
    assertEquals(1, transport.requestsTo("DELETE /v1/tafs/setup_1/chats/s4").size)
  }

  @Test fun oneTafsCloudDoesntHaveLeavesTheListQuietly() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, twoChats)
    transport.answer("DELETE /v1/tafs/setup_1/chats/s3", 404, """{"error":"no such chat"}""")
    val m = model()
    m.loadCoachChats()
    assertTrue(m.deleteCoachChat("s3"))
    assertEquals(listOf("s4"), m.state.value.coachChats.map { it.session })
    assertEquals(null, m.state.value.lastError)
  }

  @Test fun oneStillWorkingStaysAndSaysToStopOrAnswerItFirst() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, twoChats)
    transport.answer("DELETE /v1/tafs/setup_1/chats/s4", 409, """{"error":"Arrête-la ou réponds-y d'abord."}""")
    val m = model()
    m.loadCoachChats()
    assertFalse(m.deleteCoachChat("s4"))
    assertEquals(listOf("s4", "s3"), m.state.value.coachChats.map { it.session })
    // tafs cloud's own words for it, from the refusal.
    assertEquals("Arrête-la ou réponds-y d'abord.", m.state.value.lastError)
  }

  @Test fun aRefusalWithNoWordsOfItsOwnTakesTheStandIn() = runTest {
    reads()
    transport.answer("DELETE /v1/tafs/setup_1/chats/s4", 409, "{}")
    val m = model()
    assertFalse(m.deleteCoachChat("s4"))
    assertEquals(bot.tafs.app.model.DeleteChatWords.BUSY, m.state.value.lastError)
  }

  @Test fun theListsWordsForDeletingAreKeptAndAMissingOneTakesItsStandIn() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, twoChats.dropLast(1) +
      ""","delete":{"item":"Supprimer la conversation","confirm_title":"Supprimer cette conversation ?","failed":"Elle n'a pas été supprimée."}}""")
    val m = model()
    m.loadCoachChats()
    val words = bot.tafs.app.model.DeleteChatWords.of(m.state.value.coachDeleteWords)
    assertEquals("Supprimer la conversation", words.menu)
    assertEquals("Supprimer cette conversation ?", words.title)
    // Not sent: the stand-ins.
    assertEquals(bot.tafs.app.model.DeleteChatWords.BODY, words.body)
    assertEquals(bot.tafs.app.model.DeleteChatWords.BUTTON, words.button)
    // An archived read without words keeps those the list brought.
    transport.answer("GET /v1/tafs/setup_1/chats/archived", 200, """{"chats":[]}""")
    m.loadArchivedCoachChats()
    assertEquals("Supprimer la conversation", bot.tafs.app.model.DeleteChatWords.of(m.state.value.coachDeleteWords).menu)
  }

  @Test fun aListWithNoWordsGivesTheStandIns() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, twoChats)
    val m = model()
    m.loadCoachChats()
    assertEquals(null, m.state.value.coachDeleteWords)
    assertEquals(bot.tafs.app.model.DeleteChatWords.MENU, bot.tafs.app.model.DeleteChatWords.of(m.state.value.coachDeleteWords).menu)
  }

  @Test fun aDeleteThatDidntGoThroughKeepsItAndSaysSo() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, twoChats.dropLast(1) + ""","delete":{"failed":"Elle n'a pas été supprimée."}}""")
    transport.drop("DELETE /v1/tafs/setup_1/chats/s4")
    val m = model()
    m.loadCoachChats()
    assertFalse(m.deleteCoachChat("s4"))
    assertEquals(listOf("s4", "s3"), m.state.value.coachChats.map { it.session })
    // With no network, the words the last list read brought.
    assertEquals("Elle n'a pas été supprimée.", m.state.value.lastError)
  }

  /** A file opened from a deleted conversation keeps a copy in the phone's cache: a delete that went through clears it, one that didn't leaves it. */
  @Test fun aDeletedConversationsOpenedFilesLeaveThePhonesCache() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, twoChats)
    transport.answer("DELETE /v1/tafs/setup_1/chats/s4", 409, """{"error":"busy"}""")
    transport.drop("DELETE /v1/tafs/setup_1/chats/s3")
    val opened = java.nio.file.Files.createTempDirectory("opened").toFile()
    val copy = java.io.File(opened, "att_1/report.pdf").apply { parentFile!!.mkdirs(); writeText("kept copy") }
    val m = model(opened)
    m.loadCoachChats()
    // Refused (409) and not sent (no network): the copy stays.
    assertFalse(m.deleteCoachChat("s4"))
    assertTrue(copy.exists())
    assertFalse(m.deleteCoachChat("s3"))
    assertTrue(copy.exists())
    // Deleted: nothing opened from it is left on the phone.
    transport.answer("DELETE /v1/tafs/setup_1/chats/s3", 200, """{"session":"s3","deleted":true}""")
    assertTrue(m.deleteCoachChat("s3"))
    assertFalse(copy.exists())
    assertTrue(opened.listFiles().isNullOrEmpty())
  }

  @Test fun aDeleteThatFailedBeforeAnyWordsCameTakesTheStandIn() = runTest {
    reads()
    transport.drop("DELETE /v1/tafs/setup_1/chats/s4")
    val m = model()
    assertFalse(m.deleteCoachChat("s4"))
    assertEquals(bot.tafs.app.model.DeleteChatWords.FAILED, m.state.value.lastError)
  }

  @Test fun anArchivedConversationIsPutBack() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats/archived", 200, """{"chats":[{"session":"s2","started":"2026-10-25T09:00:00Z","updated":"2026-10-25T10:00:00Z","title":"Old"}]}""")
    transport.answer("POST /v1/tafs/setup_1/chats/s2/unarchive", 200, """{"archived":false}""")
    transport.answer("GET /v1/tafs/setup_1/chats", 200, """{"chats":[{"session":"s2","started":"2026-10-25T09:00:00Z","updated":"2026-10-25T10:00:00Z","title":"Old"}]}""")
    val m = model()
    m.loadArchivedCoachChats()
    assertTrue(m.unarchiveCoachChat("s2"))
    assertTrue(m.state.value.coachArchived.isEmpty())
    assertEquals(listOf("s2"), m.state.value.coachChats.map { it.session })
  }

  @Test fun theNewsOfWhatCoachHandedTafsIsReadAndOneTheAppCantReadIsLeftOut() = runTest {
    reads(
      conversation = """{"messages":[],"coach_lines":[{"at":"2026-10-28T08:00:00Z","taf":"Refunds","said":"Refunded 2 payments","away":true},{"at":"2026-10-28T08:30:00Z","taf":"Chargebacks","said":"Saved a draft"},{"taf":7}]}""",
    )
    val m = model()
    m.loadChat(COACH_CHAT)
    val lines = m.state.value.coachLines
    assertEquals(listOf("Refunds", "Chargebacks"), lines.map { it.taf })
    assertEquals(listOf(true, null), lines.map { it.away })
  }
}
