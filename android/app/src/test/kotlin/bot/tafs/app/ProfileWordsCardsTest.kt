@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.Jcs
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.ProfileWords
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * KIFF's words on Coach's approval cards (KIFF's profile contract). Two cards could arrive in the same
 * second, and each said "Reading KIFF's words…" with Approve greyed until a tab switch. The words were read only by the card
 * itself, and a card drawn again lost its read. They're now read with Coach's thread, in the model's own scope, each card's for
 * its own grant.
 */
class ProfileWordsCardsTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-11-17T20:11:29Z")

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
  }

  private suspend fun model(scope: kotlinx.coroutines.CoroutineScope): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false, scope = scope).also { it.restore() }
  }

  private fun resource(name: String) = javaClass.classLoader!!.getResource("profile/$name.json")!!.readText()
  private fun digestOf(json: String) = MessageDigest.getInstance("SHA-256").digest(Jcs.canonical(json).toByteArray()).joinToString("") { "%02x".format(it) }

  /** A pending grant whose request carries [profile]: one app's connection, as planque lists it. */
  private fun grant(id: String, server: String, profile: String): String {
    val request = """{"server":"$server","agent":"reminders","tools":[{"tool":"t","read_only":false}],"profile":$profile}"""
    return """{"id":"$id","status":"pending","text":"Connect.","digest":"${digestOf(request)}","request":$request,"events":[]}"""
  }

  private val stripe = resource("stripe-refunds")
  private val gmail = resource("gmail-drafts")
  private val words = "POST /v1/profiles/words"

  /** Two approval cards in one thread, shown in the same second, each for its own grant. */
  private fun twoCards() {
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"}}""")
    fun shown(id: String, grant: String) =
      """{"at":"2026-11-17T20:11:29Z","session":"s1","run":"r1","component":{"type":"approval","id":"$id","grant":"$grant","title":"Connect"}}"""
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"components":[${shown("stripe-tools", "g_stripe")},${shown("gmail-tools", "g_gmail")}]}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("g_stripe", "mcp.stripe.com", stripe)},${grant("g_gmail", "gmail.googleapis.com", gmail)}]}""")
  }

  private fun digest(profile: String) = ProfileWords.digest(KiffClient.json.parseToJsonElement(profile).jsonObject)

  @Test fun twoCardsArrivingAtOnceEachGetTheirOwnWordsWithNoCardDrawn() = runTest {
    reads()
    twoCards()
    // Read in the cards' order: Stripe's first, then Gmail's.
    transport.answer(words, 200, resource("stripe-refunds.expected"))
    transport.answer(words, 200, resource("gmail-drafts.expected"))
    val m = model(backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    runCurrent()
    val s = m.state.value
    val stripeWords = s.profileWordsFor(s.grants.first { it.id == "g_stripe" })
    val gmailWords = s.profileWordsFor(s.grants.first { it.id == "g_gmail" })
    assertNotNull("Stripe's card has KIFF's words", stripeWords)
    assertNotNull("Gmail's card has KIFF's words", gmailWords)
    // Each its own: the words' digest is its own grant's profile, never the other card's.
    assertEquals(digest(stripe), stripeWords!!.profileSha256)
    assertEquals(digest(gmail), gmailWords!!.profileSha256)
    // Asked of KIFF once each, with that grant's own profile.
    assertEquals(listOf(Jcs.canonical(stripe), Jcs.canonical(gmail)), transport.requestsTo(words).map { Jcs.canonical(String(it.body!!)) })
  }

  /** A card drawn while its words are still being read asks again: one read each, not two, and each card still gets its own. */
  @Test fun aCardDrawnWhileItsWordsAreReadDoesNotReadThemTwice() = runTest {
    reads()
    twoCards()
    transport.answer(words, 200, resource("stripe-refunds.expected"))
    transport.answer(words, 200, resource("gmail-drafts.expected"))
    val m = model(backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    // Both cards drawn, each asking for its words, while the thread's reads of them haven't answered yet.
    m.readProfileWords("g_stripe")
    m.readProfileWords("g_gmail")
    runCurrent()
    assertEquals(2, transport.requestsTo(words).size)
    val s = m.state.value
    assertEquals(digest(stripe), s.profileWordsFor(s.grants.first { it.id == "g_stripe" })?.profileSha256)
    assertEquals(digest(gmail), s.profileWordsFor(s.grants.first { it.id == "g_gmail" })?.profileSha256)
  }
}
