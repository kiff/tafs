package bot.tafs.app

import bot.tafs.app.kiff.Jcs
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.PurchaseReceipt
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * KIFF's purchase receipt (contracts/purchase.json, `receipt`): read as the contract writes it, and checked on the phone, Ed25519
 * over the canonical JSON of `value` without `sig`, against a key the phone holds. The contract's `sig` is a placeholder, so a
 * test key signs the contract's own value here.
 */
class PurchaseReceiptTest {
  private val contract = KiffClient.json.parseToJsonElement(File("../../contracts/purchase.json").readText()).jsonObject.getValue("receipt").jsonObject
  private val value = contract.getValue("value").jsonObject
  private val private = Ed25519PrivateKeyParameters(SecureRandom())
  private val keys = mapOf("kiff-receipts-1" to private.generatePublicKey().encoded)
  private val case = value.getValue("case_id").let { (it as JsonPrimitive).content }

  /** [v] as KIFF would serve it, signed with the test key over its canonical JSON without `sig`. */
  private fun signed(v: JsonObject = value): JsonObject {
    val bytes = Jcs.canonical(JsonObject(v - "sig")).toByteArray()
    val sig = Ed25519Signer().run { init(true, private); update(bytes, 0, bytes.size); generateSignature() }
    return JsonObject(v + ("sig" to JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(sig))))
  }

  private fun with(v: JsonObject, key: String, to: kotlinx.serialization.json.JsonElement) = JsonObject(v + (key to to))

  @Test fun itReadsTheContractsReceiptAsKiffWritesIt() {
    val r = assertNotNull(PurchaseReceipt.read(value)).let { PurchaseReceipt.read(value)!! }
    assertEquals(case, r.caseId)
    assertEquals("Bought", r.words.state)
    assertEquals("token", r.path)
    assertEquals(listOf("You approved up to €86.20 at this shop, once.", "One card for this purchase, used once. Its number never reached tafs or KIFF.",
      "Order PL-2026-118204 confirmed by the shop.", "No refunds."), r.words.lines)
    // Also when KIFF's answer wraps it as `value`.
    assertEquals(case, PurchaseReceipt.read(JsonObject(mapOf("value" to value)))!!.caseId)
  }

  @Test fun signedByAKeyThePhoneHoldsItChecks() {
    assertEquals(PurchaseReceipt.Check.Verified, PurchaseReceipt.read(signed())!!.check(case, keys))
  }

  @Test fun aChangedWordOrStateNoLongerChecks() {
    val s = signed()
    val words = s.getValue("words").jsonObject
    val tampered = with(s, "words", JsonObject(words + ("state" to JsonPrimitive("Bought (refunded)"))))
    assertEquals(PurchaseReceipt.Check.Changed, PurchaseReceipt.read(tampered)!!.check(case, keys))
    val amounts = s.getValue("amounts").jsonObject
    assertEquals(PurchaseReceipt.Check.Changed, PurchaseReceipt.read(with(s, "amounts", JsonObject(amounts + ("captured" to JsonPrimitive(9999)))))!!.check(case, keys))
  }

  @Test fun aKeyThePhoneDoesntHoldOrARevokedOneIsNeverChecked() {
    val r = PurchaseReceipt.read(signed())!!
    assertEquals(PurchaseReceipt.Check.UnknownKey, r.check(case, emptyMap()))
    assertEquals(PurchaseReceipt.Check.UnknownKey, r.check(case, keys, revoked = setOf("kiff-receipts-1")))
    // A receipt that names another kid isn't checked against this one.
    assertEquals(PurchaseReceipt.Check.UnknownKey, PurchaseReceipt.read(signed(with(value, "kid", JsonPrimitive("kiff-receipts-9"))))!!.check(case, keys))
  }

  @Test fun aReceiptForAnotherCaseIsntThisLinesReceipt() {
    assertEquals(PurchaseReceipt.Check.OtherCase, PurchaseReceipt.read(signed())!!.check("pc_other", keys))
  }

  @Test fun unknownIsSaidAsUnknown() {
    val unknown = contract.getValue("examples").jsonObject.getValue("unknown").jsonObject
    val v = JsonObject(value + ("states" to unknown.getValue("states")) + ("words" to unknown.getValue("words")))
    val r = PurchaseReceipt.read(signed(v))!!
    assertEquals("Not known yet", r.words.state)
    assertEquals("unknown", r.caseState)
    assertEquals(PurchaseReceipt.Check.Verified, r.check(case, keys))
  }

  @Test fun anythingElseIsntAPurchaseReceipt() {
    assertNull(PurchaseReceipt.read(with(value, "schema", JsonPrimitive("kiff.receipt.v1"))))
    assertNull(PurchaseReceipt.read(JsonObject(value - "kid")))
    assertNull(PurchaseReceipt.read(JsonObject(value - "words")))
  }

  @Test fun withNoKeyPinnedYetNothingReadsAsChecked() {
    // KIFF's first receipt key isn't pinned in this build yet: a receipt is drawn, never shown as checked.
    assertEquals(PurchaseReceipt.Check.UnknownKey, PurchaseReceipt.read(signed())!!.check(case))
  }
}
