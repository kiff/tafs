package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.CoachStart
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coach's first start under one fingerprint (docs/coach.md): tafs cloud's `start` carries the tools batch for KIFF's gateway and
 * Coach's draft with its Cards; the phone shows them as Coach's tools step, then signs both once together (`kiff.requests.v1`)
 * and sends them as they came: the batch, the draft, then the claim. Fakes stand in for KIFF, planque and tafs cloud.
 */
class CoachStartTogetherTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-11-13T09:00:00Z")

  private val batchBody = """{"connections":[{"name":"tafs_state","url":"https://cloud.tafs.kiff.dev/v1/mcp","tool":"tafs_state","read_only":true}]}"""
  private val draftBody = """{"agent_id":"setup","label":"Coach","contract_digest":"sha256:abc","cards":[{"id":"coach-reads","grants":[{"action":"tafs_state"}]}]}"""
  private val step = """{"title":"Coach's tools","intro":"What Coach may do","groups":[],"details":"","ask":"Give Coach these tools?","give":"Start Coach","not_now":"Not now","row":"Coach's tools"}"""
  private val coach = """{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"}"""

  private fun start(pair: Boolean = true, draftPath: String = "/v1/me/agent-drafts"): String {
    val connect = """{"id":"connect","method":"POST","path":"/v1/connections/batch","gateway":true,"body":$batchBody,"lines":["See your tafs"]}"""
    val draft = """{"id":"draft","method":"POST","path":"$draftPath","body":$draftBody,"card_title":"Coach's Cards","cards":[{"title":"Reads","lines":["See your tafs, up to 500 a day"]}]}"""
    val scheme = if (pair) "kiff.requests.v1" else "kiff.request.v1"
    return """{"state":"to_give","scheme":"$scheme","agent_id":"setup","requests":[${if (pair) "$connect," else ""}$draft],"coach_tools_step":$step}"""
  }

  /** The account's reads; [setups] are `GET /v1/setup`'s answers in turn, then Coach with its tools given. */
  private fun reads(vararg setups: String) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    setups.forEach { transport.answer("GET /v1/setup", 200, it) }
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":{"state":"given"}}""")
    transport.answer("GET /v1/accounts/t_01/grants", 403, """{"error":"this device is not enrolled"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[]}""")
  }

  private fun startAnswers() {
    transport.answer("POST /v1/connections/batch", 200, "{}")
    transport.answer("POST /v1/me/agent-drafts", 201, """{"id":"drf_s","agent_id":"setup","status":"confirmed","version":"v1","claim_secret":"kiff_claim_x"}""")
    transport.answer("POST /v1/keys", 200, """{"plaintext":"kk_connector"}""")
    transport.answer("POST /v1/accounts/t_01/enrolment", 200, """{"tenant":"t_01","device":"ab12"}""")
    transport.answer("POST /v1/setup", 200, """{"setup":$coach}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore() }
  }

  private fun json(s: String): JsonObject = KiffClient.json.parseToJsonElement(s).jsonObject
  private fun sentBody(key: String): JsonObject = json(String(transport.requestsTo(key).single().body!!))
  private fun header(key: String): Map<String, String> =
    transport.requestsTo(key).single().headers.getValue("KIFF-Device-Signature").split(",").associate { it.substringBefore("=") to it.substringAfter("=") }

  @Test fun theStartReadsAsTafsCloudSendsItAndOnlyKnownRoutesAreSigned() {
    val pair = CoachStart.read(json(start()))!!
    assertTrue(pair.startable)
    assertTrue(pair.together)
    assertEquals(listOf("See your tafs"), pair.tools.connect?.lines)
    assertEquals("Coach's Cards", pair.tools.card?.cardTitle)
    assertEquals(listOf("Reads" to listOf("See your tafs, up to 500 a day")), pair.tools.card?.words)
    assertEquals("Start Coach", pair.tools.step?.give)
    assertTrue(pair.tools.toGive)
    val alone = CoachStart.read(json(start(pair = false)))!!
    assertTrue(alone.startable)
    assertFalse(alone.together)
    assertFalse("a request to another route is never signed", CoachStart.read(json(start(draftPath = "/v1/me/agents")))!!.startable)
    assertFalse("a scheme it doesn't know", CoachStart.read(json(start().replace("kiff.requests.v1", "kiff.requests.v9")))!!.startable)
    assertFalse("never Cards without KIFF's words", CoachStart.read(json(start().replace(""""cards":[{"title":"Reads","lines":["See your tafs, up to 500 a day"]}]""", """"cards":[]""")))!!.startable)
    assertNull("one the app can't read is none", CoachStart.read(KiffClient.json.parseToJsonElement("[1]")))
  }

  @Test fun theFirstSendStartsCoachUnderOneFingerprintFromStartsScreen() = runTest {
    reads("""{"setup":null,"tools":null,"start":${start()}}""")
    val m = model()
    m.loadCoach()
    assertEquals(true, m.state.value.coachStart?.startable)
    assertEquals(true, m.state.value.coachTools?.toGive)
    startAnswers()
    // Start's screen already shows the Cards in KIFF's words: the first Send starts Coach, with no second screen.
    assertTrue(m.startCoach())
    assertFalse(m.state.value.coachToolsAsked)
    // One fingerprint for the batch and the draft with its Cards; the enrolment and the claim go under it.
    assertEquals(listOf("Start Coach"), auth.fingerprints)
    assertEquals(listOf("Start Coach"), auth.asked)
    assertEquals("setup_1", m.state.value.coachCloudId)
    assertNull(m.state.value.coachStart)
    assertFalse(m.state.value.coachToolsAsked)
    assertEquals("given", m.state.value.coachTools?.state)

    // This phone enrolled first, before anything went to KIFF for Coach; then the batch, the draft and the claim. Never an empty draft.
    val order = transport.requests.filter { it.method == "POST" }.map { java.net.URI.create(it.url).path }
      .filter { it in listOf("/v1/accounts/t_01/enrolment", "/v1/connections/batch", "/v1/me/agent-drafts", "/v1/setup") }
    assertEquals(listOf("/v1/accounts/t_01/enrolment", "/v1/connections/batch", "/v1/me/agent-drafts", "/v1/setup"), order)
    assertTrue(transport.requestsTo("POST /v1/connections/batch").single().url.startsWith("https://mcp."))
    // Each body as tafs cloud wrote it, its contract digest with it.
    assertEquals(json(batchBody), sentBody("POST /v1/connections/batch"))
    assertEquals(json(draftBody), sentBody("POST /v1/me/agent-drafts"))
    assertEquals(mapOf("draft_id" to "drf_s", "claim_secret" to "kiff_claim_x"), sentBody("POST /v1/setup").mapValues { it.value.toString().trim('"') })

    // One signed_at and one signature; each request names the other's hash, and that hash is its message's.
    val batch = header("POST /v1/connections/batch")
    val draft = header("POST /v1/me/agent-drafts")
    assertEquals(batch.getValue("t"), draft.getValue("t"))
    assertEquals(batch.getValue("v3"), draft.getValue("v3"))
    assertNotEquals(batch.getValue("with"), draft.getValue("with"))
    val t = batch.getValue("t")
    val batchHash = KiffClient.messageHash(KiffClient.requestMessage("t_01", "POST", "/v1/connections/batch", transport.requestsTo("POST /v1/connections/batch").single().body!!, t))
    val draftHash = KiffClient.messageHash(KiffClient.requestMessage("t_01", "POST", "/v1/me/agent-drafts", transport.requestsTo("POST /v1/me/agent-drafts").single().body!!, t))
    assertEquals("sha256:$draftHash", batch.getValue("with"))
    assertEquals("sha256:$batchHash", draft.getValue("with"))
    // The enrolment is signed under the same confirmation: no second prompt.
    assertNotNull(transport.requestsTo("POST /v1/keys").single().headers["KIFF-Device-Signature"])
  }

  @Test fun anEnrolmentPlanqueRefusesSendsNothingToKiff() = runTest {
    reads("""{"setup":null,"tools":null,"start":${start()}}""")
    val m = model()
    m.loadCoach()
    transport.answer("POST /v1/keys", 200, """{"plaintext":"kk_connector"}""")
    transport.answer("POST /v1/accounts/t_01/enrolment", 409, """{"error":"another device is enrolled for this account"}""")
    assertFalse(m.giveCoachTools())
    // No tools in KIFF and no confirmed draft nobody claims: the next Start begins clean.
    assertTrue(transport.requestsTo("POST /v1/connections/batch").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/setup").isEmpty())
    assertEquals(bot.tafs.app.model.Words.Coach.connectOtherDevice, m.state.value.coachToolsError)
    assertEquals(listOf("Start Coach"), auth.fingerprints)
  }

  @Test fun theDraftAloneGoesUnderItsOwnSingleFingerprint() = runTest {
    reads("""{"setup":null,"tools":null,"start":${start(pair = false)}}""")
    val m = model()
    m.loadCoach()
    startAnswers()
    assertTrue(m.giveCoachTools())
    assertEquals(listOf("Start Coach"), auth.fingerprints)
    assertTrue(transport.requestsTo("POST /v1/connections/batch").isEmpty())
    assertEquals(json(draftBody), sentBody("POST /v1/me/agent-drafts"))
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts").single().headers.getValue("KIFF-Device-Signature").contains(",v1="))
    // The fingerprint first, then the enrolment, then the draft.
    val order = transport.requests.filter { it.method == "POST" }.map { java.net.URI.create(it.url).path }
      .filter { it in listOf("/v1/accounts/t_01/enrolment", "/v1/me/agent-drafts") }
    assertEquals(listOf("/v1/accounts/t_01/enrolment", "/v1/me/agent-drafts"), order)
    assertEquals("setup_1", m.state.value.coachCloudId)
  }

  @Test fun aDraftRefusedAfterTheBatchWentInIsSaidAndTheNextReadCarriesTheDraftAlone() = runTest {
    reads("""{"setup":null,"tools":null,"start":${start()}}""", """{"setup":null,"tools":null,"start":${start(pair = false)}}""")
    val m = model()
    m.loadCoach()
    transport.answer("POST /v1/keys", 200, """{"plaintext":"kk_connector"}""")
    transport.answer("POST /v1/accounts/t_01/enrolment", 200, """{"tenant":"t_01","device":"ab12"}""")
    transport.answer("POST /v1/connections/batch", 200, "{}")
    transport.answer("POST /v1/me/agent-drafts", 409, """{"error":"the tools changed since these Cards were worded; read them again","code":"contract_changed"}""")
    assertFalse(m.giveCoachTools())
    assertNull(m.state.value.coachCloudId)
    // Said in the app's words for a change, in the person's language, as any KIFF 409.
    assertEquals(bot.tafs.app.model.Words.Coach.couldntStart(why = bot.tafs.app.model.ErrorWords.of(409)), m.state.value.coachToolsError)
    assertTrue(transport.requestsTo("POST /v1/setup").isEmpty())
    // Read again: the batch is in, so the start is the draft alone.
    assertEquals(false, m.state.value.coachStart?.together)
    assertEquals(true, m.state.value.coachStart?.startable)
  }

  @Test fun withNoStartCoachStartsAsBefore() = runTest {
    reads("""{"setup":null}""")
    val m = model()
    m.loadCoach()
    assertNull(m.state.value.coachStart)
    assertFalse(m.giveCoachTools())
    assertFalse(m.state.value.coachToolsAsked)
  }
}
