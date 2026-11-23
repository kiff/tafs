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

  /** The grant's sites as this phone signed them, with its approval key (`tafs-chrome/sites/v1`), as planque lists the grant. */
  private suspend fun sitesSig(sites: List<String>): String {
    val der = signer.signApproval(ChromePairing.sitesBytes(chromeId, grant, sites), "sites")
    return ChromePairing.rawSignature(Base64.getDecoder().decode(der))!!
  }

  /** planque's grants for the account: one connection on the paired Chrome, with this phone's signature over its sites. */
  private suspend fun grants(sites: List<String> = listOf("shop.example"), sig: String? = null): String {
    val s = sig ?: sitesSig(sites)
    val list = sites.joinToString(",", "[", "]") { "\"$it\"" }
    return """{"grants":[{"id":"$grant","status":"connected","text":"","digest":"d","request":{"server":"s","agent":"a","tools":[],
      "browser":{"driver":"extension","chrome":"$chromeId","sites":$list}},"chrome_sites_sig":"$s"}]}"""
  }

  private fun held(fill: String) =
    """{"id":"ce_1","subject":"a-1","action":"browser_act","title":"Fill a field","entity_id":"op_9K3T","proposal_id":"prop_1","accounting_version":1,
       "next_steps":[],"breaches":[],"arguments":{"op":"op_9K3T"},"created_at":"$t0","expires_at":"2099-01-01T00:00:00Z",
       "browser":{"site":"shop.example","origin":"https://shop.example","action_words":"Fill “Password”","context":{"title":"Sign in · Shop"},
         "chrome":{"id":"$chromeId","name":"Work Mac"},"release_fields":$fill}}"""

  /** planque's block's `release_fields`: what the release signs, and nothing else. */
  private fun fill(sealed: String = handle, chrome: String = chromeId) =
    """{"chrome_id":"$chrome","op":"op_9K3T","sealed":"$sealed","origin":"https://shop.example","field_ref":"f2","read_id":"rd_7c1"}"""

  private suspend fun kotlinx.coroutines.test.TestScope.model(held: String, on: Boolean = true, grantsJson: String? = null): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"id_a-1","name":"A","kiff_agent":"a-1","color":1,"state":"waiting","card":{"ids":["c_a-1"],"active":true,"tools":[],"rules":[]}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$held]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, grantsJson ?: grants())
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
    val m = model(held(fill()))
    val step = m.state.value.held.single().browser!!
    val f = step.fill!!
    assertEquals(chromeId, f.chromeId)
    assertEquals("op_9K3T", f.op)
    assertEquals(handle, f.sealed)
    assertEquals("https://shop.example", f.origin)
    assertEquals("f2", f.fieldRef)
    assertEquals("rd_7c1", f.readId)
    assertEquals(java.time.Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(), f.expiresAt)
    // What's drawn is the block's own words, as plain text: the site, the Chrome's name and the page's title.
    assertEquals("shop.example", step.site)
    assertEquals("Work Mac", step.chrome)
    assertEquals("Sign in · Shop", step.pageTitle)
    assertEquals(SealedRelease.Part.PASSWORD, SealedRelease.part(f.sealed))
  }

  @Test fun theSitesComeFromThisPhonesOwnGrantsNotTheBlock() = runTest {
    // planque lists the connection with a site this phone never signed: the signature no longer holds, so nothing is signed.
    val widened = grants(sites = listOf("other.example", "shop.example"), sig = sitesSig(listOf("other.example")))
    val m = model(held(fill()), grantsJson = widened)
    auth.asked.clear()
    val call = m.state.value.held.single()
    assertEquals(SealedRelease.Refusal.NOT_SIGNED, m.fillRefusal(call))
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
  }

  @Test fun allowAsksTheReleaseThenTheAnswerAndSendsBothTogether() = runTest {
    val m = model(held(fill()))
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
    val m = model(held(fill()))
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
    val m = model(held(fill(chrome = "chr_other")))
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
    val m = model(held(fill(sealed = "sec_Ab3dE-f_9q:password:username")))
    val call = m.state.value.held.single()
    assertEquals(SealedRelease.Refusal.HANDLE, m.fillRefusal(call))
    assertEquals(Words.Chrome.releaseUnreadable, SealedRelease.refusalWords(SealedRelease.Refusal.HANDLE))
  }

  @Test fun withTheSwitchOffASealedFillCanOnlyBeRefused() = runTest {
    assertFalse(SealedRelease.ENABLED)
    val m = model(held(fill()), on = false)
    auth.asked.clear()
    val call = m.state.value.held.single()
    // Never an allowing answer without its release: nothing is asked, nothing is sent, and the card says why.
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertEquals(Words.Chrome.releaseUnreadable, m.state.value.lastError)
    // Refusing takes power away: it's always open.
    transport.answer("POST /v1/me/card-exceptions/ce_1/reject", 200, "{}")
    assertTrue(m.answer(call, Answer.Refuse))
    assertEquals(1, transport.requestsTo("POST /v1/me/card-exceptions/ce_1/reject").size)
  }

  @Test fun aPresignedAnswerNeverAllowsASealedFill() = runTest {
    val m = model(held(fill()))
    auth.asked.clear()
    // An answer signed elsewhere (a notification's action, an approval window's tap) carries no release.
    val presigned = AppModel.SignedAnswer("sig", KiffClient.AnswerSignature("2026-10-28T09:00:00Z", "sig"))
    assertFalse(m.answer(m.state.value.held.single(), Answer.ApproveOnce, presigned = presigned))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
  }

  @Test fun extraRoomNeverLetsASealedFillGoOn() = runTest {
    val m = model(held(fill()))
    auth.asked.clear()
    assertFalse(m.answer(m.state.value.held.single(), Answer.ExtraRoom))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/card-exceptions/ce_1/change-card").isEmpty())
    assertEquals(Words.Chrome.releaseUnreadable, m.state.value.lastError)
  }

  @Test fun noStandingRuleIsOfferedForASealedFill() = runTest {
    val m = model(held(fill()))
    val call = m.state.value.held.single()
    // Even over a limit "Allow this from now on" could change: a sealed fill is always held, and allowed only with its release.
    val overALimit = call.copy(breachOutcomes = listOf(bot.tafs.app.model.HoldAllow.ALLOWABLE.first()))
    assertTrue(overALimit.needsRelease)
    assertTrue(bot.tafs.app.model.HoldAllow.allowable(overALimit.copy(needsRelease = false, browser = overALimit.browser!!.copy(fill = null))))
    assertFalse(bot.tafs.app.model.HoldAllow.allowable(overALimit))
    assertFalse(m.openAllowNow(overALimit))
  }

  /** A held call as KIFF sends it, with [browser] as its block's raw JSON. */
  private fun heldWith(id: String, browser: String) =
    """{"id":"$id","subject":"a-1","action":"browser_act","title":"A step","entity_id":"op_$id","proposal_id":"prop_$id","accounting_version":1,
       "next_steps":[],"breaches":[],"arguments":{"op":"op_$id"},"created_at":"$t0","expires_at":"2099-01-01T00:00:00Z","browser":$browser}"""

  @Test fun aFillThatCantBeDrawnStillCantBeAllowed() = runTest {
    // A sealed fill with no site: the step's block can't be drawn, yet it's a fill, so it needs its release all the same.
    val noSite = held(fill()).replace(""""site":"shop.example",""", """"site":"",""")
    val m = model(noSite)
    auth.asked.clear()
    val call = m.state.value.held.single()
    assertNull(call.browser)
    assertTrue(call.needsRelease)
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
  }

  @Test fun aBlockThatDoesntReadIsAFillAndHidesNoOtherHeldCall() = runTest {
    val blocks = listOf(
      "\"sealed\"" to true,
      "[1]" to true,
      """{"site":"shop.example","action_words":"Fill","release_fields":"sec_x:password"}""" to true,
      """{"site":"shop.example","action_words":"Fill","release_fields":{"chrome_id":1}}""" to true,
      """{"site":"shop.example","action_words":"Fill","sealed_fill":"sec_x:password"}""" to true,
      """{"site":"shop.example","action_words":"Fill","fill":{"field_ref":"f1"}}""" to true,
      """{"site":"shop.example","action_words":"Click","sealed_fill":null}""" to false,
      """{"site":"shop.example","action_words":"Click","release_fields":null}""" to false,
      "null" to false,
    )
    val all = blocks.mapIndexed { i, (b, _) -> heldWith("ce_$i", b) }.joinToString(",")
    // An ordinary held call beside them stays shown, with its Refuse.
    val m = model(all + "," + heldWith("ce_ok", "null"))
    val held = m.state.value.held
    assertEquals(blocks.size + 1, held.size)
    blocks.forEachIndexed { i, (b, fill) ->
      val call = held.single { it.kiffId == "ce_$i" }
      assertEquals(b, fill, call.needsRelease)
    }
    // An unreadable one can't be allowed: nothing asked, nothing sent.
    auth.asked.clear()
    assertFalse(m.answer(held.single { it.kiffId == "ce_0" }, Answer.ApproveOnce))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/card-exceptions/ce_0/approve-once").isEmpty())
  }

  @Test fun aSealedFillWithNoEndIsExpiredNotTenMinutes() = runTest {
    val m = model(held(fill()).replace(""","expires_at":"2099-01-01T00:00:00Z"""", ""))
    val call = m.state.value.held.single()
    assertEquals(0L, call.browser!!.fill!!.expiresAt)
    assertEquals(SealedRelease.Refusal.EXPIRED, m.fillRefusal(call))
  }
}
