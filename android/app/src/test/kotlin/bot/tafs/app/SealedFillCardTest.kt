@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.ChromePairing
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SealedRelease
import bot.tafs.app.model.Answer
import bot.tafs.app.model.Words
import bot.tafs.app.signing.ApprovalSigning
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release card (docs/chrome.md, "The phone's release card"), behind its switch: a held sealed fill is read byte for byte, and
 * Allow asks two fingerprints, the release's then the answer's, each naming what it signs. Nothing is sent unless both are signed,
 * and the phone signs nothing its own checks refuse. A fake KIFF and planque stand in.
 */
class SealedFillCardTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer: ApprovalSigning = softwareSigner(auth)
  private val t0 = "2026-10-28T09:00:00Z"
  private val chromeId = "chr_01HZX5"
  private val grant = "g_7Q2M"
  private val handle = "sec_Ab3dE-f_9q:password"
  private val approve = "POST /v1/me/card-exceptions/ce_1/approve-once"

  /** The grant's sites as this phone signed them, with its approval key (`tafs-chrome/sites/v1`), as planque relays them. */
  private suspend fun sitesSig(sites: List<String>): String {
    val der = signer.signApproval(ChromePairing.sitesBytes(chromeId, grant, sites), "sites")
    return ChromePairing.rawSignature(Base64.getDecoder().decode(der))!!
  }

  private fun held(fill: String) =
    """{"id":"ce_1","subject":"a-1","action":"browser_act","title":"Fill a field","entity_id":"op_9K3T","proposal_id":"prop_1","accounting_version":1,
       "next_steps":[],"breaches":[],"arguments":{"op":"op_9K3T"},"created_at":"$t0","expires_at":"2099-01-01T00:00:00Z",
       "browser":{"site":"shop.example","origin":"https://shop.example","action_words":"Fill “Password”","context":{"title":"Sign in · Shop"},
         "chrome":{"id":"$chromeId","name":"Work Mac"},"sealed_fill":$fill}}"""

  private fun fill(sitesSig: String, sealed: String = handle, chrome: String = chromeId) =
    """{"chrome_id":"$chrome","op":"op_9K3T","sealed":"$sealed","origin":"https://shop.example","field_ref":"f2","read_id":"rd_7c1",
       "grant":"$grant","field_label":"Password","sites_signed":{"sites":["shop.example"],"sig":"$sitesSig"},"sensitive_allowed":[]}"""

  private suspend fun kotlinx.coroutines.test.TestScope.model(held: String, on: Boolean = true): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"id_a-1","name":"A","kiff_agent":"a-1","color":1,"state":"waiting","card":{"ids":["c_a-1"],"active":true,"tools":[],"rules":[]}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$held]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    transport.answer("GET /v1/accounts/t_01/chromes", 200, """{"chromes":[{"chrome_id":"$chromeId","name":"Work Mac"}]}""")
    transport.answer(approve, 200, "{}")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, start = false, scope = backgroundScope).also {
      it.sealedReleaseOn = on
      it.restore()
      it.refreshConnections()
      it.refreshChromes()
    }
  }

  private fun sent(): JsonObject = KiffClient.json.parseToJsonElement(String(transport.requestsTo(approve).single().body!!)).jsonObject

  @Test fun aHeldSealedFillIsReadByteForByteAndItsPartFromTheHandle() = runTest {
    val m = model(held(fill(sitesSig = "sig")))
    val step = m.state.value.held.single().browser!!
    val f = step.fill!!
    assertEquals(chromeId, f.chromeId)
    assertEquals("op_9K3T", f.op)
    assertEquals(handle, f.sealed)
    assertEquals("https://shop.example", f.origin)
    assertEquals("f2", f.fieldRef)
    assertEquals("rd_7c1", f.readId)
    assertEquals(grant, f.grant)
    assertEquals(SealedRelease.SitesSigned(listOf("shop.example"), "sig"), f.sitesSigned)
    assertEquals(java.time.Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(), f.expiresAt)
    assertEquals("Password", step.fieldLabel)
    assertEquals(SealedRelease.Part.PASSWORD, SealedRelease.part(f.sealed))
  }

  @Test fun allowAsksTheReleaseThenTheAnswerAndSendsBothTogether() = runTest {
    val m = model(held(fill(sitesSig(listOf("shop.example")))))
    auth.asked.clear(); auth.fingerprints.clear()
    val call = m.state.value.held.single()
    assertNull("the phone's checks hold", m.fillRefusal(call))
    assertTrue(m.answer(call, Answer.ApproveOnce))
    // Two fingerprints, each naming what it signs: the release first, then the answer.
    assertEquals(listOf(Words.Chrome.releasePromptPassword(site = "shop.example"), Words.Chrome.releasePromptAllow(site = "shop.example")), auth.asked)
    val body = sent()
    assertTrue("the answer's own signature", body.containsKey("signature"))
    val r = body.getValue("release").jsonObject
    assertEquals(chromeId, r.getValue("chrome_id").jsonPrimitive.content)
    assertEquals("op_9K3T", r.getValue("op").jsonPrimitive.content)
    assertEquals("https://shop.example", r.getValue("origin").jsonPrimitive.content)
    val nonce = r.getValue("nonce").jsonPrimitive.content
    val exp = r.getValue("exp").jsonPrimitive.long
    assertEquals(java.time.Instant.parse(t0).toEpochMilli() + SealedRelease.VALID_MS, exp)
    // What the extension checks: the approval key over the eight fields, raw r‖s.
    val signed = SealedRelease.bytes(chromeId, "op_9K3T", handle, "https://shop.example", "f2", "rd_7c1", nonce, exp)
    assertTrue(ChromePairing.verifiesRaw(signer.approvalPublicKeyDER, signed, r.getValue("sig").jsonPrimitive.content))
  }

  @Test fun closingTheSecondPromptSendsNothingNotEvenTheRelease() = runTest {
    val m = model(held(fill(sitesSig(listOf("shop.example")))))
    auth.asked.clear()
    // The release's fingerprint is given; the answer's is closed.
    var prompts = 0
    auth.whilePrompted = { prompts++; auth.confirms = prompts < 2 }
    assertFalse(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    assertEquals(2, auth.asked.size)
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertEquals(1, m.state.value.held.size)
  }

  @Test fun aFillThePhonesChecksRefuseAsksNoFingerprintAndSendsNothing() = runTest {
    // Another Chrome than the one paired here.
    val m = model(held(fill(sitesSig(listOf("shop.example")), chrome = "chr_other")))
    auth.asked.clear()
    val call = m.state.value.held.single()
    assertEquals(SealedRelease.Refusal.CHROME, m.fillRefusal(call))
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
    // Each refusal the card can show has its words; a late hold is said by the card's own expired line.
    for (r in SealedRelease.Refusal.entries) {
      assertEquals(r.name, r == SealedRelease.Refusal.EXPIRED, SealedRelease.refusalWords(r) == null)
    }
  }

  @Test fun aHandleOfAnyOtherShapeIsRefusedAsUnreadable() = runTest {
    val m = model(held(fill(sitesSig(listOf("shop.example")), sealed = "sec_Ab3dE-f_9q:password:username")))
    val call = m.state.value.held.single()
    assertEquals(SealedRelease.Refusal.HANDLE, m.fillRefusal(call))
    assertEquals(Words.Chrome.releaseUnreadable, SealedRelease.refusalWords(SealedRelease.Refusal.HANDLE))
  }

  @Test fun withTheSwitchOffTheHoldIsAnsweredAsAnyAndNoReleaseIsMade() = runTest {
    assertFalse(SealedRelease.ENABLED)
    val m = model(held(fill(sitesSig(listOf("shop.example")))), on = false)
    auth.asked.clear()
    assertTrue(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    assertEquals(1, auth.asked.size)
    assertFalse(sent().containsKey("release"))
  }
}
