package bot.tafs.app

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

  private val entry = """{"id":"qonto-key@2","name":"qonto-key","kind":"api_key","tools":[{"tool":"list_quotes"},{"tool":"create_client_invoice","fixed_arguments":{"status":"draft"}}]}"""
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
    assertTrue(view.contains("t.readOnly"))
    assertFalse("never the entry's own labels", view.contains("revision.entry"))
  }
}
