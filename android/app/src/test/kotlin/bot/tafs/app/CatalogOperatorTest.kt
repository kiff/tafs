package bot.tafs.app

import bot.tafs.app.catalog.CatalogDisagreement
import bot.tafs.app.catalog.CatalogOperator
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SigningTime
import bot.tafs.app.signing.CatalogKeys
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog operator (docs/android-app.md, "Catalog operator"): the catalog key signs the connection server's
 * `planque.catalog.v1` message for one revision, behind the fingerprint, only when the phone's own JCS SHA-256 of the entry is the
 * server's digest. Fakes stand in for the server, the Keystore and the fingerprint.
 */
class CatalogOperatorTest {
  /** A catalog key in memory, as the Keystore's would sign. */
  private class FakeCatalogKeys : CatalogKeys {
    var pair: KeyPair? = null
    override val publicKeyDER: ByteArray get() = pair?.public?.encoded ?: ByteArray(0)
    override fun make() {
      if (pair == null) pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
    override fun signature(): Signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair!!.private) }
  }

  private val base = "https://planque.example.test"
  private val transport = FakeTransport()
  private val keys = FakeCatalogKeys()
  private val auth = FakeAuthenticator()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-11-12T22:00:00Z")
  private val operator = CatalogOperator(transport, base, keys, auth, secrets, SigningTime { t0 })

  private val entry = """{"id":"qonto-key@2","vendor":"Qonto","title":"Qonto invoicing","kind":"api_key",
    "api":{"url":"https://specs.example.test/qonto.json","base":"https://thirdparty.example.test/v2","auth":{"in":"header","name":"Authorization"}},
    "description_sha256":"ab","tools":[{"tool":"list_quotes","name":"qonto_list_quotes","title":"List the quotes","read_only":true},
    {"tool":"create_client_invoice","name":"qonto_create_invoice","title":"Draft a client invoice","fixed_arguments":{"status":"draft","currency":"EUR"}}]}"""
  private val entryDigest = CatalogOperator.digestOf(KiffClient.json.parseToJsonElement(entry))

  private fun revision(state: String = "reviewed", digest: String = entryDigest, body: String = entry) =
    """{"id":"qonto-key@2","entry":$body,"digest":"$digest","state":"$state","drafted_by":"op_a","changed_by":"op_a","changed":"2026-11-12T21:00:00Z",
       "described":{"from":"description","description_sha256":"ab","tools":[
         {"tool":"list_quotes","method":"GET","path":"/v2/quotes","read_only":true},
         {"tool":"create_client_invoice","method":"POST","path":"/v2/client_invoices","read_only":false,"final_arguments":[{"argument":"status","undo":"draft"}]}]}}"""

  private val read = "GET /v1/catalog/entries/qonto-key@2"
  private val publish = "POST /v1/catalog/entries/qonto-key@2/publish"
  private val retire = "POST /v1/catalog/entries/qonto-key@2/retire"

  private fun enrolled() {
    assertTrue(operator.enrol("op_b"))
  }

  @Test fun theMessageIsTheConnectionServersLineByLine() {
    val message = String(CatalogOperator.message("$base/", "publish", "qonto-key@2", "d1", "n1", "2026-11-12T22:00:00.000Z"))
    assertEquals("planque.catalog.v1\n$base\npublish\nqonto-key@2\nd1\nn1\n2026-11-12T22:00:00.000Z", message)
  }

  @Test fun enrollingMakesTheKeyAndKeepsTheOperatorAndOnlyThenTheRowShows() {
    operator.restore()
    assertFalse("nothing made yet: no Settings row", operator.state.value.enrolled)
    assertFalse("an empty id is no enrolment", operator.enrol("  "))
    enrolled()
    assertTrue(operator.state.value.enrolled)
    assertEquals("op_b", secrets.load(CatalogOperator.OPERATOR))
    assertEquals(Base64.getEncoder().encodeToString(keys.publicKeyDER), operator.state.value.publicKey)
  }

  @Test fun aReviewedRevisionIsPublishedWithOneFingerprintAndASignatureTheServerCanCheck() = runTest {
    enrolled()
    transport.answer(read, 200, revision())
    transport.answer(publish, 200, """{"id":"qonto-key@2","state":"published"}""")
    assertTrue(operator.open("qonto-key@2"))
    transport.answer(read, 200, revision(state = "published"))
    assertTrue(operator.act(CatalogOperator.PUBLISH))
    assertEquals(listOf("Publish qonto-key@2"), auth.fingerprints)

    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo(publish).single().body!!)).jsonObject
    assertEquals("op_b", body.getValue("operator").jsonPrimitive.content)
    assertEquals(entryDigest, body.getValue("digest").jsonPrimitive.content)
    val nonce = body.getValue("nonce").jsonPrimitive.content
    assertTrue("16 to 64 of A-Z a-z 0-9 _ -", Regex("^[A-Za-z0-9_-]{16,64}$").matches(nonce))
    val signedAt = body.getValue("signed_at").jsonPrimitive.content
    assertEquals("2026-11-12T22:00:00.000Z", signedAt)
    val verify = Signature.getInstance("SHA256withECDSA")
    verify.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keys.publicKeyDER)))
    verify.update(CatalogOperator.message(base, "publish", "qonto-key@2", entryDigest, nonce, signedAt))
    assertTrue("the server's check: ECDSA over the SHA-256 of its message", verify.verify(Base64.getDecoder().decode(body.getValue("signature").jsonPrimitive.content)))
    assertEquals("read again after", "published", operator.state.value.sheet!!.revision!!.state)
    assertEquals("qonto-key@2 is published.", operator.state.value.sheet!!.done)
  }

  @Test fun anEntryThatIsntWhatTheDigestCoversIsNeverSigned() = runTest {
    enrolled()
    // The server's digest is of another entry than the one it sends: the phone's own digest differs, and nothing is offered.
    transport.answer(read, 200, revision(body = entry.replace("\"draft\"", "\"sent\"")))
    assertFalse(operator.open("qonto-key@2"))
    assertFalse(operator.state.value.sheet!!.matches)
    assertFalse(operator.act(CatalogOperator.PUBLISH))
    assertTrue("no fingerprint asked", auth.fingerprints.isEmpty())
    assertTrue("nothing sent", transport.requestsTo(publish).isEmpty())
  }

  @Test fun onlyTheActionTheRevisionsStateAllowsIsOffered() = runTest {
    enrolled()
    assertEquals(CatalogOperator.PUBLISH, CatalogOperator.expectedAction("reviewed"))
    assertEquals(CatalogOperator.RETIRE, CatalogOperator.expectedAction("published"))
    assertNull(CatalogOperator.expectedAction("draft"))
    assertNull(CatalogOperator.expectedAction("retired"))
    transport.answer(read, 200, revision(state = "draft"))
    assertTrue(operator.open("qonto-key@2"))
    assertFalse("a draft isn't published", operator.act(CatalogOperator.PUBLISH))
    assertFalse(operator.act(CatalogOperator.RETIRE))
    assertTrue(auth.fingerprints.isEmpty())
    transport.reset()
    transport.answer(read, 200, revision(state = "published"))
    transport.answer(retire, 200, "{}")
    assertTrue(operator.open("qonto-key@2"))
    assertFalse("a published one isn't published again", operator.act(CatalogOperator.PUBLISH))
    assertTrue(operator.act(CatalogOperator.RETIRE))
    assertEquals(listOf("Retire qonto-key@2"), auth.fingerprints)
  }

  @Test fun aRefusalIsSaidInTheServersWordsAndAFingerprintNotGivenSendsNothing() = runTest {
    enrolled()
    transport.answer(read, 200, revision())
    transport.answer(publish, 403, """{"error":"who drafted or last changed qonto-key@2 can't publish it"}""")
    assertTrue(operator.open("qonto-key@2"))
    assertFalse(operator.act(CatalogOperator.PUBLISH))
    assertEquals("who drafted or last changed qonto-key@2 can't publish it", operator.state.value.sheet!!.error)
    auth.confirms = false
    assertFalse(operator.act(CatalogOperator.PUBLISH))
    assertEquals("the refused fingerprint sent nothing more", 1, transport.requestsTo(publish).size)
  }

  @Test fun aRevisionTheServerDoesntKnowSaysSo() = runTest {
    transport.answer(read, 404, """{"error":"no catalog revision qonto-key@9"}""")
    assertFalse(operator.open("qonto-key@2"))
    assertEquals("no catalog revision qonto-key@9", operator.state.value.sheet!!.error)
    assertNull(operator.state.value.sheet!!.revision)
  }

  @Test fun theSheetDrawsReadOrWriteFromTheServersReadingNeverTheEntrysLabels() {
    val view = java.io.File("src/main/kotlin/bot/tafs/app/views/CatalogViews.kt").readText()
    assertTrue(view.contains("d.readOnly"))
    assertFalse("never the entry's own label", view.contains("t.readOnly"))
  }

  /** A revision whose entry is [body], read as the server would answer, with its own digest so only the agreement is tested. */
  private fun agreeing(body: String, described: String) =
    """{"id":"qonto-key@2","entry":$body,"digest":"${CatalogOperator.digestOf(KiffClient.json.parseToJsonElement(body))}","state":"reviewed","described":$described}"""

  private val readsWell = """{"from":"description","tools":[{"tool":"list_quotes","method":"GET","path":"/v2/quotes","read_only":true},
    {"tool":"create_client_invoice","method":"POST","path":"/v2/client_invoices","read_only":false,"final_arguments":[{"argument":"status","undo":"draft"}]}]}"""

  @Test fun whatIsDrawnOfTheEntryIsTheSignedEntrysOwnWords() = runTest {
    enrolled()
    transport.answer(read, 200, revision())
    assertTrue(operator.open("qonto-key@2"))
    val e = operator.state.value.sheet!!.entry!!
    assertEquals("Qonto", e.vendor)
    assertEquals("Qonto invoicing", e.title)
    assertEquals("where the key is sent", "https://thirdparty.example.test/v2", e.base)
    assertEquals("header", e.authIn)
    assertEquals("Authorization", e.authName)
    assertEquals("https://specs.example.test/qonto.json", e.descriptionUrl)
    assertEquals(listOf("List the quotes", "Draft a client invoice"), e.tools.map { it.title })
    assertEquals(listOf("qonto_list_quotes", "qonto_create_invoice"), e.tools.map { it.name })
    assertTrue(operator.state.value.sheet!!.disagreements.isEmpty())
  }

  @Test fun anArgumentThatCanMakeAWriteFinalAndIsLeftOpenLeavesNothingToSign() = runTest {
    enrolled()
    val open = entry.replace(""""fixed_arguments":{"status":"draft","currency":"EUR"}""", """"fixed_arguments":{"currency":"EUR"}""")
    transport.answer(read, 200, agreeing(open, readsWell))
    assertFalse(operator.open("qonto-key@2"))
    assertTrue("the digest holds", operator.state.value.sheet!!.matches)
    assertEquals(listOf(CatalogDisagreement.LeftOpen("create_client_invoice", "status", "\"draft\"")), operator.state.value.sheet!!.disagreements)
    assertFalse(operator.act(CatalogOperator.PUBLISH))
    assertTrue(auth.fingerprints.isEmpty())
    assertTrue(transport.requestsTo(publish).isEmpty())
    // Pinned to another value is no better than left open.
    transport.reset()
    transport.answer(read, 200, agreeing(entry.replace(""""status":"draft"""", """"status":"sent""""), readsWell))
    assertFalse(operator.open("qonto-key@2"))
    assertEquals(1, operator.state.value.sheet!!.disagreements.size)
  }

  @Test fun toolListsThatDifferOrAReadOnlyLabelOnAWriteLeaveNothingToSign() = runTest {
    enrolled()
    val extra = """{"from":"description","tools":[{"tool":"list_quotes","read_only":true},
      {"tool":"create_client_invoice","read_only":false,"final_arguments":[{"argument":"status","undo":"draft"}]},
      {"tool":"delete_client","read_only":false}]}"""
    transport.answer(read, 200, agreeing(entry, extra))
    assertFalse(operator.open("qonto-key@2"))
    assertEquals(listOf(CatalogDisagreement.NotInEntry("delete_client")), operator.state.value.sheet!!.disagreements)

    transport.reset()
    val missing = """{"from":"description","tools":[{"tool":"list_quotes","read_only":true}]}"""
    transport.answer(read, 200, agreeing(entry, missing))
    assertFalse(operator.open("qonto-key@2"))
    assertEquals(listOf(CatalogDisagreement.NotDescribed("create_client_invoice")), operator.state.value.sheet!!.disagreements)

    transport.reset()
    val writes = readsWell.replace(""""path":"/v2/quotes","read_only":true""", """"path":"/v2/quotes","read_only":false""")
    transport.answer(read, 200, agreeing(entry, writes))
    assertFalse(operator.open("qonto-key@2"))
    assertEquals(listOf(CatalogDisagreement.LabelDiffers("list_quotes")), operator.state.value.sheet!!.disagreements)

    transport.reset()
    transport.answer(read, 200, agreeing(entry, """{"from":"description","why":"the description couldn't be read"}"""))
    assertFalse(operator.open("qonto-key@2"))
    assertEquals(listOf(CatalogDisagreement.Unread), operator.state.value.sheet!!.disagreements)
    assertFalse(operator.act(CatalogOperator.PUBLISH))
    assertTrue(auth.fingerprints.isEmpty())
  }

  @Test fun anMcpEntrysToolsAreListedByItsServerSoThereIsNothingToCompare() = runTest {
    enrolled()
    val mcp = """{"id":"linear@1","vendor":"Linear","title":"Linear","kind":"mcp","server":"https://mcp.example.test/sse","tools":[]}"""
    transport.answer("GET /v1/catalog/entries/linear@1", 200,
      """{"id":"linear@1","entry":$mcp,"digest":"${CatalogOperator.digestOf(KiffClient.json.parseToJsonElement(mcp))}","state":"reviewed","described":{"from":"server","why":"listed by its server once connected"}}""")
    assertTrue(operator.open("linear@1"))
    assertEquals("https://mcp.example.test/sse", operator.state.value.sheet!!.entry!!.server)
  }

  @Test fun theEntrysOwnIdMustBeTheOneAskedForToo() = runTest {
    enrolled()
    // The answer says qonto-key@2, but the entry it carries, digest and all, is qonto-key@1's.
    val other = entry.replace(""""id":"qonto-key@2"""", """"id":"qonto-key@1"""")
    transport.answer(read, 200, agreeing(other, readsWell))
    assertFalse(operator.open("qonto-key@2"))
    assertFalse(operator.state.value.sheet!!.matches)
    assertFalse(operator.act(CatalogOperator.PUBLISH))
    assertTrue(auth.fingerprints.isEmpty())
  }
}
