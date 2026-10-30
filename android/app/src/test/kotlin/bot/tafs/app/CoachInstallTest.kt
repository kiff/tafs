package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.CardException
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.model.Answer
import bot.tafs.app.model.VersionInstall
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coach's held version_install (kiff/tafs#206): approving it is installing the version, then Coach hears. Fakes stand in for KIFF and tafs cloud. */
class CoachInstallTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private val coach = "setup-acme"
  private fun installHeld(subject: String = coach, args: String = """{"taf":"taf_1","n":2}""") =
    """{"id":"ce_7","subject":"$subject","action":"version_install","title":"Install a version","entity_id":"tc-7","proposal_id":"prop_7","accounting_version":1,
       "breaches":[{"outcome":"limit_reached","reason":"every install waits for you"}],"arguments":$args,
       "created_at":"2026-10-28T09:00:00Z","expires_at":"2099-01-01T00:00:00Z"}"""

  private val home = """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}"""

  private val versions = "GET /v1/tafs/taf_1/versions"
  private val switch2 = "POST /v1/tafs/taf_1/versions/2/switch"
  private val live2 = "POST /v1/tafs/taf_1/versions/2/live"
  private val cancel = "DELETE /v1/tafs/taf_1/switching"
  private val approve = "POST /v1/me/card-exceptions/ce_7/approve-once"

  private fun state(v2Live: Boolean, v2Draft: Boolean) =
    """{"versions":[{"n":1,"created":"2026-10-20T09:00:00Z","live":${!v2Live}},{"n":2,"note":"Asks before refunds over 50","by":"setup","created":"2026-10-27T09:00:00Z","live":$v2Live${if (v2Draft) ""","draft":"dr_1"""" else ""}}],"switching":null}"""

  private fun reads(held: String = installHeld(), coachNamed: Boolean = true) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["holds.answer","cards.manage","members.manage"]}""")
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$held]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true}}}""")
    transport.answer("GET /v1/setup", 200, if (coachNamed) """{"setup":{"id":"setup_1","name":"Coach","kiff_agent":"$coach"},"tools":{"state":"given"}}""" else """{"setup":null}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  private fun order() = transport.requests.map { it.method + " " + URI(it.url).path }

  // MARK: Which held call is an install

  private fun exception(json: String) = KiffClient.json.decodeFromString<CardException>(json)

  @Test fun onlyCoachsOwnHeldVersionInstallIsAnInstall() {
    assertEquals(VersionInstall("taf_1", 2), KiffMapping.install(exception(installHeld()), coach))
    assertNull("another agent's call of the same name", KiffMapping.install(exception(installHeld(subject = "refunds-1")), coach))
    assertNull("no Coach named by tafs cloud", KiffMapping.install(exception(installHeld()), null))
    assertNull("no version", KiffMapping.install(exception(installHeld(args = """{"taf":"taf_1"}""")), coach))
    assertNull("no taf", KiffMapping.install(exception(installHeld(args = """{"n":2}""")), coach))
    assertEquals(VersionInstall("Refunds", 3), KiffMapping.install(exception(installHeld(args = """{"taf":"Refunds","n":"3"}""")), coach))
  }

  @Test fun theListReadsCoachsAgentToKnowItsInstall() = runTest {
    reads()
    val m = model()
    assertEquals(VersionInstall("taf_1", 2), m.state.value.held.single().install)
  }

  @Test fun withNoCoachFromTafsCloudItAsksAsAnyHeldCall() = runTest {
    reads(coachNamed = false)
    val m = model()
    assertNull(m.state.value.held.single().install)
  }

  // MARK: Installing

  @Test fun approvingAnInstallAloneIsRefusedSoCoachNeverHearsOfAHalfDoneOne() = runTest {
    reads()
    val m = model()
    assertEquals(false, m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    assertEquals("Install it from its card: the version goes live, then Coach hears.", m.state.value.lastError)
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun anInstallWithNoCardAsksMakesTheVersionLiveThenApprovesCoachsCallWithAFingerprint() = runTest {
    reads()
    transport.answer(versions, 200, state(v2Live = false, v2Draft = false))
    transport.answer(switch2, 200, """{"switching":{"n":2,"draft":null,"at":"2026-10-28T09:00:00Z"}}""")
    transport.answer(live2, 200)
    transport.answer(approve, 200)
    val m = model()
    val call = m.state.value.held.single()
    val ready = m.beginCoachInstall(call)
    assertNotNull(ready)
    assertEquals(false, ready!!.hasCard)
    assertEquals("refunds-1", ready.tafId)
    // What's in the way is stopped first.
    assertEquals("true", KiffClient.json.parseToJsonElement(String(transport.requestsTo(switch2).first().body!!)).jsonObject.getValue("stop_blocking").jsonPrimitive.content)
    assertTrue(m.useVersion(ready.version, ready.tafId, null, approving = call))
    // The switch, then Coach's held call: approving it takes its own fingerprint.
    assertEquals(listOf("Use version 2 of Refunds", "Answer Install a version"), auth.asked)
    assertEquals(listOf("Answer Install a version"), auth.fingerprints)
    val o = order()
    assertTrue("live before Coach hears", o.indexOf(live2) < o.indexOf(approve))
    assertNotNull("signed", transport.requestsTo(approve).single().body)
  }

  @Test fun anInstallWithACardConfirmsTheCardShownThenGoesLiveThenApprovesCoachsCallEachWithAFingerprint() = runTest {
    reads()
    transport.answer(versions, 200, state(v2Live = false, v2Draft = true))
    transport.answer(switch2, 200, """{"switching":{"n":2,"draft":"dr_1","at":"2026-10-28T09:00:00Z"}}""")
    transport.answer("GET /v1/me/agent-drafts/dr_1", 200, """{"id":"dr_1","agent_id":"refunds-1","status":"draft","version":"v3","cards":[]}""")
    transport.answer("POST /v1/me/agent-drafts/dr_1/confirm", 200, """{"id":"dr_1","status":"confirmed","version":"v3"}""")
    transport.answer(live2, 200)
    transport.answer(approve, 200)
    val m = model()
    val call = m.state.value.held.single()
    val ready = m.beginCoachInstall(call)!!
    assertTrue(ready.hasCard)
    assertTrue(m.useVersion(ready.version, ready.tafId, "v3", approving = call))
    // No presence prompt: KIFF's confirm of the Card shown, then Coach's held call, each behind its own fingerprint.
    assertEquals(listOf("Use version 2 of Refunds, with its Card", "Answer Install a version"), auth.fingerprints)
    assertEquals(auth.fingerprints, auth.asked)
    val o = order()
    assertTrue(o.indexOf("POST /v1/me/agent-drafts/dr_1/confirm") < o.indexOf(live2))
    assertTrue(o.indexOf(live2) < o.indexOf(approve))
    // The new Card is read again once live, so the taf's page shows its limits, not the old ones.
    assertTrue(o.lastIndexOf("GET /v1/home") > o.indexOf(live2))
  }

  @Test fun aVersionLiveAlreadyIsOnlyToldToCoach() = runTest {
    reads()
    transport.answer(versions, 200, state(v2Live = true, v2Draft = false))
    transport.answer(approve, 200)
    val m = model()
    assertNull(m.beginCoachInstall(m.state.value.held.single()))
    assertTrue(transport.requestsTo(switch2).isEmpty())
    assertEquals(1, transport.requestsTo(approve).size)
    assertEquals(1, auth.asked.size)
  }

  @Test fun anOwnerWhoBacksOutLeavesTheLiveVersionAndCoachUnanswered() = runTest {
    reads()
    transport.answer(versions, 200, state(v2Live = false, v2Draft = false))
    transport.answer(switch2, 200, """{"switching":{"n":2,"draft":null,"at":"2026-10-28T09:00:00Z"}}""")
    transport.answer(cancel, 200, """{"cancelled":true}""")
    val m = model()
    val call = m.state.value.held.single()
    val ready = m.beginCoachInstall(call)!!
    auth.confirms = false
    assertEquals(false, m.useVersion(ready.version, ready.tafId, null, approving = call))
    assertEquals(1, transport.requestsTo(cancel).size)
    assertTrue(transport.requestsTo(live2).isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
  }

  @Test fun aTafThatIsNotHereOrAVersionItLacksSaysSo() = runTest {
    reads(held = installHeld(args = """{"taf":"Payouts","n":2}"""))
    val m = model()
    assertNull(m.beginCoachInstall(m.state.value.held.single()))
    assertEquals("Coach asked to install a version of a taf that isn't here.", m.state.value.lastError)
  }
}
